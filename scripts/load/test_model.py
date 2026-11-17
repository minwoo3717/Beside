"""model.py: the derived traffic table must reproduce the numbers of docs/perf/load/TRAFFIC_MODEL.md (§3, §5).

Each expected value below is copied from TRAFFIC_MODEL.md, not from model.py's output. Where the document rounds, the
tolerance is half of its last digit. A disagreement is reported, not hidden: see the disk-days test at the bottom.
"""
import contextlib
import io
import json
import subprocess
import sys
import unittest
from pathlib import Path

import model

HERE = Path(__file__).resolve().parent


class ErlangCTest(unittest.TestCase):
    def test_single_server_wait_probability_is_rho(self):
        self.assertAlmostEqual(model.erlang_c(1, 0.7), 0.7, places=12)

    def test_two_servers_known_value(self):
        # c = 2, a = 1 Erlang: C = (a^2/2! * 2/(2-1)) / (1 + a + a^2/2! * 2/(2-1)) = 1 / 3
        self.assertAlmostEqual(model.erlang_c(2, 1.0), 1 / 3, places=12)

    def test_overloaded_is_one(self):
        self.assertEqual(model.erlang_c(3, 3.0), 1.0)
        self.assertEqual(model.erlang_c(1, 1.5), 1.0)

    def test_large_c_is_stable(self):
        value = model.erlang_c(429, 300.0)
        self.assertGreaterEqual(value, 0.0)
        self.assertLess(value, 0.01)

    def test_mm1_and_md1_waits(self):
        # M/M/1: Wq = rho T / (1 - rho); M/D/1 (Pollaczek-Khinchine): half of that.
        self.assertAlmostEqual(model.wq_mmc(1, 0.5, 10), 10.0, places=9)
        self.assertAlmostEqual(model.wq_mdc_approx(1, 0.5, 10), 5.0, places=9)
        self.assertIsNone(model.wq_mmc(1, 1.2, 10))


class PlanNumbersTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.table = model.compute(model.load_tiers(HERE / "tiers.json"))
        cls.t1, cls.t2, cls.t3 = (cls.table["tiers"][k] for k in ("T1", "T2", "T3"))

    def per_t(self, tier, t):
        return next(row for row in tier["perT"] if row["tSec"] == t)

    # --- T3 (Socar scale) ---
    def test_t3_users(self):
        self.assertEqual(self.t3["dau"], 150_000)
        self.assertAlmostEqual(self.t3["peakConcurrent"], 1_800, places=6)

    def test_t3_arrival_rate(self):
        self.assertAlmostEqual(self.t3["lambdaPerS"], 1.0, places=9)
        self.assertAlmostEqual(self.t3["lambdaPerHour"], 3_600, places=6)

    def test_t3_spike(self):
        self.assertAlmostEqual(self.t3["spike"]["lambdaPerS"], 3.0, places=9)
        self.assertAlmostEqual(self.t3["spike"]["peakConcurrent"], 5_400, places=6)

    def test_t3_rps_at_t60(self):
        row = self.per_t(self.t3, 60)
        self.assertAlmostEqual(row["rpsNormal"], 37, delta=0.5)
        self.assertAlmostEqual(self.t3["rpsSaturated"], 307, delta=0.5)
        self.assertAlmostEqual(row["spikeRpsNormal"], 111, delta=0.5)
        self.assertAlmostEqual(self.t3["spike"]["rpsSaturated"], 920, delta=920 * 0.01)

    def test_t3_bandwidth_and_disk(self):
        self.assertAlmostEqual(self.t3["assetMbps"], 11.4, delta=0.05)
        self.assertAlmostEqual(self.t3["spike"]["assetMbps"], 34, delta=0.5)
        self.assertAlmostEqual(self.t3["uploadMbps"], 6.4, places=9)
        self.assertAlmostEqual(self.t3["diskGBPerDay"], 69, delta=0.5)

    def test_t3_gpus(self):
        self.assertEqual([self.per_t(self.t3, t)["gpusNeeded"] for t in (30, 60, 120, 300)], [43, 86, 172, 429])

    # --- T2 (public beta) ---
    def test_t2_users_and_rate(self):
        self.assertEqual(self.t2["dau"], 2_000)
        self.assertAlmostEqual(self.t2["peakConcurrent"], 24, places=6)
        self.assertAlmostEqual(self.t2["lambdaPerHour"], 120, places=6)
        self.assertAlmostEqual(self.t2["lambdaPerS"], 0.033, delta=0.0005)

    def test_t2_gpus(self):
        self.assertEqual([self.per_t(self.t2, t)["gpusNeeded"] for t in (30, 60, 120, 300)], [2, 3, 6, 15])

    def test_t2_single_gpu_max_t(self):
        self.assertAlmostEqual(self.t2["singleGpuMaxTSec"], 21, places=6)

    def test_t2_rps_bandwidth_disk(self):
        self.assertAlmostEqual(self.per_t(self.t2, 60)["rpsNormal"], 1.6, delta=0.05)
        self.assertAlmostEqual(self.t2["rpsSaturated"], 10, delta=0.5)
        self.assertAlmostEqual(self.t2["assetMbps"], 0.4, delta=0.05)
        self.assertAlmostEqual(self.t2["diskGBPerDay"], 2.3, delta=0.05)

    # --- T1 (team) ---
    def test_t1(self):
        self.assertEqual(self.t1["dau"], 20)
        self.assertAlmostEqual(self.t1["peakConcurrent"], 5, places=6)
        self.assertAlmostEqual(self.t1["lambdaPerHour"], 10, places=6)
        self.assertLess(self.per_t(self.t1, 60)["rpsNormal"], 1)

    # --- one GPU and the queue cap ---
    def test_single_gpu_capacity(self):
        cap = {row["tSec"]: row for row in self.table["singleGpu"]}
        self.assertEqual([cap[t]["jobsPerHourAtRho"] for t in (30, 60, 120, 300)], [84, 42, 21, 8.4])
        self.assertEqual([cap[t]["jobsPerHourMax"] for t in (30, 60, 120, 300)], [120, 60, 30, 12])

    def test_q_max(self):
        self.assertEqual(model.q_max(1, 60, 600), 9)
        self.assertEqual(model.q_max(2, 60, 600), 18)
        self.assertEqual(model.q_max(1, 600, 600), 0)
        qrow = next(r for r in self.table["qMax"] if r["c"] == 1 and r["tSec"] == 60)
        self.assertEqual(qrow["qMax"], 9)

    def test_rps_components_add_up(self):
        row = self.per_t(self.t3, 60)
        parts = row["rpsParts"]
        self.assertAlmostEqual(sum(parts.values()), row["rpsNormal"], places=9)
        self.assertAlmostEqual(parts["healthz"], 5.0, places=9)   # 1,800 users / 360 s session
        self.assertAlmostEqual(parts["create"], 1.0, places=9)
        self.assertAlmostEqual(parts["asset"] + parts["retry"], 1.0, places=9)

    def test_conversion_ratio_users_per_flow(self):
        # api_staged.js turns users into flow arrivals with lambda = U / 1,800 per second (the T3 ratio).
        self.assertAlmostEqual(self.t3["usersPerJobPerS"], 1_800, places=6)

    def test_g1_predictions(self):
        rows = {r["rho"]: r for r in self.table["g1"]}
        self.assertAlmostEqual(rows[0.5]["wqMM1Sec"], 10.0, places=6)
        self.assertAlmostEqual(rows[0.5]["wqMD1Sec"], 5.0, places=6)
        self.assertAlmostEqual(rows[0.9]["wqMM1Sec"], 90.0, places=6)
        self.assertIsNone(rows[1.2]["wqMM1Sec"])
        self.assertAlmostEqual(rows[1.2]["queueGrowthPerMin"], 1.2, places=6)  # (lambda - mu) * 60 = (0.12 - 0.1) * 60

    def test_disk_days_to_fill_30gb(self):
        # TRAFFIC_MODEL.md §7: "사용 가능 약 20 GB [가정] 기준 약 9일 (30 GB 전부면 13일)". model.py divides the whole
        # diskBudgetGB (30) by 2.3 GB per day (2.3 MB per job, 1,000 jobs a day), which is the 13-day figure.
        self.assertAlmostEqual(self.t2["daysToFillDiskBudget"], 13.0, delta=0.05)


