"""Tests of deploy/runpod/ship_logs.py against a fake Loki (standard library only, no network beyond 127.0.0.1):
    python -I deploy/runpod/test_ship_logs.py"""
import http.server
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ship_logs  # noqa: E402

TS = "2026-11-27T01:02:03.456Z"


class FakeLoki:
    """Records every POST (path as sent: absolute-form when it came through as a proxy) and answers the next status
    from .statuses, 204 when none is left."""

    def __init__(self):
        self.posts = []
        self.statuses = []
        self.lock = threading.Lock()
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                with fake.lock:
                    status = fake.statuses.pop(0) if fake.statuses else 204
                    fake.posts.append({"path": self.path, "type": self.headers.get("Content-Type"),
                                       "body": json.loads(body.decode("utf-8")), "status": status})
                payload = b"" if status == 204 else b"entry too far behind"
                self.send_response(status)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *_):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.url = self.base + "/loki/api/v1/push"

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def accepted(self):
        """[(file, ts, line)] of the posts answered 2xx, in order."""
        out = []
        for post in self.posts:
            if post["status"] < 300:
                for stream in post["body"]["streams"]:
                    out.extend((stream["stream"]["file"], ts, line) for ts, line in stream["values"])
        return out

    def lines(self):
        return [line for _, _, line in self.accepted()]


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t

    def advance(self, seconds):
        self.t += seconds


def closed_port():
    import socket
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class ShipLogsCase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="ship-logs-test-")
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.loki = FakeLoki()
        self.addCleanup(self.loki.close)
        self.clock = Clock()
        self.status = []

    def make(self, **kw):
        options = dict(env="prod", host="beside-gpu", clock=self.clock, status=self.status.append)
        options.update(kw)
        return ship_logs.Shipper(self.dir, options.pop("url", self.loki.url), **options)

    def write(self, name, *lines, mode="ab"):
        with open(os.path.join(self.dir, name), mode) as fh:
            fh.write("".join(line + "\n" for line in lines).encode("utf-8"))

    def settle(self, shipper, seconds=1.0):
        """Lets the held entry close and the 1 s push come due."""
        self.clock.advance(seconds)
        shipper.step()

    def saved(self):
        with open(os.path.join(self.dir, ship_logs.POS_FILE), encoding="utf-8") as fh:
            return json.load(fh)["files"]


class StartAndLabelsTest(ShipLogsCase):
    def test_a_file_seen_for_the_first_time_starts_at_its_end(self):
        self.write("inference.log", f"{TS} INFO beside.inference old run")
        self.write("start.log", "[4/4] old start")
        shipper = self.make()
        self.write("inference.log", f"{TS} INFO beside.inference new line")
        self.write("ship_logs.log", "own status, never shipped")
        shipper.step()
        self.settle(shipper)
        self.assertEqual(self.loki.lines(), [f"{TS} INFO beside.inference new line"])
        (post,) = self.loki.posts
        self.assertEqual((post["path"], post["type"]), ("/loki/api/v1/push", "application/json"))
        (stream,) = post["body"]["streams"]
        self.assertEqual(stream["stream"], {"file": "inference.log", "host": "beside-gpu", "env": "prod"})
        (ts, _), = stream["values"]
        self.assertRegex(ts, r"^\d{19}$")   # ns since the epoch, as a string
        self.assertLess(abs(int(ts) / 1e9 - time.time()), 60)   # when it was read

    def test_a_file_that_appears_later_is_read_from_its_start(self):
        shipper = self.make()
        self.write("supervisor.log", "2026-11-27T01:02:03Z start: service pid 7, supervisor pid 6: bash x",
                   "2026-11-27T01:02:09Z service pid 7 exited (status 137, signal 9) after 6 s; restart 1 in 5 s")
        shipper.step()
        self.settle(shipper)
        self.assertEqual([(f, line[21:26]) for f, _, line in self.loki.accepted()],
                         [("supervisor.log", "start"), ("supervisor.log", "servi")])

    def test_labels_come_from_the_settings(self):
        shipper = self.make(env="loadtest", host="beside-gpu-2")
        self.write("start.log", "[1/4] Tailscale 1.104.6 already in /workspace/tailscale/bin")
        shipper.step()
        self.settle(shipper)
        self.assertEqual(self.loki.posts[0]["body"]["streams"][0]["stream"],
                         {"file": "start.log", "host": "beside-gpu-2", "env": "loadtest"})


