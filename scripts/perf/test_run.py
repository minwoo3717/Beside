"""run.py and the result plumbing of scenarios.py: rotation, events.jsonl, counts, schema round trip, jar cache."""
import json
import tempfile
import unittest
import zipfile
from datetime import datetime, timezone
from pathlib import Path

import run
import scenarios


def _event(job_id, finished, processing, result="COMPLETED", **extra):
    event = {"jobId": job_id, "attempt": 1, "finishedAt": finished, "uploadBytes": 10, "uploadMs": 3,
             "queuedMs": 5, "processingMs": processing, "totalMs": 5 + (processing or 0), "workerType": "real",
             "result": result, "inferMs": None, "gpuPeakMB": None, "modelParams": None, "outputVertices": None,
             "outputTriangles": None, "convertMs": 0, "glbBytes": 1500000, "passthrough": True, "modelVersion": None}
    event.update(extra)
    return event


class RotationTest(unittest.TestCase):
    def test_each_repetition_starts_one_later(self):
        self.assertEqual(run.rotation(["v0", "v1", "v2"], 3),
                         [["v0", "v1", "v2"], ["v1", "v2", "v0"], ["v2", "v0", "v1"]])

    def test_more_repetitions_than_versions_wrap(self):
        self.assertEqual(run.rotation(["a", "b"], 3), [["a", "b"], ["b", "a"], ["a", "b"]])
        self.assertEqual(run.rotation(["a"], 2), [["a"], ["a"]])
        self.assertEqual(run.rotation([], 2), [])


class CountsTest(unittest.TestCase):
    def test_quick_divides_counts_but_not_the_shape(self):
        full, quick = scenarios.effective_counts(False), scenarios.effective_counts(True)
        self.assertEqual(full["healthz.n"], 500)
        self.assertEqual(quick["healthz.n"], 50)
        self.assertEqual(quick["upload_large.n"], 2)
        self.assertEqual(quick["upload_large.warmup"], 2)  # 2 // 10 = 0 -> at least 2
        self.assertEqual(quick["poll_load.perThread"], 25)
        self.assertEqual(quick["concurrent_e2e.threads"], 16)
        self.assertEqual(quick["poll_load.processes"], 4)
        self.assertEqual(full["scale.targets"], [200, 1000, 2000])
        self.assertEqual(quick["scale.targets"], [50, 100, 200])
        self.assertTrue(all(v >= 2 for k, v in quick.items() if k in scenarios.COUNTS))


