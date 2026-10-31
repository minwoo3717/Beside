"""scenarios.py parts that need no backend: the thread group, the E2E flow against a fake API, the stub check."""
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

import httpbench
import procs
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

        for _ in range(20):  # a failure right after the start line used to break the others still leaving it
            results, errors, wall_ms = scenarios._run_threads(3, work, timeout_s=10)
            self.assertEqual(results, [0, 10, None])
            self.assertEqual(errors[:2], [None, None])
            self.assertIn("ValueError: boom", errors[2])
            self.assertGreaterEqual(wall_ms, 0)

    def test_a_failure_before_the_start_line_releases_the_others(self):
        def work(i, barrier):
            if i == 0:
                raise ConnectionRefusedError("server gone")  # never reaches the start line
            barrier.wait(timeout=60)
            return i

        started = time.monotonic()
        results, errors, _wall = scenarios._run_threads(3, work, timeout_s=10)
        self.assertLess(time.monotonic() - started, 5)  # nobody waits 60 s for thread 0
        self.assertEqual(results, [None, None, None])
        self.assertIn("ConnectionRefusedError", errors[0])
        self.assertTrue(all("BrokenBarrierError" in e for e in errors[1:]))


class _FakeApi(BaseHTTPRequestHandler):
    """POST /api/v1/jobs -> 202 with "Connection: close" (like Tomcat's 100th answer on a connection), the job is
    COMPLETED at once, the asset is 1000 bytes."""
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, status, body: bytes, close=False, content_type="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        if close:
            self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        if close:
            self.close_connection = True

    def do_POST(self):
        self.rfile.read(int(self.headers["Content-Length"]))
        self._send(202, b'{"jobId": "j1", "status": "PENDING"}', close=True)

    def do_GET(self):
        if self.path.endswith("/asset"):
            self._send(200, b"g" * 1000, content_type="model/gltf-binary")
        else:
            self._send(200, b'{"id": "j1", "status": "COMPLETED"}')


class _SlowConnectClient(httpbench.HttpClient):
    def _open(self):
        time.sleep(0.08)  # a slow connect / accept: must not be counted in the flow
        super()._open()


class FlowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _FakeApi)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def test_connection_setup_inside_a_flow_is_left_out(self):
        port = self.server.server_address[1]
        with _SlowConnectClient("127.0.0.1", port, timeout=10) as client:
            flow = scenarios.run_flow(client, b"photo", "application/octet-stream", 0.01, 1000, 10)
        self.assertTrue(flow["ok"], flow["error"])
        self.assertEqual(client.connects, 2)  # before the POST, and for the poll after "Connection: close"
        self.assertGreaterEqual(flow["untimedMs"], 160)
        # with the two 80 ms connects inside the window the flow would take 160 ms or more
        self.assertLess(flow["flowMs"], 60)
        self.assertLess(flow["completeMs"], 60)
        self.assertLessEqual(flow["completeMs"], flow["flowMs"])


class StubCheckTest(unittest.TestCase):
    def test_runs_in_the_stub_environment_and_folder(self):
        calls = []

        def fake_run(cmd, **kwargs):
            calls.append((cmd, kwargs))
            return subprocess.CompletedProcess(cmd, 0, b"", b"")

        with tempfile.TemporaryDirectory() as folder:
            (Path(folder) / "app.py").write_text("app = None\n", encoding="utf-8")
            with mock.patch.dict(os.environ, {"PYTHONPATH": "/only/here", "BESIDE_WORK_DIR": "x"}), \
                    mock.patch.object(scenarios.subprocess, "run", side_effect=fake_run):
                self.assertIsNone(scenarios.stub_check(sys.executable, Path(folder)))
            _cmd, kwargs = calls[0]
            self.assertEqual(kwargs["cwd"], str(Path(folder)))
            self.assertNotIn("PYTHONPATH", kwargs["env"])
            self.assertNotIn("BESIDE_WORK_DIR", kwargs["env"])
            self.assertIn("no inference stub", scenarios.stub_check(sys.executable, Path(folder) / "none"))
        self.assertEqual(scenarios.stub_check(None), "no --stub-python")

    def test_packages_reachable_only_through_pythonpath_do_not_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            lib, app_dir = Path(folder) / "lib", Path(folder) / "app"
            lib.mkdir()
            app_dir.mkdir()
            (app_dir / "app.py").write_text("app = None\n", encoding="utf-8")
            for name in ("fastapi", "uvicorn", "multipart"):
                (lib / f"{name}.py").write_text("", encoding="utf-8")
            probe = subprocess.run([sys.executable, "-c", "import fastapi, uvicorn, multipart"], capture_output=True,
                                   env=procs.clean_env()[0], cwd=str(app_dir), timeout=120)
            if probe.returncode == 0:
                self.skipTest("this Python has fastapi, uvicorn and python-multipart installed")
            with mock.patch.dict(os.environ, {"PYTHONPATH": str(lib)}):
                reason = scenarios.stub_check(sys.executable, app_dir)
        self.assertIsNotNone(reason)  # the stub would not find them either (start_stub drops PYTHONPATH)
        self.assertIn("cannot import", reason)


if __name__ == "__main__":
    unittest.main()
