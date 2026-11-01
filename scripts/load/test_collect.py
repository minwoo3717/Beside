"""collect.py: the fixed query list, CSV writing, run.json assembly and the Prometheus pull (stubbed and over HTTP)."""
import csv
import io
import json
import tempfile
import threading
import unittest
import urllib.parse
from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import collect

REQUIRED = ["http_p95_by_uri", "http_rps_by_uri", "http_5xx_ratio", "jobs_queue", "process_cpu", "system_cpu", "jvm_heap_used",
            "jvm_heap_max", "jvm_heap_after_gc", "tomcat_busy", "disk_free"]
# The number of CSVs collect.py writes. scripts/load/README.md names it too ("Prometheus query_range CSV N개"):
# ReadmeTest compares the two.
CSV_COUNT = 18


def matrix(*series):
    return {"status": "success", "data": {"resultType": "matrix", "result": list(series)}}


SUMMARY = {
    "schema": "beside-load-summary/1",
    "meta": {"script": "api_staged.js", "scenario": "A1", "tier": "", "runId": "a1r1", "baseUrl": "https://beside.example.org",
             "startedAt": "2026-11-10T05:00:00.000Z", "endedAt": "2026-11-10T06:00:00.000Z", "stages": [{"index": 0, "users": 25}]},
    "verdict": {"thresholdsOk": False, "aborted": None, "unfinishedFlows": 0, "breakingPoint": {"lastPassingStage": 3}, "guard": None},
    "k6": {"metrics": {}},
}


def summary_with(aborted=None, guard=None, guard_abort=None, drop_aborted=False):
    """SUMMARY with verdict.aborted / verdict.guard as lib/summary.js writes them, and load_guardAbort when the guard stopped."""
    doc = json.loads(json.dumps(SUMMARY))
    doc["verdict"]["aborted"] = aborted
    doc["verdict"]["guard"] = guard
    if aborted:
        doc["verdict"]["breakingPoint"] = None
    if guard_abort is not None:
        doc["k6"]["metrics"]["load_guardAbort"] = {"type": "gauge", "contains": "default",
                                                   "values": {"value": guard_abort, "min": guard_abort, "max": guard_abort}}
    if drop_aborted:
        del doc["verdict"]["aborted"]
    return doc


def aborted(by, reason):
    return {"by": by, "reason": reason, "atS": 61.2, "plannedS": 80, "cutStages": [1], "unfinishedFlows": 3}


GUARD_OK = {"promUrl": "http://localhost:9090", "env": "loadtest", "queries": 40, "failures": 0, "lastError": None,
            "maxErrorRatio": 0, "maxDiskRatio": 0.31}
GUARD_BLIND = dict(GUARD_OK, failures=4, lastError="HTTP 500")


class QueryListTest(unittest.TestCase):
    def test_required_queries_present_and_scoped(self):
        qs = dict(collect.queries("loadtest"))
        for name in REQUIRED:
            self.assertIn(name, qs)
        for name, q in qs.items():
            self.assertIn('env="loadtest"', q, name)
        self.assertIn('status=~"5.."', qs["http_5xx_ratio"])
        self.assertIn("histogram_quantile(0.95", qs["http_p95_by_uri"])
        self.assertIn("by (le, uri)", qs["http_p95_by_uri"])
        self.assertIn('area="heap"', qs["jvm_heap_used"])
        self.assertEqual(len(qs), len(collect.queries("loadtest")))  # names are unique
        self.assertEqual(len(qs), CSV_COUNT)

    def test_heap_after_gc_is_the_long_lived_pool_ratio(self):
        # "GC 뒤 힙 < 75 %": Micrometer's 0..1 ratio of the long-lived pool after the last GC, not used/max (Eden included)
        qs = dict(collect.queries("loadtest"))
        self.assertEqual(qs["jvm_heap_after_gc"],
                         'max(jvm_memory_usage_after_gc_percent{env="loadtest",area="heap",pool="long-lived"})')
        self.assertEqual(dict(collect.queries(""))["jvm_heap_after_gc"],
                         'max(jvm_memory_usage_after_gc_percent{area="heap",pool="long-lived"})')
        # the earlier heap CSVs stay as they were, so runs stay comparable
        self.assertEqual(qs["jvm_heap_used"], 'sum(jvm_memory_used_bytes{env="loadtest",area="heap"})')
        self.assertEqual(qs["jvm_heap_max"], 'sum(jvm_memory_max_bytes{env="loadtest",area="heap"})')

    def test_no_env_label(self):
        qs = dict(collect.queries(""))
        self.assertEqual(qs["disk_free"], "disk_free_bytes")
        self.assertIn('http_server_requests_seconds_count{status=~"5.."}', qs["http_5xx_ratio"])
        for q in qs.values():
            self.assertNotIn("{,", q)
            self.assertNotIn("{}", q)


