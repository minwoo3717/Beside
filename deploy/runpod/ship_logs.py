#!/usr/bin/env python3
"""Ships the GPU Pod's log files to the VM's Loki over the tailnet. Standard library only (Python 3.8+).

deploy/runpod/start_pod.sh starts it, under a restart loop, when BESIDE_TELEMETRY_VM is set:
    LOG_DIR=/workspace/beside-logs BESIDE_LOG_PUSH_URL=http://<VM tailnet IP>:3500/loki/api/v1/push \
      BESIDE_TELEMETRY_PROXY=http://127.0.0.1:1055 BESIDE_TELEMETRY_ENV=prod TS_HOSTNAME=beside-gpu \
      python3 deploy/runpod/ship_logs.py [LOG_DIR]

- Follows $LOG_DIR/inference.log, start.log and supervisor.log. A file seen for the first time starts at its end (no
  backlog of old runs), one that appears later from its start. How far each file was shipped (inode, offset, hash of
  its first bytes) is kept in $LOG_DIR/ship_logs.pos, so a restarted shipper or Pod goes on where it stopped; lines
  read but not yet accepted by Loki are read again after a restart. A file that got shorter (truncated) or whose first
  bytes changed (replaced, rotated) is read from its start. The first bytes, not the inode alone, say "same file",
  because an inode may not survive a remount of a network volume.
- One Loki entry per log line. A line that continues the one before - a traceback with its frames and exception line,
  an indented line - is appended to it, so an ERROR and its stack are one entry. Lines that start with a time or a
  level, uvicorn's "INFO:" lines, "timing ..." lines and everything else start their own entry.
- Pushes every second, or as soon as 200 entries wait: Loki push JSON with the labels file, host ($TS_HOSTNAME) and
  env ($BESIDE_TELEMETRY_ENV, default prod). An entry's time is when it was read; it stays the same when it is sent
  again. Requests go through the HTTP proxy $BESIDE_TELEMETRY_PROXY (userspace tailscaled: the Pod's processes reach
  100.x only through it).
- A failed push is tried again after 1, 2, 4 ... 30 s while reading goes on. Above 5000 waiting entries (or 8 MiB) the
  oldest are dropped and counted. A 4xx answer other than 408/429 drops that batch: sending it again would not help.
- Secret values (the inference token, token=/secret=/password=/api_key= values, Bearer/Basic credentials, hf_/tskey-/gh*_
  keys) become ***, and every line naming X-Inference-Token is dropped.
- Its own status lines go to stdout (start_pod.sh appends them to $LOG_DIR/ship_logs.log, a file it never ships).
It is a process of its own: whatever happens here, the inference service goes on.
"""
from __future__ import annotations

import collections
import hashlib
import http.client
import json
import os
import re
import signal
import socket
import sys
import time
import urllib.error
import urllib.request

FILES = ("inference.log", "start.log", "supervisor.log")
POS_FILE = "ship_logs.pos"
BATCH_LINES = 200
BATCH_BYTES = 1024 * 1024
PUSH_EVERY_S = 1.0
POLL_S = 0.25
HOLD_S = 0.5                      # an entry waits this long for more continuation lines
MAX_BUFFER = 5000
MAX_BUFFER_BYTES = 8 * 1024 * 1024
BACKOFF_FIRST_S = 1.0
BACKOFF_MAX_S = 30.0
TIMEOUT_S = 5.0
READ_MAX = 1024 * 1024            # bytes read from one file per poll
MAX_ENTRY = 32 * 1024             # characters of one entry; the rest is cut
HEAD_BYTES = 512                  # first bytes that identify a file
SAVE_EVERY_S = 1.0
REPORT_EVERY_S = 60.0             # a lasting problem is reported again at most this often

TRACEBACK = "Traceback (most recent call last):"
CHAINED = ("During handling of the above exception, another exception occurred:",
           "The above exception was the direct cause of the following exception:")
