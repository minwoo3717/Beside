"""cap_model.py: the queue-cap rule, trace replay, Monte Carlo and retry traffic for agenda #21 ([계산]).

The expected cap values below come from the approved plan's table (c=1, S_busy 8.67 s, mu 415/h, 4th round), not from
cap_model.py's output. The replay fixture is built by hand from the same FIFO slot model, so an uncapped replay must
give back its own processing starts exactly. The g3c1 tests read the committed run folder of the 4th round.
"""
import contextlib
import datetime as dt
import io
import json
import tempfile
import unittest
from pathlib import Path

import cap_model

HERE = Path(__file__).resolve().parent
G3C1 = HERE.parent.parent / "docs" / "perf" / "load" / "data" / "2026-11-20_0256-g3c1"
S_BUSY = 8.67
MU = 415.0


class FormulaTest(unittest.TestCase):
    def test_c1_table_of_the_plan(self):
        # W -> (N, worst wait rounded, Retry-After header)
        expected = {120: (12, 113, 104), 180: (19, 173, 165), 300: (33, 295, 286), 600: (68, 598, 590)}
        for w, (n, worst, r) in expected.items():
            with self.subTest(w=w):
                self.assertEqual(cap_model.cap_c1(w, S_BUSY), n)
                self.assertEqual(round(cap_model.worst_c1(n, S_BUSY)), worst)
                self.assertEqual(cap_model.retry_after_header(n, MU), r)

    def test_c1_boundary(self):
        # exactly W = 2 S gives N = 1 (worst wait 2 S = W); just below it no cap >= 1 meets the target
        self.assertEqual(cap_model.cap_c1(2 * S_BUSY, S_BUSY), 1)
        self.assertEqual(cap_model.cap_c1(3 * S_BUSY, S_BUSY), 2)
        self.assertEqual(cap_model.cap_c1(3 * S_BUSY - 0.01, S_BUSY), 1)
        with self.assertRaises(ValueError):
            cap_model.cap_c1(2 * S_BUSY - 0.01, S_BUSY)
        for bad in ((180, 0), (180, -1), (0, S_BUSY)):
            with self.assertRaises(ValueError):
                cap_model.cap_c1(*bad)

    def test_c1_cap_is_the_largest_that_meets_the_target(self):
        for w in (20, 60, 120, 180, 240, 300, 450, 600, 900):
            with self.subTest(w=w):
                n = cap_model.cap_c1(w, S_BUSY)
                self.assertLessEqual(cap_model.worst_c1(n, S_BUSY), w + 1e-9)
                self.assertGreater(cap_model.worst_c1(n + 1, S_BUSY), w)

    def test_c2_rule_uses_measured_throughput(self):
        n = cap_model.cap_rate(180, 495, 9.86)
        self.assertEqual(n, 23)
        self.assertAlmostEqual(cap_model.retry_after(n, 495), 167.27, places=2)
        self.assertEqual(cap_model.retry_after_header(n, 495), 167)
        self.assertLessEqual(cap_model.worst_rate(n, 495, 9.86), 180)
        self.assertGreater(cap_model.worst_rate(n + 1, 495, 9.86), 180)
        with self.assertRaises(ValueError):
            cap_model.cap_rate(9.86, 495, 9.86)

    def test_c1_rule_is_the_rate_rule_with_mu_3600_over_s(self):
        for w in (30, 120, 180, 300, 600, 1000):
            with self.subTest(w=w):
                self.assertEqual(cap_model.cap_rate(w, 3600 / S_BUSY, S_BUSY), cap_model.cap_c1(w, S_BUSY))

    def test_slot_time_drift(self):
        # 2nd round S0 = 11.5 s: N 19 still under 600 s, the formula value 68 is not
        self.assertAlmostEqual(cap_model.worst_c1(19, 11.5), 230.0, places=9)
        self.assertAlmostEqual(cap_model.worst_c1(68, 11.5), 793.5, places=9)

    def test_retry_after_header_is_a_positive_integer(self):
        self.assertEqual(cap_model.retry_after_header(1, 100000), 1)
        self.assertEqual(cap_model.retry_after_header(19, 415), 165)
        with self.assertRaises(ValueError):
            cap_model.retry_after(19, 0)


class FillTest(unittest.TestCase):
    def test_fill_minutes_from_net_arrivals(self):
        # 4th round: 471/h arrived, 415/h capacity -> net 56/h, N = 19 fills in about 20 min
        self.assertAlmostEqual(cap_model.fill_minutes(19, 471, 415), 19 / 56 * 60, places=9)
        self.assertAlmostEqual(cap_model.fill_minutes(68, 471, 415), 68 / 56 * 60, places=9)
        self.assertIsNone(cap_model.fill_minutes(19, 415, 415))
        self.assertIsNone(cap_model.fill_minutes(19, 300, 415))


def write_run(folder: Path, start: str, end: str, lines: list) -> None:
    (folder / "attachments").mkdir(parents=True)
    run = {"schema": "beside-load-run/1", "window": {"start": start, "end": end},
           "attachments": [{"name": "events.jsonl", "file": "attachments/events.jsonl"}]}
    (folder / "run.json").write_text(json.dumps(run), encoding="utf-8")
    with open(folder / "attachments" / "events.jsonl", "w", encoding="utf-8", newline="\n") as fh:
        for e in lines:
            fh.write(json.dumps(e) + "\n")


