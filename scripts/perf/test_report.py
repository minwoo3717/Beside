"""report.py and mdlite.py: HTML from synthetic results of three versions, valid inline SVG, tables, labels."""
import io
import json
import random
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

import mdlite
import report
import run
import scenarios

SVG = re.compile(r"<svg\b.*?</svg>", re.S)


def make_suite(label, rep, factor, capabilities):
    """A suite whose times are `factor` x a fixed profile (with 3 % jitter)."""
    rng = random.Random(f"{label}-{rep}")

    def ms(center, n=40):
        return [round(center * factor * (1 + rng.uniform(-0.03, 0.03)), 2) for _ in range(n)]

    sc = {name: scenarios.new_result() for name in scenarios.ALL_SCENARIOS}
    sc["startup"]["values"].update({"bench.startupMs": 8000 * factor, "bench.springStartedMs": 7000 * factor,
                                    "bench.idleRssMB": 350.0})
    sc["healthz"]["samples"]["healthzMs"] = ms(1.2)
    for name, center in (("upload_small", 10), ("upload_large", 180), ("upload_12mp", 30)):
        sc[name]["samples"]["postMs"] = ms(center)
        sc[name]["server"] = {"postMs": {"n": 3, "samples": {"uploadMs": [3, 4, 5]}}}
    sc["upload_large"]["values"].update({"bench.uploadMBps": 100 / factor, "bench.serverCpuMs": 4000 * factor,
                                         "bench.settleAfterMs": 1000.0})
    sc["upload_12mp"]["samples"]["postConcurrentMs"] = ms(80)
    sc["e2e_mock"]["samples"].update({"flowMs": ms(40), "completeMs": ms(30), "assetMs": ms(8)})
    sc["e2e_mock"]["values"]["bench.pollsPerJob"] = 1.6
    sc["concurrent_e2e"]["samples"].update({"flowMs": ms(400), "postMs": ms(100), "pollMs": ms(50), "assetMs": ms(60)})
    sc["concurrent_e2e"]["values"].update({"bench.jobsPerSec": 30 / factor, "bench.errors": 0})
    sc["poll_load"]["samples"]["pollMs"] = ms(12)
    sc["poll_load"]["values"].update({"bench.pollReqPerSec": 600 / factor, "bench.errors": 0})
    for n in (200, 1000, 2000):
        sc["scale"]["samples"].update({f"listFirstMs@{n}": ms(n / 100), f"listDeepMs@{n}": ms(n / 50),
                                       f"getMs@{n}": ms(0.7), f"postMs@{n}": ms(9)})
        sc["scale"]["values"][f"bench.actualN@{n}"] = max(n, 390)
    sc["resources"]["values"].update({"bench.peakRssMB": 410.0, "bench.jarBytes": 55_000_000})
    sc["pending_poll"]["values"]["bench.restartStartupMs"] = 8200 * factor
    sc["pending_poll"]["samples"]["pendingPollMs"] = ms(4.5)
    for name in scenarios.REAL_SCENARIOS:
        needs = scenarios.REAL_SETUP[name][2]
        if any(cap not in capabilities for cap in needs):
            sc[name] = {**scenarios.skipped("capability"), "missing": [c for c in needs if c not in capabilities]}
            continue
        sc[name]["samples"].update({"flowMs": ms(150), "completeMs": ms(130), "assetMs": ms(12), "postMs": ms(60),
                                    "serverQueuedMs": ms(20), "serverProcessingMs": ms(40),
                                    "serverTotalMs": ms(70), "queueGapMs": ms(15, 4)})
        sc[name]["values"]["bench.startupMs"] = 8500.0
    version = {"ref": label, "sha": (label.encode().hex() * 20)[:40], "title": f"title {label}",
               "description": f"<b>{label}</b> & co", "capabilities": capabilities}
    return run.suite_record(label, rep, 1, version, False, "2026-10-31T10:00:00+09:00", "2026-10-31T10:05:00+09:00",
                            300.0, {"sha": version["sha"], "bytes": 55_000_000}, {"counts": scenarios.effective_counts(False)},
                            {}, {}, sc, [])


class ReportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        root = Path(cls.tmp.name)
        data = root / "data"
        data.mkdir()
        all_caps = ["mock", "real_path", "real_upload", "prod"]
        plan = {"v0": (1.3, ["mock", "real_path"]), "v1": (1.0, all_caps), "v2": (0.7, all_caps)}
        for label, (factor, caps) in plan.items():
            for rep in (1, 2):
                run.write_json(data / f"{label}-r{rep}.json", make_suite(label, rep, factor, caps))
        run.write_json(data / "run.json", {"schema": run.RUN_SCHEMA, "startedAt": "2026-10-31T10:00:00+09:00",
                                           "finishedAt": "2026-10-31T11:00:00+09:00", "status": "ok",
                                           "command": "python scripts/perf/run.py --versions v0,v1,v2 --reps 2",
                                           "tool": {"commit": "f" * 40, "dirty": False},
                                           "env": {"os": "TestOS", "cpuModel": "Test CPU", "logicalCpus": 12,
                                                   "jvmArgs": ["-Xmx1536m"], "pinnedCpus": [10, 11]},
                                           "counts": scenarios.effective_counts(False)})
        versions = root / "versions.json"
        versions.write_text(json.dumps({"baseline": "v1", "order": ["v0", "v1", "v1p", "v2"], "pairs": [
            {"from": "v1", "to": "v2", "title": "다음 버전"}, {"from": "v0", "to": "v2"}, {"from": "v1", "to": "v1p"}],
            "versions": {label: {"ref": label, "title": f"title {label}", "description": f"<b>{label}</b> & co"}
                         for label in plan}}), encoding="utf-8")
        notes = root / "notes"
        notes.mkdir()
        (notes / "20-next.md").write_text("# 다음 버전 절차\n\n1. 측정\n2. 비교\n", encoding="utf-8")
        (notes / "10-cases.md").write_text("# 개선 사례\n\n| 지표 | 전 | 후 |\n|---|--:|--:|\n| flowMs | 40 | 28 |\n",
                                           encoding="utf-8")
        cls.html = report.build_html(report.load_data([data]), versions, notes, "python scripts/perf/report.py --data x")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_every_inline_svg_is_well_formed_xml(self):
        svgs = SVG.findall(self.html)
        self.assertGreater(len(svgs), 10)
        for svg in svgs:
            root = ET.fromstring(svg)
            self.assertEqual(root.tag, "{http://www.w3.org/2000/svg}svg")
            self.assertIsNotNone(root.find("{http://www.w3.org/2000/svg}title"))

    def test_each_chart_is_its_own_svg(self):
        # one picture per metric, so a page can break between charts (a real scenario's eight are taller than a page)
        for svg in SVG.findall(self.html):
            self.assertEqual(svg.count('class="ct"'), 1, svg[:200])
        real_charts = [svg for svg in SVG.findall(self.html) if "real · path 전송" in svg]
        self.assertEqual(len(real_charts), 8)                       # flow, complete, asset, post, queued, processing, total, gap

    def test_bar_chart_has_one_bar_per_version_in_fixed_colours(self):
        healthz = next(svg for svg in SVG.findall(self.html) if "헬스 체크" in svg)
        fills = re.findall(r'<path d="M[^"]*" fill="(#[0-9a-f]{6})"', healthz)
        # colour follows the position in the versions file order: v0 slot 1, v1 slot 2, v2 slot 4 (after v1p)
        self.assertEqual(fills, [report.SERIES[0], report.SERIES[1], report.SERIES[3]])

    def test_scale_line_charts(self):
        lines = [svg for svg in SVG.findall(self.html) if "작업 수 N" in svg]
        self.assertEqual(len(lines), 4)
        self.assertEqual(len(re.findall(r'class="line"', lines[0])), 3)
        self.assertEqual(len(re.findall(r"<circle ", lines[0])), 9)

    def test_tables_labels_and_verdicts(self):
        html = self.html
        self.assertIn("<title>Beside 백엔드 성능 보고서</title>", html)
        self.assertIn("<h1>Beside 백엔드 성능 보고서</h1>", html)
        self.assertIn("핵심 지표", html)
        self.assertIn("목록 첫 페이지 (N=2000)", html)
        self.assertIn('class="badge better"', html)  # v2 is 30 % faster
        self.assertIn('class="badge worse"', html)   # v0 is 30 % slower
        self.assertIn('class="badge same"', html)    # identical RSS
        self.assertIn('<span class="basetag">기준</span>', html)
        self.assertIn("측정 안 함", html)              # v0 has no real_upload
        self.assertIn("이 버전에 없는 기능 (real_upload, prod)", html)
        self.assertIn("&lt;b&gt;v0&lt;/b&gt; &amp; co", html)  # descriptions are escaped
        self.assertNotIn("<b>v0</b>", html)
        self.assertIn("TestOS", html)
        self.assertIn("p50 / p95", html)
        for label in ("v0", "v1", "v2"):
            self.assertIn(f"{label}-r1.json", html)

    def test_idle_wait_rows_and_method_sentences(self):
        html = self.html
        # key metric: the server CPU behind the large uploads (work moved out of a request stays in this number);
        # its value is rendered with a change rate but never a verdict (tick-granular, SMT-shared)
        start = html.index("큰 사진 업로드의 서버 CPU 점유")
        row = html[start:html.index("</tr>", start)]
        self.assertIn("4,000", row)                                 # v1: 4000 ms x factor 1.0
        self.assertIn("+30.0%", row)                                # v0: factor 1.3
        self.assertNotIn("badge", row)
        self.assertIn("서버 CPU 점유 시간 (시나리오 + 뒤따른 백그라운드 일)", html)
        self.assertIn("끝난 뒤 서버가 쉴 때까지 더 기다린 시간 (확인 창 제외)", html)
        self.assertIn("서버가 쉴 때까지 기다리지 않았다", html)        # the fixture suites carry no settings.settle
        self.assertIn("프로세스 우선순위: 기록 없음", html)          # this run.json predates the priority record
        self.assertIn("'보통 이상'(Windows ABOVE_NORMAL)", report.priority_text(
            {"env": {"priority": {"servers": "above_normal", "loadGenerator": "above_normal"}}}))
        self.assertIn("부하 생성기는 올리지 못했다", report.priority_text(
            {"env": {"priority": {"servers": "above_normal", "loadGenerator": "normal"}}}))
        self.assertIn("바꾸지 않았다", report.priority_text({"env": {"priority": {"servers": "normal"}}}))

    def test_idle_waits_are_values_without_a_change_rate_or_chart(self):
        # a wait is whole check windows (0.5 ms against 1,000 ms is one window more): a change rate would read as news
        label = "끝난 뒤 서버가 쉴 때까지 더 기다린 시간 (확인 창 제외)"
        rows = [self.html[m.start():self.html.find("</tr>", m.start())]
                for m in re.finditer(re.escape(label), self.html)]
        row = next(r for r in rows if "1,000" in r)                 # upload_large: 1000 ms in every version
        self.assertNotIn("%", row)
        self.assertNotIn("badge", row)
        self.assertFalse([svg for svg in SVG.findall(self.html) if label in svg])

    def test_report_tool_line_flags_uncommitted_changes(self):
        answers = {"rev-parse": "c" * 40, "status": " M scripts/perf/report.py"}
        with mock.patch.object(report, "_git", side_effect=lambda *args: answers[args[0]]):
            self.assertIn("(scripts/perf 에 커밋 안 된 변경 있음)", report.report_tool_line())
            answers["status"] = ""
            line = report.report_tool_line()
        self.assertIn("c" * 40, line)
        self.assertNotIn("커밋 안 된", line)

    def test_settle_sentence_comes_from_the_suites(self):
        waited, older = make_suite("v1", 1, 1.0, ["mock"]), make_suite("v0", 1, 1.0, ["mock"])
        waited["settings"]["settle"] = dict(scenarios.SETTLE)
        text = report.settle_text([waited])
        self.assertIn("한 코어의 5 % 이하", text)
        self.assertIn("1초 창(최대 120초)", text)
        self.assertIn("0.5초 창(최대 30초)", text)
        self.assertNotIn("참고용", text)
        self.assertIn("대기 없는 이전 결과 포함", report.settle_text([waited, older]))
        self.assertIn("기다리지 않았다", report.settle_text([older]))

    def test_suites_measured_with_and_without_idle_waits_are_not_pooled(self):
        with tempfile.TemporaryDirectory() as root:
            old = make_suite("v1", 1, 1.0, ["mock"])
            new = make_suite("v1", 2, 1.0, ["mock"])
            new["settings"].update(settle=dict(scenarios.SETTLE), priority="above_normal")
            first = write_data(Path(root) / "old", [old])
            second = write_data(Path(root) / "new", [new])
            with self.assertRaises(ValueError) as caught:
                report.build_html(report.load_data([first, second]), Path(root) / "none.json", None, "cmd")
            message = str(caught.exception)
            self.assertIn("full, no idle waits, priority None [old/v1-r1.json]", message)
            self.assertIn("full, idle waits, priority above_normal [new/v1-r2.json]", message)

    def test_fresh_file_open_sentence(self):
        slow = {"env": {"freshFileOpen": {"bytes": 4_542_211, "firstMs": 200.4, "secondMs": 1.2}}}
        quick = {"env": {"freshFileOpen": {"bytes": 4_542_211, "firstMs": 2.0, "secondMs": 1.1}}}
        self.assertIn("4.5 MB 사진 사본을 쓰자마자 열어 읽는 데 200 ms", report.fresh_open_text(slow))
        self.assertIn("실시간 백신 검사로 보인다", report.fresh_open_text(slow))
        self.assertIn("차이 없음", report.fresh_open_text(quick))
        self.assertEqual(report.fresh_open_text({"env": {}}), "")
        self.assertNotIn("갓 쓴 파일", self.html)  # the fixture's run.json has no such record

    def test_pairs_table_compares_each_pair_on_its_own_reference(self):
        start = self.html.index("<h2>쌍 비교</h2>")
        table = self.html[start:self.html.index("</table>", start)]
        self.assertIn('다음 버전<div class="unit">v1 → v2</div>', table)
        self.assertIn('v0 → v2<div class="unit">v0 → v2</div>', table)  # no title: the pair names itself
        self.assertIn("측정하지 않은 버전이 든 쌍은 뺐다: v1 → v1p", table)
        self.assertNotIn("v1p<div", table)
        self.assertIn('class="badge better"', table)  # both pairs end at the 30 % faster v2
        self.assertNotIn('class="badge worse"', table)
        self.assertIn('class="badge same"', table)    # identical RSS
        self.assertLess(start, self.html.index("<h1>측정 방법</h1>"))  # on the cover, after the key metrics

    def test_scale_rows_name_the_n_they_were_measured_at(self):
        # earlier scenarios had made 390 jobs before scale started: the "200" rows were measured at 390
        self.assertIn("목록 첫 페이지 (limit 20) · N=390 (목표 200)", self.html)
        self.assertIn("단건 조회 · N=1000<", self.html)
        self.assertNotIn("· N=200<", self.html)
        self.assertIn("목록 첫 페이지 (N=2000)", self.html)  # cover row: the largest N was reached as planned

    def test_sections_and_notes_order(self):
        html = self.html
        positions = [html.index(text) for text in ("<h1>측정 방법</h1>", "<h1>시나리오별 결과</h1>",
                                                   "<h1>개선 사례</h1>", "<h1>다음 버전 절차</h1>", "<h1>부록</h1>")]
        self.assertEqual(positions, sorted(positions))
        self.assertIn('<td style="text-align:right">28</td>', html)
        self.assertIn("<ol><li>측정</li><li>비교</li></ol>", html)
        self.assertEqual(html.count('<section class="major'), 2 + 2 + 1)  # method, results, two notes, appendix

    def test_skipped_everywhere_and_missing_baseline(self):
        with tempfile.TemporaryDirectory() as folder:
            data = Path(folder)
            suite = make_suite("v9", 1, 1.0, ["mock"])
            run.write_json(data / "v9-r1.json", suite)
            html = report.build_html(report.load_data([data]), data / "none.json", None, "cmd")
        self.assertIn("기준 버전 없음", html)
        self.assertIn("반복 1회", html)
        self.assertIn("측정 안 함 — 이 버전에 없는 기능", html)
        for svg in SVG.findall(html):
            ET.fromstring(svg)


