"""httpbench.py: exact multipart bytes, keep-alive, reconnects and timing against a local http.server."""
import hashlib
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpbench


class MultipartTest(unittest.TestCase):
    def test_exact_bytes(self):
        body, content_type = httpbench.multipart(
            [("a.png", b"\x89PNG\r\n", "image/png"), ("b.png", b"xyz", "image/png")], boundary="B0UND")
        expected = (b"--B0UND\r\n"
                    b'Content-Disposition: form-data; name="photos"; filename="a.png"\r\n'
                    b"Content-Type: image/png\r\n\r\n"
                    b"\x89PNG\r\n\r\n"
                    b"--B0UND\r\n"
                    b'Content-Disposition: form-data; name="photos"; filename="b.png"\r\n'
                    b"Content-Type: image/png\r\n\r\n"
                    b"xyz\r\n"
                    b"--B0UND--\r\n")
        self.assertEqual(body, expected)
        self.assertEqual(content_type, "multipart/form-data; boundary=B0UND")

    def test_rejects_unsafe_names_and_boundary_in_data(self):
        with self.assertRaises(ValueError):
            httpbench.multipart([('x".png', b"1", "image/png")], boundary="B")
        with self.assertRaises(ValueError):
            httpbench.multipart([("x.png", b"..--B..", "image/png")], boundary="B")
        body, content_type = httpbench.multipart([("x.png", b"1", "image/png")])
        self.assertIn(content_type.split("boundary=")[1].encode(), body)

    def test_upload_headers_have_fresh_keys(self):
        first = httpbench.upload_headers("multipart/form-data; boundary=x")
        second = httpbench.upload_headers("multipart/form-data; boundary=x")
        self.assertEqual(first["Content-Type"], "multipart/form-data; boundary=x")
        self.assertNotEqual(first["Idempotency-Key"], second["Idempotency-Key"])
        self.assertLessEqual(len(first["Idempotency-Key"]), 128)


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    connections = 0
    dropped = set()
    lock = threading.Lock()

    def setup(self):
        super().setup()
        with _Handler.lock:
            _Handler.connections += 1

    def log_message(self, *args):
        pass

    def _send(self, status, body: bytes, close=False):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        if close:
            self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        if close:
            self.close_connection = True

    def do_GET(self):
        if self.path == "/close":
            self._send(200, b"bye", close=True)
        elif self.path.startswith("/drop-once"):
            with _Handler.lock:
                first = self.path not in _Handler.dropped
                _Handler.dropped.add(self.path)
            if first:
                self.close_connection = True  # close without any answer
                return
            self._send(200, b"second try")
        else:
            self._send(200, b"pong")

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        answer = {"length": len(body), "type": self.headers["Content-Type"],
                  "key": self.headers.get("Idempotency-Key"), "sha": hashlib.sha256(body).hexdigest()}
        self._send(202, json.dumps(answer).encode())


class ClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        _Handler.connections = 0

    def test_keep_alive_reuses_one_connection(self):
        with httpbench.HttpClient("127.0.0.1", self.port, timeout=10) as client:
            answers = [client.get("/ping") for _ in range(5)]
        self.assertTrue(all(a.status == 200 and a.body == b"pong" for a in answers))
        self.assertTrue(all(a.ms > 0 for a in answers))
        status, body, ms = answers[0]  # (status, body bytes, elapsed ms)
        self.assertEqual((status, body), (200, b"pong"))
        self.assertEqual(ms, answers[0].ms)
        self.assertEqual(client.connects, 1)
        self.assertEqual(client.reconnects, 0)
        self.assertEqual(_Handler.connections, 1)

    def test_reopens_after_connection_close(self):
        with httpbench.HttpClient("127.0.0.1", self.port, timeout=10) as client:
            self.assertEqual(client.get("/close").body, b"bye")
            self.assertEqual(client.get("/ping").body, b"pong")
        self.assertEqual(client.connects, 2)
        self.assertEqual(client.reconnects, 0)  # an announced close is not a failure

    def test_retries_once_when_the_connection_dies(self):
        with httpbench.HttpClient("127.0.0.1", self.port, timeout=10) as client:
            client.get("/ping")
            answer = client.get("/drop-once-a")
        self.assertEqual(answer.status, 200)
        self.assertEqual(answer.body, b"second try")
        self.assertEqual(client.reconnects, 1)

    def test_post_sends_the_multipart_body(self):
        body, content_type = httpbench.multipart([("p.png", b"\x89PNG" + bytes(range(256)) * 40, "image/png")])
        with httpbench.HttpClient("127.0.0.1", self.port, timeout=10) as client:
            answer = client.request("POST", "/api/v1/jobs", body=body, headers=httpbench.upload_headers(content_type))
        self.assertEqual(answer.status, 202)
        echoed = answer.json()
        self.assertEqual(echoed["length"], len(body))
        self.assertEqual(echoed["type"], content_type)
        self.assertEqual(echoed["sha"], hashlib.sha256(body).hexdigest())
        self.assertTrue(echoed["key"].startswith("perf-"))
        self.assertEqual(answer.headers["content-type"], "application/json")


if __name__ == "__main__":
    unittest.main()
