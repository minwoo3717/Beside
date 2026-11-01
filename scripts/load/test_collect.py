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

REQUIRED = ["http_p95_by_uri", "http_rps_by_uri", "http_5xx_ratio", "jobs_queue", "process_cpu", "jvm_heap_used",
            "tomcat_busy", "disk_free"]


def matrix(*series):
    return {"status": "success", "data": {"resultType": "matrix", "result": list(series)}}


SUMMARY = {
    "schema": "beside-load-summary/1",
    "meta": {"script": "api_staged.js", "scenario": "A1", "tier": "", "runId": "a1r1", "baseUrl": "https://beside.example.org",
             "startedAt": "2026-11-10T05:00:00.000Z", "endedAt": "2026-11-10T06:00:00.000Z", "stages": [{"index": 0, "users": 25}]},
    "verdict": {"thresholdsOk": False, "breakingPoint": {"lastPassingStage": 3}},
    "k6": {"metrics": {}},
}


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

    def test_no_env_label(self):
        qs = dict(collect.queries(""))
        self.assertEqual(qs["disk_free"], "disk_free_bytes")
        self.assertIn('http_server_requests_seconds_count{status=~"5.."}', qs["http_5xx_ratio"])
        for q in qs.values():
            self.assertNotIn("{,", q)
            self.assertNotIn("{}", q)


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

    def test_local_run_is_marked(self):
        args = collect.parse_args(["out", "--base-url", "http://localhost:18080"])
        args.summary_name = None
        doc = collect.build_run_json(args, None, {}, {}, [], {}, [])
        self.assertTrue(doc["target"]["local"])
        self.assertIn("인용하지 않는다", doc["status"])

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
        return code, err.getvalue()

    def test_pulls_every_query_over_the_padded_window(self):
        code, err = self.run_main("--scenario", "A1", "--pad-s", "60", "--step", "15")
        self.assertEqual(code, 0, err)
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

        with redirect_stdout(io.StringIO()):
            code = collect.main([str(self.root / "stub"), "--start", "2026-11-10T05:00:00Z", "--end", "2026-11-10T05:10:00Z",
                                 "--prom-url", "http://prom.invalid:9090", "--prom-env", ""], fetch=fetch, tool={})
        self.assertEqual(code, 0)
        self.assertEqual(len(seen), len(collect.queries("")))
        self.assertTrue(seen[0].startswith("http://prom.invalid:9090/api/v1/query_range?query="))


if __name__ == "__main__":
    unittest.main()