class ErrorsAndZeroBaselineTest(unittest.TestCase):
    @staticmethod
    def _suites(label, errors_by_rep):
        """Suites of one version whose concurrent_e2e had these error counts, one per repetition."""
        suites = []
        for rep, errors in enumerate(errors_by_rep, 1):
            suite = make_suite(label, rep, 1.0, ["mock"])
            result = suite["scenarios"]["concurrent_e2e"]
            result["values"]["bench.errors"] = errors
            if errors:
                result.update(status="error", reason=f"{errors} failed flows (bench.errors)")
                suite["status"] = "error"
            suite["_dir"], suite["_file"] = "data", f"{label}-r{rep}.json"
            suites.append(suite)
        return suites

    def test_errors_are_summed_shown_red_and_never_called_noise(self):
        row = ("value", "bench.errors")
        base = report.entry(self._suites("v1", [0, 0, 0]), "concurrent_e2e", row)
        cand = report.entry(self._suites("v2", [5, 6, 5]), "concurrent_e2e", row)
        self.assertEqual(cand["value"], 16)
        html = report.cell(cand, row, base, False, report.row_meta(row)[2])
        self.assertTrue(html.startswith('<td class="err">'), html)
        self.assertIn("16", html)
        self.assertIn("3회 합계", html)
        self.assertNotIn("badge", html)
        self.assertNotIn("차이 없음", html)
        # a failure in one repetition out of three is not hidden by a median of 0
        partial = report.entry(self._suites("v2", [5, 0, 0]), "concurrent_e2e", row)
        self.assertEqual((partial["value"], partial["state"]), (5, "error"))
        self.assertIn("오류 포함", report.cell(partial, row, base, False, None))
        self.assertTrue(report.cell(base, row, None, True, None).startswith("<td>"))  # 0 errors: not red

    def test_background_load_is_reported_and_warned_about(self):
        quiet, busy = self._suites("v1", [0, 0]), self._suites("v2", [0])
        for suite, pct in zip(quiet + busy, (3.0, 5.0, 18.0)):
            suite["settings"]["backgroundCpuPct"] = pct
        self.assertEqual(report.background_load(quiet + busy), (3.0, 5.0, 18.0, 3))
        self.assertIsNone(report.background_load(self._suites("v1", [0])))  # older results: no sentence at all
        self.assertIn("최소 3 %, 중앙 5 %, 최대 18 %", report.background_text(report.background_load(quiet + busy)))
        with tempfile.TemporaryDirectory() as folder:
            data = Path(folder)
            for suite in quiet + busy:
                run.write_json(data / f"{suite['label']}-r{suite['rep']}.json", suite)
            html = report.build_html(report.load_data([data]), data / "none.json", None, "cmd")
            self.assertIn("다른 프로그램이 돌던 PC", html)
            self.assertIn("다른 프로그램의 부하", html)
            for suite in busy:
                suite["settings"]["backgroundCpuPct"] = 4.0
                run.write_json(data / f"{suite['label']}-r{suite['rep']}.json", suite)
            html = report.build_html(report.load_data([data]), data / "none.json", None, "cmd")
            self.assertNotIn("다른 프로그램이 돌던 PC", html)  # below 10 %: the sentence stays, the banner goes
            self.assertIn("다른 프로그램의 부하", html)

    def test_zero_baseline_is_not_comparable(self):
        row = ("sample", "healthzMs")
        base_suites, cand_suites = self._suites("v1", [0]), self._suites("v2", [0])
        base_suites[0]["scenarios"]["healthz"]["samples"]["healthzMs"] = [0.0, 0.0]
        cand_suites[0]["scenarios"]["healthz"]["samples"]["healthzMs"] = [1.0, 2.0]
        html = report.cell(report.entry(cand_suites, "healthz", row), row, report.entry(base_suites, "healthz", row),
                           False, True)
        self.assertIn("비교 불가", html)
        self.assertNotIn("badge", html)

    def test_cover_banner_names_version_repetition_and_scenario(self):
        suites = self._suites("v0", [0, 3]) + self._suites("v1", [0])
        self.assertEqual(report.failed_suites(suites), ["v0 r2 (concurrent_e2e)"])
        with tempfile.TemporaryDirectory() as folder:
            for suite in suites:
                run.write_json(Path(folder) / suite["_file"], {k: v for k, v in suite.items() if k[0] != "_"})
            html = report.build_html(report.load_data([folder]), Path(folder) / "none.json", None, "cmd")
        self.assertIn("오류가 있는 측정</b>: v0 r2 (concurrent_e2e)", html)


