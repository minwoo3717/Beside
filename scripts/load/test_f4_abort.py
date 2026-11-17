"""failure/f4_abort.py against a local fake API v1 server: aborts, the after-checks and the setup errors."""
import hashlib
import importlib.util
import io
import json
import tempfile
import threading
import unittest
from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("f4_abort", HERE / "failure" / "f4_abort.py")
f4 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(f4)

ASSET_A = bytes(range(256)) * 1200   # 307,200 bytes
ASSET_B = bytes(reversed(range(256))) * 1200


class FakeApi(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def send_json(self, status, doc):
        body = json.dumps(doc).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def job_doc(self, job_id, status):
        asset = {"url": f"/api/v1/jobs/{job_id}/asset?variant=base", "bytes": len(ASSET_A), "variant": "base",
                 "contentType": "model/gltf-binary"} if status == "COMPLETED" else None
        return {"id": job_id, "status": status, "asset": asset, "timings": {"queuedMs": 0}, "uploadedFiles": []}

    def do_GET(self):
        srv = self.server
        parts = self.path.split("?")[0].strip("/").split("/")
        if parts[:3] != ["api", "v1", "jobs"] or len(parts) < 4:
            return self.send_json(404, {"error": {"code": "NOT_FOUND", "message": "x"}})
        job_id = parts[3]
        if len(parts) == 4:
            with srv.lock:
                srv.polls[job_id] = srv.polls.get(job_id, 0) + 1
                polls = srv.polls[job_id]
            status = "PENDING" if job_id == "pending" or (job_id == "created" and polls < 2) else "COMPLETED"
            return self.send_json(200, self.job_doc(job_id, status))
        with srv.lock:
            index = srv.asset_requests
            srv.asset_requests += 1
        data = ASSET_B if srv.mutate and index > 0 else ASSET_A
        self.send_response(200)
        self.send_header("Content-Type", "model/gltf-binary")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            for start in range(0, len(data), 16384):
                self.wfile.write(data[start:start + 16384])
        except OSError:
            with srv.lock:
                srv.client_aborts += 1
            self.close_connection = True

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        self.server.uploads.append({"body": body, "key": self.headers.get("Idempotency-Key")})
        self.send_json(202, {"jobId": "created"})


class F4Test(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeApi)
        self.server.daemon_threads = True
        self.server.lock = threading.Lock()
        self.server.polls, self.server.uploads = {}, []
        self.server.asset_requests, self.server.client_aborts, self.server.mutate = 0, 0, False
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = f4.main(["--base-url", self.base, "--settle-s", "0", "--poll-s", "0.01", *extra])
        return code, out.getvalue(), err.getvalue()

    def test_aborts_then_job_and_file_unchanged(self):
        code, out, _ = self.run_main("--job-id", "done", "--n", "6", "--concurrency", "3", "--abort-after", "20000")
        report = json.loads(out)
        self.assertEqual(code, 0, out)
        self.assertEqual(report["outcomes"], {"aborted": 6})
        self.assertEqual(report["abortAfterBytes"], 20000)
        self.assertEqual(report["jobStatusAfter"], "COMPLETED")
        self.assertTrue(report["redownload"]["match"])
        self.assertEqual(report["reference"]["sha256"], hashlib.sha256(ASSET_A).hexdigest())
        self.assertEqual(report["assetPath"], "/api/v1/jobs/done/asset")
        self.assertEqual(self.server.asset_requests, 8)  # reference + 6 aborted + re-download

    def test_rst_abort(self):
        code, out, _ = self.run_main("--job-id", "done", "--n", "3", "--concurrency", "3", "--rst")
        self.assertEqual(code, 0, out)
        self.assertEqual(json.loads(out)["abortAfterBytes"], f4.CHUNK)

    def test_changed_file_fails_the_check(self):
        self.server.mutate = True
        code, out, _ = self.run_main("--job-id", "done", "--n", "2", "--concurrency", "2")
        report = json.loads(out)
        self.assertEqual(code, 1)
        self.assertFalse(report["checks"]["redownloadMatches"])

    def test_job_not_completed_cannot_start(self):
        code, _, err = self.run_main("--job-id", "pending", "--wait-s", "0")
        self.assertEqual(code, 2)
        self.assertIn("not COMPLETED", err)

    def test_creates_a_job_from_photos(self):
        with tempfile.TemporaryDirectory() as tmp:
            photo = Path(tmp) / "p.png"
            photo.write_bytes(b"\x89PNG\r\n\x1a\n" + bytes(100))
            report_path = Path(tmp) / "f4.json"
            code, out, _ = self.run_main("--photo", str(photo), "--photo", str(photo), "--run-id", "unit",
                                         "--n", "2", "--json-out", str(report_path))
            self.assertEqual(code, 0, out)
            self.assertEqual(json.loads(report_path.read_text(encoding="utf-8"))["jobId"], "created")
        upload = self.server.uploads[0]
        self.assertTrue(upload["key"].startswith("lt-unit-f4-"))
        self.assertEqual(upload["body"].count(b'filename="lt-unit-f4-'), 2)
        self.assertIn(b"Content-Type: image/png", upload["body"])

    def test_run_id_with_fail_token_is_refused(self):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            f4.parse_args(["--base-url", self.base, "--photo", "x.png", "--run-id", "myFAILrun"])


if __name__ == "__main__":
    unittest.main()
