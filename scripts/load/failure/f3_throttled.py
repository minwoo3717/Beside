"""F3 throttled upload: one valid photo POSTed at 10 kB/s while failure/f3_uploads.js polls
(docs/perf/load/TRAFFIC_MODEL.md §9, F3). k6 cannot throttle a request body, so this runs next to the k6 run. Stdlib only.

    newrun f3r1
    python scripts/load/failure/f3_throttled.py --base-url $BASE_URL --run-id $RUN_ID --k6-summary $RUN/k6-summary.json \\
        --k6-exit-file $RUN/k6-exit.txt --json-out $RUN/f3-throttled.json & F3T=$!
    k6 run -e RUN_ID=$RUN_ID -e SUMMARY_PATH=$RUN/k6-summary.json scripts/load/failure/f3_uploads.js; echo $? > $RUN/k6-exit.txt
    wait $F3T; echo "f3_throttled exit $?"      # expected: 202 in about 40 s, inside the polling window, exit 0

--k6-exit-file is where the shell writes k6's exit code once k6 has ended. With it this script stops waiting for the k6
summary as soon as k6 is gone (k6 refused to start, e.g. CASES matched nothing or RTT_BASE_MS is missing for a remote
BASE_URL: no summary will come), and sends no upload at all when k6 ended before the upload began; without it the
summary wait lasts up to --summary-wait-s (900 s). A file older than this script is ignored. The code goes to
polling.k6ExitCode (99: a k6 threshold failed, 107: k6 refused to start).

It waits --delay-s (20) so that k6's setup is done and its background poller runs, then sends the photo
(default scripts/load/out/fixtures/photo-400k.png, 0.4 MB) at --rate bytes/s (10,000), named
lt-<run-id>-f3throttle-1.<ext> with a new Idempotency-Key lt-<run-id>-f3throttle-<uuid> on every invocation (a rerun
with the same RUN_ID makes a new job instead of replaying the first one).

Expected result: 202 after about body / rate seconds (0.4 MB at 10 kB/s = about 40 s): a slow but valid upload is
accepted (Caddy and Tomcat set no limit on how long a body may take). Checks:
  accepted        HTTP 202 with a jobId
  throttled       it took at least 0.9 x body / rate (the body really went slowly; else nothing was tested)
  inTime          it took at most 1.5 x body / rate + 10 s
With --k6-summary (the SUMMARY_PATH of the k6 run) it then waits for that summary and also checks
  insidePolling   the upload started and ended inside the background poller's window (setup_data.readyAtMs to
                  readyAtMs + min(meta.loadPlannedS, meta.loadRanS)), so k6's poll p95 covers it
  pollHeld        every threshold on http_req_duration{endpoint:poll} passed, with samples
and, for a server whose disk this PC can read (a local server), after k6 has ended:
  noLeftovers     --uploads-dir (the server's storage/uploads): no stored file contains "_lt-<run-id>-f3bad-" (the
                  uploads f3_uploads.js expects the server to reject); --multipart-tmp (glob of Tomcat's multipart
                  temp dir, <java.io.tmpdir>/tomcat.<port>.*/work/Tomcat/localhost/ROOT): no upload_*.tmp file
For the VM (its disk is in a docker volume) the same count is a shell step in the backend container: see the header of
failure/f3_uploads.js, which also prints it with the run's RUN_ID at the end of the k6 run.
Exit code: 0 all checks passed, 1 a check failed, 2 the run could not start (bad arguments, photo missing, server down,
k6 ended before the upload).
"""
from __future__ import annotations

import argparse
import glob
import http.client
import json
import re
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
DEFAULT_PHOTO = HERE.parent / "out" / "fixtures" / "photo-400k.png"
CONTENT_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp"}
TAG = "f3throttle"
RUN_ID_RE = re.compile(r"^[A-Za-z0-9_.-]{1,40}$")  # lib/config.js validRunId
MIN_FACTOR, MAX_FACTOR, MAX_SLACK_S = 0.9, 1.5, 10.0
EXIT_AFTER_SUMMARY_S = 30.0  # how long to wait for k6's exit code once its summary is there


