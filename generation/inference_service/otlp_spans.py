"""Spans of the inference service as OTLP/JSON over HTTP, standard library only.

Why no OpenTelemetry SDK: the model runs in a conda Python 3.10 + torch environment that start_pod.sh does not reinstall,
and the SDK's OTLP exporter pulls in protobuf - a version clash or a failed install there would stop inference. The VM's
Alloy takes OTLP/HTTP JSON on :4320 and hands the spans to Tempo, next to Spring's (deploy/monitoring/alloy/config.alloy).

- Off unless BESIDE_OTLP_URL is set (deploy/runpod/start_pod.sh sets it with BESIDE_TELEMETRY_VM): no span, no thread.
- app.py's _RequestContext opens one SERVER span per job request (the routes behind the token) as a child of the
  traceparent Spring sent; a traceparent with the sampled flag off (flags 00) records nothing, a request without one
  starts its own trace. span() opens INTERNAL children of whatever span is current (contextvars, so FastAPI's thread
  pool sees it).
- JSON as OTLP wants it: lowercase hex ids, integer kind and status code, int attributes as strings ({"intValue":"5"}),
  times as strings of ns. Resource: service.name=beside-inference, deployment.environment=$BESIDE_TELEMETRY_ENV (prod).
- Finished spans wait in a queue of 2048 (more are dropped and counted); a daemon thread posts them every 2 s through
  the proxy $BESIDE_TELEMETRY_PROXY, 5 s timeout, and drops what it could not send. Nothing here blocks or fails a request.
"""
from __future__ import annotations

import contextlib
import contextvars
import http.client
import json
import logging
import os
import queue
import threading
import time
import urllib.request
from typing import Optional

SERVICE_NAME = "beside-inference"
SCOPE_NAME = "beside.inference"
KIND_INTERNAL, KIND_SERVER = 1, 2
STATUS_ERROR = 2
QUEUE_MAX = 2048
BATCH_MAX = 512
INTERVAL_S = 2.0
TIMEOUT_S = 5.0
REPORT_EVERY_S = 600.0

log = logging.getLogger("beside.otlp")


def _attribute(key: str, value) -> dict:
    if isinstance(value, bool):
        typed = {"boolValue": value}
    elif isinstance(value, int):
        typed = {"intValue": str(value)}
    elif isinstance(value, float):
        typed = {"doubleValue": value}
    else:
        typed = {"stringValue": str(value)}
    return {"key": key, "value": typed}


def _new_id(size: int) -> str:
    while True:
        value = os.urandom(size)
        if any(value):   # all-zero ids are invalid
            return value.hex()


def describe(error: BaseException) -> str:
    """Status message of a span that ended with this exception: the HTTP answer for an HTTPException, else type: text."""
    status = getattr(error, "status_code", None)
    if isinstance(status, int):
        detail = getattr(error, "detail", None)
        code = detail.get("code") if isinstance(detail, dict) else None
        return f"{status} {code}" if code else str(status)
    return f"{type(error).__name__}: {error}"[:200]


class Exporter:
    def __init__(self, url: str, proxy: Optional[str] = None, environment: str = "prod", *, interval: float = INTERVAL_S,
                 timeout: float = TIMEOUT_S, max_queue: int = QUEUE_MAX, background: bool = True):
        self.url, self.proxy, self.environment = url, proxy, environment
        self.interval, self.timeout = interval, timeout
        self.queue: queue.Queue = queue.Queue(max_queue)
        self.dropped = 0      # spans that found the queue full
        self.failed = 0       # spans in posts that failed
        self.sent = 0
        self.resource = {"attributes": [_attribute("service.name", SERVICE_NAME),
                                        _attribute("deployment.environment", environment)]}
        # no proxy given: none at all, also not one from http_proxy in the environment
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": proxy, "https": proxy} if proxy else {}))
        self._background = background
        self._thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()
        self._reported = float("-inf")

    def submit(self, span: dict) -> None:
        try:
            self.queue.put_nowait(span)
        except queue.Full:
            with self._lock:
                self.dropped += 1
            return
        if self._background and self._thread is None:
            with self._lock:
                if self._thread is None:
                    self._thread = threading.Thread(target=self._loop, name="otlp-export", daemon=True)
                    self._thread.start()

    def _loop(self) -> None:
        while True:
            time.sleep(self.interval)
            try:
                self.flush()
            except Exception:  # noqa: BLE001  the thread must outlive any surprise
                log.exception("span export failed")

    def flush(self) -> bool:
        """Posts what is queued now, BATCH_MAX spans per request. False when a post failed (its spans are gone)."""
        ok = True
        while True:
            batch = []
            while len(batch) < BATCH_MAX:
                try:
                    batch.append(self.queue.get_nowait())
                except queue.Empty:
                    break
            if not batch:
                return ok
            ok = self._post(batch) and ok

    def _post(self, spans: list) -> bool:
        body = {"resourceSpans": [{"resource": self.resource,
                                   "scopeSpans": [{"scope": {"name": SCOPE_NAME}, "spans": spans}]}]}
        request = urllib.request.Request(self.url, data=json.dumps(body).encode("utf-8"),
                                         headers={"Content-Type": "application/json"}, method="POST")
        try:
            with self.opener.open(request, timeout=self.timeout) as answer:
                answer.read()
        except (OSError, http.client.HTTPException, ValueError) as e:   # URLError, HTTPError, timeouts
            self.failed += len(spans)
            now = time.monotonic()
            if now - self._reported >= REPORT_EVERY_S:   # one line per 10 min at most: the log is shipped too
                self._reported = now
                log.warning("spans: could not send %d to %s (%s), dropped them; %d dropped so far",
                            len(spans), self.url, getattr(e, "reason", None) or e, self.failed + self.dropped)
            return False
        self.sent += len(spans)
        return True


