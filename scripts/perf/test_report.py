"""report.py and mdlite.py: HTML from synthetic results of three versions, valid inline SVG, tables, labels."""
import json
import random
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

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
    sc["upload_large"]["values"]["bench.uploadMBps"] = 100 / factor
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
        versions.write_text(json.dumps({"baseline": "v1", "order": ["v0", "v1", "v1p", "v2"], "versions": {
            label: {"ref": label, "title": f"title {label}", "description": f"<b>{label}</b> & co"}
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