class QueuePredictionTest(unittest.TestCase):
    """--queue-*: the G series prediction recomputed from the measured arrival rate and mean slot time (TRAFFIC_MODEL.md §4, §9)."""

    def test_single_slot(self):
        # lambda = 180/h = 0.05/s, S = 10 s: rho 0.5, M/M/1 Wq = rho S / (1 - rho) = 10 s, M/D/1 half of that.
        p = model.queue_prediction(180, 10, 1)
        self.assertAlmostEqual(p["rho"], 0.5, places=12)
        self.assertAlmostEqual(p["wqMMcSec"], 10.0, places=9)
        self.assertAlmostEqual(p["wqMD1Sec"], 5.0, places=9)
        self.assertAlmostEqual(p["lambdaPerS"], 0.05, places=12)

    def test_two_slots(self):
        # lambda = 0.1/s, S = 10 s, c = 2: a = 1 Erlang, Erlang C = 1/3, Wq = C S / (c - a) = 10/3 s; no M/D/1 for c > 1.
        p = model.queue_prediction(360, 10, 2)
        self.assertAlmostEqual(p["rho"], 0.5, places=12)
        self.assertAlmostEqual(p["wqMMcSec"], 10 / 3, places=9)
        self.assertIsNone(p["wqMD1Sec"])

    def test_measured_slot_time_changes_the_prediction(self):
        # G1 rho 0.7 sends 252 jobs/h for T_SEC = 10; a measured slot of 12 s (upload, stub sleep, download) is rho 0.84.
        p = model.queue_prediction(252, 12, 1)
        self.assertAlmostEqual(p["rho"], 0.84, places=12)
        self.assertAlmostEqual(p["wqMMcSec"], 0.84 * 12 / 0.16, places=9)
        self.assertAlmostEqual(p["wqMD1Sec"], 0.84 * 12 / 0.16 / 2, places=9)

    def test_recompute_names_both_measured_inputs(self):
        # gpu_queue.js meta.recompute passes the arrival rate the run really had (meta.measuredJobsPerHour), not the
        # requested meta.prediction.jobsPerHour, so the status, the text title and the --help say "measured arrival rate".
        p = model.queue_prediction(1200, 2.089, 1)
        self.assertIn("측정한 도착률과 평균 슬롯 시간", p["status"])
        self.assertIn("측정한 도착률과 평균 슬롯 시간", model.queue_text(p).splitlines()[0])
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), self.assertRaises(SystemExit) as done:
            model.main(["--help"])
        self.assertEqual(done.exception.code, 0)
        self.assertIn("meta.measuredJobsPerHour", buf.getvalue())
        self.assertNotIn("meta.prediction.jobsPerHour", buf.getvalue())

    def test_overload_has_no_steady_state(self):
        p = model.queue_prediction(432, 10, 1)
        self.assertAlmostEqual(p["rho"], 1.2, places=12)
        self.assertIsNone(p["wqMMcSec"])
        self.assertIsNone(p["wqMD1Sec"])
        self.assertAlmostEqual(p["queueGrowthPerMin"], 1.2, places=9)

    def test_bad_arguments(self):
        for args in ((0, 10, 1), (180, 0, 1), (180, 10, 0), (180, 10, 1.5), (-1, 10, 1)):
            with self.assertRaises(ValueError, msg=str(args)):
                model.queue_prediction(*args)


class CliTest(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run([sys.executable, "-I", str(HERE / "model.py"), *args], capture_output=True,
                              text=True, encoding="utf-8", check=True).stdout

    def test_queue_text(self):
        text = self.run_cli("--queue-rate-per-h", "180", "--queue-service-s", "10", "--queue-slots", "1")
        self.assertIn("ρ = λ·S/c = 0.500", text)
        self.assertIn("M/M/c Wq = 10.0 s", text)
        self.assertIn("M/D/1 Wq = 5.0 s", text)
        self.assertIn("[파생]", text)

    def test_queue_text_two_slots_and_overload(self):
        two = self.run_cli("--queue-rate-per-h", "360", "--queue-service-s", "10", "--queue-slots", "2")
        self.assertIn("M/M/c Wq = 3.3 s", two)
        self.assertNotIn("M/D/1 Wq =", two)
        over = self.run_cli("--queue-rate-per-h", "432", "--queue-service-s", "10", "--queue-slots", "1")
        self.assertIn("ρ = λ·S/c = 1.200", over)
        self.assertIn("1.20", over)  # queue growth per minute

    def test_queue_json(self):
        data = json.loads(self.run_cli("--queue-rate-per-h", "180", "--queue-service-s", "10", "--queue-slots", "1", "--json"))
        self.assertAlmostEqual(data["rho"], 0.5, places=9)
        self.assertAlmostEqual(data["wqMMcSec"], 10.0, places=6)
        self.assertAlmostEqual(data["wqMD1Sec"], 5.0, places=6)
        self.assertEqual(data["slots"], 1)

    def test_queue_needs_rate_and_service(self):
        res = subprocess.run([sys.executable, "-I", str(HERE / "model.py"), "--queue-service-s", "10"],
                             capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(res.returncode, 2)
        self.assertIn("--queue-rate-per-h", res.stderr)

    def test_json_output(self):
        data = json.loads(self.run_cli("--json"))
        self.assertIn("T3", data["tiers"])
        self.assertEqual(data["targetRho"], 0.7)

    def test_markdown_output(self):
        text = self.run_cli()
        self.assertIn("| T3", text)
        self.assertIn("Q_max", text)
        self.assertIn("[파생]", text)


if __name__ == "__main__":
    unittest.main()
