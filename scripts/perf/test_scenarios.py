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


class SettleTest(unittest.TestCase):
    """The idle wait after a mock scenario: its time and the server CPU of the scenario including that tail."""

    def _server(self):
        return mock.Mock(proc=mock.Mock(pid=4242))

    def test_wait_and_cpu_are_recorded_on_the_scenario(self):
        ctx, result = mock.Mock(), scenarios.new_result()
        ctx.settle.return_value = (1500.0, True)
        with mock.patch.object(scenarios.procs, "cpu_time_ms", return_value=900.0):
            scenarios._settle_after(ctx, self._server(), result, cpu_before=650.0)
        self.assertEqual(result["values"]["bench.settleAfterMs"], 1500.0)
        self.assertEqual(result["values"]["bench.serverCpuMs"], 250.0)
        self.assertEqual(result["notes"], [])

    def test_a_server_that_never_settles_is_noted(self):
        ctx, result = mock.Mock(), scenarios.new_result()
        ctx.settle.return_value = (120000.0, False)
        with mock.patch.object(scenarios.procs, "cpu_time_ms", return_value=None):
            scenarios._settle_after(ctx, self._server(), result, cpu_before=None)
        self.assertEqual(result["values"]["bench.settleAfterMs"], 120000.0)
        self.assertNotIn("bench.serverCpuMs", result["values"])
        self.assertIn("still busy 120 s", result["notes"][0])

    def test_no_process_cpu_time_means_no_values(self):
        ctx, result = mock.Mock(), scenarios.new_result()
        ctx.settle.return_value = (0.0, None)
        with mock.patch.object(scenarios.procs, "cpu_time_ms", return_value=None):
            scenarios._settle_after(ctx, self._server(), result, cpu_before=None)
        self.assertEqual(result["values"], {})
        self.assertIn("no process CPU time", result["notes"][0])

    def test_every_measured_mock_scenario_is_followed_by_a_wait(self):
        measured = [name for name, _fn in scenarios.MOCK_STEPS if name != "resources"]
        self.assertEqual(list(scenarios.SETTLED_STEPS), measured)

    def test_the_wait_reported_leaves_out_the_window_that_confirms_idleness(self):
        with tempfile.TemporaryDirectory() as folder:
            ctx = scenarios.SuiteContext("v1", 1, "x.jar", "java", [], None, {}, {}, folder, print)
            server = self._server()
            with mock.patch.object(scenarios.procs, "wait_idle", return_value=(1700.0, True)) as wait:
                self.assertEqual(ctx.settle(server), (700.0, True))
            self.assertEqual(wait.call_args.kwargs, {"max_s": scenarios.SETTLE["maxS"],
                                                     "window_s": scenarios.SETTLE["windowS"],
                                                     "busy_pct": scenarios.SETTLE["busyPct"]})
            with mock.patch.object(scenarios.procs, "wait_idle", return_value=(500.0, True)) as wait:
                self.assertEqual(ctx.settle_request(server), (0.0, True))  # idle at once: nothing extra
            self.assertEqual(wait.call_args.kwargs["window_s"], scenarios.SETTLE["perRequestWindowS"])
            with mock.patch.object(scenarios.procs, "wait_idle", return_value=(0.0, None)):
                self.assertEqual(ctx.settle(server), (0.0, None))

    def test_servers_start_without_the_actuator_management_port(self):
        # Jars with Actuator bind management.server.port (8081) besides server.port; -1 keeps two servers, or a
        # leftover one, from clashing on it. Older jars ignore the unknown property.
        with tempfile.TemporaryDirectory() as folder:
            ctx = scenarios.SuiteContext("v1", 1, "x.jar", "java", [], None, {}, {}, folder, print)
            proc = mock.Mock(pid=1, pinned=None, in_job=False, priority=None)
            with mock.patch.object(scenarios.procs, "start", return_value=proc) as start, \
                    mock.patch.object(scenarios.procs, "wait_http_ok", return_value=1.0):
                ctx.start_server("A-mock", Path(folder) / "storage", "mock", [])
            self.assertIn("--management.server.port=-1", start.call_args.args[1])