class SetupError(Exception):
    """The run cannot start; exit code 2."""


def iso(epoch_s: float | None) -> str | None:
    if epoch_s is None:
        return None
    return datetime.fromtimestamp(epoch_s, timezone.utc).isoformat(timespec="milliseconds")


def multipart(filename: str, data: bytes, ctype: str) -> tuple:
    boundary = "----beside-f3t-" + uuid.uuid4().hex
    head = (f'--{boundary}\r\nContent-Disposition: form-data; name="photos"; filename="{filename}"\r\n'
            f"Content-Type: {ctype}\r\n\r\n").encode("ascii")
    return head + data + f"\r\n--{boundary}--\r\n".encode("ascii"), f"multipart/form-data; boundary={boundary}"


def connect(base_url: str, timeout: float):
    parts = urlsplit(base_url.rstrip("/"))
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise SetupError(f"--base-url must be http(s)://host[:port], got {base_url!r}")
    cls = http.client.HTTPSConnection if parts.scheme == "https" else http.client.HTTPConnection
    return cls(parts.hostname, parts.port, timeout=timeout), parts.path


def throttled_post(base_url: str, body: bytes, ctype: str, key: str, rate: float, timeout: float) -> dict:
    """POST body to /api/v1/jobs, at most `rate` bytes per second; returns what happened."""
    conn, prefix = connect(base_url, timeout)
    chunk = max(256, int(rate // 20))
    try:
        try:
            conn.connect()
        except OSError as exc:
            raise SetupError(f"cannot connect to {base_url}: {exc}") from exc
        conn.putrequest("POST", prefix + "/api/v1/jobs", skip_accept_encoding=True)
        conn.putheader("Content-Type", ctype)
        conn.putheader("Content-Length", str(len(body)))
        conn.putheader("Idempotency-Key", key)
        conn.endheaders()
        started = time.time()
        t0 = time.monotonic()
        sent, send_error = 0, None
        try:
            while sent < len(body):
                wait = t0 + sent / rate - time.monotonic()
                if wait > 0:
                    time.sleep(wait)
                n = min(chunk, len(body) - sent)
                conn.send(body[sent:sent + n])
                sent += n
        except OSError as exc:  # the server answered (or dropped the connection) before the body ended
            send_error = f"{type(exc).__name__}: {exc}"
        status, doc = 0, None
        try:
            resp = conn.getresponse()
            status = resp.status
            raw = resp.read()
            try:
                doc = json.loads(raw.decode("utf-8")) if raw else None
            except ValueError:
                doc = None
        except (OSError, http.client.HTTPException) as exc:
            send_error = send_error or f"{type(exc).__name__}: {exc}"
        ended = time.time()
        seconds = time.monotonic() - t0
    finally:
        conn.close()
    doc = doc if isinstance(doc, dict) else {}
    error = doc.get("error") if isinstance(doc.get("error"), dict) else {}
    return {"status": status, "jobId": doc.get("jobId"), "errorCode": error.get("code"), "sentBytes": sent,
            "sendError": send_error, "startedAtS": started, "endedAtS": ended, "seconds": round(seconds, 3)}


def upload_checks(result: dict, expected: dict) -> dict:
    return {
        "accepted": result["status"] == expected["status"] and bool(result["jobId"]),
        "throttled": result["seconds"] >= expected["minS"],
        "inTime": result["seconds"] <= expected["maxS"],
    }


def k6_exit(path: Path | None, not_before: float) -> dict | None:
    """None without --k6-exit-file; else {"ended": the shell wrote k6's exit code to path after not_before, "code"}.
    The code is read from `echo $? > file` (bash) or `$LASTEXITCODE > file` (Windows PowerShell 5.1 writes UTF-16)."""
    if path is None:
        return None
    try:
        if path.stat().st_mtime < not_before:
            return {"ended": False, "code": None}  # an earlier run's file
        raw = path.read_bytes()
    except OSError:
        return {"ended": False, "code": None}
    text = raw.decode("utf-16", "replace") if raw[:2] in (b"\xff\xfe", b"\xfe\xff") else raw.decode("utf-8", "replace")
    found = re.search(r"-?\d+", text)
    return {"ended": True, "code": int(found.group()) if found else None}


def wait_for_k6_exit(path: Path, not_before: float, wait_s: float, every_s: float = 0.5) -> dict:
    """k6 exits right after it writes its summary: wait up to wait_s for the shell to write the exit code (99 means a
    k6 threshold failed), else report it unknown."""
    deadline = time.monotonic() + wait_s
    while True:
        info = k6_exit(path, not_before)
        if info["ended"] or time.monotonic() >= deadline:
            return info
        time.sleep(every_s)


def wait_before_upload(delay_s: float, exit_path: Path | None, not_before: float, every_s: float = 0.5) -> None:
    """Sleep delay_s; raise SetupError if k6 ends meanwhile (then it ran no poller, and an upload would only add a job)."""
    deadline = time.monotonic() + delay_s
    while True:
        ended = k6_exit(exit_path, not_before)
        if ended and ended["ended"]:
            raise SetupError(f"k6 ended (exit {ended['code']}) before the upload, so nothing was uploaded: see k6's output "
                             "(a refused start, e.g. CASES or RTT_BASE_MS), then run both again")
        left = deadline - time.monotonic()
        if left <= 0:
            return
        time.sleep(min(every_s, left))


def read_fresh_summary(path: Path, not_before: float, wait_s: float, every_s: float = 1.0,
                       exit_path: Path | None = None) -> dict | None:
    """The k6 summary at path, once it exists, was written after not_before and parses; None after wait_s, or as soon as
    exit_path says k6 has ended without one (k6 writes its summary before it exits, so none will come)."""
    deadline = time.monotonic() + wait_s
    while True:
        ended = k6_exit(exit_path, not_before)  # before the summary: a summary there at k6's exit is complete
        try:
            if path.stat().st_mtime >= not_before:
                return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            pass  # not there yet, or k6 is still writing it
        if (ended and ended["ended"]) or time.monotonic() >= deadline:
            return None
        time.sleep(every_s)


def polling_report(summary: dict | None, path: Path, result: dict, exit_info: dict | None = None) -> dict:
    out = {"summary": str(path), "found": summary is not None, "k6Ended": None, "k6ExitCode": None,
           "pollerStartedAt": None, "pollerEndedAt": None, "k6ThresholdsOk": None, "k6Aborted": None, "pollP95Ms": None,
           "pollSamples": 0, "insidePolling": False, "pollHeld": False, "note": None}
    if exit_info is not None:
        out["k6Ended"], out["k6ExitCode"] = exit_info["ended"], exit_info["code"]
    if summary is None:
        if exit_info and exit_info["ended"]:
            out["note"] = (f"k6 ended (exit {exit_info['code']}) without writing a summary after this script started: "
                           "k6 refused to start (see its output) or SUMMARY_PATH differs from --k6-summary")
        else:
            out["note"] = ("no k6 summary written after this script started (k6 not run, another SUMMARY_PATH, or still "
                           "running): start this script right before k6 run, with the same SUMMARY_PATH")
        return out
    meta, verdict = summary.get("meta") or {}, summary.get("verdict") or {}
    k6data = summary.get("k6") or {}
    out["k6ThresholdsOk"] = verdict.get("thresholdsOk")
    aborted = verdict.get("aborted")
    out["k6Aborted"] = aborted.get("by") if isinstance(aborted, dict) else None
    ready_ms = (k6data.get("setup_data") or {}).get("readyAtMs")
    lengths = [x for x in (meta.get("loadPlannedS"), meta.get("loadRanS")) if isinstance(x, (int, float))]
    if isinstance(ready_ms, (int, float)) and lengths:
        start, end = ready_ms / 1000.0, ready_ms / 1000.0 + min(lengths)
        out["pollerStartedAt"], out["pollerEndedAt"] = iso(start), iso(end)
        out["insidePolling"] = start <= result["startedAtS"] and result["endedAtS"] <= end
        if not out["insidePolling"]:
            out["note"] = "the upload was not entirely inside the poller's window: start it right before k6, raise F3_BG_S or lower --delay-s"
    else:
        out["note"] = "the summary has no setup_data.readyAtMs or load length (k6 setup failed?)"
    held = None
    for name, metric in (k6data.get("metrics") or {}).items():
        if not name.startswith("http_req_duration{") or "endpoint:poll" not in name:
            continue
        values, ths = metric.get("values") or {}, metric.get("thresholds") or {}
        out["pollP95Ms"] = values.get("p(95)")
        out["pollSamples"] = int(values.get("count") or 0)
        held = bool(ths) and all(t.get("ok") is not False for t in ths.values())
    out["pollHeld"] = bool(held) and out["pollSamples"] > 0
    return out


def leftover_report(run_id: str, uploads_dir: str | None, tmp_glob: str | None) -> dict:
    """Stored rejected uploads of this run and Tomcat multipart temp files; ok only when both are 0 and the paths
    exist (a wrong path must not read as "no leftovers")."""
    marker = f"_lt-{run_id}-f3bad-"
    out = {"rejectedMarker": marker, "uploadsDir": uploads_dir, "rejectedStored": None, "storedOfRun": None,
           "examples": [], "multipartTmp": tmp_glob, "multipartTmpDirs": None, "multipartTmpFiles": None, "notes": []}
    if uploads_dir and not Path(uploads_dir).is_dir():
        out["notes"].append(f"--uploads-dir {uploads_dir} is not a directory (the server's storage/uploads?)")
    elif uploads_dir:
        names = sorted(p.name for p in Path(uploads_dir).iterdir())
        bad = [n for n in names if marker in n]
        out["rejectedStored"] = len(bad)
        out["examples"] = bad[:10]
        out["storedOfRun"] = {tag: sum(1 for n in names if f"_lt-{run_id}-{tag}-" in n)
                              for tag in ("f3bad", "f3acc", "f3bg", TAG)}
    if tmp_glob:
        dirs = [Path(p) for p in glob.glob(tmp_glob) if Path(p).is_dir()]
        out["multipartTmpDirs"] = len(dirs)
        if dirs:
            out["multipartTmpFiles"] = sum(len(list(d.glob("upload_*.tmp"))) for d in dirs)
        else:
            out["notes"].append(f"--multipart-tmp {tmp_glob} matched no directory")
    out["ok"] = not out["notes"] and out["rejectedStored"] in (None, 0) and out["multipartTmpFiles"] in (None, 0)
    return out


def run(args) -> dict:
    photo = Path(args.photo)
    if not photo.is_file():
        raise SetupError(f"photo {photo} not found: run python scripts/load/make_fixtures.py first (or pass --photo)")
    ext = photo.suffix.lower() or ".png"
    filename = f"lt-{args.run_id}-{TAG}-1{ext}"
    key = f"lt-{args.run_id}-{TAG}-{uuid.uuid4().hex}"
    body, ctype = multipart(filename, photo.read_bytes(), CONTENT_TYPES.get(ext, "image/png"))
    planned = len(body) / args.rate
    expected = {"status": 202, "plannedS": round(planned, 3), "minS": round(planned * MIN_FACTOR, 3),
                "maxS": round(planned * MAX_FACTOR + MAX_SLACK_S, 3)}
    began = time.time()
    report = {"schema": "beside-load-f3-throttled/1", "baseUrl": args.base_url.rstrip("/"), "runId": args.run_id,
              "photo": str(photo), "filename": filename, "idempotencyKey": key, "rateBytesPerS": args.rate,
              "bodyBytes": len(body), "delayS": args.delay_s, "expected": expected, "startedAt": iso(began)}
    print(f"f3_throttled: in {args.delay_s:g} s, {len(body):,} B at {args.rate:,.0f} B/s (about {planned:.0f} s) "
          f"to {report['baseUrl']}/api/v1/jobs as {filename}", file=sys.stderr, flush=True)
    exit_path = Path(args.k6_exit_file) if args.k6_exit_file else None
    wait_before_upload(args.delay_s, exit_path, began - 1.0)
    result = throttled_post(args.base_url, body, ctype, key, args.rate, args.timeout)
    report["upload"] = {k: v for k, v in result.items() if k not in ("startedAtS", "endedAtS")}
    report["upload"]["startedAt"], report["upload"]["endedAt"] = iso(result["startedAtS"]), iso(result["endedAtS"])
    checks = upload_checks(result, expected)
    print(f"f3_throttled: HTTP {result['status']} after {result['seconds']} s (expected 202 in "
          f"{expected['minS']}-{expected['maxS']} s)", file=sys.stderr, flush=True)

    report["polling"] = None
    report["leftovers"] = None
    if args.k6_summary:
        path = Path(args.k6_summary)
        summary = read_fresh_summary(path, began - 1.0, args.summary_wait_s, exit_path=exit_path)
        exit_info = k6_exit(exit_path, began - 1.0)
        if summary is not None and exit_info is not None:
            exit_info = wait_for_k6_exit(exit_path, began - 1.0, EXIT_AFTER_SUMMARY_S)
        report["polling"] = polling_report(summary, path, result, exit_info)
        checks["insidePolling"] = report["polling"]["insidePolling"]
        checks["pollHeld"] = report["polling"]["pollHeld"]
        if (args.uploads_dir or args.multipart_tmp) and summary is not None:
            report["leftovers"] = leftover_report(args.run_id, args.uploads_dir, args.multipart_tmp)
            checks["noLeftovers"] = report["leftovers"]["ok"]
        elif args.uploads_dir or args.multipart_tmp:
            checks["noLeftovers"] = False  # without the summary, k6 may still be uploading: not counted
    report["checks"] = checks
    report["ok"] = all(checks.values())
    report["endedAt"] = iso(time.time())
    return report


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="F3: one photo uploaded at 10 kB/s while f3_uploads.js polls.")
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--run-id", required=True, help="the RUN_ID of the k6 run (photo name and key prefix lt-<run-id>-)")
    ap.add_argument("--photo", default=str(DEFAULT_PHOTO), help="a valid photo (default: the 0.4 MB fixture)")
    ap.add_argument("--rate", type=float, default=10000.0, help="upload bytes per second (10 kB/s)")
    ap.add_argument("--delay-s", type=float, default=20.0, help="wait before the upload, so k6's poller is running")
    ap.add_argument("--timeout", type=float, default=120.0, help="socket timeout per send/receive")
    ap.add_argument("--k6-summary", help="the k6 run's SUMMARY_PATH: wait for it and check the polling window")
    ap.add_argument("--k6-exit-file", help="where the shell writes k6's exit code after k6 run (echo $? > file): stop "
                                           "waiting once k6 has ended, and upload nothing if it ended first")
    ap.add_argument("--summary-wait-s", type=float, default=900.0,
                    help="at most this long to wait for the k6 summary (with --k6-exit-file, until k6 has ended)")
    ap.add_argument("--uploads-dir", help="local server's storage/uploads: count stored rejected uploads (needs --k6-summary)")
    ap.add_argument("--multipart-tmp", help="glob of Tomcat's multipart temp dir(s): count upload_*.tmp (needs --k6-summary)")
    ap.add_argument("--json-out", help="also write the report here")
    args = ap.parse_args(argv)
    if not RUN_ID_RE.match(args.run_id):
        ap.error("--run-id must be 1-40 characters of A-Z a-z 0-9 _ . -")
    if "fail" in args.run_id.lower():
        ap.error("--run-id must not contain 'fail' (the mock worker fails such uploads)")
    if args.rate <= 0 or args.delay_s < 0:
        ap.error("--rate must be > 0 and --delay-s >= 0")
    if (args.uploads_dir or args.multipart_tmp) and not args.k6_summary:
        ap.error("--uploads-dir / --multipart-tmp need --k6-summary (leftovers are counted after k6 has ended)")
    return args


def main(argv=None) -> int:
    args = parse_args(argv)
    try:
        report = run(args)
    except SetupError as exc:
        print(f"f3_throttled: cannot run: {exc}", file=sys.stderr)
        return 2
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.json_out:
        Path(args.json_out).write_text(text + "\n", encoding="utf-8")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")  # the paths may hold characters the console code page lacks
    print(text)
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