class ReadmeTest(unittest.TestCase):
    """scripts/load/README.md against collect.py, so the documented CSV count and heap criterion cannot drift again.

    README.md is not part of the change that added jvm_heap_after_gc: it still says "CSV 17개" and judges "GC 뒤 힙 < 75 %"
    from jvm_heap_used/max, so both checks fail today and are marked expectedFailure. The commit that fixes README.md
    removes the two decorators; fixing README.md without removing them is an "unexpected success", which unittest
    counts as a failed run, so the two documents cannot silently disagree in either direction."""

    @staticmethod
    def readme() -> str:
        return (collect.HERE / "README.md").read_text(encoding="utf-8")

    @unittest.expectedFailure  # README.md:24 still says "CSV 17개"
    def test_readme_names_the_csv_count(self):
        self.assertIn(f"Prometheus query_range CSV {CSV_COUNT}개", self.readme())

    @unittest.expectedFailure  # README.md:84 still judges "GC 뒤 힙 < 75 %" from jvm_heap_used/max
    def test_readme_judges_heap_after_gc_from_its_csv(self):
        lines = [line for line in self.readme().splitlines() if "GC 뒤 힙" in line]
        self.assertTrue(lines)
        for line in lines:
            self.assertIn("jvm_heap_after_gc", line)


class CsvTest(unittest.TestCase):
    def test_series_label_drops_common_labels(self):
        self.assertEqual(collect.series_label({"__name__": "x", "env": "loadtest", "instance": "b:8081", "job": "backend",
                                               "uri": "/api/v1/jobs/{jobId}", "method": "GET"}), "method=GET;uri=/api/v1/jobs/{jobId}")
        self.assertEqual(collect.series_label({"env": "loadtest"}), "all")

    def test_rows_and_csv_round_trip(self):
        t0 = collect.parse_time("2026-11-10T05:00:00Z")
        doc = matrix({"metric": {"uri": "/b"}, "values": [[t0, "0.01"], [t0 + 15, "0.02"]]},
                     {"metric": {"uri": "/a"}, "values": [[t0, "NaN"]]})
        rows = collect.matrix_rows(doc)
        self.assertEqual([r[2] for r in rows], ["uri=/a", "uri=/b", "uri=/b"])
        self.assertEqual(rows[1][1], "2026-11-10T05:00:00+00:00")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "sub" / "q.csv"
            collect.write_csv(path, rows)
            with open(path, newline="", encoding="utf-8") as fh:
                back = list(csv.reader(fh))
        self.assertEqual(back[0], ["unix", "time", "series", "value"])
        self.assertEqual(back[1:], rows)

    def test_bad_response(self):
        with self.assertRaises(ValueError):
            collect.matrix_rows({"status": "error", "error": "parse error"})
        with self.assertRaises(ValueError):
            collect.matrix_rows({"status": "success", "data": {"resultType": "vector", "result": []}})


