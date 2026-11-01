"""failure/f3_throttled.py against a local fake API v1 server: the paced upload, its checks, the k6 summary window and
the leftover count."""
import importlib.util
import io
import json
import os
import tempfile
import threading
import time
import unittest
from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("f3_throttled", HERE / "failure" / "f3_throttled.py")
f3t = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(f3t)

PHOTO = b"\x89PNG\r\n\x1a\n" + bytes(range(256)) * 80  # 20,488 bytes


class FakeApi(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_POST(self):
        srv = self.server
        length = int(self.headers.get("Content-Length", "0"))
        first, got, chunks = None, 0, []
        while got < length:
            block = self.rfile.read1(min(65536, length - got))
            if not block:
                break
            if first is None:
                first = time.monotonic()
            got += len(block)
            chunks.append(block)
        srv.uploads.append({"body": b"".join(chunks), "key": self.headers.get("Idempotency-Key"),
                            "bodySeconds": time.monotonic() - (first or time.monotonic())})
        status, doc = srv.answer
        body = json.dumps(doc).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def k6_summary(ready_ms, planned_s=100, ran_s=100, poll_ok=True, poll_count=50):
    th = {"p(95)<300": {"ok": poll_ok}, "p(99)<600": {"ok": True}}
    return {"schema": "beside-load-summary/1", "meta": {"loadPlannedS": planned_s, "loadRanS": ran_s},
            "verdict": {"thresholdsOk": poll_ok, "aborted": None},
            "k6": {"setup_data": {"readyAtMs": ready_ms, "pollJob": "j"},
                   "metrics": {"http_req_duration{endpoint:poll}": {"values": {"p(95)": 12.5, "count": poll_count},
                                                                     "thresholds": th}}}}


class F3ThrottledTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeApi)
        self.server.daemon_threads = True
        self.server.uploads = []
        self.server.answer = (202, {"jobId": "job-1"})
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.photo = self.dir / "p.png"
        self.photo.write_bytes(PHOTO)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def run_main(self, *extra, rate="100000"):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = f3t.main(["--base-url", self.base, "--run-id", "unit", "--photo", str(self.photo), "--rate", rate,
                             "--delay-s", "0", *extra])
        return code, out.getvalue(), err.getvalue()

    def write_summary(self, doc, name="k6-summary.json"):
        path = self.dir / name
        path.write_text(json.dumps(doc), encoding="utf-8")
        return path

    def test_paced_upload_is_accepted_with_the_run_prefix(self):
        code, out, err = self.run_main()
        report = json.loads(out)
        self.assertEqual(code, 0, out + err)
        self.assertEqual(report["checks"], {"accepted": True, "throttled": True, "inTime": True})
        self.assertEqual(report["upload"]["status"], 202)
        self.assertEqual(report["upload"]["jobId"], "job-1")
        self.assertEqual(report["filename"], "lt-unit-f3throttle-1.png")
        self.assertEqual(report["expected"]["status"], 202)
        upload = self.server.uploads[0]
        self.assertEqual(len(upload["body"]), report["bodyBytes"])
        self.assertEqual(report["upload"]["sentBytes"], report["bodyBytes"])
        self.assertIn(b'filename="lt-unit-f3throttle-1.png"', upload["body"])
        self.assertIn(b"Content-Type: image/png", upload["body"])
        self.assertTrue(upload["key"].startswith("lt-unit-f3throttle-"))
        # paced: the body took about bodyBytes / rate (0.2 s) to arrive, not one burst
        self.assertGreaterEqual(upload["bodySeconds"], 0.8 * report["expected"]["plannedS"])
        self.assertGreaterEqual(report["upload"]["seconds"], report["expected"]["minS"])
        self.assertIn("HTTP 202", err)

    def test_every_invocation_sends_a_new_idempotency_key(self):
        self.run_main()
        self.run_main()
        keys = [u["key"] for u in self.server.uploads]
        self.assertEqual(len(set(keys)), 2)
        self.assertNotIn("slow", self.server.uploads[0]["body"].decode("latin1"))  # the stub's slow token

    def test_rejected_upload_fails_the_check(self):
        self.server.answer = (400, {"error": {"code": "UNSUPPORTED_IMAGE_TYPE", "message": "x"}})
        code, out, _ = self.run_main()
        report = json.loads(out)
        self.assertEqual(code, 1)
        self.assertFalse(report["checks"]["accepted"])
        self.assertEqual(report["upload"]["errorCode"], "UNSUPPORTED_IMAGE_TYPE")

    def test_time_checks(self):
        expected = {"status": 202, "minS": 36.0, "maxS": 70.0}
        fast = {"status": 202, "jobId": "j", "seconds": 0.5}
        self.assertEqual(f3t.upload_checks(fast, expected), {"accepted": True, "throttled": False, "inTime": True})
        stalled = {"status": 202, "jobId": "j", "seconds": 90.0}
        self.assertFalse(f3t.upload_checks(stalled, expected)["inTime"])

    def test_upload_inside_the_k6_poller_window(self):
        path = self.dir / "k6-summary.json"

        def later():  # k6 writes its summary when it ends, after this script started
            time.sleep(0.4)
            self.write_summary(k6_summary(ready_ms=(time.time() - 5) * 1000))

        threading.Thread(target=later, daemon=True).start()
        code, out, err = self.run_main("--k6-summary", str(path), "--summary-wait-s", "10")
        report = json.loads(out)
        self.assertEqual(code, 0, out + err)
        self.assertTrue(report["polling"]["found"])
        self.assertTrue(report["checks"]["insidePolling"])
        self.assertTrue(report["checks"]["pollHeld"])
        self.assertEqual(report["polling"]["pollP95Ms"], 12.5)

    def test_upload_outside_the_poller_window_fails(self):
        # the poller started 100 s ago and ran 30 s: the upload came after it (the README order before this script)
        path = self.write_summary(k6_summary(ready_ms=(time.time() - 100) * 1000, planned_s=40, ran_s=30))
        os.utime(path, (time.time() + 5, time.time() + 5))
        code, out, _ = self.run_main("--k6-summary", str(path), "--summary-wait-s", "2")
        report = json.loads(out)
        self.assertEqual(code, 1)
        self.assertFalse(report["checks"]["insidePolling"])
        self.assertIn("window", report["polling"]["note"])

    def test_failed_poll_threshold_fails_pollheld(self):
        path = self.write_summary(k6_summary(ready_ms=(time.time() - 5) * 1000, poll_ok=False))
        os.utime(path, (time.time() + 5, time.time() + 5))
        code, out, _ = self.run_main("--k6-summary", str(path), "--summary-wait-s", "2")
        report = json.loads(out)
        self.assertEqual(code, 1)
        self.assertTrue(report["checks"]["insidePolling"])
        self.assertFalse(report["checks"]["pollHeld"])

    def test_stale_or_missing_summary_is_not_read(self):
        path = self.write_summary(k6_summary(ready_ms=(time.time() - 5) * 1000))
        os.utime(path, (time.time() - 3600, time.time() - 3600))  # a summary from an earlier run
        code, out, _ = self.run_main("--k6-summary", str(path), "--summary-wait-s", "0.5",
                                     "--uploads-dir", str(self.dir))
        report = json.loads(out)
        self.assertEqual(code, 1)
        self.assertFalse(report["polling"]["found"])
        self.assertFalse(report["checks"]["insidePolling"])
        self.assertFalse(report["checks"]["noLeftovers"])  # k6 may still be uploading: not counted
        self.assertIsNone(report["leftovers"])

    def test_k6_that_ended_before_the_upload_gets_no_upload(self):
        # k6 refused to start (CASES matched nothing: exit 107) while this script waited out --delay-s
        exit_file = self.dir / "k6-exit.txt"
        exit_file.write_text("107\n", encoding="utf-8")
        t0 = time.monotonic()
        code, out, err = self.run_main("--k6-summary", str(self.dir / "k6-summary.json"), "--k6-exit-file", str(exit_file),
                                       "--summary-wait-s", "60")
        self.assertEqual(code, 2, out + err)
        self.assertLess(time.monotonic() - t0, 10)
        self.assertEqual(self.server.uploads, [])  # no job made for a run without load
        self.assertIn("k6 ended (exit 107) before the upload", err)

    def test_exit_file_ends_the_summary_wait(self):
        # k6 ends without a summary while the upload is under way: stop waiting at once, not after --summary-wait-s
        exit_file = self.dir / "k6-exit.txt"

        def k6_gone():
            time.sleep(0.3)
            exit_file.write_text("107\n", encoding="utf-8")

        threading.Thread(target=k6_gone, daemon=True).start()
        t0 = time.monotonic()
        code, out, err = self.run_main("--k6-summary", str(self.dir / "k6-summary.json"), "--k6-exit-file", str(exit_file),
                                       "--summary-wait-s", "60", "--uploads-dir", str(self.dir), rate="20000")
        report = json.loads(out)
        self.assertLess(time.monotonic() - t0, 15)
        self.assertEqual(code, 1)
        self.assertEqual(len(self.server.uploads), 1)  # it began before k6 ended
        polling = report["polling"]
        self.assertEqual((polling["found"], polling["k6Ended"], polling["k6ExitCode"]), (False, True, 107))
        self.assertIn("without writing a summary", polling["note"])
        self.assertFalse(report["checks"]["insidePolling"])
        self.assertFalse(report["checks"]["noLeftovers"])  # nothing to count against

    def test_exit_file_with_a_summary_and_stale_exit_files(self):
        path = self.dir / "k6-summary.json"
        exit_file = self.dir / "k6-exit.txt"

        def k6_ends():  # during the upload: k6 writes its summary, then the shell its exit code
            time.sleep(0.3)
            self.write_summary(k6_summary(ready_ms=(time.time() - 5) * 1000))
            exit_file.write_bytes("0\r\n".encode("utf-16"))  # Windows PowerShell 5.1: $LASTEXITCODE > file

        threading.Thread(target=k6_ends, daemon=True).start()
        code, out, err = self.run_main("--k6-summary", str(path), "--k6-exit-file", str(exit_file), "--summary-wait-s", "10",
                                       rate="20000")
        report = json.loads(out)
        self.assertEqual(code, 0, out + err)
        self.assertEqual((report["polling"]["found"], report["polling"]["k6Ended"], report["polling"]["k6ExitCode"]),
                         (True, True, 0))
        # an earlier run's exit file (same folder, same minute) neither stops the upload nor the wait
        exit_file.write_text("99\n", encoding="utf-8")
        os.utime(exit_file, (time.time() - 3600, time.time() - 3600))
        os.utime(path, (time.time() + 5, time.time() + 5))  # this run's summary
        t0 = time.monotonic()
        with mock.patch.object(f3t, "EXIT_AFTER_SUMMARY_S", 1.0):  # the shell never writes this run's exit code
            code, out, _ = self.run_main("--k6-summary", str(path), "--k6-exit-file", str(exit_file), "--summary-wait-s", "2")
        report = json.loads(out)
        self.assertEqual(code, 0, out)
        self.assertEqual(len(self.server.uploads), 2)
        self.assertEqual((report["polling"]["k6Ended"], report["polling"]["k6ExitCode"]), (False, None))
        self.assertLess(time.monotonic() - t0, 5)
        self.assertEqual(f3t.k6_exit(None, 0), None)

    def test_leftovers_of_rejected_uploads_and_multipart_temp_files(self):
        uploads = self.dir / "uploads"
        uploads.mkdir()
        for name in ("a1_lt-unit-f3bg-1.png", "a1_lt-unit-f3bg-2.png", "b2_lt-unit-f3throttle-1.png",
                     "c3_lt-unit-f3acc-fake_jpg-1.jpg", "d4_lt-other-f3bad-gif-1.gif"):
            (uploads / name).write_bytes(b"x")
        work = self.dir / "tomcat.18680.1" / "work" / "Tomcat" / "localhost" / "ROOT"
        work.mkdir(parents=True)
        path = self.write_summary(k6_summary(ready_ms=(time.time() - 5) * 1000))
        os.utime(path, (time.time() + 5, time.time() + 5))
        tmp_glob = str(self.dir / "tomcat.18680.*" / "work" / "Tomcat" / "localhost" / "ROOT")
        args = ("--k6-summary", str(path), "--summary-wait-s", "2", "--uploads-dir", str(uploads), "--multipart-tmp", tmp_glob)
        code, out, _ = self.run_main(*args)
        report = json.loads(out)
        self.assertEqual(code, 0, out)
        left = report["leftovers"]
        self.assertEqual(left["rejectedStored"], 0)  # another run's f3bad file does not count
        self.assertEqual(left["storedOfRun"], {"f3bad": 0, "f3acc": 1, "f3bg": 2, "f3throttle": 1})
        self.assertEqual((left["multipartTmpDirs"], left["multipartTmpFiles"]), (1, 0))

        (uploads / "e5_lt-unit-f3bad-photos_11-3.png").write_bytes(b"x")
        (work / "upload_0b1c_00000001.tmp").write_bytes(b"x")
        code, out, _ = self.run_main(*args)
        left = json.loads(out)["leftovers"]
        self.assertEqual(code, 1)
        self.assertEqual(left["rejectedStored"], 1)
        self.assertEqual(left["examples"], ["e5_lt-unit-f3bad-photos_11-3.png"])
        self.assertEqual(left["multipartTmpFiles"], 1)

    def test_wrong_leftover_paths_do_not_read_as_clean(self):
        path = self.write_summary(k6_summary(ready_ms=(time.time() - 5) * 1000))
        os.utime(path, (time.time() + 5, time.time() + 5))
        code, out, _ = self.run_main("--k6-summary", str(path), "--summary-wait-s", "2",
                                     "--uploads-dir", str(self.dir / "nope"), "--multipart-tmp", str(self.dir / "tomcat.*"))
        left = json.loads(out)["leftovers"]
        self.assertEqual(code, 1)
        self.assertEqual(len(left["notes"]), 2)

    def test_cannot_start(self):
        self.photo.unlink()
        code, _, err = self.run_main()
        self.assertEqual(code, 2)
        self.assertIn("make_fixtures.py", err)
        self.photo.write_bytes(PHOTO)
        port = self.server.server_address[1]
        self.server.shutdown()
        self.server.server_close()
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = f3t.main(["--base-url", f"http://127.0.0.1:{port}", "--run-id", "unit", "--photo", str(self.photo),
                             "--delay-s", "0", "--timeout", "5"])
        self.assertEqual(code, 2)
        self.assertIn("cannot connect", err.getvalue())
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeApi)  # tearDown shuts down a serving server
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def test_arguments_are_refused(self):
        bad = [["--run-id", "myFAILrun"], ["--run-id", "has space"], ["--run-id", "ok", "--uploads-dir", "x"],
               ["--run-id", "ok", "--rate", "0"]]
        for extra in bad:
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                f3t.parse_args(["--base-url", self.base, *extra])


if __name__ == "__main__":
    unittest.main()