def from_env(environ=None) -> Optional[Exporter]:
    environ = os.environ if environ is None else environ
    url = (environ.get("BESIDE_OTLP_URL") or "").strip()
    if not url:
        return None
    return Exporter(url, proxy=(environ.get("BESIDE_TELEMETRY_PROXY") or "").strip() or None,
                    environment=(environ.get("BESIDE_TELEMETRY_ENV") or "").strip() or "prod")


EXPORTER: Optional[Exporter] = from_env()


class Span:
    recording = True

    def __init__(self, name: str, trace_id: str, parent_id: Optional[str], kind: int, exporter: Exporter):
        self.name, self.trace_id, self.parent_id, self.kind = name, trace_id, parent_id, kind
        self.span_id = _new_id(8)
        self.exporter = exporter
        self.attributes: dict = {}
        self.error: Optional[str] = None
        self.start_ns = time.time_ns()
        self.end_ns: Optional[int] = None

    def set(self, **attributes) -> "Span":
        """Attributes; None values are left out."""
        self.attributes.update((k, v) for k, v in attributes.items() if v is not None)
        return self

    def fail(self, message: str) -> None:
        self.error = message[:200]

    def end(self) -> None:
        if self.end_ns is not None:
            return
        self.end_ns = time.time_ns()
        try:
            self.exporter.submit(self.to_otlp())
        except Exception:  # noqa: BLE001  never the request's problem
            pass

    def to_otlp(self) -> dict:
        span = {"traceId": self.trace_id, "spanId": self.span_id, "name": self.name, "kind": self.kind,
                "startTimeUnixNano": str(self.start_ns), "endTimeUnixNano": str(self.end_ns or time.time_ns()),
                "attributes": [_attribute(k, v) for k, v in self.attributes.items()]}
        if self.parent_id:
            span["parentSpanId"] = self.parent_id
        if self.error is not None:
            span["status"] = {"code": STATUS_ERROR, "message": self.error}
        return span


class _NoSpan:
    """What span() and current() give when nothing is recorded: takes attributes and forgets them."""

    recording = False

    def set(self, **attributes) -> "_NoSpan":
        return self

    def fail(self, message: str) -> None:
        pass


NO_SPAN = _NoSpan()
CURRENT: "contextvars.ContextVar[Optional[Span]]" = contextvars.ContextVar("beside_span", default=None)


def server_span(name: str, traceparent: Optional[str]) -> Optional[Span]:
    """The SERVER span of a request (end() it), or None: export off, or a traceparent with the sampled flag off.
    traceparent: a valid W3C header (app.py checks the form) or None for a trace of its own."""
    exporter = EXPORTER
    if exporter is None:
        return None
    trace_id, parent_id = None, None
    if traceparent:
        try:
            _, trace_id, parent_id, flags = traceparent.split("-")
            if not int(flags, 16) & 1:
                return None
        except ValueError:
            trace_id, parent_id = None, None
    try:
        return Span(name, trace_id or _new_id(16), parent_id, KIND_SERVER, exporter)
    except Exception:  # noqa: BLE001  no span rather than a failed request
        return None


def current():
    """The span open in this context (the request's, or a child's), else NO_SPAN."""
    return CURRENT.get() or NO_SPAN


@contextlib.contextmanager
def span(name: str, **attributes):
    """An INTERNAL child of the current span for the with block (ERROR when the block raises); NO_SPAN without one."""
    parent = CURRENT.get()
    if parent is None:
        yield NO_SPAN
        return
    child = Span(name, parent.trace_id, parent.span_id, KIND_INTERNAL, parent.exporter).set(**attributes)
    token = CURRENT.set(child)
    try:
        yield child
    except BaseException as e:
        child.fail(describe(e))
        raise
    finally:
        CURRENT.reset(token)
        child.end()