class RunJsonTest(unittest.TestCase):
    def test_assembly(self):
        args = collect.parse_args(["out", "--tier", "T3", "--k6-version", "k6 v1.2.3", "--vm-vcpu", "2", "--vm-ram-gb", "4",
                                   "--uplink-mbps", "94.5", "--server-sha", "abc123", "--note", "first"])
        args.summary_name = "k6-summary.json"
        doc = collect.build_run_json(args, SUMMARY, {"start": "s", "end": "e"}, {"gitSha": "f00", "scriptsDirty": False},
                                     [{"name": "p.png", "bytes": 3, "sha256": "x"}], {"skipped": "no --prom-url"}, [])
        self.assertEqual(doc["scenario"], "A1")       # from the summary
        self.assertEqual(doc["tier"], "T3")           # from the argument
        self.assertEqual(doc["runId"], "a1r1")
        self.assertEqual(doc["tool"], {"gitSha": "f00", "scriptsDirty": False, "k6Version": "k6 v1.2.3", "script": "api_staged.js"})
        self.assertEqual(doc["vm"]["vcpu"], 2)
        self.assertEqual(doc["client"]["uplinkMbps"], 94.5)
        self.assertEqual(doc["target"], {"baseUrl": "https://beside.example.org", "local": False, "serverSha": "abc123"})
        self.assertEqual(doc["k6"]["verdict"]["breakingPoint"]["lastPassingStage"], 3)
        self.assertNotIn("metrics", json.dumps(doc["k6"]))  # the raw k6 data stays in k6-summary.json
        self.assertTrue(doc["status"].startswith("[실험 결과]"))
        self.assertTrue(doc["resultCandidate"])
        self.assertEqual(doc["outcome"], {"ended": "normal", "abortedBy": None, "reason": None, "guardFailures": None,
                                          "guardLastError": None})

    def test_local_run_is_marked(self):
        args = collect.parse_args(["out", "--base-url", "http://localhost:18080"])
        args.summary_name = None
        doc = collect.build_run_json(args, None, {}, {}, [], {}, [])
        self.assertTrue(doc["target"]["local"])
        self.assertIn("인용하지 않는다", doc["status"])
        self.assertFalse(doc["resultCandidate"])