class BatchTest(ShipLogsCase):
    def test_200_entries_go_at_once_and_the_rest_after_a_second(self):
        shipper = self.make()
        self.write("inference.log", *(f"{TS} INFO beside.inference line {i}" for i in range(450)))
        shipper.step()
        self.assertEqual([len(p["body"]["streams"][0]["values"]) for p in self.loki.posts], [200, 200])
        self.clock.advance(0.5)
        shipper.step()   # the last 50 wait for the second to pass
        self.assertEqual(len(self.loki.posts), 2)
        self.settle(shipper, 0.5)
        self.assertEqual([len(p["body"]["streams"][0]["values"]) for p in self.loki.posts], [200, 200, 50])
        accepted = self.loki.accepted()
        self.assertEqual([line for _, _, line in accepted], [f"{TS} INFO beside.inference line {i}" for i in range(450)])
        stamps = [int(ts) for _, ts, _ in accepted]
        self.assertEqual(stamps, sorted(set(stamps)))   # strictly increasing: equal lines never share a time

    def test_one_push_carries_one_stream_per_file(self):
        shipper = self.make()
        self.write("inference.log", f"{TS} INFO beside.inference a")
        self.write("start.log", "[3/4] tailnet login as 'beside-gpu'")
        shipper.step()
        self.settle(shipper)
        (post,) = self.loki.posts
        self.assertEqual(sorted(s["stream"]["file"] for s in post["body"]["streams"]), ["inference.log", "start.log"])


class ContinuationTest(ShipLogsCase):
    def test_a_traceback_goes_with_its_error_line_and_other_lines_stay_apart(self):
        shipper = self.make()
        self.write("inference.log",
                   f"{TS} ERROR beside.inference job j-1: inference failed rid=j-1-1 trace=abc",
                   "Traceback (most recent call last):",
                   '  File "/app.py", line 937, in _run',
                   "    pred = MODEL.predict(image, str(raw_dir))",
                   "torch.OutOfMemoryError: CUDA out of memory",
                   "",
                   "During handling of the above exception, another exception occurred:",
                   "",
                   "Traceback (most recent call last):",
                   '  File "/app.py", line 940, in _run',
                   "ValueError: boom",
                   'INFO:     127.0.0.1:53120 - "POST /infer/upload HTTP/1.1" 500 Internal Server Error',
                   "timing POST /infer/upload 500 client=127.0.0.1:53120 in=1 body_ms=- start_ms=- end_ms=- gone_ms=- "
                   "out=0 err=ValueError at=" + TS,
                   "[Checkpoint] loaded",
                   "/opt/torch/nn/x.py:12: UserWarning: something",
                   "  warnings.warn(",
                   "10%|#         | 1/10\r50%|#####     | 5/10\r100%|##########| 10/10\r")
        self.write("start.log", "NOTE: this Tailscale state was last used by Pod abc - make sure that Pod is stopped;",
                   "      two Pods running with one node key keep knocking each other off the tailnet.",
                   "", "Pod tailnet address : 100.64.0.7")
        shipper.step()
        self.settle(shipper)
        by_file = {}
        for name, _, line in self.loki.accepted():
            by_file.setdefault(name, []).append(line)
        error, access, timing, checkpoint, warning, progress = by_file["inference.log"]
        self.assertEqual(error.splitlines()[0], f"{TS} ERROR beside.inference job j-1: inference failed rid=j-1-1 trace=abc")
        self.assertEqual(error.splitlines()[-1], "ValueError: boom")
        self.assertIn("torch.OutOfMemoryError: CUDA out of memory\n\nDuring handling", error)
        self.assertEqual(error.count("Traceback (most recent call last):"), 2)
        self.assertTrue(access.startswith("INFO:     127.0.0.1:53120"))
        self.assertTrue(timing.startswith("timing POST /infer/upload 500 "))
        self.assertEqual(checkpoint, "[Checkpoint] loaded")
        self.assertEqual(warning, "/opt/torch/nn/x.py:12: UserWarning: something\n  warnings.warn(")
        self.assertEqual(progress, "100%|##########| 10/10")
        note, address = by_file["start.log"]
        self.assertEqual(len(note.splitlines()), 2)
        self.assertEqual(address, "Pod tailnet address : 100.64.0.7")

    def test_a_stack_split_across_two_reads_still_joins_its_line(self):
        shipper = self.make()
        self.write("inference.log", f"{TS} ERROR beside.inference job j-2: conversion crashed")
        shipper.step()
        self.clock.advance(0.2)   # less than HOLD_S: the entry still waits
        self.write("inference.log", "Traceback (most recent call last):", "KeyError: 'x'")
        shipper.step()
        self.settle(shipper)
        self.assertEqual(self.loki.lines(), [f"{TS} ERROR beside.inference job j-2: conversion crashed\n"
                                             "Traceback (most recent call last):\nKeyError: 'x'"])

    def test_a_line_waits_for_its_newline(self):
        shipper = self.make()
        with open(os.path.join(self.dir, "inference.log"), "ab") as fh:
            fh.write(f"{TS} INFO beside.inference half".encode())
        shipper.step()
        self.settle(shipper)
        self.assertEqual(self.loki.posts, [])
        self.write("inference.log", " and the rest")
        shipper.step()
        self.settle(shipper)
        self.assertEqual(self.loki.lines(), [f"{TS} INFO beside.inference half and the rest"])


