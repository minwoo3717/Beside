"""F4 aborted downloads: start N asset downloads, abort each in the middle of the body, then check that the job is
still COMPLETED and that a full re-download has the same SHA-256 as before (plan section 3, F4). Stdlib only.

    python scripts/load/failure/f4_abort.py --base-url https://<domain> --job-id <uuid> --n 1000 --concurrency 20
    python scripts/load/failure/f4_abort.py --base-url http://localhost:18080 --run-id f4local \
        --photo scripts/load/out/fixtures/photo-400k.png --photo scripts/load/out/fixtures/photo-400k.png

The server-side half of F4 (busy Tomcat threads and open files back to the baseline within 30 s, the
beside.asset.downloads{outcome=client_abort} count) is read from Prometheus over the window this script prints
(startedAt / endedAt), e.g. with scripts/load/collect.py. Exit code: 0 all checks passed, 1 a check failed,
2 the run could not start (server down, job not COMPLETED, asset too small to abort mid-body).
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import http.client
import json
import socket
import struct
import sys
import time
import uuid
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

CHUNK = 64 * 1024
CONTENT_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp"}


class SetupError(Exception):
    """The run cannot start; exit code 2."""


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class Target:
    def __init__(self, base_url: str, timeout: float):
        parts = urlsplit(base_url.rstrip("/"))
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise SetupError(f"--base-url must be http(s)://host[:port], got {base_url!r}")
        self.scheme, self.host, self.port = parts.scheme, parts.hostname, parts.port
        self.prefix = parts.path
        self.timeout = timeout
        self.base_url = base_url.rstrip("/")

    def connect(self) -> http.client.HTTPConnection:
        cls = http.client.HTTPSConnection if self.scheme == "https" else http.client.HTTPConnection
        return cls(self.host, self.port, timeout=self.timeout)

    def path(self, rel: str) -> str:
        return self.prefix + rel


def request_json(target: Target, method: str, rel: str, body: bytes | None = None, headers: dict | None = None):
    conn = target.connect()
    try:
        conn.request(method, target.path(rel), body=body, headers=headers or {})
        resp = conn.getresponse()
        data = resp.read()
        try:
            doc = json.loads(data.decode("utf-8")) if data else None
        except ValueError:
            doc = None
        return resp.status, doc
    finally:
        conn.close()


def multipart(run_id: str, photos: list) -> tuple:
    boundary = "----beside-f4-" + uuid.uuid4().hex
    out = []
    for i, path in enumerate(photos, 1):
        p = Path(path)
        ctype = CONTENT_TYPES.get(p.suffix.lower(), "image/png")
        name = f"lt-{run_id}-f4-{i}{p.suffix.lower() or '.png'}"
        out.append(f'--{boundary}\r\nContent-Disposition: form-data; name="photos"; filename="{name}"\r\n'
                   f"Content-Type: {ctype}\r\n\r\n".encode("ascii"))
        out.append(p.read_bytes())
        out.append(b"\r\n")
    out.append(f"--{boundary}--\r\n".encode("ascii"))
    return b"".join(out), f"multipart/form-data; boundary={boundary}"


def create_job(target: Target, run_id: str, photos: list) -> str:
    body, ctype = multipart(run_id, photos)
    status, doc = request_json(target, "POST", "/api/v1/jobs", body,
                               {"Content-Type": ctype, "Idempotency-Key": f"lt-{run_id}-f4-{uuid.uuid4()}"})
    if status != 202 or not doc or "jobId" not in doc:
        raise SetupError(f"POST /api/v1/jobs returned {status}: {doc}")
    return doc["jobId"]


def wait_completed(target: Target, job_id: str, wait_s: float, poll_s: float) -> dict:
    deadline = time.monotonic() + wait_s
    while True:
        status, doc = request_json(target, "GET", f"/api/v1/jobs/{job_id}")
        if status != 200 or not doc:
            raise SetupError(f"GET /api/v1/jobs/{job_id} returned {status}")
        if doc.get("status") == "COMPLETED":
            return doc
        if doc.get("status") == "FAILED" or time.monotonic() > deadline:
            raise SetupError(f"job {job_id} is {doc.get('status')}, not COMPLETED")
        time.sleep(poll_s)


def download_full(target: Target, rel: str) -> dict:
    conn = target.connect()
    try:
        conn.request("GET", target.path(rel))
        resp = conn.getresponse()
        digest, total = hashlib.sha256(), 0
        while True:
            block = resp.read(CHUNK)
            if not block:
                break
            digest.update(block)
            total += len(block)
        length = resp.getheader("Content-Length")
        return {"status": resp.status, "bytes": total, "contentLength": int(length) if length else None,
                "sha256": digest.hexdigest()}
    finally:
        conn.close()


def abort_one(target: Target, rel: str, abort_after: int, rst: bool) -> dict:
    """Read the headers and abort_after bytes of the body, then drop the connection."""
    conn = target.connect()
    try:
        conn.request("GET", target.path(rel))
        resp = conn.getresponse()
        if resp.status != 200:
            resp.read()
            return {"outcome": f"status_{resp.status}", "read": 0}
        got = 0
        while got < abort_after:
            block = resp.read(min(CHUNK, abort_after - got))
            if not block:
                break
            got += len(block)
        if got < abort_after:
            return {"outcome": "short", "read": got}
        sock = conn.sock
        if rst and sock is not None:
            # SO_LINGER {on, 0 s}: close() sends RST instead of FIN, like a killed client.
            linger = struct.pack("HH" if sys.platform == "win32" else "ii", 1, 0)  # Windows: two u_short
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, linger)
        return {"outcome": "aborted", "read": got}
    except (OSError, http.client.HTTPException) as exc:
        return {"outcome": f"error_{type(exc).__name__}", "read": 0}
    finally:
        conn.close()


def run(args) -> dict:
    target = Target(args.base_url, args.timeout)
    report = {"schema": "beside-load-f4/1", "baseUrl": target.base_url, "n": args.n, "concurrency": args.concurrency,
              "rst": args.rst, "startedAt": now_iso()}
    job_id = args.job_id or create_job(target, args.run_id, args.photo)
    report["jobId"] = job_id
    job = wait_completed(target, job_id, args.wait_s, args.poll_s)
    asset = job.get("asset") or {}
    rel = asset.get("url") or f"/api/v1/jobs/{job_id}/asset"
    size = int(asset.get("bytes") or 0)
    if size < 2:
        raise SetupError(f"asset is {size} B: put the 1.5 MB synthetic GLB on the load-test volume first")
    abort_after = min(args.abort_after, size // 2) if args.abort_after > 0 else size // 2
    first = download_full(target, rel)
    if first["status"] != 200 or first["bytes"] != size:
        raise SetupError(f"reference download: status {first['status']}, {first['bytes']} B (job says {size} B)")
    report.update({"assetPath": rel.split("?")[0], "assetBytes": size, "abortAfterBytes": abort_after, "reference": first})

    outcomes = Counter()
    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        for result in pool.map(lambda _: abort_one(target, rel, abort_after, args.rst), range(args.n)):
            outcomes[result["outcome"]] += 1
    report["abortSeconds"] = round(time.monotonic() - started, 3)
    report["outcomes"] = dict(sorted(outcomes.items()))
    report["abortsEndedAt"] = now_iso()

    if args.settle_s > 0:
        time.sleep(args.settle_s)
    status, after = request_json(target, "GET", f"/api/v1/jobs/{job_id}")
    again = download_full(target, rel)
    report["jobStatusAfter"] = after.get("status") if status == 200 and after else f"http_{status}"
    report["redownload"] = dict(again, match=again["sha256"] == first["sha256"] and again["bytes"] == size)
    report["checks"] = {
        "allAborted": outcomes.get("aborted", 0) == args.n,
        "jobStillCompleted": report["jobStatusAfter"] == "COMPLETED",
        "redownloadMatches": report["redownload"]["match"],
    }
    report["ok"] = all(report["checks"].values())
    report["endedAt"] = now_iso()
    return report


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="F4: abort asset downloads mid-body, then verify the job and the file.")
    ap.add_argument("--base-url", required=True)
    who = ap.add_mutually_exclusive_group(required=True)
    who.add_argument("--job-id", help="a COMPLETED job whose asset is at least a few hundred kB")
    who.add_argument("--photo", action="append", help="create a job from these photos (repeat for each photo)")
    ap.add_argument("--run-id", default="f4", help="filename / Idempotency-Key prefix lt-<run-id>- (with --photo)")
    ap.add_argument("--n", type=int, default=1000)
    ap.add_argument("--concurrency", type=int, default=20)
    ap.add_argument("--abort-after", type=int, default=CHUNK, help="body bytes read before aborting (at most half the file)")
    ap.add_argument("--rst", action="store_true", help="abort with TCP RST (SO_LINGER 0) instead of a normal close")
    ap.add_argument("--settle-s", type=float, default=5.0, help="pause before the after-checks")
    ap.add_argument("--wait-s", type=float, default=900.0, help="how long to wait for the job to be COMPLETED")
    ap.add_argument("--poll-s", type=float, default=2.0)
    ap.add_argument("--timeout", type=float, default=30.0, help="socket timeout per request")
    ap.add_argument("--json-out", help="also write the report here")
    args = ap.parse_args(argv)
    if args.n < 1 or args.concurrency < 1:
        ap.error("--n and --concurrency must be at least 1")
    if "fail" in args.run_id.lower():
        ap.error("--run-id must not contain 'fail' (the mock worker fails such uploads)")
    return args


def main(argv=None) -> int:
    args = parse_args(argv)
    try:
        report = run(args)
    except (SetupError, OSError, http.client.HTTPException) as exc:
        print(f"f4_abort: cannot run: {exc}", file=sys.stderr)
        return 2
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.json_out:
        Path(args.json_out).write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
