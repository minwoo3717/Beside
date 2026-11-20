#!/usr/bin/env python3
"""Failure-drill client: an outside probe plus a slow stream of real jobs, recorded with timestamps.

Runs on a Runpod CPU Pod (never on the dev PC, same rule as k6). Standard library only.

  python3 drill_client.py run   --base https://beside-team.duckdns.org --photo dog.jpg --out out/fd1 --run dr1 \
                                [--interval 15] [--duration 600] [--no-jobs]
  python3 drill_client.py mark  --base ... --photo dog.jpg --out out/fd5 --run dr5a --count 3
  python3 drill_client.py check --base ... --ids out/fd5/marked-dr5a.txt

run   probe.jsonl  one line per second: GET /api/v1/healthz with a 5 s timeout (code 0 = no answer)
      flows.jsonl  one line per job: POST result, every status change seen by 2 s polling, final status and error code,
                   server timings; COMPLETED jobs also download the asset (size, ms)
mark  creates --count jobs and writes their ids (FD5 markers), does not wait for them
check GETs each id and prints status or 404 (FD5: which markers survived the restore)

Photo file names start with lt-<run>- like the k6 scripts, so the jobs stay countable as load-test jobs.
Run ids must not contain "fail" or "slow" (the stub and mock workers treat those names specially).
"""
import argparse
import json
import os
import signal
import ssl
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

CTX = ssl.create_default_context()
STOP = threading.Event()


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def request(method, url, body=None, headers=None, timeout=30.0):
    """Returns (status, body bytes, elapsed ms, error text). status 0 = no HTTP answer."""
    t0 = time.monotonic()
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            data = r.read()
            return r.status, data, (time.monotonic() - t0) * 1000, None
    except urllib.error.HTTPError as e:
        try:
            data = e.read()
        except Exception:  # noqa: BLE001 - the body is best effort
            data = b""
        return e.code, data, (time.monotonic() - t0) * 1000, None
    except Exception as e:  # noqa: BLE001 - every network failure is a data point here
        return 0, b"", (time.monotonic() - t0) * 1000, f"{type(e).__name__}: {e}"[:200]


class Writer:
    def __init__(self, path):
        self.lock = threading.Lock()
        self.f = open(path, "a", encoding="utf-8")

    def write(self, obj):
        with self.lock:
            self.f.write(json.dumps(obj, ensure_ascii=False) + "\n")
            self.f.flush()


def multipart(photo_bytes, filename):
    boundary = "----drill" + uuid.uuid4().hex
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"photos\"; filename=\"{filename}\"\r\n"
            "Content-Type: image/jpeg\r\n\r\n").encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    return head + photo_bytes + tail, f"multipart/form-data; boundary={boundary}"


def create_job(base, photo, run, n):
    body, ctype = multipart(photo, f"lt-{run}-{n}.jpg")
    headers = {"Content-Type": ctype, "Idempotency-Key": f"lt-{run}-{n}-{uuid.uuid4().hex[:8]}"}
    status, data, ms, err = request("POST", f"{base}/api/v1/jobs", body, headers, timeout=60)
    job_id, code = None, None
    try:
        doc = json.loads(data or b"{}")
        job_id = doc.get("jobId")
        code = (doc.get("error") or {}).get("code")
    except ValueError:
        pass
    return {"postAt": now_iso(), "postStatus": status, "postMs": round(ms, 1), "postErr": err,
            "postCode": code, "jobId": job_id}


def follow(base, rec, writer, max_wait):
    """Poll every 2 s until COMPLETED/FAILED or max_wait; record each status change."""
    seen, last, deadline = [], None, time.monotonic() + max_wait
    final = None
    while time.monotonic() < deadline and not STOP.is_set():
        status, data, ms, err = request("GET", f"{base}/api/v1/jobs/{rec['jobId']}", timeout=10)
        key = None
        if status == 200:
            try:
                doc = json.loads(data)
            except ValueError:
                doc = {}
            key = doc.get("status")
            if key != last:
                seen.append({"at": now_iso(), "status": key})
                last = key
            if key in ("COMPLETED", "FAILED"):
                final = doc
                break
        else:
            marker = f"http{status}" if status else "noanswer"
            if marker != last:
                seen.append({"at": now_iso(), "status": marker, "err": err})
                last = marker
        time.sleep(2)
    rec["seen"] = seen
    if final is not None:
        rec["final"] = final.get("status")
        rec["errorCode"] = (final.get("error") or {}).get("code")
        rec["timings"] = final.get("timings")
        rec["attempt"] = final.get("attempt")
        if rec["final"] == "COMPLETED":
            s, d, ms, err = request("GET", f"{base}/api/v1/jobs/{rec['jobId']}/asset", timeout=60)
            rec["asset"] = {"status": s, "bytes": len(d), "ms": round(ms, 1), "err": err}
    else:
        rec["final"] = "UNFINISHED"
    rec["doneAt"] = now_iso()
    writer.write(rec)