def event(t0: dt.datetime, job: str, a: float, pre: float, ps: float, proc: float, attempt: int = 1) -> dict:
    """One events.jsonl line: created at t0 + a, processing start at t0 + ps (seconds); ms fields as the backend writes."""
    queued = round((ps - a) * 1000)
    total = queued + round(proc * 1000)
    fin = t0 + dt.timedelta(seconds=a) + dt.timedelta(milliseconds=total)
    return {"jobId": job, "attempt": attempt, "finishedAt": fin.strftime("%Y-%m-%dT%H:%M:%S.%f") + "123Z",
            "queuedMs": queued, "processingMs": round(proc * 1000), "totalMs": total,
            "preprocessMs": round(pre * 1000), "result": "COMPLETED"}


class ReplayFixtureTest(unittest.TestCase):
    """Four jobs built with the FIFO slot model itself: c = 1, cutout 0.5 s, processing 10 s, healthz h = 0.25 s before
    processing and DELETE g = 0.25 s after it (handover 0.5 s). Job i: (created, processing start).
      job0 (1, 1.75)   job1 (2, 12.25)   job2 (3, 22.75)   job3 (31, 33.25)
    Plus three lines the loader must drop: one created before the window, one after it, one retry (attempt 2)."""

    T0 = dt.datetime(2026, 11, 19, 10, 0, 0, tzinfo=dt.timezone.utc)
    JOBS = [(1.0, 1.75), (2.0, 12.25), (3.0, 22.75), (31.0, 33.25)]

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name) / "run"
        lines = [event(self.T0, f"before", -100.0, 0.5, -99.0, 10.0)]
        lines += [event(self.T0, f"job{i}", a, 0.5, ps, 10.0) for i, (a, ps) in enumerate(self.JOBS)]
        lines += [event(self.T0, "job0", 50.0, 0.0, 50.5, 10.0, attempt=2), event(self.T0, "after", 700.0, 0.5, 701.0, 10.0)]
        write_run(self.dir, "2026-11-19T10:00:00+00:00", "2026-11-19T10:10:00+00:00", lines)

    def tearDown(self):
        self.tmp.cleanup()

    def test_window_filter_keeps_first_attempts_created_inside(self):
        run = cap_model.load_run(self.dir)
        self.assertEqual([round(j["a"], 3) for j in run["jobs"]], [1.0, 2.0, 3.0, 31.0])
        self.assertEqual(run["lines"], 7)
        self.assertEqual(run["outside"], 2)
        self.assertEqual(run["retries"], 1)

    def test_handover_and_busy_slot_time(self):
        run = cap_model.load_run(self.dir)
        self.assertAlmostEqual(cap_model.handover(run["jobs"]), 0.5, places=3)
        self.assertAlmostEqual(cap_model.s_busy(run["jobs"]), 10.5, places=3)

    def test_uncapped_replay_reproduces_its_own_waits(self):
        run = cap_model.load_run(self.dir)
        res = cap_model.replay(run, cap=0)
        self.assertEqual(res["refused"], 0)
        for job, got in zip(run["jobs"], res["users"]):
            self.assertAlmostEqual(got["ps"], job["ps"], places=3)
            self.assertAlmostEqual(got["pe"] - got["a"], job["sojourn"], places=3)

    def test_cap_above_the_peak_refuses_nothing(self):
        run = cap_model.load_run(self.dir)
        free = cap_model.replay(run, cap=0)
        self.assertEqual(free["peak_seen"], 1)          # job2 found job1 waiting
        self.assertEqual(cap_model.measured_peak_seen(run["jobs"]), 1)
        same = cap_model.replay(run, cap=free["peak_seen"] + 1)
        self.assertEqual(same["refused"], 0)
        self.assertEqual([u["ps"] for u in same["users"]], [u["ps"] for u in free["users"]])
        self.assertGreaterEqual(cap_model.replay(run, cap=free["peak_seen"])["refused"], 1)

    def test_refused_job_takes_no_slot(self):
        run = cap_model.load_run(self.dir)
        res = cap_model.replay(run, cap=1)
        self.assertEqual([u["admitted"] for u in res["users"]], [True, True, False, True])
        self.assertAlmostEqual(res["users"][3]["ps"], 31.75, places=3)   # slot free since 22.5, own cutout ends at 31.5
        self.assertEqual(res["peak_seen_admitted"], 0)