class WaitPlacementTest(unittest.TestCase):
    """Idle waits and CPU readings sit between timed regions, never inside one."""

    def test_mock_loop_reads_cpu_before_a_scenario_and_waits_after_it(self):
        events = []
        with tempfile.TemporaryDirectory() as folder:
            ctx = mock.Mock(run_dir=Path(folder), fixtures={"glb_sample": {"path": "x.glb"}}, keep_storage=True,
                            servers={"A-mock": {}}, counts=scenarios.effective_counts(True))
            server = mock.Mock()
            server.proc.alive.return_value = True
            ctx.start_server.return_value = server
            ctx.settle.side_effect = lambda *a, **k: (events.append("wait"), (0.0, True))[1]

            def step(name):
                return lambda _ctx, _server, _result: events.append(name)

            steps = [(name, step(name)) for name, _fn in scenarios.MOCK_STEPS]
            with mock.patch.object(scenarios, "MOCK_STEPS", steps), \
                    mock.patch.object(scenarios.procs, "cpu_time_ms",
                                      side_effect=lambda _pid: (events.append("cpu"), 1.0)[1]):
                results = scenarios.run_mock(ctx, ["healthz", "upload_large", "resources"])
        for name in ("healthz", "upload_large"):
            at = events.index(name)
            self.assertEqual(events[at - 1:at + 3], ["cpu", name, "wait", "cpu"], events)
        at = events.index("resources")
        self.assertEqual(events[at - 1:at + 1], ["cpu", "resources"])
        self.assertEqual(events[at + 1:], [])  # nothing measured after resources: no wait
        self.assertEqual(results["healthz"]["values"]["bench.serverCpuMs"], 0.0)
        self.assertEqual(results["upload_small"]["status"], "skipped")

    def test_each_upload_starts_after_its_wait(self):
        events = []
        ctx, result = mock.Mock(), scenarios.new_result()
        ctx.settle_request.side_effect = lambda _server: (events.append("wait"), (100.0, True))[1]
        server = mock.MagicMock()
        answer = httpbench.Response(202, b'{"jobId": "j"}', 5.0)
        with mock.patch.object(scenarios, "post_photos",
                               side_effect=lambda *a: (events.append("post"), (answer, "j"))[1]):
            samples, errors = scenarios._sequential_posts(ctx, server, result, b"body", "type", 2, 3, "postMs",
                                                          "postMs", settle_each=True)
        self.assertEqual(events, ["wait", "post"] * 5)
        self.assertEqual((samples, errors), ([5.0, 5.0, 5.0], 0))
        self.assertEqual(result["values"]["bench.settleTotalMs"], 300.0)  # the three measured uploads only
        events.clear()
        with mock.patch.object(scenarios, "post_photos",
                               side_effect=lambda *a: (events.append("post"), (answer, "j"))[1]):
            scenarios._sequential_posts(ctx, server, scenarios.new_result(), b"body", "type", 1, 2, "postMs",
                                        "postMs")
        self.assertEqual(events, ["post"] * 3)  # upload_small: back to back, no waits

    def test_real_flows_and_the_batch_start_after_a_wait(self):
        events = []
        with tempfile.TemporaryDirectory() as folder:
            ctx = mock.Mock(servers={"C-real_path": {}},
                            counts={"real.warmup": 1, "real.n": 2, "real.batch": 2, "real.pollMs": 20},
                            fixtures={"png_large": {"path": "big.png", "data": b"png"}, "glb_sample": {"bytes": 3}})
            server = mock.MagicMock(startup_ms=1.0)
            ctx.start_server.return_value = server
            ctx.settle.side_effect = lambda *a, **k: (events.append("wait"), (0.0, True))[1]
            ctx.settle_request.side_effect = lambda *a, **k: (events.append("wait"), (0.0, True))[1]
            flow = {"ok": True, "jobId": "j", "flowMs": 1.0, "completeMs": 1.0, "assetMs": 1.0, "postMs": 1.0,
                    "error": None}
            stub = mock.Mock(base_url="http://127.0.0.1:1")
            with mock.patch.object(scenarios, "run_flow", side_effect=lambda *a: (events.append("flow"), flow)[1]), \
                    mock.patch.object(scenarios, "_run_threads",
                                      side_effect=lambda n, _fn: (events.append("batch"), ([None] * n, [None] * n, 1.0))[1]), \
                    mock.patch.object(scenarios, "wait_terminal", return_value={}), \
                    mock.patch.object(scenarios, "parse_events", return_value=([], 0)), \
                    mock.patch.object(scenarios.procs, "cpu_time_ms", return_value=1.0):
                result = scenarios._scenario_real(ctx, "real_path", stub, Path(folder))
        timed = [i for i, e in enumerate(events) if e in ("flow", "batch")]
        self.assertEqual(len(timed), 1 + 2 + 1)
        for i in timed:
            self.assertEqual(events[i - 1], "wait", events)
        self.assertEqual(result["values"]["bench.settleTotalMs"], 0.0)


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
