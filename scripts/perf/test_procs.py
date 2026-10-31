"""procs.py: a timed-out run kills its whole tree, and stop() never forgets a process that is still running."""
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

import procs

if os.name == "nt":
    import ctypes
    from ctypes import wintypes

    _k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _k32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    _k32.OpenProcess.restype = wintypes.HANDLE
    _k32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    _k32.WaitForSingleObject.restype = wintypes.DWORD
    _k32.CloseHandle.argtypes = [wintypes.HANDLE]

    def pid_alive(pid: int) -> bool:
        handle = _k32.OpenProcess(0x00100000 | 0x1000, False, pid)  # SYNCHRONIZE | QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        try:
            return _k32.WaitForSingleObject(handle, 0) == 0x102  # WAIT_TIMEOUT: still running
        finally:
            _k32.CloseHandle(handle)
else:
    def pid_alive(pid: int) -> bool:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        except PermissionError:
            return True
        try:
            with open(f"/proc/{pid}/status", encoding="ascii", errors="replace") as status:
                for line in status:
                    if line.startswith("State:"):
                        return line.split()[1] != "Z"  # a zombie has exited
        except OSError:
            pass
        return True


def wait_dead(pid: int, timeout_s: float = 15.0) -> bool:
    deadline = time.monotonic() + timeout_s
    while pid_alive(pid):
        if time.monotonic() > deadline:
            return False
        time.sleep(0.1)
    return True


# A child that ignores SIGTERM (POSIX graceful stop) and just sleeps: only a kill ends it.
SLEEPER = "import signal, time\nsignal.signal(signal.SIGTERM, signal.SIG_IGN)\ntime.sleep(120)\n"


class RunLoggedTest(unittest.TestCase):
    def test_timeout_kills_the_whole_tree(self):
        before = procs.live_pids()
        with tempfile.TemporaryDirectory() as folder:
            pid_file = Path(folder) / "grandchild.pid"
            code = ("import subprocess, sys, time\n"
                    "child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])\n"
                    f"open({str(pid_file)!r}, 'w').write(str(child.pid))\n"
                    "time.sleep(120)\n")
            started = time.monotonic()
            with self.assertRaises(subprocess.TimeoutExpired):
                procs.run_logged("tree", [sys.executable, "-c", code], cwd=folder, log_path=Path(folder) / "tree.log",
                                 env=dict(os.environ), timeout_s=4)
            self.assertLess(time.monotonic() - started, 60)
            self.assertTrue(pid_file.is_file(), "the child did not start its grandchild in time")
            grandchild = int(pid_file.read_text())
            # subprocess.run(timeout=...) would leave this one running (it kills only the top process)
            self.assertTrue(wait_dead(grandchild), "the grandchild outlived the timeout")
        self.assertEqual(procs.live_pids(), before)

    def test_exit_code_and_log(self):
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / "out.log"
            code = procs.run_logged("ok", [sys.executable, "-c", "print('built'); raise SystemExit(3)"], cwd=folder,
                                    log_path=log, env=dict(os.environ), timeout_s=60)
            self.assertEqual(code, 3)
            self.assertIn("built", log.read_text(encoding="utf-8", errors="replace"))


class StopTest(unittest.TestCase):
    def _sleeper(self, folder):
        return procs.start("sleeper", [sys.executable, "-c", SLEEPER], cwd=folder, log_path=Path(folder) / "s.log",
                           env=dict(os.environ))

    def test_interrupt_during_the_graceful_wait_kills_the_tree(self):
        with tempfile.TemporaryDirectory() as folder:
            proc = self._sleeper(folder)
            real_wait, calls = proc.popen.wait, []

            def wait(timeout=None):
                calls.append(timeout)
                if len(calls) == 1:
                    raise KeyboardInterrupt  # a second Ctrl+C while stop() waits for the graceful exit
                return real_wait(timeout=timeout)

            proc.popen.wait = wait
            with mock.patch.object(procs, "_ctrl_c", return_value=True):  # asked, but the child does not react
                with self.assertRaises(KeyboardInterrupt):
                    procs.stop(proc, timeout_s=30)
            self.assertIsNotNone(proc.popen.poll(), "the interrupted stop left the process running")
            self.assertNotIn(proc.pid, procs.live_pids())

    def test_a_process_that_survives_stop_stays_registered(self):
        with tempfile.TemporaryDirectory() as folder:
            proc = self._sleeper(folder)
            try:
                with mock.patch.object(procs, "_ctrl_c", return_value=False), \
                        mock.patch.object(procs, "kill_tree", side_effect=OSError("taskkill failed")):
                    with self.assertRaises(OSError):
                        procs.stop(proc, timeout_s=1)
                self.assertIsNone(proc.popen.poll())
                self.assertIn(proc.pid, procs.live_pids())  # stop_all (atexit, error paths) still knows it
            finally:
                procs.stop_all()
            self.assertIsNotNone(proc.popen.poll())
            self.assertNotIn(proc.pid, procs.live_pids())

    def test_plain_stop_unregisters(self):
        with tempfile.TemporaryDirectory() as folder:
            proc = self._sleeper(folder)
            self.assertIn(proc.pid, procs.live_pids())
            with mock.patch.object(procs, "_ctrl_c", return_value=False):
                self.assertEqual(procs.stop(proc, timeout_s=1), "killed")
            self.assertNotIn(proc.pid, procs.live_pids())


class CpuBusyTest(unittest.TestCase):
    def test_machine_wide_busy_percentage(self):
        value = procs.cpu_busy_pct(0.3)
        if os.name == "nt" or Path("/proc/stat").exists():
            self.assertIsNotNone(value)
            self.assertGreaterEqual(value, 0.0)
            self.assertLessEqual(value, 100.0)
        else:
            self.assertIsNone(value)  # macOS: no counters, recorded as unknown

    def test_busy_is_one_minus_the_idle_share(self):
        with mock.patch.object(procs, "_cpu_times", side_effect=[(100, 1000), (400, 2000)]), \
                mock.patch.object(procs.time, "sleep"):
            self.assertEqual(procs.cpu_busy_pct(2.0), 70.0)  # 300 idle of 1000 ticks
        with mock.patch.object(procs, "_cpu_times", return_value=None):
            self.assertIsNone(procs.cpu_busy_pct(0.0))


if __name__ == "__main__":
    unittest.main()