def probe_loop(base, writer):
    while not STOP.is_set():
        t = time.monotonic()
        status, data, ms, err = request("GET", f"{base}/api/v1/healthz", timeout=5)
        writer.write({"t": now_iso(), "code": status, "ms": round(ms, 1), "err": err,
                      "body": data[:120].decode("utf-8", "replace") if status == 200 else None})
        STOP.wait(max(0.0, 1.0 - (time.monotonic() - t)))


def cmd_run(a):
    os.makedirs(a.out, exist_ok=True)
    photo = open(a.photo, "rb").read()
    probe_w, flow_w = Writer(os.path.join(a.out, "probe.jsonl")), Writer(os.path.join(a.out, "flows.jsonl"))
    threading.Thread(target=probe_loop, args=(a.base, probe_w), daemon=True).start()
    workers, n, end = [], 0, time.monotonic() + a.duration
    print(f"{now_iso()} run {a.run} start, out {a.out}", flush=True)
    while not STOP.is_set() and time.monotonic() < end:
        if not a.no_jobs:
            n += 1
            rec = create_job(a.base, photo, a.run, n)
            rec["n"] = n
            print(f"{rec['postAt']} job {n} POST {rec['postStatus']} {rec['postCode'] or ''} {rec['jobId'] or ''}",
                  flush=True)
            if rec["jobId"]:
                th = threading.Thread(target=follow, args=(a.base, rec, flow_w, a.max_wait), daemon=True)
                th.start()
                workers.append(th)
            else:
                rec["final"] = "NOT_CREATED"
                flow_w.write(rec)
        STOP.wait(a.interval)
    print(f"{now_iso()} run {a.run} stops creating; waiting for {sum(t.is_alive() for t in workers)} flows", flush=True)
    for th in workers:
        th.join(timeout=a.max_wait)
    STOP.set()
    print(f"{now_iso()} run {a.run} done", flush=True)


def cmd_mark(a):
    os.makedirs(a.out, exist_ok=True)
    photo = open(a.photo, "rb").read()
    path = os.path.join(a.out, f"marked-{a.run}.txt")
    with open(path, "a", encoding="utf-8") as f:
        for n in range(1, a.count + 1):
            rec = create_job(a.base, photo, a.run, n)
            print(f"{rec['postAt']} {a.run} {n} POST {rec['postStatus']} {rec['jobId']}", flush=True)
            if rec["jobId"]:
                f.write(f"{rec['jobId']} {rec['postAt']}\n")


def cmd_check(a):
    for line in open(a.ids, encoding="utf-8"):
        job_id = line.split()[0]
        status, data, ms, err = request("GET", f"{a.base}/api/v1/jobs/{job_id}", timeout=10)
        st = None
        if status == 200:
            try:
                st = json.loads(data).get("status")
            except ValueError:
                st = "?"
        print(f"{now_iso()} {job_id} http {status} {st or ''}", flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    for name in ("run", "mark", "check"):
        s = sub.add_parser(name)
        s.add_argument("--base", required=True)
        if name != "check":
            s.add_argument("--photo", required=True)
            s.add_argument("--out", required=True)
            s.add_argument("--run", required=True)
        else:
            s.add_argument("--ids", required=True)
    r = sub.choices["run"]
    r.add_argument("--interval", type=float, default=15.0)
    r.add_argument("--duration", type=float, default=600.0)
    r.add_argument("--max-wait", type=float, default=900.0)
    r.add_argument("--no-jobs", action="store_true", help="probe only")
    sub.choices["mark"].add_argument("--count", type=int, default=3)
    a = p.parse_args()
    if getattr(a, "run", None) and any(w in a.run.lower() for w in ("fail", "slow")):
        p.error("run id must not contain 'fail' or 'slow'")
    signal.signal(signal.SIGTERM, lambda *_: STOP.set())
    {"run": cmd_run, "mark": cmd_mark, "check": cmd_check}[a.cmd](a)


if __name__ == "__main__":
    main()