class SecretTest(ShipLogsCase):
    def test_secret_values_are_masked_and_lines_naming_the_token_header_dropped(self):
        token = "Zx81-pod-token-value"
        shipper = self.make(secrets=[token, "short"])
        self.write("inference.log",
                   f"{TS} WARNING beside.inference echo {token} here",
                   f"{TS} INFO beside.inference GET /x?token=abc123def&b=1 api_key: 'k-998877' password=hunter22",
                   f"{TS} INFO beside.inference Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload",
                   f"{TS} INFO beside.inference hf_abcdefghijklmnopqrstuvwxyz tskey-auth-kAbCdEf12345-xyz",
                   f"{TS} WARNING beside.inference POST /infer/upload: 401 INFERENCE_UNAVAILABLE wrong X-Inference-Token",
                   "Traceback (most recent call last):",
                   "  this continuation of a dropped line goes too",
                   f"{TS} ERROR beside.inference failed",
                   "Traceback (most recent call last):",
                   "    headers = {'x-inference-token': 'sent-value'}",
                   "ValueError: bad",
                   "INFO:     token: set (X-Inference-Token required on /infer)",
                   "a short value stays",
                   )
        shipper.step()
        self.settle(shipper)
        lines = self.loki.lines()
        self.assertEqual(lines, [
            f"{TS} WARNING beside.inference echo *** here",
            f"{TS} INFO beside.inference GET /x?token=***&b=1 api_key: '***' password=***",
            f"{TS} INFO beside.inference Authorization: Bearer ***",
            f"{TS} INFO beside.inference *** ***",
            f"{TS} ERROR beside.inference failed\nTraceback (most recent call last):\nValueError: bad",
            "a short value stays",
        ])
        self.assertEqual(shipper.filtered, 2)
        everything = json.dumps(self.loki.posts)
        for secret in (token, "abc123def", "k-998877", "hunter22", "eyJhbGciOiJIUzI1NiJ9", "sent-value"):
            self.assertNotIn(secret, everything)