def write_data(folder: Path, suites, env=None, work_dir=""):
    """A results folder: the suites and a run.json with this environment."""
    folder.mkdir(parents=True, exist_ok=True)
    for suite in suites:
        run.write_json(folder / f"{suite['label']}-r{suite['rep']}.json", suite)
    run.write_json(folder / "run.json", {"schema": run.RUN_SCHEMA, "startedAt": "2026-10-31T10:00:00+09:00",
                                         "env": env or {}, "workDir": work_dir,
                                         "counts": suites[0]["settings"]["counts"]})
    return folder


def quick_suite(label, rep, capabilities=("mock",)):
    suite = make_suite(label, rep, 1.0, list(capabilities))
    suite["quick"] = True
    suite["settings"] = {"counts": scenarios.effective_counts(True)}
    return suite


class ConditionsTest(unittest.TestCase):
    def test_one_version_measured_two_ways_is_not_pooled(self):
        with tempfile.TemporaryDirectory() as root:
            full = write_data(Path(root) / "full", [make_suite("v1", 1, 1.0, ["mock"])])
            quick = write_data(Path(root) / "quick", [quick_suite("v1", 2)])
            with self.assertRaises(ValueError) as caught:
                report.build_html(report.load_data([full, quick]), Path(root) / "none.json", None, "cmd")
            message = str(caught.exception)
            self.assertIn("v1: full [full/v1-r1.json] vs --quick [quick/v1-r2.json]", message)
            self.assertIn("healthz.n", message)
            html_out = Path(root) / "report.html"
            with mock.patch("sys.stdout", new_callable=io.StringIO) as printed:
                code = report.main(["--data", str(full), "--data", str(quick), "--versions-file",
                                    str(Path(root) / "none.json"), "--html-out", str(html_out),
                                    "--out", str(Path(root) / "report.pdf")])
            self.assertEqual(code, 2)
            self.assertIn("report not built", printed.getvalue())
            self.assertFalse(html_out.exists())

    def test_versions_measured_with_different_counts_are_flagged(self):
        with tempfile.TemporaryDirectory() as root:
            quick = write_data(Path(root) / "q-run", [quick_suite("v0", 1)])
            full = write_data(Path(root) / "f-run", [make_suite("v1", 1, 1.0, ["mock"])])
            html = report.build_html(report.load_data([quick, full]), Path(root) / "none.json", None, "cmd")
        self.assertIn("측정 조건이 다른 데이터", html)
        self.assertIn("빠른 측정(--quick)이 섞여 있다</b>: v0", html)
        self.assertIn("<h2>데이터별 조건</h2>", html)
        self.assertIn("<code>q-run</code> (v0): --quick", html)
        self.assertIn("<code>f-run</code> (v1): 보고용 횟수", html)
        self.assertIn("healthz.n=50", html)
        self.assertIn("healthz.n=500", html)

    def test_condition_sentences_come_from_run_json(self):
        pinned = {"os": "Linux-6.1-x86_64", "java": 'openjdk version "21.0.2"\nOpenJDK Runtime', "serverCpus": 2,
                  "pinnedCpus": [10, 11], "pinnedCores": 1,
                  "jvmArgs": ["-Xms256m", "-XX:ActiveProcessorCount=2"]}
        unpinned = {"os": "Windows-11", "serverCpus": 0, "pinnedCpus": None, "jvmArgs": ["-Xms256m"]}
        with tempfile.TemporaryDirectory() as root:
            a = write_data(Path(root) / "a", [make_suite("v1", 1, 1.0, ["mock"])], pinned, "/tmp/beside-perf")
            html_pinned = report.build_html(report.load_data([a]), Path(root) / "none.json", None, "cmd")
            b = write_data(Path(root) / "b", [make_suite("v1", 1, 1.0, ["mock"])], unpinned,
                           "C:/Users/x/OneDrive/work")
            html_unpinned = report.build_html(report.load_data([b]), Path(root) / "none.json", None, "cmd")
        for html in (html_pinned, html_unpinned):
            self.assertNotIn("CPU 2개에 고정했지만", html)
            self.assertNotIn("JDK 17 Windows", html)
            self.assertNotIn("OS 임시 폴더의 새 폴더", html)
            self.assertIn("서버 B 는 A 의 저장소를 그대로 다시 연다", html)
        self.assertIn("물리 코어 1개를 SMT 로 나눈 논리 CPU 2개", html_pinned)
        self.assertIn("ActiveProcessorCount=2</code> 로 CPU 2개라고 알렸다", html_pinned)
        self.assertIn('openjdk version &quot;21.0.2&quot; · Linux-6.1-x86_64', html_pinned)
        self.assertIn("<code>/tmp/beside-perf</code>", html_pinned)
        self.assertNotIn("OneDrive 안이라", html_pinned)
        self.assertIn("서버 CPU 를 고정하지 않았다(--server-cpus 0)", html_unpinned)
        self.assertIn("같은 CPU 를 다투고", html_unpinned)
        self.assertIn("OneDrive 안이라", html_unpinned)

    def test_notes_name_version_and_repetition(self):
        def suite(label, rep, notes):
            s = make_suite(label, rep, 1.0, ["mock"])
            s["scenarios"]["scale"]["notes"] = notes
            s["_dir"], s["_file"] = "data", f"{label}-r{rep}.json"
            return s

        groups = {"v0": [suite("v0", 1, ["N=200: not every awaited job COMPLETED", "server lists 5 jobs"]),
                         suite("v0", 2, ["N=200: not every awaited job COMPLETED"])],
                  "v1": [suite("v1", 1, ["server lists 5 jobs"] + [f"note {i}" for i in range(5)])]}
        notes = report.scenario_notes(["v0", "v1"], groups, "scale")
        self.assertEqual(notes[:3], ["v0 r1·r2: N=200: not every awaited job COMPLETED",
                                     "v0 r1: server lists 5 jobs", "v1 r1: server lists 5 jobs"])
        self.assertEqual(notes[-1], "v1: 메모 2개 더 (결과 JSON 의 notes)")  # at most 4 per version
        self.assertEqual(len(notes), 2 + 4 + 1)