class OutcomeTest(unittest.TestCase):
    """run.json status / resultCandidate / outcome from the summary's verdict.aborted and verdict.guard (lib/summary.js)."""

    def doc(self, summary, *argv):
        args = collect.parse_args(["out", *argv])
        args.summary_name = "k6-summary.json"
        return collect.build_run_json(args, summary, {}, {}, [], {}, [])

    def test_normal_run_with_a_working_guard_is_a_plain_candidate(self):
        doc = self.doc(summary_with(guard=GUARD_OK))
        self.assertEqual(doc["outcome"]["ended"], "normal")
        self.assertEqual(doc["outcome"]["guardFailures"], 0)
        self.assertTrue(doc["resultCandidate"])
        self.assertEqual(doc["status"], "[실험 결과] 원시 데이터 — " + collect.CITE)

    def test_aborts_unrelated_to_capacity_are_not_results(self):
        cases = [
            ("setup", "setup() did not finish (it threw, timed out or was interrupted; ...)", None),
            ("guard", "disk 91.2 % used > GUARD_MAX_DISK_RATIO 0.8 (k6 exit code 108)", 2),
            ("guard", "Prometheus at http://localhost:9090 could not be read in the first 3 checks (HTTP 500) (k6 exit code 108)", 3),
            ("interrupted", "the load stopped before its planned end and neither the guard nor an abortOnFail threshold did it", None),
            ("unfinished", "7 flow(s) were still running when k6 ended and have no outcome", None),
        ]
        for by, reason, code in cases:
            with self.subTest(by=by, code=code):
                doc = self.doc(summary_with(aborted=aborted(by, reason), guard_abort=code))
                self.assertEqual(doc["outcome"]["ended"], "aborted")
                self.assertEqual(doc["outcome"]["abortedBy"], by)
                self.assertEqual(doc["outcome"]["reason"], reason)
                self.assertFalse(doc["resultCandidate"])
                self.assertNotIn("[실험 결과] 원시", doc["status"])
                self.assertTrue(doc["status"].startswith("인용하지 않는다 — 중단된 실행(k6.verdict.aborted.by = " + by + ")"))
                self.assertIn(reason, doc["status"])
                self.assertEqual(doc["k6"]["verdict"]["aborted"]["by"], by)  # the verdict is still copied unchanged

    def test_rule_stops_stay_candidates_but_name_only_the_rule(self):
        # the abortOnFail error rule and the guard's 5xx rule also fire when the server restarts or the network drops
        # (a real k6 run whose server was killed at 0.5 req/s ended with exit 99 and a breaking point), so the status names
        # the rule, not "capacity", and says what to rule out before reading the breaking point
        threshold = self.doc(summary_with(aborted=aborted("threshold", "load_errorRatio rate<0.05 crossed (k6 exit code 99)")))
        guard = self.doc(summary_with(aborted=aborted("guard", "server 5xx ratio 0.2 > GUARD_MAX_ERROR_RATIO 0.05 over 1m"),
                                      guard=GUARD_OK, guard_abort=1))
        for doc, by, rule in ((threshold, "threshold", "abortOnFail 오류 기준"), (guard, "guard", "가드의 5xx 기준")):
            with self.subTest(by=by):
                self.assertEqual(doc["outcome"]["ended"], "ruleStop")
                self.assertTrue(doc["resultCandidate"])  # as lib/summary.js brokeAt: stages and breakingPoint are kept
                self.assertTrue(doc["status"].startswith(
                    f"[실험 결과] 원시 데이터 — 오류 기준으로 멈춘 실행({rule}, k6.verdict.aborted.by = {by}): 용량 한계일 때만 결과다"))
                self.assertNotIn("용량 한계에서 멈춘", doc["status"])
                self.assertIn("서버 재시작·재배포·네트워크 끊김이 없었음을", doc["status"])
                self.assertIn("확인한 뒤 k6.verdict.stages·breakingPoint", doc["status"])
                self.assertTrue(doc["status"].endswith(collect.CITE))
                self.assertNotEqual(doc["status"], "[실험 결과] 원시 데이터 — " + collect.CITE)  # never the plain label
        self.assertTrue(any("ruleStop" in line and "서버 재시작" in line for line in threshold["honesty"]))

    def test_guard_5xx_stop_without_the_gauge_is_read_from_the_reason(self):
        doc = self.doc(summary_with(aborted=aborted("guard", "server 5xx ratio 0.3 > GUARD_MAX_ERROR_RATIO 0.05 over 1m")))
        self.assertEqual(doc["outcome"]["ended"], "ruleStop")
        doc = self.doc(summary_with(aborted=aborted("guard", "disk 90.0 % used > GUARD_MAX_DISK_RATIO 0.8")))
        self.assertEqual(doc["outcome"]["ended"], "aborted")

    def test_unreadable_prometheus_is_named_but_keeps_the_candidate(self):
        doc = self.doc(summary_with(guard=GUARD_BLIND))
        self.assertEqual(doc["outcome"]["guardFailures"], 4)
        self.assertEqual(doc["outcome"]["guardLastError"], "HTTP 500")
        self.assertTrue(doc["resultCandidate"])
        # the caveat comes right after the label, before the citing rule
        self.assertTrue(doc["status"].startswith("[실험 결과] 원시 데이터 — 주의: 가드가 Prometheus 응답 4개를 읽지 못했다(마지막: HTTP 500)"))
        self.assertTrue(doc["status"].endswith(collect.CITE))
        rule_stop = self.doc(summary_with(aborted=aborted("threshold", "load_errorRatio rate<0.05 crossed"), guard=GUARD_BLIND))
        self.assertIn("thresholdsOk 는 늘 false 다. 주의: 가드가 Prometheus 응답 4개", rule_stop["status"])
        self.assertTrue(rule_stop["status"].endswith(collect.CITE))

    def test_summary_without_verdict_aborted_is_not_a_result(self):
        doc = self.doc(summary_with(drop_aborted=True))
        self.assertEqual(doc["outcome"]["ended"], "unknown")
        self.assertFalse(doc["resultCandidate"])
        self.assertTrue(doc["status"].startswith("인용하지 않는다 — k6 요약에 verdict.aborted 가 없다"))

    def test_local_aborted_run_says_both(self):
        summary = summary_with(aborted=aborted("guard", "disk 90.0 % used > GUARD_MAX_DISK_RATIO 0.8"), guard=GUARD_BLIND, guard_abort=2)
        doc = self.doc(summary, "--base-url", "http://localhost:18740")
        self.assertFalse(doc["resultCandidate"])
        self.assertTrue(doc["status"].startswith("동작 확인용 로컬 실행 — 보고서에 인용하지 않는다 (중단된 실행, k6.verdict.aborted.by = guard)"))
        self.assertIn("응답 4개를 읽지 못했다", doc["status"])
        rule_stop = self.doc(summary_with(aborted=aborted("threshold", "load_errorRatio rate<0.05 crossed")),
                             "--base-url", "http://127.0.0.1:18740")
        self.assertEqual(rule_stop["outcome"]["ended"], "ruleStop")
        self.assertFalse(rule_stop["resultCandidate"])
        self.assertEqual(rule_stop["status"], "동작 확인용 로컬 실행 — 보고서에 인용하지 않는다 (중단된 실행, k6.verdict.aborted.by = threshold)")

    def test_no_summary_keeps_the_previous_status(self):
        args = collect.parse_args(["out"])
        args.summary_name = None
        doc = collect.build_run_json(args, None, {}, {}, [], {}, [])
        self.assertIsNone(doc["outcome"]["ended"])
        self.assertTrue(doc["resultCandidate"])
        self.assertEqual(doc["status"], "[실험 결과] 원시 데이터 — " + collect.CITE)

    def test_redact_url(self):
        self.assertEqual(collect.redact_url("http://user:pw@localhost:9090/prom?x=1"), "http://localhost:9090/prom")