class FileChangeTest(ShipLogsCase):
    def test_a_truncated_file_is_read_from_its_start(self):
        self.write("inference.log", "=== 2026-11-27T01:00:00Z start_pod.sh ===")
        shipper = self.make()
        self.write("inference.log", f"{TS} INFO beside.inference a rather long line before the truncation")
        shipper.step()
        self.settle(shipper)
        self.write("inference.log", "=== 2026-11-27T01:00:00Z start_pod.sh ===", "INFO:     short", mode="wb")
        shipper.step()
        self.settle(shipper)
        self.assertEqual(self.loki.lines()[1:], ["=== 2026-11-27T01:00:00Z start_pod.sh ===", "INFO:     short"])
        self.assertTrue(any("inference.log got shorter (truncated)" in s for s in self.status), self.status)

    def test_a_replaced_file_is_read_from_its_start(self):
        self.write("inference.log", "=== 2026-11-27T01:00:00Z start_pod.sh ===", f"{TS} INFO beside.inference old")
        shipper = self.make()
        os.replace(os.path.join(self.dir, "inference.log"), os.path.join(self.dir, "inference.log.1"))
        self.write("inference.log", "=== 2026-11-27T02:00:00Z start_pod.sh ===",
                   f"{TS} INFO beside.inference new file, longer than the old one was: " + "x" * 80)
        shipper.step()
        self.settle(shipper)
        self.assertEqual(self.loki.lines(), ["=== 2026-11-27T02:00:00Z start_pod.sh ===",
                                             f"{TS} INFO beside.inference new file, longer than the old one was: " + "x" * 80])
        self.assertTrue(any("replaced or rotated" in s for s in self.status), self.status)


class BackoffTest(ShipLogsCase):
    def run_until(self, shipper, posts, limit=400):
        """Steps every 0.5 s until the fake Loki has seen posts requests; the clock at each new request."""
        times, seen = [], len(self.loki.posts)
        for _ in range(limit):
            if len(self.loki.posts) >= posts:
                break
            self.clock.advance(0.5)
            shipper.step()
            if len(self.loki.posts) > seen:
                times.append(self.clock.t)
                seen = len(self.loki.posts)
        return times

    def test_a_failed_push_is_sent_again_with_the_same_times_after_1_2_4_s(self):
        self.loki.statuses = [500, 503, 429]
        shipper = self.make()
        self.write("inference.log", f"{TS} ERROR beside.inference one", "Traceback (most recent call last):", "KeyError: 1")
        times = self.run_until(shipper, 4)
        self.assertEqual([round(b - a, 1) for a, b in zip(times, times[1:])], [1.0, 2.0, 4.0])
        values = [p["body"]["streams"][0]["values"] for p in self.loki.posts]
        self.assertEqual(len(values), 4)
        self.assertTrue(all(v == values[0] for v in values), values)   # same entries, same times
        self.assertEqual([p["status"] for p in self.loki.posts], [500, 503, 429, 204])
        self.assertEqual(len([s for s in self.status if s.startswith("push failed (HTTP 500")]), 1, self.status)
        self.assertTrue(any(s.startswith("push works again after 3 failed tries") for s in self.status), self.status)
        self.loki.statuses = [500]   # the pause starts again at 1 s
        self.write("inference.log", f"{TS} INFO beside.inference three")
        times = self.run_until(shipper, 6)
        self.assertEqual(round(times[1] - times[0], 1), 1.0)

    def test_the_pause_doubles_up_to_30_s(self):
        self.loki.statuses = [500] * 8
        shipper = self.make()
        self.write("inference.log", f"{TS} INFO beside.inference one")
        times = self.run_until(shipper, 9)
        self.assertEqual([round(b - a, 1) for a, b in zip(times, times[1:])], [1.0, 2.0, 4.0, 8.0, 16.0, 30.0, 30.0, 30.0])

    def test_an_unreachable_vm_is_retried_and_reading_goes_on(self):
        shipper = self.make(url=f"http://127.0.0.1:{closed_port()}/loki/api/v1/push")
        self.write("inference.log", f"{TS} INFO beside.inference one")
        shipper.step()
        self.settle(shipper)
        self.write("inference.log", f"{TS} INFO beside.inference two")
        self.settle(shipper)
        self.settle(shipper)
        self.assertEqual(len(shipper.buffer), 2)
        self.assertGreaterEqual(shipper.failures, 1)
        shipper.url = self.loki.url
        for _ in range(70):
            self.settle(shipper)
        self.assertEqual(self.loki.lines(), [f"{TS} INFO beside.inference one", f"{TS} INFO beside.inference two"])

    def test_a_refused_batch_is_dropped_not_sent_again(self):
        self.loki.statuses = [400]
        shipper = self.make()
        self.write("inference.log", f"{TS} INFO beside.inference refused")
        shipper.step()
        self.settle(shipper)
        self.write("inference.log", f"{TS} INFO beside.inference next")
        shipper.step()
        self.settle(shipper)
        self.assertEqual([p["status"] for p in self.loki.posts], [400, 204])
        self.assertEqual(self.loki.lines(), [f"{TS} INFO beside.inference next"])
        self.assertEqual(shipper.refused, 1)
        self.assertTrue(any("push refused (HTTP 400 entry too far behind)" in s for s in self.status), self.status)

    def test_a_full_buffer_drops_the_oldest_and_counts_them(self):
        self.loki.statuses = [500, 500]
        shipper = self.make()
        self.write("inference.log", *(f"{TS} INFO beside.inference line {i}" for i in range(5100)))
        shipper.step()
        self.settle(shipper)
        self.assertEqual(len(shipper.buffer), ship_logs.MAX_BUFFER)
        self.assertEqual(shipper.dropped, 100)
        for _ in range(40):
            self.settle(shipper)
        self.assertEqual(self.loki.lines(), [f"{TS} INFO beside.inference line {i}" for i in range(100, 5100)])
        self.assertEqual(len([s for s in self.status if s.startswith("buffer full")]), 1, self.status)
        self.assertTrue(any("100 entries were dropped meanwhile" in s for s in self.status), self.status)