DROP_MARKER = "x-inference-token"
_STARTS_ENTRY = re.compile(r"\d{4}-\d\d-\d\d[T ]\d\d:\d\d|(?:DEBUG|INFO|NOTE|WARN|WARNING|ERROR|CRITICAL|FATAL)(?::|\s)")
# inside a traceback: its exception line ("ValueError: boom", "torch.OutOfMemoryError: ...") or a bare name
_EXCEPTION_LINE = re.compile(r"[A-Za-z_][\w.]*(?::.*)?")
_BEARER = re.compile(r"(?i)\b(bearer|basic)\s+(?!\*\*\*)[A-Za-z0-9._~+/=-]{8,}")
_SECRET_VALUE = re.compile(r"(?i)\b([\w.-]*(?:token|secret|password|passwd|api[_-]?key))(\s*[=:]\s*[\"']?)(?!\*\*\*)"
                           r"([^\s\"',;&]+)")
_KNOWN_KEYS = re.compile(r"\b(?:hf_[A-Za-z0-9]{20,}|tskey-[A-Za-z0-9-]{10,}|gh[pousr]_[A-Za-z0-9]{20,})")


def _utc() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _print_status(message: str) -> None:
    print(f"{_utc()} ship_logs: {message}", flush=True)


class _Entry:
    __slots__ = ("file", "gen", "start", "ts", "lines", "size", "touched", "dropped", "cut")

    def __init__(self, file: str, gen: int, start: int, ts: int, now: float, dropped: bool):
        self.file, self.gen, self.start, self.ts = file, gen, start, ts
        self.lines: list = []
        self.size = 0
        self.touched = now
        self.dropped = dropped
        self.cut = False

    def add(self, text: str) -> None:
        if self.dropped:
            return
        if self.size + len(text) + 1 > MAX_ENTRY:
            if not self.cut:
                room = max(0, MAX_ENTRY - self.size - 1)
                if room:
                    self.lines.append(text[:room])
                    self.size += room + 1
                self.cut = True
            return
        self.lines.append(text)
        self.size += len(text) + 1

    def text(self) -> str:
        lines = list(self.lines)
        while len(lines) > 1 and not lines[-1]:
            lines.pop()
        return "\n".join(lines) + (" ...[cut]" if self.cut else "")


class _File:
    def __init__(self, name: str):
        self.name = name
        self.inode = None
        self.offset = 0          # bytes turned into entries (a line without its newline yet is not counted)
        self.head = ""           # sha1 of the first head_len bytes
        self.head_len = 0
        self.gen = 0             # +1 whenever the file was truncated or replaced
        self.open = None         # the entry that may still get continuation lines
        self.in_tb = False       # the open entry is in a traceback
        self.last_ts = 0