class HelpersTest(unittest.TestCase):
    def test_nice_ticks(self):
        self.assertEqual(report.nice_ticks(7.3), ([0, 2, 4, 6, 8], 8))
        ticks, top = report.nice_ticks(1234)
        self.assertEqual(top, 1250)
        self.assertEqual(ticks[1], 250)
        self.assertEqual(report.nice_ticks(0), ([0.0, 1.0], 1.0))

    def test_formats(self):
        self.assertEqual(report.fmt_delta(-12.34), "−12.3%")
        self.assertEqual(report.fmt_delta(5), "+5.0%")
        self.assertEqual(report.fmt_delta(0.01), "±0.0%")
        self.assertEqual(report.fmt_ms(0.456), "0.46")
        self.assertEqual(report.fmt_ms(45.6), "45.6")
        self.assertEqual(report.fmt_ms(1234.5), "1,234")
        self.assertEqual(report.fmt_tick(1500), "1.5K")
        self.assertEqual(report.fmt_tick(2.5), "2.5")
        self.assertIsNone(report.find_browser("Z:/no/such/browser.exe"))


class MdliteTest(unittest.TestCase):
    def test_blocks(self):
        html = mdlite.render("# 제목\n## 둘\n### 셋\n\n첫 줄\n이어지는 줄\n\n- 가\n- 나\n  계속\n\n1. 하나\n2. 둘\n")
        self.assertIn("<h1>제목</h1>", html)
        self.assertIn("<h2>둘</h2>", html)
        self.assertIn("<h3>셋</h3>", html)
        self.assertIn("<p>첫 줄 이어지는 줄</p>", html)
        self.assertIn("<ul><li>가</li><li>나 계속</li></ul>", html)
        self.assertIn("<ol><li>하나</li><li>둘</li></ol>", html)

    def test_ordered_list_keeps_its_first_number(self):
        html = mdlite.render("1. 하나\n\n```\ncode\n```\n\n5. 다섯\n6. 여섯\n")
        self.assertIn("<ol><li>하나</li></ol>", html)
        self.assertIn('<ol start="5"><li>다섯</li><li>여섯</li></ol>', html)

    def test_inline_and_escaping(self):
        html = mdlite.render("**굵게** `a<b>|c` [링크](https://example.com/x) <script>alert(1)</script> & 끝")
        self.assertIn("<strong>굵게</strong>", html)
        self.assertIn("<code>a&lt;b&gt;|c</code>", html)
        self.assertIn("링크", html)
        self.assertNotIn("example.com", html)
        self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt; &amp; 끝", html)
        self.assertNotIn("<script>", html)

    def test_table(self):
        html = mdlite.render("| 지표 | 값 | 비고 |\n|:---|---:|:---:|\n| `a|b` | **1** | x |\n| 짧은 행 |\n")
        self.assertIn("<thead><tr><th style=\"text-align:left\">지표</th>", html)
        self.assertIn('<td style="text-align:left"><code>a|b</code></td>', html)
        self.assertIn('<td style="text-align:right"><strong>1</strong></td>', html)
        self.assertIn('<td style="text-align:center"></td>', html)  # short rows are padded

    def test_code_fence_offset_and_rule(self):
        html = mdlite.render("```\n<x> **no**\n```\n---\n# A", heading_offset=1)
        self.assertIn("<pre><code>&lt;x&gt; **no**</code></pre>", html)
        self.assertIn("<hr>", html)
        self.assertIn("<h2>A</h2>", html)
        self.assertEqual(mdlite.render(""), "")


if __name__ == "__main__":
    unittest.main()