class ProxyTest(ShipLogsCase):
    def test_pushes_go_through_the_proxy(self):
        # the fake Loki stands in for tailscaled's HTTP proxy: the request arrives in absolute form
        shipper = self.make(url="http://loki.invalid:3500/loki/api/v1/push", proxy=self.loki.base)
        self.write("inference.log", f"{TS} INFO beside.inference via proxy")
        shipper.step()
        self.settle(shipper)
        self.assertEqual(self.loki.posts[0]["path"], "http://loki.invalid:3500/loki/api/v1/push")
        self.assertEqual(self.loki.lines(), [f"{TS} INFO beside.inference via proxy"])

    def test_without_a_proxy_setting_no_environment_proxy_is_used(self):
        with mock.patch.dict(os.environ, {"http_proxy": f"http://127.0.0.1:{closed_port()}",
                                          "HTTP_PROXY": f"http://127.0.0.1:{closed_port()}"}):
            shipper = self.make()
            self.write("inference.log", f"{TS} INFO beside.inference direct")
            shipper.step()
            self.settle(shipper)
        self.assertEqual(self.loki.lines(), [f"{TS} INFO beside.inference direct"])


class PositionTest(ShipLogsCase):
    def test_a_restart_goes_on_where_the_last_push_ended(self):
        self.write("inference.log", f"{TS} INFO beside.inference before the shipper")
        first = self.make()
        self.write("inference.log", f"{TS} INFO beside.inference A")
        first.step()
        self.settle(first)
        saved = self.saved()["inference.log"]
        self.assertEqual(saved["offset"], os.path.getsize(os.path.join(self.dir, "inference.log")))
        self.assertEqual(saved["inode"], os.stat(os.path.join(self.dir, "inference.log")).st_ino)
        self.write("inference.log", f"{TS} INFO beside.inference B (shipper down)")
        second = self.make()
        second.step()
        self.settle(second)
        self.assertEqual(self.loki.lines(), [f"{TS} INFO beside.inference A", f"{TS} INFO beside.inference B (shipper down)"])

    def test_lines_loki_never_took_are_read_again_after_a_restart(self):
        self.loki.statuses = [500] * 3
        first = self.make()
        self.write("inference.log", f"{TS} INFO beside.inference not taken yet")
        first.step()
        self.settle(first)
        self.settle(first)
        self.assertEqual(self.loki.lines(), [])
        first.run(sleep=lambda _: first.stop())   # one more step, then stop: positions saved
        self.assertTrue(self.status[-1].startswith("stopped (1 entries not sent"), self.status)
        self.loki.statuses = []
        second = self.make()
        second.step()
        self.settle(second)
        self.assertEqual(self.loki.lines(), [f"{TS} INFO beside.inference not taken yet"])

    def test_a_file_replaced_while_the_shipper_was_down_is_read_from_its_start(self):
        self.write("inference.log", "=== 2026-11-27T01:00:00Z start_pod.sh ===", f"{TS} INFO beside.inference old")
        self.make().step()
        os.remove(os.path.join(self.dir, "inference.log"))
        self.write("inference.log", "=== 2026-11-27T03:00:00Z start_pod.sh ===", f"{TS} INFO beside.inference after",
                   f"{TS} INFO beside.inference and more than the old file had")
        second = self.make()
        second.step()
        self.settle(second)
        self.assertEqual(self.loki.lines()[0], "=== 2026-11-27T03:00:00Z start_pod.sh ===")
        self.assertEqual(len(self.loki.lines()), 3)

    def test_an_unreadable_position_file_starts_every_file_at_its_end(self):
        self.write("inference.log", f"{TS} INFO beside.inference old")
        self.write(ship_logs.POS_FILE, "{not json")
        shipper = self.make()
        self.write("inference.log", f"{TS} INFO beside.inference new")
        shipper.step()
        self.settle(shipper)
        self.assertEqual(self.loki.lines(), [f"{TS} INFO beside.inference new"])
        self.assertTrue(any(s.startswith("could not read") for s in self.status), self.status)