@unittest.skipUnless(G3C1.is_dir(), "4th-round run folder not in this checkout")
class G3c1Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.trace = cap_model.load_run(G3C1)

    def test_window_yields_157_jobs(self):
        # events.jsonl also holds the earlier g0d4 run: 189 lines, 157 created inside run.json's window
        self.assertEqual(self.trace["lines"], 189)
        self.assertEqual(len(self.trace["jobs"]), 157)
        self.assertEqual(self.trace["outside"], 32)

    def test_uncapped_replay_is_close_to_the_measurement(self):
        res = cap_model.replay(self.trace, cap=0)
        meas = sum(j["sojourn"] for j in self.trace["jobs"]) / len(self.trace["jobs"])
        rep = sum(u["pe"] - u["a"] for u in res["users"]) / len(res["users"])
        self.assertLess(abs(rep - meas) / meas, 0.03)

    def test_capped_replay(self):
        for cap, refused in ((12, 12), (19, 5)):
            with self.subTest(cap=cap):
                res = cap_model.replay(self.trace, cap=cap)
                self.assertEqual(res["refused"], refused)
                self.assertLessEqual(res["peak_seen_admitted"], cap - 1)

    def test_cap_above_the_peak_refuses_nothing(self):
        peak = cap_model.replay(self.trace, cap=0)["peak_seen"]
        self.assertEqual(cap_model.replay(self.trace, cap=peak + 1)["refused"], 0)


class MonteCarloTest(unittest.TestCase):
    SAMPLES = [(0.5, 8.0), (0.6, 8.2), (0.4, 7.9)]

    def test_deterministic_for_a_seed(self):
        a = cap_model.monte_carlo(self.SAMPLES, 517, 20, cap=12, runs=30, seed=7, h=0.29, g=0.29)
        b = cap_model.monte_carlo(self.SAMPLES, 517, 20, cap=12, runs=30, seed=7, h=0.29, g=0.29)
        c = cap_model.monte_carlo(self.SAMPLES, 517, 20, cap=12, runs=30, seed=8, h=0.29, g=0.29)
        self.assertEqual(a, b)
        self.assertNotEqual(a["refused_frac"], c["refused_frac"])

    def test_no_cap_refuses_nothing(self):
        res = cap_model.monte_carlo(self.SAMPLES, 517, 20, cap=0, runs=10, seed=1)
        self.assertEqual(sum(res["refused"]), 0)

    def test_capped_admitted_wait_is_bounded(self):
        # one cutout time for all, so no later arrival can ask for the slot first
        samples = [(0.5, p) for _, p in self.SAMPLES]
        res = cap_model.monte_carlo(samples, 800, 20, cap=5, runs=20, seed=3, h=0.25, g=0.25)
        slot = max(p for _, p in samples) + 0.5
        self.assertGreater(sum(res["refused"]), 0)
        # at most cap - 1 waiting ahead plus the slot holder, then its own slot: (N + 1) slots
        self.assertLessEqual(max(res["max_sojourn"]), (5 + 1) * slot + 1e-9)

    def test_steady_state_is_deterministic(self):
        a = cap_model.steady_state(self.SAMPLES, 517, cap=19, hours=3, warmup_h=0.5, seed=1, h=0.29, g=0.29)
        self.assertEqual(a, cap_model.steady_state(self.SAMPLES, 517, cap=19, hours=3, warmup_h=0.5, seed=1, h=0.29, g=0.29))
        self.assertGreater(a["refused_frac"], 0)


class RetryTest(unittest.TestCase):
    SAMPLES = [(0.5, 8.0), (0.6, 8.2)]

    def test_one_try_is_the_plain_cap(self):
        r = cap_model.retry_traffic(self.SAMPLES, 600, cap=10, retry_after_s=60, max_tries=1, hours=2, warmup_h=0, seed=5)
        self.assertEqual(r["posts"], r["users"])
        self.assertEqual(r["gave_up"], r["refused_posts"])

    def test_no_cap_means_one_post_per_user(self):
        r = cap_model.retry_traffic(self.SAMPLES, 600, cap=0, retry_after_s=60, max_tries=5, hours=2, warmup_h=0, seed=5)
        self.assertEqual(r["posts"], r["users"])
        self.assertEqual(r["refused_posts"], 0)

    def test_retries_add_posts_and_are_deterministic(self):
        kw = dict(cap=10, retry_after_s=60, max_tries=4, hours=2, warmup_h=0, seed=5)
        r = cap_model.retry_traffic(self.SAMPLES, 600, **kw)
        self.assertEqual(r, cap_model.retry_traffic(self.SAMPLES, 600, **kw))
        self.assertEqual(r["posts"], r["users"] + r["refused_posts"] - r["gave_up"])
        self.assertGreater(r["posts"], r["users"])
        self.assertLessEqual(r["posts"], 4 * r["users"])

    def test_time_budget_limits_tries(self):
        r = cap_model.retry_traffic(self.SAMPLES, 900, cap=5, retry_after_s=100, max_tries=100, hours=2, warmup_h=0,
                                    seed=5, give_up_s=250)
        self.assertLessEqual(r["max_tries_used"], 3)    # tries at 0, 100, 200 s


class CliTest(unittest.TestCase):
    def test_formula_command_prints_the_table(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(cap_model.main(["formula"]), 0)
        text = out.getvalue()
        self.assertIn("W 180 s: N 19", text)
        self.assertIn("Retry-After 165", text)

    def test_fill_command(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(cap_model.main(["fill", "--cap", "19", "--rate", "471", "--capacity", "415"]), 0)
        self.assertIn("20.4 min", out.getvalue())


if __name__ == "__main__":
    unittest.main()