class EventsTest(unittest.TestCase):
    def test_instant_parsing_keeps_sub_millisecond_digits(self):
        base = datetime(2026, 10, 4, 8, 35, 21, tzinfo=timezone.utc).timestamp() * 1000
        self.assertAlmostEqual(scenarios.parse_instant_ms("2026-10-04T08:35:21.148009200Z"), base + 148.0092, places=4)
        self.assertAlmostEqual(scenarios.parse_instant_ms("2026-10-04T17:35:21+09:00"), base, places=4)
        self.assertAlmostEqual(scenarios.parse_instant_ms("2026-10-04T08:35:21Z"), base, places=4)
        with self.assertRaises(ValueError):
            scenarios.parse_instant_ms("yesterday")

    def test_parse_skips_bad_lines(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "events.jsonl"
            lines = [json.dumps(_event("a", "2026-10-31T00:00:00.100Z", 40)), "", "{not json",
                     json.dumps({"attempt": 1}), json.dumps(_event("b", "2026-10-31T00:00:00.200Z", 40)),
                     json.dumps(_event("c", "2026-10-31T00:00:00.300Z", None, result="FAILED:INFERENCE_FAILED"))]
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            events, bad = scenarios.parse_events(path)
            self.assertEqual([e["jobId"] for e in events], ["a", "b", "c"])
            self.assertEqual(bad, 2)
            self.assertEqual(scenarios.parse_events(Path(folder) / "missing.jsonl"), ([], 0))

    def test_aggregate_per_job_group(self):
        events = [_event("a", "2026-10-31T00:00:00.100Z", 40), _event("b", "2026-10-31T00:00:00.200Z", 42),
                  _event("c", "2026-10-31T00:00:00.300Z", None, result="FAILED:INFERENCE_FAILED")]
        out = scenarios.aggregate_events(events, ["a", "c", "zzz"])
        self.assertEqual(out["n"], 2)
        self.assertEqual(out["jobs"], 3)
        self.assertEqual(out["missing"], 1)
        self.assertEqual(out["results"], {"COMPLETED": 1, "FAILED:INFERENCE_FAILED": 1})
        self.assertEqual(out["samples"]["processingMs"], [40])  # null values are left out
        self.assertEqual(out["samples"]["queuedMs"], [5, 5])
        self.assertEqual(out["passthrough"], 2)

    def test_queue_gaps_from_finish_and_processing_time(self):
        # runs: a 1000-1040, b 1050-1090, c 1100-1150 (ms after the second): gaps 10 and 10
        events = [_event("c", "2026-10-31T00:00:01.150Z", 50), _event("a", "2026-10-31T00:00:01.040Z", 40),
                  _event("b", "2026-10-31T00:00:01.090Z", 40),
                  _event("x", "2026-10-31T00:00:01.500Z", 40),  # not in the batch
                  _event("d", "2026-10-31T00:00:01.700Z", 10, result="FAILED:CONVERSION_FAILED")]
        gaps = scenarios.queue_gaps(events, ["a", "b", "c", "d"])
        self.assertEqual(len(gaps), 2)
        for gap in gaps:
            self.assertAlmostEqual(gap, 10.0, places=3)


class SchemaTest(unittest.TestCase):
    def _suite(self):
        ok = scenarios.new_result()
        ok["samples"]["healthzMs"] = [0.51, 0.62]
        ok["values"]["bench.errors"] = 0
        results = {"healthz": ok, "real_upload": {**scenarios.skipped("capability"), "missing": ["real_upload"]}}
        version = {"ref": "af4a747", "sha": "a" * 40, "title": "t", "description": "d", "capabilities": ["mock"]}
        return run.suite_record("v1", 2, 1, version, True, "2026-10-31T10:00:00+09:00",
                                "2026-10-31T10:01:00+09:00", 60.0, {"sha": "a" * 40, "bytes": 1},
                                {"counts": scenarios.effective_counts(True)}, {"png_small": {"bytes": 1}},
                                {"A-mock": {"stop": "graceful"}}, results, [])

    def test_round_trip(self):
        suite = self._suite()
        self.assertEqual(run.validate_suite(suite), [])
        self.assertEqual(suite["status"], "ok")
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "v1-r2.json"
            run.write_json(path, suite)
            loaded = json.loads(path.read_text(encoding="utf-8"))
            self.assertFalse(list(Path(folder).glob("*.tmp")))
        self.assertEqual(loaded, suite)
        self.assertEqual(run.validate_suite(loaded), [])

    def test_status_and_problems(self):
        suite = self._suite()
        suite["scenarios"]["healthz"]["status"] = "error"
        self.assertEqual(run.validate_suite(suite), ["healthz: error without a reason"])
        broken = self._suite()
        del broken["scenarios"]["healthz"]["samples"]
        broken["scenarios"]["real_upload"]["values"]["bench.x"] = "fast"
        broken["scenarios"]["nonsense"] = scenarios.new_result()
        problems = run.validate_suite(broken)
        self.assertIn("healthz: missing samples", problems)
        self.assertIn("real_upload.bench.x: value must be a number or null", problems)
        self.assertIn("unknown scenario nonsense", problems)
        errored = run.suite_record("v1", 1, 1, {}, False, "", "", 0, {}, {}, {}, {},
                                   {"healthz": {**scenarios.new_result(), "status": "error", "reason": "boom"}}, [])
        self.assertEqual(errored["status"], "error")


class JarCacheTest(unittest.TestCase):
    def test_jar_check_and_build_lock(self):
        with tempfile.TemporaryDirectory() as folder:
            good = Path(folder) / "good.jar"
            with zipfile.ZipFile(good, "w") as jar:
                jar.writestr("META-INF/MANIFEST.MF", "Start-Class: com.example.mockbackend.MockBackendApplication\n")
                jar.writestr(run.BOOT_CLASS, b"\xca\xfe\xba\xbe")
            self.assertIsNone(run.jar_problem(good))
            plain = Path(folder) / "plain.jar"
            with zipfile.ZipFile(plain, "w") as jar:
                jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            self.assertIn("not the Beside backend", run.jar_problem(plain))
            (Path(folder) / "junk.jar").write_bytes(b"junk")
            self.assertIn("not a readable jar", run.jar_problem(Path(folder) / "junk.jar"))
            first, second = run._BuildLock(Path(folder) / "x.lock"), run._BuildLock(Path(folder) / "x.lock")
            self.assertTrue(first.acquire(timeout_s=1))
            self.assertFalse(second.acquire(timeout_s=0))
            first.release()
            self.assertTrue(second.acquire(timeout_s=1))
            second.release()
            self.assertFalse((Path(folder) / "x.lock").exists())


if __name__ == "__main__":
    unittest.main()
