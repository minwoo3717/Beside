"""scenarios.py helpers that need no backend: the thread group of the concurrent scenarios."""
import threading
import time
import unittest

import scenarios


class RunThreadsTest(unittest.TestCase):
    def test_one_deadline_for_the_whole_group(self):
        release = threading.Event()

        def stuck(_i, barrier):
            barrier.wait(timeout=10)
            release.wait(30)  # a stalled server: every thread hangs

        started = time.monotonic()
        try:
            with self.assertRaises(RuntimeError):
                scenarios._run_threads(4, stuck, timeout_s=0.5)
            elapsed = time.monotonic() - started
        finally:
            release.set()
        self.assertLess(elapsed, 1.5)  # one join of 0.5 s after another would take 4 x 0.5 s

    def test_results_errors_and_wall_time(self):
        def work(i, barrier):
            barrier.wait(timeout=10)
            if i == 2:
                raise ValueError("boom")
            return i * 10

        results, errors, wall_ms = scenarios._run_threads(3, work, timeout_s=10)
        self.assertEqual(results, [0, 10, None])
        self.assertEqual(errors[:2], [None, None])
        self.assertIn("ValueError: boom", errors[2])
        self.assertGreaterEqual(wall_ms, 0)


if __name__ == "__main__":
    unittest.main()
