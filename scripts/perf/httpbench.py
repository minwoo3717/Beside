"""A tiny HTTP/1.1 benchmark client on http.client (standard library only).

One HttpClient holds one keep-alive connection; use one per thread. The timed region of request() starts just before
the request is written and ends when the whole response body has been read (time.perf_counter_ns). Opening the TCP
connection is not timed: it happens before the timer starts, both for the first request and after the server closed
the connection (Tomcat closes a keep-alive connection after 100 requests by default). When the connection turns out
to be dead (reset, aborted, closed without an answer) the client reconnects and sends the request once more; only the
successful attempt is timed and `reconnects` counts the retries. POST retries are safe here because every POST
carries its own Idempotency-Key (the server answers a repeated key with the same job).
"""
from __future__ import annotations

import http.client
import json
import time
import uuid

RETRYABLE = (ConnectionError, http.client.RemoteDisconnected, http.client.BadStatusLine)


class Response(tuple):
    """(status, body bytes, elapsed ms): unpacks as that 3-tuple; .headers has the answer's headers (lower-case)."""

    def __new__(cls, status: int, body: bytes, ms: float, headers: dict = None):
        self = super().__new__(cls, (status, body, ms))
        self.headers = dict(headers or {})
        return self

    @property
    def status(self) -> int:
        return self[0]

    @property
    def body(self) -> bytes:
        return self[1]

    @property
    def ms(self) -> float:
        return self[2]

    def json(self):
        return json.loads(self.body.decode("utf-8")) if self.body else None


class HttpClient:
    """One keep-alive HTTP/1.1 connection to host:port. Not thread-safe: one client per thread."""

    def __init__(self, host: str = "127.0.0.1", port: int = 8080, timeout: float = 60.0):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.conn = None
        self.connects = 0
        self.reconnects = 0

    def _open(self):
        self.conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        self.conn.connect()  # http.client sets TCP_NODELAY
        self.connects += 1

    def close(self):
        if self.conn is not None:
            try:
                self.conn.close()
            finally:
                self.conn = None

    def request(self, method: str, path: str, body: bytes = None, headers: dict = None) -> Response:
        """Send one request and read the whole answer. Returns (status, body, elapsed ms, headers)."""
        headers = dict(headers or {})
        last_error = None
        for _attempt in range(2):
            if self.conn is None or self.conn.sock is None:
                self._open()  # not timed
            start = time.perf_counter_ns()
            try:
                self.conn.request(method, path, body=body, headers=headers)
                answer = self.conn.getresponse()
                data = answer.read()
                end = time.perf_counter_ns()
            except RETRYABLE as error:
                self.close()
                self.reconnects += 1
                last_error = error
                continue
            except BaseException:
                self.close()
                raise
            if answer.will_close:
                self.close()  # http.client already handed the socket to the response; open a new one next time
            return Response(answer.status, data, (end - start) / 1e6, {k.lower(): v for k, v in answer.getheaders()})
        raise last_error

    def get(self, path: str, headers: dict = None) -> Response:
        return self.request("GET", path, headers=headers)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def multipart(files, field: str = "photos", boundary: str = None):
    """Encode files as multipart/form-data. files: [(filename, bytes, content_type)].

    Returns (body bytes, Content-Type header value). Every part is
    --boundary CRLF, Content-Disposition: form-data; name="<field>"; filename="<name>" CRLF, Content-Type CRLF, CRLF,
    the bytes, CRLF; the body ends with --boundary-- CRLF.
    """
    boundary = boundary or "beside-perf-" + uuid.uuid4().hex
    if not boundary.isascii() or any(c in boundary for c in "\r\n\""):
        raise ValueError("bad boundary")
    marker = b"--" + boundary.encode("ascii")
    out = []
    for filename, data, content_type in files:
        if any(c in filename for c in "\r\n\"") or not filename.isascii():
            raise ValueError(f"unsupported filename {filename!r}")
        if marker in data:
            raise ValueError("the boundary occurs in the file data")
        out.append(marker + b"\r\n")
        out.append(f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'.encode("ascii"))
        out.append(f"Content-Type: {content_type}\r\n\r\n".encode("ascii"))
        out.append(bytes(data))
        out.append(b"\r\n")
    out.append(marker + b"--\r\n")
    return b"".join(out), f"multipart/form-data; boundary={boundary}"


def upload_headers(content_type: str) -> dict:
    """Headers of one POST /api/v1/jobs: the multipart type and a fresh Idempotency-Key, like the Unity app sends."""
    return {"Content-Type": content_type, "Idempotency-Key": "perf-" + uuid.uuid4().hex}