class ScriptTest(ShipLogsCase):
    def test_without_a_push_url_it_exits_2(self):
        env = {k: v for k, v in os.environ.items() if k != "BESIDE_LOG_PUSH_URL"}
        done = subprocess.run([sys.executable, "-I", os.path.join(HERE, "ship_logs.py"), self.dir], env=env,
                              capture_output=True, text=True, timeout=60)
        self.assertEqual(done.returncode, 2, done.stdout + done.stderr)
        self.assertIn("BESIDE_LOG_PUSH_URL is not set", done.stdout)

    def test_the_script_ships_a_new_line(self):
        env = dict(os.environ, BESIDE_LOG_PUSH_URL=self.loki.url, BESIDE_TELEMETRY_ENV="loadtest", TS_HOSTNAME="pod-x",
                   BESIDE_INFERENCE_TOKEN="pod-token-for-the-script-test", PYTHONIOENCODING="utf-8")
        env.pop("BESIDE_TELEMETRY_PROXY", None)
        self.write("inference.log", f"{TS} INFO beside.inference before")
        process = subprocess.Popen([sys.executable, "-I", os.path.join(HERE, "ship_logs.py"), self.dir], env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            pos = os.path.join(self.dir, ship_logs.POS_FILE)
            deadline = time.monotonic() + 20
            while not os.path.exists(pos) and time.monotonic() < deadline:
                time.sleep(0.05)
            self.write("inference.log", f"{TS} INFO beside.inference after pod-token-for-the-script-test")
            while not self.loki.posts and time.monotonic() < deadline:
                time.sleep(0.05)
        finally:
            process.terminate()
            out, _ = process.communicate(timeout=20)
        self.assertEqual(self.loki.lines(), [f"{TS} INFO beside.inference after ***"], out)
        self.assertEqual(self.loki.posts[0]["body"]["streams"][0]["stream"],
                         {"file": "inference.log", "host": "pod-x", "env": "loadtest"})
        self.assertIn("ship_logs: start: inference.log, start.log, supervisor.log", out)


if __name__ == "__main__":
    unittest.main()