class FakeProm(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        params = dict(urllib.parse.parse_qsl(url.query))
        self.server.calls.append((url.path, params))
        if "tomcat" in params.get("query", "") and self.server.fail_tomcat:
            body, status = b'{"status":"error","error":"boom"}', 400
        else:
            start = float(params["start"])
            body = json.dumps(matrix({"metric": {"__name__": "m", "env": "loadtest", "state": "pending"},
                                      "values": [[start, "1"], [start + 15, "2"]]})).encode()
            status = 200
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class EndToEndTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeProm)
        self.server.calls, self.server.fail_tomcat = [], False
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.prom = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "k6s.json").write_text(json.dumps(SUMMARY), encoding="utf-8")
        (self.root / "photo.png").write_bytes(b"\x89PNG\r\n\x1a\n1234")
        (self.root / "events.jsonl").write_text('{"jobId":"x"}\n', encoding="utf-8")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = collect.main([str(self.root / "run"), "--summary", str(self.root / "k6s.json"), "--prom-url", self.prom,
                                 "--photo", str(self.root / "photo.png"), "--attach", str(self.root / "events.jsonl"), *extra],
                                tool={"gitSha": "deadbeef", "scriptsDirty": False})
        self.stdout = out.getvalue()
        return code, err.getvalue()

    def test_pulls_every_query_over_the_padded_window(self):
        code, err = self.run_main("--scenario", "A1", "--pad-s", "60", "--step", "15")
        self.assertEqual(code, 0, err)
        self.assertIn(f"prometheus: {CSV_COUNT} of {CSV_COUNT} CSV written to ", self.stdout)
        self.assertIn("status: [실험 결과] 원시 데이터", self.stdout)
        self.assertEqual(len(list((self.root / "run" / "prometheus").glob("*.csv"))), CSV_COUNT)
        run = self.root / "run"
        doc = json.loads((run / "run.json").read_text(encoding="utf-8"))
        self.assertEqual(len(self.server.calls), len(collect.queries("loadtest")))
        path, params = self.server.calls[0]
        self.assertEqual(path, "/api/v1/query_range")
        self.assertEqual(float(params["start"]), collect.parse_time("2026-11-10T05:00:00Z") - 60)
        self.assertEqual(float(params["end"]), collect.parse_time("2026-11-10T06:00:00Z") + 60)
        self.assertEqual(params["step"], "15")
        for name in REQUIRED:
            self.assertTrue((run / "prometheus" / f"{name}.csv").is_file(), name)
        with open(run / "prometheus" / "jobs_queue.csv", newline="", encoding="utf-8") as fh:
            rows = list(csv.reader(fh))
        self.assertEqual(rows[1][2], "state=pending")
        self.assertEqual(doc["window"]["durationS"], 3600.0)
        self.assertEqual(doc["photos"][0]["bytes"], 12)
        self.assertEqual(doc["attachments"][0]["file"], "attachments/events.jsonl")
        self.assertTrue((run / "attachments" / "events.jsonl").is_file())
        self.assertTrue((run / "k6-summary.json").is_file())
        self.assertEqual(doc["k6"]["file"], "k6-summary.json")
        self.assertEqual(doc["tool"]["gitSha"], "deadbeef")
        self.assertTrue(all("error" not in q for q in doc["prometheus"]["queries"]))

    def test_failed_query_is_recorded_and_exit_1(self):
        self.server.fail_tomcat = True
        code, _ = self.run_main()
        self.assertEqual(code, 1)
        doc = json.loads((self.root / "run" / "run.json").read_text(encoding="utf-8"))
        bad = [q for q in doc["prometheus"]["queries"] if "error" in q]
        self.assertEqual([q["name"] for q in bad], ["tomcat_busy"])
        self.assertIsNone(bad[0]["file"])
        self.assertIn(f"prometheus: {CSV_COUNT - 1} of {CSV_COUNT} CSV written to ", self.stdout)
        self.assertIn("; failed: tomcat_busy", self.stdout)

    def test_aborted_summary_is_flagged_in_run_json_and_stdout(self):
        summary = summary_with(aborted=aborted("interrupted", "the load stopped before its planned end"), guard=GUARD_OK)
        (self.root / "k6s.json").write_text(json.dumps(summary), encoding="utf-8")
        code, err = self.run_main()
        self.assertEqual(code, 0, err)
        doc = json.loads((self.root / "run" / "run.json").read_text(encoding="utf-8"))
        self.assertFalse(doc["resultCandidate"])
        self.assertEqual(doc["outcome"]["abortedBy"], "interrupted")
        self.assertIn("status: 인용하지 않는다 — 중단된 실행(k6.verdict.aborted.by = interrupted)", self.stdout)

    def test_unknown_window_writes_nothing(self):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = collect.main([str(self.root / "empty")], tool={})
        self.assertEqual(code, 2)
        self.assertFalse((self.root / "empty").exists())

    def test_stubbed_fetch_without_network(self):
        seen = []

        def fetch(url):
            seen.append(url)
            return matrix()

        out = io.StringIO()
        with redirect_stdout(out):
            code = collect.main([str(self.root / "stub"), "--start", "2026-11-10T05:00:00Z", "--end", "2026-11-10T05:10:00Z",
                                 "--prom-url", "http://prom.invalid:9090", "--prom-env", ""], fetch=fetch, tool={})
        self.assertEqual(code, 0)
        self.assertEqual(len(seen), len(collect.queries("")))
        self.assertTrue(seen[0].startswith("http://prom.invalid:9090/api/v1/query_range?query="))
        # an empty answer is written (header only) but named, e.g. a PROM_ENV that matches nothing
        self.assertIn(f"prometheus: {CSV_COUNT} of {CSV_COUNT} CSV written", out.getvalue())
        self.assertIn("; no data: http_p95_by_uri, ", out.getvalue())
        self.assertIn("jvm_heap_after_gc", out.getvalue())


if __name__ == "__main__":
    unittest.main()