class Shipper:
    def __init__(self, log_dir: str, url: str, *, proxy: str = None, env: str = "prod", host: str = "beside-gpu",
                 secrets=(), files=FILES, clock=time.monotonic, wall_ns=time.time_ns, status=None):
        self.log_dir = log_dir
        self.url = url
        self.proxy = proxy or None
        self.labels = {"host": host, "env": env}
        self.clock, self.wall_ns = clock, wall_ns
        self.status = status or _print_status
        self.secrets = sorted({s.strip() for s in secrets if s and len(s.strip()) >= 8}, key=len, reverse=True)
        # no proxy given: none at all, also not one from http_proxy in the environment
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": self.proxy, "https": self.proxy} if self.proxy else {}))
        self.pos_path = os.path.join(log_dir, POS_FILE)
        self.files = [_File(name) for name in files]
        self.buffer: collections.deque = collections.deque()
        self.buffer_bytes = 0
        self.dropped = 0          # entries dropped because the buffer was full, since the last push that worked
        self.dropped_total = 0
        self.filtered = 0         # entries dropped for naming X-Inference-Token
        self.refused = 0          # entries in batches Loki refused (4xx)
        self.failures = 0         # failed pushes in a row
        self.failing_since = 0.0
        self.backoff = BACKOFF_FIRST_S
        self.retry_at = float("-inf")
        self.last_push = float("-inf")
        self.last_save = float("-inf")
        self.saved = None
        self.reported: dict = {}  # problem key -> clock() of its last report
        self.stopping = False
        self._load()

    # ------------------------------------------------------------------ positions
    def _path(self, f: _File) -> str:
        return os.path.join(self.log_dir, f.name)

    def _load(self) -> None:
        saved = {}
        try:
            with open(self.pos_path, encoding="utf-8") as fh:
                saved = json.load(fh)["files"]
            if not isinstance(saved, dict):
                raise ValueError("files is not an object")
        except FileNotFoundError:
            pass
        except (OSError, ValueError, KeyError, TypeError) as e:
            self.status(f"could not read {self.pos_path} ({e}): every file starts at its end")
            saved = {}
        for f in self.files:
            s = saved.get(f.name)
            if (isinstance(s, dict) and isinstance(s.get("offset"), int) and s["offset"] >= 0
                    and isinstance(s.get("head"), str) and isinstance(s.get("headLen"), int)):
                f.inode, f.offset, f.head, f.head_len = s.get("inode"), s["offset"], s["head"], s["headLen"]
                continue
            try:   # first sight: from its end, or from its start once it appears
                with open(self._path(f), "rb") as fh:
                    st = os.fstat(fh.fileno())
                    head = fh.read(HEAD_BYTES)
                f.inode, f.offset = st.st_ino, st.st_size
                f.head, f.head_len = hashlib.sha1(head).hexdigest(), len(head)
            except FileNotFoundError:
                pass
            except OSError as e:
                self.status(f"{f.name}: {e}")
        self._save(force=True)

    def positions(self) -> dict:
        """Per file: where a restart should read from - the oldest entry not yet accepted by Loki, else the read end."""
        pending = {}
        for e in self.buffer:
            pending.setdefault((e.file, e.gen), e.start)
        out = {}
        for f in self.files:
            offset = f.offset
            if f.open is not None and not f.open.dropped:
                offset = min(offset, f.open.start)
            offset = min(offset, pending.get((f.name, f.gen), offset))
            out[f.name] = {"inode": f.inode, "offset": offset, "head": f.head, "headLen": f.head_len}
        return out

    def _save(self, force: bool = False) -> None:
        state = self.positions()
        if state == self.saved and not force:
            return
        tmp = self.pos_path + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump({"version": 1, "files": state}, fh)
            os.replace(tmp, self.pos_path)
            self.saved = state
        except OSError as e:
            self._report("save", f"could not save positions to {self.pos_path}: {e}")

    # ------------------------------------------------------------------ reading
    def _read(self, f: _File, now: float) -> None:
        try:
            fh = open(self._path(f), "rb")
        except FileNotFoundError:
            return
        with fh:
            st = os.fstat(fh.fileno())
            head = fh.read(HEAD_BYTES)
            reason = None
            if st.st_size < f.offset:
                reason = "got shorter (truncated)"
            elif f.head_len and hashlib.sha1(head[:f.head_len]).hexdigest() != f.head:
                reason = "has other first bytes (replaced or rotated)"
            elif not f.head_len and f.offset and f.inode is not None and st.st_ino != f.inode:
                reason = "is another file (inode)"
            if reason:
                self._close(f)
                self.status(f"{f.name} {reason}: reading it from its start")
                f.offset, f.gen, f.in_tb = 0, f.gen + 1, False
            f.inode, f.head, f.head_len = st.st_ino, hashlib.sha1(head).hexdigest(), len(head)
            if st.st_size <= f.offset:
                return
            fh.seek(f.offset)
            data = fh.read(min(READ_MAX, st.st_size - f.offset))
        end = data.rfind(b"\n") + 1
        if end == 0:
            if len(data) < READ_MAX:
                return   # wait for the end of the line
            end = len(data)   # a line longer than READ_MAX: take what there is
        start = f.offset
        for raw in data[:end].split(b"\n"):
            if start >= f.offset + end:
                break
            self._line(f, raw, start, now)
            start += len(raw) + 1
        f.offset += end

    def _continues(self, f: _File, text: str) -> bool:
        if _STARTS_ENTRY.match(text):
            return False
        if text[0] in " \t" or text == TRACEBACK or text in CHAINED:
            return True
        return f.in_tb and _EXCEPTION_LINE.fullmatch(text) is not None

    def _line(self, f: _File, raw: bytes, start: int, now: float) -> None:
        text = raw.decode("utf-8", "replace").rstrip("\r")
        if "\r" in text:   # progress bars redraw with \r: keep what a terminal would show last
            text = [part for part in text.split("\r") if part][-1] if text.strip("\r") else ""
        if not text.strip():
            if f.open is not None and f.in_tb:   # blank lines between chained tracebacks
                f.open.add("")
                f.open.touched = now
            return
        if f.open is not None and self._continues(f, text):
            if text == TRACEBACK:
                f.in_tb = True
            if DROP_MARKER not in text.lower():
                f.open.add(self._mask(text))
            f.open.touched = now
            return
        self._close(f)
        f.in_tb = text == TRACEBACK
        f.last_ts = max(self.wall_ns(), f.last_ts + 1)   # one stream: identical lines must not share a time
        f.open = _Entry(f.name, f.gen, start, f.last_ts, now, dropped=DROP_MARKER in text.lower())
        f.open.add(self._mask(text))

    def _mask(self, text: str) -> str:
        for secret in self.secrets:
            text = text.replace(secret, "***")
        text = _BEARER.sub(lambda m: m.group(1) + " ***", text)
        text = _SECRET_VALUE.sub(lambda m: m.group(1) + m.group(2) + "***", text)
        return _KNOWN_KEYS.sub("***", text)

    def _close(self, f: _File) -> None:
        entry, f.open = f.open, None
        if entry is None:
            return
        if entry.dropped:
            self.filtered += 1
            return
        self.buffer.append(entry)
        self.buffer_bytes += entry.size
        while len(self.buffer) > MAX_BUFFER or self.buffer_bytes > MAX_BUFFER_BYTES:
            old = self.buffer.popleft()
            self.buffer_bytes -= old.size
            if not self.dropped:
                self.status(f"buffer full ({MAX_BUFFER} entries or {MAX_BUFFER_BYTES // 1048576} MiB): dropping the "
                            f"oldest until a push works again")
            self.dropped += 1
            self.dropped_total += 1

    # ------------------------------------------------------------------ pushing
    def _due(self, now: float) -> bool:
        if not self.buffer or now < self.retry_at:
            return False
        return len(self.buffer) >= BATCH_LINES or now - self.last_push >= PUSH_EVERY_S

    def _post(self, entries) -> None:
        streams: dict = {}
        for e in entries:
            streams.setdefault(e.file, []).append([str(e.ts), e.text()])
        body = {"streams": [{"stream": dict(self.labels, file=name), "values": values}
                            for name, values in streams.items()]}
        request = urllib.request.Request(self.url, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                         headers={"Content-Type": "application/json"}, method="POST")
        with self.opener.open(request, timeout=TIMEOUT_S) as answer:
            answer.read()

    def _remove(self, count: int) -> None:
        for _ in range(count):
            self.buffer_bytes -= self.buffer.popleft().size

    def _push(self, now: float) -> bool:
        """One batch from the head of the buffer. True when the loop may go on with the next one."""
        batch, size = [], 0
        for e in self.buffer:
            if batch and (len(batch) >= BATCH_LINES or size + e.size > BATCH_BYTES):
                break
            batch.append(e)
            size += e.size
        try:
            self._post(batch)
        except urllib.error.HTTPError as e:
            try:
                why = e.read(200).decode("utf-8", "replace").strip()
            except Exception:  # noqa: BLE001
                why = ""
            finally:
                e.close()
            if 400 <= e.code < 500 and e.code not in (408, 429):
                self._remove(len(batch))
                self.refused += len(batch)
                self.status(f"push refused (HTTP {e.code} {why[:160]}): dropped those {len(batch)} entries")
                self.last_push = now
                return True
            return self._failed(now, f"HTTP {e.code} {why[:160]}".strip())
        except (OSError, http.client.HTTPException, ValueError) as e:   # URLError, timeouts, refused connections
            return self._failed(now, getattr(e, "reason", None) or f"{type(e).__name__}: {e}")
        self._remove(len(batch))
        if self.failures:
            self.status(f"push works again after {self.failures} failed tries over {now - self.failing_since:.0f} s"
                        + (f"; {self.dropped} entries were dropped meanwhile (buffer full)" if self.dropped else ""))
        self.failures, self.dropped, self.backoff, self.retry_at, self.last_push = 0, 0, BACKOFF_FIRST_S, float("-inf"), now
        return True

    def _failed(self, now: float, why) -> bool:
        self.failures += 1
        if self.failures == 1:
            self.failing_since = now
            self.reported["failing"] = now
            self.status(f"push failed ({why}): {len(self.buffer)} entries wait, trying again with a growing pause "
                        f"(1 s up to {BACKOFF_MAX_S:.0f} s)")
        self.retry_at = now + self.backoff
        self.backoff = min(self.backoff * 2, BACKOFF_MAX_S)
        return False

    # ------------------------------------------------------------------ loop
    def _report(self, key: str, message: str) -> None:
        now = self.clock()
        if now - self.reported.get(key, float("-inf")) >= REPORT_EVERY_S:
            self.reported[key] = now
            self.status(message)

    def step(self) -> None:
        now = self.clock()
        for f in self.files:
            try:
                self._read(f, now)
            except OSError as e:
                self._report("read " + f.name, f"{f.name}: {e}")
            if f.open is not None and now - f.open.touched >= HOLD_S:
                self._close(f)
        while self._due(now) and self._push(now):
            pass
        if self.failures and now - self.reported.get("failing", now) >= REPORT_EVERY_S:
            self._report("failing", f"push still failing: {self.failures} tries in {now - self.failing_since:.0f} s, "
                                    f"{len(self.buffer)} entries wait, {self.dropped} dropped (buffer full)")
        if now - self.last_save >= SAVE_EVERY_S:
            self.last_save = now
            self._save()

    def stop(self, *_):
        self.stopping = True

    def run(self, sleep=time.sleep) -> None:
        self.status(f"start: {', '.join(f.name for f in self.files)} in {self.log_dir} -> {self.url} "
                    f"(proxy {self.proxy or 'none'}, labels {json.dumps(self.labels, sort_keys=True)})")
        while not self.stopping:
            try:
                self.step()
            except Exception as e:  # noqa: BLE001  keep shipping whatever went wrong once
                self._report("error", f"unexpected {type(e).__name__}: {e}")
            sleep(POLL_S)
        self._save(force=True)
        self.status(f"stopped ({len(self.buffer)} entries not sent: read again at the next start)")


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    log_dir = argv[0] if argv else (os.environ.get("LOG_DIR") or "/workspace/beside-logs")
    url = (os.environ.get("BESIDE_LOG_PUSH_URL") or "").strip()
    if not url:
        _print_status("BESIDE_LOG_PUSH_URL is not set: nothing to ship to")
        return 2
    if not os.path.isdir(log_dir):
        _print_status(f"{log_dir} is not a directory")
        return 2
    shipper = Shipper(log_dir, url, proxy=(os.environ.get("BESIDE_TELEMETRY_PROXY") or "").strip() or None,
                      env=(os.environ.get("BESIDE_TELEMETRY_ENV") or "").strip() or "prod",
                      host=(os.environ.get("TS_HOSTNAME") or "").strip() or socket.gethostname(),
                      secrets=[os.environ.get("BESIDE_INFERENCE_TOKEN") or ""])
    signal.signal(signal.SIGTERM, shipper.stop)
    signal.signal(signal.SIGINT, shipper.stop)
    shipper.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
