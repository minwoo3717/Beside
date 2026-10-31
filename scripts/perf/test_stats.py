"""stats.py: type-7 percentiles, summary, and the noise rule of compare()."""
import math
import unittest

import stats


class PercentileTest(unittest.TestCase):
    def test_linear_interpolation_matches_numpy_linear(self):
        xs = list(range(1, 11))
        self.assertAlmostEqual(stats.percentile(xs, 50), 5.5)
        self.assertAlmostEqual(stats.percentile(xs, 90), 9.1)
        self.assertAlmostEqual(stats.percentile(xs, 95), 9.55)
        self.assertAlmostEqual(stats.percentile(xs, 99), 9.91)
        self.assertAlmostEqual(stats.percentile(xs, 0), 1)
        self.assertAlmostEqual(stats.percentile(xs, 100), 10)
        # numpy.percentile([15, 20, 35, 40, 50], [30, 40]) -> [23., 29.]
        self.assertAlmostEqual(stats.percentile([50, 15, 40, 20, 35], 30), 23.0)
        self.assertAlmostEqual(stats.percentile([50, 15, 40, 20, 35], 40), 29.0)

    def test_edge_cases(self):
        self.assertEqual(stats.percentile([7], 95), 7.0)
        with self.assertRaises(ValueError):
            stats.percentile([], 50)
        with self.assertRaises(ValueError):
            stats.percentile([1, 2], 101)
        self.assertEqual(stats.median([3, 1, 2]), 2.0)


class SummaryTest(unittest.TestCase):
    def test_keys_values_and_rounding(self):
        s = stats.summary(list(range(1, 11)))
        self.assertEqual(list(s), ["n", "min", "p50", "p90", "p95", "p99", "max", "mean", "stdev"])
        self.assertEqual(s["n"], 10)
        self.assertEqual((s["min"], s["max"]), (1.0, 10.0))
        self.assertEqual((s["p50"], s["p90"], s["p95"], s["p99"]), (5.5, 9.1, 9.55, 9.91))
        self.assertEqual(s["mean"], 5.5)
        self.assertEqual(s["stdev"], round(math.sqrt(82.5 / 9), 3))  # sample standard deviation
        self.assertEqual(stats.summary([1.23456])["p50"], 1.235)

    def test_empty_and_single(self):
        empty = stats.summary([])
        self.assertEqual(empty["n"], 0)
        self.assertTrue(all(v is None for k, v in empty.items() if k != "n"))
        self.assertEqual(stats.summary([4])["stdev"], 0.0)


class CompareTest(unittest.TestCase):
    def test_better_when_large_and_ranges_apart(self):
        base = [[100, 101, 99], [102, 100, 101], [98, 99, 100]]
        cand = [[80, 81, 79], [82, 80, 81], [78, 79, 80]]
        result = stats.compare(base, cand)
        self.assertEqual(result["verdict"], "better")
        self.assertLess(result["deltaPct"], -10)
        self.assertFalse(result["overlap"])
        self.assertEqual(result["reps"], [3, 3])

    def test_worse(self):
        base = [[100, 100], [101, 99], [100, 102]]
        cand = [[130, 131], [129, 130], [132, 128]]
        self.assertEqual(stats.compare(base, cand)["verdict"], "worse")

    def test_small_difference_is_noise_even_without_overlap(self):
        base = [[100], [100.5], [101]]
        cand = [[95], [95.5], [96]]  # about -5 %
        result = stats.compare(base, cand)
        self.assertFalse(result["overlap"])
        self.assertEqual(result["verdict"], "same")

    def test_overlapping_repetitions_are_noise(self):
        base = [[90, 90], [110, 110]]
        cand = [[70, 70], [95, 95]]  # pooled median -17.5 %, but 70..95 overlaps 90..110
        result = stats.compare(base, cand)
        self.assertTrue(result["overlap"])
        self.assertLess(result["deltaPct"], -10)
        self.assertEqual(result["verdict"], "same")

    def test_higher_is_better_for_throughput(self):
        base = [[100], [102], [98]]
        cand = [[130], [128], [132]]
        self.assertEqual(stats.compare(base, cand, lower_is_better=False)["verdict"], "better")
        self.assertEqual(stats.compare(cand, base, lower_is_better=False)["verdict"], "worse")

    def test_threshold_is_inclusive(self):
        result = stats.compare([[100]], [[90]])
        self.assertEqual(result["deltaPct"], -10.0)
        self.assertEqual(result["verdict"], "better")  # one repetition each: the ranges are points

    def test_zero_baseline_and_empty(self):
        self.assertEqual(stats.compare([[0], [0]], [[0], [0]])["verdict"], "same")
        # 0 -> 5 (say errors per repetition) has no rate of change; it must not read as noise
        result = stats.compare([[0], [0], [0]], [[5], [6], [5]])
        self.assertIsNone(result["deltaPct"])
        self.assertEqual(result["verdict"], "n/a")
        self.assertEqual(stats.compare([[0]], [[5]], lower_is_better=False)["verdict"], "n/a")
        self.assertIsNone(stats.compare([], [[1]]))
        self.assertIsNone(stats.compare([[1]], [[]]))

    def test_pooled_median_not_median_of_medians(self):
        base = [[10, 10, 10, 10], [20]]
        result = stats.compare(base, [[10]])
        self.assertEqual(result["baseline"], 10.0)  # pooled: 10,10,10,10,20 -> 10


if __name__ == "__main__":
    unittest.main()
