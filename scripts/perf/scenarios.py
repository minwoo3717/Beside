"""The scenarios of one suite (one backend version x one repetition) and the servers they run against.

Server A: profile mock, fresh storage, mock delays 0, every job serves the synthetic GLB. Scenarios 1-10 run on it in
order (startup ... resources); then A stops and its events.jsonl is read. Server B restarts on A's storage with
mock.worker.pending-ms=600000 for pending_poll. With an inference stub, servers C (profile real, or real,prod) run the
real_* scenarios against the stub in passthrough mode, each on fresh storage.

Every scenario returns {"status": "ok"|"skipped"|"error", "reason"?, "samples": {name: [ms]}, "values": {name: x},
"jobs": [jobId], "jobGroups": {group: [jobId]}, "notes": [str], "counts": {...}} and, after its server stopped,
"server": {group: aggregate of events.jsonl lines}. Warm-up requests are never recorded; jobs made by warm-ups,
seeding and setup are counted on the suite but belong to no scenario.
"""
from __future__ import annotations

import json
import math
import multiprocessing
import os
import re
import shutil
import subprocess
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

import httpbench
import procs
import stats

API = "/api/v1"
HEALTHZ = API + "/healthz"
KNOWN_STATUSES = ("PENDING", "PROCESSING", "COMPLETED", "FAILED")

MOCK_SCENARIOS = ["startup", "healthz", "upload_small", "upload_large", "upload_12mp", "e2e_mock", "concurrent_e2e",
                  "poll_load", "scale", "resources", "pending_poll"]
REAL_SCENARIOS = ["real_path", "real_upload", "real_upload_prod"]
ALL_SCENARIOS = MOCK_SCENARIOS + REAL_SCENARIOS

# Real scenarios: (profiles, inference.transfer, capabilities the version needs)
REAL_SETUP = {
    "real_path": ("real", "path", ("real_path",)),
    "real_upload": ("real", "upload", ("real_upload",)),
    "real_upload_prod": ("real,prod", "upload", ("real_upload", "prod")),
}

# Iteration counts; --quick divides each by 10 (at least 2).
COUNTS = {
    "healthz.warmup": 100, "healthz.n": 500,
    "upload_small.warmup": 10, "upload_small.n": 100,
    "upload_large.warmup": 2, "upload_large.n": 20,
    "upload_12mp.warmup": 2, "upload_12mp.n": 20, "upload_12mp.concurrentPerThread": 5,
    "e2e_mock.warmup": 5, "e2e_mock.n": 50,
    "concurrent_e2e.perThread": 10,
    "poll_load.warmupPerThread": 5, "poll_load.perThread": 250,
    "scale.warmup": 5, "scale.listFirst": 100, "scale.listDeep": 50, "scale.get": 200, "scale.post": 30,
    "pending_poll.jobs": 5, "pending_poll.warmup": 20, "pending_poll.n": 200,
    "real.warmup": 3, "real.n": 20, "real.batch": 5,
}
# Shape of the load: never divided by --quick.
SHAPE = {
    "upload_large.files": 4, "upload_12mp.threads": 4, "concurrent_e2e.threads": 16,
    "poll_load.processes": 4, "poll_load.threads": 4, "scale.seedThreads": 8,
    "e2e_mock.pollMs": 10, "concurrent_e2e.pollMs": 50, "real.pollMs": 20,
    "scale.targets": [200, 1000, 2000], "scale.settleMs": 1000,
    "pending_poll.pendingMs": 600000, "startup.idleWaitMs": 3000,
}
QUICK_TARGETS = [50, 100, 200]
FLOW_TIMEOUT_S = {"mock": 60.0, "real": 180.0}


def effective_counts(quick: bool) -> dict:
    """COUNTS and SHAPE merged; with quick every COUNTS entry is divided by 10 (minimum 2) and N is 50/100/200."""
    counts = {k: (max(2, v // 10) if quick else v) for k, v in COUNTS.items()}
    counts.update({k: (list(v) if isinstance(v, list) else v) for k, v in SHAPE.items()})
    if quick:
        counts["scale.targets"] = list(QUICK_TARGETS)
    return counts


def new_result(counts: dict = None) -> dict:
    return {"status": "ok", "samples": {}, "values": {}, "jobs": [], "jobGroups": {}, "notes": [],
            "counts": counts or {}}


def skipped(reason: str) -> dict:
    result = new_result()
    result["status"] = "skipped"
    result["reason"] = reason
    return result


def _ms(ns_start: int, ns_end: int) -> float:
    return (ns_end - ns_start) / 1e6


def _r2(values):
    """Raw samples are stored rounded to 0.01 ms."""
    return [round(float(v), 2) for v in values]


def _r3(value):
    return None if value is None else round(float(value), 3)


# ----------------------------------------------------------------------------------------------- events.jsonl

_INSTANT = re.compile(r"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})?$")


def parse_instant_ms(text: str) -> float:
    """Epoch milliseconds of an ISO 8601 instant such as Java's Instant.toString() (nanosecond fractions kept)."""
    match = _INSTANT.match(text.strip())
    if not match:
        raise ValueError(f"not an ISO 8601 instant: {text!r}")
    year, month, day, hour, minute, second, fraction, zone = match.groups()
    moment = datetime(int(year), int(month), int(day), int(hour), int(minute), int(second), tzinfo=timezone.utc)
    ms = moment.timestamp() * 1000.0
    if fraction:
        ms += int(fraction) / 10 ** len(fraction) * 1000.0
    if zone and zone != "Z":
        sign = 1 if zone[0] == "+" else -1
        ms -= sign * (int(zone[1:3]) * 60 + int(zone[4:6])) * 60_000.0
    return ms


def parse_events(path):
    """(events, bad line count) from an events.jsonl file; a missing file gives ([], 0)."""
    events, bad = [], 0
    path = Path(path)
    if not path.is_file():
        return events, bad
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            event = json.loads(line)
        except ValueError:
            bad += 1
            continue
        if isinstance(event, dict) and event.get("jobId"):
            events.append(event)
        else:
            bad += 1
    return events, bad


SERVER_FIELDS = ("uploadMs", "queuedMs", "processingMs", "totalMs")
REAL_FIELDS = ("inferMs", "convertMs", "glbBytes")


def _number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def completed_runs(events, job_ids) -> list:
    """The measured run of each of these jobs: its COMPLETED line (the highest attempt if a retry completed), in file
    order. FAILED:* lines are left out as METRICS §1 asks for measurement tables: a FAILED:INTERNAL_ERROR left by a
    restart carries the time the server was down in queuedMs / processingMs / totalMs."""
    wanted = set(job_ids)
    best = {}
    for e in events:
        if e.get("jobId") not in wanted or e.get("result") != "COMPLETED":
            continue
        current = best.get(e["jobId"])
        attempt = e.get("attempt") if _number(e.get("attempt")) else 0
        if current is None or attempt >= (current.get("attempt") if _number(current.get("attempt")) else 0):
            best[e["jobId"]] = e
    return list(best.values())


def aggregate_events(events, job_ids) -> dict:
    """What the server wrote for these jobs: line count and count per result over every line; the raw METRICS §1
    values per field only from the measured runs (completed_runs), so failed or interrupted runs never enter the
    server-side samples. "measured" / "excluded" say how many lines were used / left out."""
    wanted = set(job_ids)
    lines = [e for e in events if e.get("jobId") in wanted]
    results = {}
    for e in lines:
        results[str(e.get("result"))] = results.get(str(e.get("result")), 0) + 1
    runs = completed_runs(lines, wanted)
    samples = {}
    for field in SERVER_FIELDS + REAL_FIELDS:
        values = [e[field] for e in runs if _number(e.get(field))]
        if values:
            samples[field] = values
    passthrough = sum(1 for e in runs if e.get("passthrough") is True)
    seen = {e.get("jobId") for e in lines}
    return {"n": len(lines), "jobs": len(wanted), "missing": len(wanted - seen), "results": results,
            "measured": len(runs), "excluded": len(lines) - len(runs), "passthrough": passthrough,
            "samples": samples}


def queue_gaps(events, job_ids):
    """Idle time of the inference slot between consecutive runs of these jobs, from events.jsonl.

    For each measured run: start = finishedAt - processingMs, queuedAt = finishedAt - totalMs (totalMs counts from
    acceptance). Sorted by start, gap = start_next - finishedAt_prev, kept only when the next job was already waiting
    when the previous one finished (queuedAt_next <= finishedAt_prev): otherwise the "gap" is how long the client
    took to send the next job, not the server handing over the slot. The server truncates to whole ms, which makes
    queuedAt an upper bound, so a kept pair was certainly waiting. Returns (gaps in ms, number of pairs left out).
    """
    runs = []
    for e in completed_runs(events, job_ids):
        if not (_number(e.get("processingMs")) and _number(e.get("totalMs")) and e.get("finishedAt")):
            continue
        finish = parse_instant_ms(e["finishedAt"])
        runs.append((finish - float(e["processingMs"]), finish, finish - float(e["totalMs"])))
    runs.sort()
    gaps, excluded = [], 0
    for (_start, finish, _queued), (next_start, _next_finish, next_queued) in zip(runs, runs[1:]):
        if next_queued <= finish:
            gaps.append(next_start - finish)
        else:
            excluded += 1
    return gaps, excluded


# ----------------------------------------------------------------------------------------------- servers


class Server:
    """A backend started from the version's jar."""

    def __init__(self, name, proc, port, storage, startup_ms):
        self.name = name
        self.proc = proc
        self.port = port
        self.storage = Path(storage)
        self.startup_ms = startup_ms
        self.ready_at = time.monotonic()  # when healthz first answered 200

    def client(self, timeout: float = 120.0) -> httpbench.HttpClient:
        return httpbench.HttpClient("127.0.0.1", self.port, timeout=timeout)


def storage_props(storage: Path) -> list:
    d = storage.resolve().as_posix()
    return [f"--storage.db.path={d}/db/beside", f"--storage.upload.dir={d}/uploads",
            f"--storage.events.path={d}/events.jsonl", f"--storage.result.dir={d}/results",
            f"--storage.tmp.dir={d}/tmp", f"--storage.thumb.dir={d}/thumbs", f"--storage.backup.dir={d}/backup",
            f"--storage.root.dir={d}"]


def mock_props(sample_glb: str, pending_ms: int = 0) -> list:
    return [f"--mock.worker.pending-ms={pending_ms}", "--mock.worker.processing-ms=0",
            "--mock.worker.fail-when-filename-contains=", f"--storage.result.sample={Path(sample_glb).as_posix()}"]


class SuiteContext:
    """Everything one suite needs: jar, JVM, pinning, fixtures, counts, run folder, and the jobs made so far."""

    def __init__(self, label, rep, jar, java, jvm_args, cpus, fixtures, counts, run_dir, log, keep_storage=False):
        self.label = label
        self.rep = rep
        self.jar = str(jar)
        self.java = java
        self.jvm_args = list(jvm_args)
        self.cpus = cpus
        self.fixtures = fixtures  # name -> {"path", "bytes", "sha256", "data"}
        self.counts = counts
        self.run_dir = Path(run_dir)
        self.log = log
        self.keep_storage = keep_storage
        self.env, self.env_removed = procs.clean_env()
        self.created = []  # job ids made on the current mock storage, in the order the 202s arrived
        self.lock = threading.Lock()
        self.servers = {}  # name -> facts about each server run (startup, stop method, log)
        self.used_ports = {8080}

    def track(self, job_id):
        with self.lock:
            self.created.append(job_id)

    def job_count(self) -> int:
        with self.lock:
            return len(self.created)

    def start_server(self, name: str, storage: Path, profiles: str, extra: list) -> Server:
        """Start the jar with the full storage property set; retry once on a port clash."""
        storage.mkdir(parents=True, exist_ok=True)
        last_error = None
        for attempt in range(2):
            port = procs.free_port(avoid=self.used_ports)
            self.used_ports.add(port)
            cmd = ([self.java] + self.jvm_args + ["-jar", self.jar, f"--server.port={port}",
                   f"--spring.profiles.active={profiles}", "--notify.webhook-url=", "--inference.token="]
                   + storage_props(storage) + list(extra))
            log_path = self.run_dir / "logs" / f"{name}{'-retry' if attempt else ''}.log"
            proc = procs.start(name, cmd, cwd=storage, log_path=log_path, env=self.env, cpus=self.cpus)
            try:
                startup_ms = procs.wait_http_ok(proc, port, HEALTHZ, timeout_s=120.0)
            except procs.ProcessError as error:
                procs.stop(proc, timeout_s=10)
                last_error = error
                if "already in use" in proc.log_text().lower():
                    continue
                raise
            self.servers[name] = {"port": port, "profiles": profiles, "pid": proc.pid, "pinnedCpus": proc.pinned,
                                  "inJob": proc.in_job, "startupMs": _r3(startup_ms), "log": str(log_path)}
            return Server(name, proc, port, storage, startup_ms)
        raise last_error

    def stop_server(self, server: Server, timeout_s: float = 30.0, note: str = None):
        method = procs.stop(server.proc, timeout_s=timeout_s)
        self.servers.setdefault(server.name, {})["stop"] = method
        if note:
            self.servers[server.name]["stopNote"] = note
        return method


# ----------------------------------------------------------------------------------------------- requests


def post_photos(client, body: bytes, content_type: str):
    """POST /api/v1/jobs. Returns (response, jobId or None). v1 answers {jobId}; {id} is accepted too."""
    answer = client.request("POST", API + "/jobs", body=body, headers=httpbench.upload_headers(content_type))
    job_id = None
    if answer.status == 202:
        try:
            data = answer.json() or {}
            job_id = data.get("jobId") or data.get("id")
        except ValueError:
            job_id = None
    return answer, job_id


def get_status(client, job_id: str):
    """GET /api/v1/jobs/{id}. Returns (response, status); an unknown status value becomes UNKNOWN."""
    answer = client.get(f"{API}/jobs/{job_id}")
    status = None
    if answer.status == 200:
        try:
            status = (answer.json() or {}).get("status")
        except ValueError:
            status = None
        status = status if status in KNOWN_STATUSES else "UNKNOWN"
    return answer, status


def run_flow(client, body: bytes, content_type: str, interval_s: float, asset_bytes, timeout_s: float) -> dict:
    """POST -> poll every interval_s until COMPLETED/FAILED -> GET asset; times from the start of the POST."""
    flow = {"ok": False, "jobId": None, "postMs": None, "pollMs": [], "assetMs": None, "completeMs": None,
            "flowMs": None, "polls": 0, "error": None}
    t0 = time.perf_counter_ns()
    answer, job_id = post_photos(client, body, content_type)
    flow["postMs"] = answer.ms
    if not job_id:
        flow["error"] = f"POST answered {answer.status}"
        return flow
    flow["jobId"] = job_id
    deadline = time.monotonic() + timeout_s
    while True:
        answer, status = get_status(client, job_id)
        flow["polls"] += 1
        flow["pollMs"].append(answer.ms)
        if answer.status != 200:
            flow["error"] = f"GET job answered {answer.status}"
            return flow
        if status == "COMPLETED":
            t_complete = time.perf_counter_ns()
            break
        if status == "FAILED":
            flow["error"] = "job FAILED: " + json.dumps((answer.json() or {}).get("error"))[:200]
            return flow
        if time.monotonic() > deadline:
            flow["error"] = f"no COMPLETED within {timeout_s:.0f} s (last status {status})"
            return flow
        time.sleep(interval_s)
    flow["completeMs"] = _ms(t0, t_complete)
    asset = client.get(f"{API}/jobs/{job_id}/asset")
    t_end = time.perf_counter_ns()
    flow["assetMs"] = asset.ms
    if asset.status != 200:
        flow["error"] = f"GET asset answered {asset.status}"
        return flow
    if asset_bytes is not None and len(asset.body) != asset_bytes:
        flow["error"] = f"asset has {len(asset.body)} bytes, expected {asset_bytes}"
        return flow
    flow["flowMs"] = _ms(t0, t_end)
    flow["ok"] = True
    return flow


def wait_terminal(client, job_ids, timeout_s: float = 120.0, interval_s: float = 0.05) -> dict:
    """Poll until every job is COMPLETED or FAILED; returns {jobId: final status} (None for timeouts)."""
    pending = list(dict.fromkeys(job_ids))
    final = {}
    deadline = time.monotonic() + timeout_s
    while pending:
        still = []
        for job_id in pending:
            answer, status = get_status(client, job_id)
            if status in ("COMPLETED", "FAILED"):
                final[job_id] = status
            else:
                still.append(job_id)
        pending = still
        if pending:
            if time.monotonic() > deadline:
                for job_id in pending:
                    final[job_id] = None
                break
            time.sleep(interval_s)
    return final


def _photo(ctx, name: str):
    fixture = ctx.fixtures[name]
    return Path(fixture["path"]).name, fixture["data"]


def small_upload(ctx):
    filename, data = _photo(ctx, "png_small")
    return httpbench.multipart([("perf-small.png", data, "image/png")])


def _run_threads(count: int, target, timeout_s: float = 900.0):
    """Run target(i, barrier) on `count` threads released together; returns (per-thread results, errors, wall ms).

    timeout_s bounds the whole group (one shared deadline), not each join: with a stalled server, 16 stuck threads
    must not hold the suite for 16 x timeout_s before "did not finish in time" is raised.
    """
    barrier = threading.Barrier(count + 1)
    results = [None] * count
    errors = [None] * count

    def body(i):
        try:
            results[i] = target(i, barrier)
        except BaseException as error:  # noqa: BLE001  reported to the caller
            errors[i] = f"{type(error).__name__}: {error}"
            try:
                barrier.abort()
            except threading.BrokenBarrierError:
                pass

    threads = [threading.Thread(target=body, args=(i,), daemon=True) for i in range(count)]
    for thread in threads:
        thread.start()
    try:
        barrier.wait(timeout=120)
    except threading.BrokenBarrierError:
        pass
    t0 = time.perf_counter_ns()
    deadline = time.monotonic() + timeout_s
    for thread in threads:
        thread.join(max(0.0, deadline - time.monotonic()))
    t1 = time.perf_counter_ns()
    if any(thread.is_alive() for thread in threads):
        raise RuntimeError("worker threads did not finish in time")
    return results, errors, _ms(t0, t1)


def _check_errors(result, errors: int, what: str, fail: bool = True):
    if errors:
        result["values"]["bench.errors"] = result["values"].get("bench.errors", 0) + errors
        result["notes"].append(f"{errors} {what}")
        if fail:
            result["status"] = "error"
            result.setdefault("reason", f"{errors} {what}")


# ----------------------------------------------------------------------------------------------- mock scenarios


def scenario_startup(ctx, server: Server, result: dict):
    result["values"]["bench.startupMs"] = _r3(server.startup_ms)
    result["values"]["bench.springStartedMs"] = procs.spring_started_ms(server.proc)
    idle_until = server.ready_at + ctx.counts["startup.idleWaitMs"] / 1000.0
    time.sleep(max(0.0, idle_until - time.monotonic()))  # RSS 3 s after the first healthz 200
    rss, _peak = procs.memory(server.proc.pid)
    result["values"]["bench.idleRssMB"] = _r3(rss / 1048576.0) if rss is not None else None
    if result["values"]["bench.springStartedMs"] is None:
        result["notes"].append("no 'Started ... in X seconds' line in the server log")


def scenario_healthz(ctx, server: Server, result: dict):
    c = ctx.counts
    errors = 0
    samples = []
    with server.client() as client:
        for _ in range(c["healthz.warmup"]):
            client.get(HEALTHZ)
        for _ in range(c["healthz.n"]):
            answer = client.get(HEALTHZ)
            if answer.status == 200:
                samples.append(answer.ms)
            else:
                errors += 1
    result["samples"]["healthzMs"] = _r2(samples)
    _check_errors(result, errors, "healthz answers other than 200")


def _sequential_posts(ctx, server, result, body, content_type, warmup, n, sample, group):
    errors = 0
    samples = []
    with server.client() as client:
        for _ in range(warmup):
            answer, job_id = post_photos(client, body, content_type)
            if job_id:
                ctx.track(job_id)
        for _ in range(n):
            answer, job_id = post_photos(client, body, content_type)
            if job_id:
                ctx.track(job_id)
                samples.append(answer.ms)
                result["jobGroups"].setdefault(group, []).append(job_id)
            else:
                errors += 1
    result["samples"][sample] = _r2(samples)
    return samples, errors


def scenario_upload_small(ctx, server: Server, result: dict):
    body, content_type = small_upload(ctx)
    c = ctx.counts
    _samples, errors = _sequential_posts(ctx, server, result, body, content_type, c["upload_small.warmup"],
                                         c["upload_small.n"], "postMs", "postMs")
    result["values"]["bench.requestBytes"] = len(body)
    _check_errors(result, errors, "POST answers other than 202")


def scenario_upload_large(ctx, server: Server, result: dict):
    _name, data = _photo(ctx, "png_large")
    files = [(f"perf-large-{i + 1}.png", data, "image/png") for i in range(ctx.counts["upload_large.files"])]
    body, content_type = httpbench.multipart(files)
    c = ctx.counts
    samples, errors = _sequential_posts(ctx, server, result, body, content_type, c["upload_large.warmup"],
                                        c["upload_large.n"], "postMs", "postMs")
    result["values"]["bench.requestBytes"] = len(body)
    if samples:
        result["values"]["bench.uploadMBps"] = _r3(len(body) / 1e6 / (stats.median(samples) / 1000.0))
    _check_errors(result, errors, "POST answers other than 202")


def scenario_upload_12mp(ctx, server: Server, result: dict):
    _name, data = _photo(ctx, "png_12mp")
    body, content_type = httpbench.multipart([("perf-12mp.png", data, "image/png")])
    c = ctx.counts
    _samples, errors = _sequential_posts(ctx, server, result, body, content_type, c["upload_12mp.warmup"],
                                         c["upload_12mp.n"], "postMs", "postMs")
    result["values"]["bench.requestBytes"] = len(body)

    def worker(_i, barrier):
        out = []
        with server.client() as client:
            client.get(HEALTHZ)  # opens the connection before the start line
            barrier.wait(timeout=120)
            for _ in range(c["upload_12mp.concurrentPerThread"]):
                answer, job_id = post_photos(client, body, content_type)
                out.append((answer.ms, job_id))
        return out

    per_thread, thread_errors, wall_ms = _run_threads(c["upload_12mp.threads"], worker)
    concurrent = []
    for out in per_thread:
        for ms, job_id in out or []:
            if job_id:
                ctx.track(job_id)
                concurrent.append(ms)
                result["jobGroups"].setdefault("postConcurrentMs", []).append(job_id)
            else:
                errors += 1
    errors += sum(1 for e in thread_errors if e)
    result["samples"]["postConcurrentMs"] = _r2(concurrent)
    result["values"]["bench.concurrentWallMs"] = _r3(wall_ms)
    for e in thread_errors:
        if e:
            result["notes"].append("thread: " + e)
    _check_errors(result, errors, "failed POSTs")


def _record_flows(ctx, result, flows, group="flowMs", poll_samples=False):
    errors = 0
    keys = ["flowMs", "completeMs", "assetMs"] + (["postMs", "pollMs"] if poll_samples else [])
    collected = {k: [] for k in keys}
    polls = []
    for flow in flows:
        if flow.get("jobId"):
            ctx.track(flow["jobId"])
        if not flow.get("ok"):
            errors += 1
            if flow.get("error") and len(result["notes"]) < 10:
                result["notes"].append(flow["error"])
            continue
        result["jobGroups"].setdefault(group, []).append(flow["jobId"])
        collected["flowMs"].append(flow["flowMs"])
        collected["completeMs"].append(flow["completeMs"])
        collected["assetMs"].append(flow["assetMs"])
        if poll_samples:
            collected["postMs"].append(flow["postMs"])
            collected["pollMs"].extend(flow["pollMs"])
        polls.append(flow["polls"])
    for key, values in collected.items():
        result["samples"][key] = _r2(values)
    return errors, polls


def scenario_e2e_mock(ctx, server: Server, result: dict):
    body, content_type = small_upload(ctx)
    c = ctx.counts
    interval = c["e2e_mock.pollMs"] / 1000.0
    glb = ctx.fixtures["glb_sample"]["bytes"]
    with server.client() as client:
        for _ in range(c["e2e_mock.warmup"]):
            flow = run_flow(client, body, content_type, interval, glb, FLOW_TIMEOUT_S["mock"])
            if flow["jobId"]:
                ctx.track(flow["jobId"])
        flows = [run_flow(client, body, content_type, interval, glb, FLOW_TIMEOUT_S["mock"])
                 for _ in range(c["e2e_mock.n"])]
    errors, polls = _record_flows(ctx, result, flows)
    if polls:
        result["values"]["bench.pollsPerJob"] = _r3(sum(polls) / len(polls))
    _check_errors(result, errors, "failed flows")


def scenario_concurrent_e2e(ctx, server: Server, result: dict):
    body, content_type = small_upload(ctx)
    c = ctx.counts
    interval = c["concurrent_e2e.pollMs"] / 1000.0
    glb = ctx.fixtures["glb_sample"]["bytes"]

    def worker(_i, barrier):
        with server.client() as client:
            client.get(HEALTHZ)
            barrier.wait(timeout=120)
            return [run_flow(client, body, content_type, interval, glb, FLOW_TIMEOUT_S["mock"])
                    for _ in range(c["concurrent_e2e.perThread"])]

    per_thread, thread_errors, wall_ms = _run_threads(c["concurrent_e2e.threads"], worker)
    flows = [flow for out in per_thread for flow in (out or [])]
    errors, polls = _record_flows(ctx, result, flows, poll_samples=True)
    errors += sum(c["concurrent_e2e.perThread"] for e in thread_errors if e)
    completed = sum(1 for flow in flows if flow.get("ok"))
    result["values"]["bench.jobsPerSec"] = _r3(completed / (wall_ms / 1000.0)) if wall_ms > 0 else None
    result["values"]["bench.wallMs"] = _r3(wall_ms)
    result["values"]["bench.errors"] = errors
    if polls:
        result["values"]["bench.pollsPerJob"] = _r3(sum(polls) / len(polls))
    for e in thread_errors:
        if e:
            result["notes"].append("thread: " + e)
    # Any failed flow marks the scenario (like every other scenario): the samples hold only the completed flows, so
    # a partial failure must show in the report rather than vanish into a clean-looking p50.
    if completed == 0:
        result["status"] = "error"
        result["reason"] = "no flow completed"
    elif errors:
        result["status"] = "error"
        result["reason"] = f"{errors} failed flows (bench.errors); samples are the {completed} completed flows"


def _poll_load_process(port, path, threads, per_thread, warmup, start_event, queue):
    """One load process of poll_load (module level so that multiprocessing's spawn can import it)."""
    ready = threading.Barrier(threads + 1)
    out = [None] * threads

    def work(i):
        client = httpbench.HttpClient("127.0.0.1", port, timeout=60)
        samples, errors = [], 0
        try:
            for _ in range(warmup):  # opens the keep-alive connection; not recorded
                client.get(path)
        except Exception:  # noqa: BLE001  counted below
            errors += 1
        try:
            ready.wait(timeout=120)
            start_event.wait(timeout=300)
        except threading.BrokenBarrierError:
            pass
        t_start = time.perf_counter_ns()
        try:
            for _ in range(per_thread):
                try:
                    answer = client.get(path)
                    if answer.status == 200:
                        samples.append(answer.ms)
                    else:
                        errors += 1
                except Exception:  # noqa: BLE001
                    errors += 1
        finally:
            client.close()
        out[i] = {"samples": samples, "errors": errors, "start": t_start, "end": time.perf_counter_ns()}

    workers = [threading.Thread(target=work, args=(i,), daemon=True) for i in range(threads)]
    for worker in workers:
        worker.start()
    try:
        ready.wait(timeout=120)
    except threading.BrokenBarrierError:
        pass
    queue.put(("ready", os.getpid()))
    for worker in workers:
        worker.join()
    queue.put(("done", out))


def scenario_poll_load(ctx, server: Server, result: dict):
    c = ctx.counts
    body, content_type = small_upload(ctx)
    with server.client() as client:
        _answer, job_id = post_photos(client, body, content_type)
        if not job_id:
            raise RuntimeError("could not create the job to poll")
        ctx.track(job_id)
        if wait_terminal(client, [job_id]).get(job_id) != "COMPLETED":
            raise RuntimeError("the job to poll did not complete")
    path = f"{API}/jobs/{job_id}"
    mp = multiprocessing.get_context("spawn")
    start_event = mp.Event()
    queue = mp.Queue()
    count = c["poll_load.processes"]
    workers = [mp.Process(target=_poll_load_process, daemon=True,
                          args=(server.port, path, c["poll_load.threads"], c["poll_load.perThread"],
                                c["poll_load.warmupPerThread"], start_event, queue)) for _ in range(count)]
    for worker in workers:
        worker.start()
    try:
        ready = 0
        while ready < count:
            kind, _payload = queue.get(timeout=180)
            ready += kind == "ready"
        start_event.set()
        outs, finished = [], 0
        while finished < count:
            kind, payload = queue.get(timeout=600)
            if kind == "done":
                finished += 1
                outs.extend(payload)  # one entry per thread of that process
    finally:
        start_event.set()
        for worker in workers:
            worker.join(timeout=30)
            if worker.is_alive():
                worker.kill()
    samples = [s for out in outs if out for s in out["samples"]]
    dead = sum(1 for out in outs if not out)  # a load thread that died before reporting lost all of its GETs
    errors = sum(out["errors"] for out in outs if out) + dead * c["poll_load.perThread"]
    starts = [out["start"] for out in outs if out]
    ends = [out["end"] for out in outs if out]
    wall_ms = _ms(min(starts), max(ends)) if starts else 0
    result["samples"]["pollMs"] = _r2(samples)
    result["values"]["bench.pollReqPerSec"] = _r3(len(samples) / (wall_ms / 1000.0)) if wall_ms > 0 else None
    result["values"]["bench.wallMs"] = _r3(wall_ms)
    result["values"]["bench.errors"] = errors
    result["jobGroups"]["pollMs"] = [job_id]
    if dead:
        result["notes"].append(f"{dead} load threads reported nothing (their GETs count as errors)")
    if not samples:
        result["status"] = "error"
        result["reason"] = "no GET succeeded"
    elif errors:  # samples hold only the successful GETs: a partial failure must show in the report
        result["status"] = "error"
        result["reason"] = f"{errors} failed GETs (bench.errors); samples are the {len(samples)} successful GETs"


def _seed(ctx, server, body, content_type, need: int, threads: int):
    """POST `need` more small jobs from `threads` threads (no polling). Returns (last job of each thread, errors)."""
    claimed = [0]
    lock = threading.Lock()

    def worker(_i, barrier):
        last, errors = None, 0
        with server.client() as client:
            barrier.wait(timeout=120)
            while True:
                with lock:
                    if claimed[0] >= need:
                        break
                    claimed[0] += 1
                job_id = None
                for _attempt in range(3):
                    _answer, job_id = post_photos(client, body, content_type)
                    if job_id:
                        break
                    errors += 1
                if job_id:
                    ctx.track(job_id)
                    last = job_id
        return last, errors

    outs, thread_errors, _wall = _run_threads(threads, worker)
    lasts = [out[0] for out in outs if out and out[0]]
    errors = sum(out[1] for out in outs if out) + sum(1 for e in thread_errors if e)
    return lasts, errors


def _list_page(client, limit: int, cursor=None):
    path = f"{API}/jobs?limit={limit}" + (f"&cursor={cursor}" if cursor else "")
    answer = client.get(path)
    if answer.status != 200:
        return answer, None, None
    data = answer.json() or {}
    return answer, data.get("items") or [], data.get("nextCursor")


def cursor_near_middle(client, total: int):
    """The server's own nextCursor after about total//2 jobs (pages of <=100), so opaque cursors work too."""
    target = total // 2
    seen, cursor = 0, None
    while seen < target:
        answer, items, next_cursor = _list_page(client, min(100, target - seen), cursor)
        if items is None or not items or not next_cursor:
            return cursor, seen
        seen += len(items)
        cursor = next_cursor
    return cursor, seen


def count_jobs(client) -> int:
    """Walk the whole list once (pages of 100) and count the jobs the server reports."""
    total, cursor = 0, None
    for _ in range(100000):
        answer, items, cursor = _list_page(client, 100, cursor)
        if items is None:
            raise RuntimeError(f"list answered {answer.status}")
        total += len(items)
        if not cursor:
            return total
    raise RuntimeError("list did not end")


def scenario_scale(ctx, server: Server, result: dict):
    c = ctx.counts
    body, content_type = small_upload(ctx)
    errors = 0
    with server.client() as client:
        for target in c["scale.targets"]:
            need = target - ctx.job_count()
            waits = []
            if need > 0:
                lasts, seed_errors = _seed(ctx, server, body, content_type, need, c["scale.seedThreads"])
                errors += seed_errors
                waits.extend(lasts)
            with ctx.lock:
                if ctx.created:
                    waits.append(ctx.created[-1])
            final = wait_terminal(client, waits, timeout_s=300.0)
            if any(status != "COMPLETED" for status in final.values()):
                result["notes"].append(f"N={target}: not every awaited job COMPLETED: {sorted(set(map(str, final.values())))}")
            time.sleep(c["scale.settleMs"] / 1000.0)
            actual = ctx.job_count()
            result["values"][f"bench.actualN@{target}"] = actual
            cursor, cursor_at = cursor_near_middle(client, actual)
            result["values"][f"bench.cursorAt@{target}"] = cursor_at

            def timed_gets(path, warmup, n, sample):
                nonlocal errors
                values = []
                for i in range(warmup + n):
                    answer = client.get(path(i) if callable(path) else path)
                    if answer.status != 200:
                        errors += 1
                    elif i >= warmup:
                        values.append(answer.ms)
                result["samples"][sample] = _r2(values)

            timed_gets(f"{API}/jobs?limit=20", c["scale.warmup"], c["scale.listFirst"], f"listFirstMs@{target}")
            if cursor:
                timed_gets(f"{API}/jobs?limit=100&cursor={cursor}", c["scale.warmup"], c["scale.listDeep"],
                           f"listDeepMs@{target}")
            else:
                result["notes"].append(f"N={target}: no cursor near the middle, listDeepMs not measured")
            with ctx.lock:
                known = list(ctx.created)
            step = max(1, len(known) // max(1, c["scale.get"]))
            picks = [known[(i * step) % len(known)] for i in range(c["scale.get"] + c["scale.warmup"])]
            timed_gets(lambda i: f"{API}/jobs/{picks[i]}", c["scale.warmup"], c["scale.get"], f"getMs@{target}")
            posts = []
            for _ in range(c["scale.post"]):
                answer, job_id = post_photos(client, body, content_type)
                if job_id:
                    ctx.track(job_id)
                    posts.append(answer.ms)
                    result["jobGroups"].setdefault(f"postMs@{target}", []).append(job_id)
                else:
                    errors += 1
            result["samples"][f"postMs@{target}"] = _r2(posts)
            ctx.log(f"scale N={actual}: list p50 {_p50(result['samples'][f'listFirstMs@{target}'])} ms")
        with ctx.lock:
            waits = ctx.created[-1:]
        wait_terminal(client, waits, timeout_s=120.0)
        total = count_jobs(client)
    result["values"]["bench.serverTotal"] = total
    result["values"]["bench.trackedTotal"] = ctx.job_count()
    if total != ctx.job_count():
        result["notes"].append(f"server lists {total} jobs, this run created {ctx.job_count()}")
    _check_errors(result, errors, "failed requests")


def _p50(values):
    return round(stats.median(values), 2) if values else None


def scenario_resources(ctx, server: Server, result: dict):
    rss, peak = procs.memory(server.proc.pid)
    result["values"]["bench.peakRssMB"] = _r3(peak / 1048576.0) if peak is not None else None
    result["values"]["bench.rssMB"] = _r3(rss / 1048576.0) if rss is not None else None
    result["values"]["bench.jarBytes"] = os.path.getsize(ctx.jar)
    if peak is None:
        result["notes"].append("this OS does not report a peak working set")


def scenario_pending_poll(ctx, server: Server, result: dict):
    """Runs on server B (pending-ms=600000): jobs stay PENDING."""
    c = ctx.counts
    result["values"]["bench.restartStartupMs"] = _r3(server.startup_ms)
    result["values"]["bench.restartSpringStartedMs"] = procs.spring_started_ms(server.proc)
    body, content_type = small_upload(ctx)
    errors, samples, not_pending = 0, [], 0
    with server.client() as client:
        jobs = []
        for _ in range(c["pending_poll.jobs"]):
            _answer, job_id = post_photos(client, body, content_type)
            if job_id:
                jobs.append(job_id)
            else:
                errors += 1
        if not jobs:
            raise RuntimeError("could not create PENDING jobs")
        target = jobs[-1]
        result["jobGroups"]["pendingPollMs"] = jobs
        for i in range(c["pending_poll.warmup"] + c["pending_poll.n"]):
            answer, status = get_status(client, target)
            if answer.status != 200:
                errors += 1
                continue
            if status != "PENDING":
                not_pending += 1
            if i >= c["pending_poll.warmup"]:
                samples.append(answer.ms)
    result["samples"]["pendingPollMs"] = _r2(samples)
    if not_pending:
        result["notes"].append(f"{not_pending} answers were not PENDING")
    _check_errors(result, errors, "failed requests")


MOCK_STEPS = [
    ("startup", scenario_startup), ("healthz", scenario_healthz), ("upload_small", scenario_upload_small),
    ("upload_large", scenario_upload_large), ("upload_12mp", scenario_upload_12mp), ("e2e_mock", scenario_e2e_mock),
    ("concurrent_e2e", scenario_concurrent_e2e), ("poll_load", scenario_poll_load), ("scale", scenario_scale),
    ("resources", scenario_resources),
]


def _run_step(ctx, name, fn, server, results, selected):
    if name not in selected:
        results[name] = skipped("not selected (--only)")
        return
    result = new_result()
    started = time.monotonic()
    try:
        fn(ctx, server, result)
    except Exception as error:  # noqa: BLE001  the suite goes on; the scenario is marked
        result["status"] = "error"
        result["reason"] = f"{type(error).__name__}: {error}"[:500]
    result["durationS"] = round(time.monotonic() - started, 3)
    for group in result["jobGroups"].values():
        result["jobs"].extend(group)
    results[name] = result
    ctx.log(f"{name}: {result['status']} ({result['durationS']:.1f} s)"
            + (f": {result.get('reason')}" if result["status"] != "ok" else ""))


def _attach_events(results, names, events):
    for name in names:
        result = results.get(name)
        if not result or result.get("status") == "skipped" or not result.get("jobGroups"):
            continue
        result["server"] = {group: aggregate_events(events, ids) for group, ids in result["jobGroups"].items()}


def _cleanup_storage(storage: Path, keep: bool):
    if keep:
        return
    # "storage" is v0's hard-coded relative storage/results (created in the working directory, which is this folder)
    for sub in ("uploads", "results", "tmp", "thumbs", "backup", "db", "storage"):
        shutil.rmtree(storage / sub, ignore_errors=True)


def run_mock(ctx, selected) -> dict:
    """Servers A and B. Returns {scenario: result}."""
    results = {}
    if not any(name in selected for name in MOCK_SCENARIOS):
        return {name: skipped("not selected (--only)") for name in MOCK_SCENARIOS}
    storage = ctx.run_dir / "A"
    glb = ctx.fixtures["glb_sample"]["path"]
    try:
        server = ctx.start_server("A-mock", storage, "mock", mock_props(glb))
    except Exception as error:  # noqa: BLE001  the real scenarios can still run
        reason = f"server A did not start: {type(error).__name__}: {error}"[:800]
        ctx.log(reason)
        for name in MOCK_SCENARIOS:
            results[name] = (skipped("not selected (--only)") if name not in selected
                             else {**new_result(), "status": "error", "reason": reason})
        _cleanup_storage(storage, ctx.keep_storage)
        return results
    try:
        for name, fn in MOCK_STEPS:
            if not server.proc.alive():
                results[name] = {**new_result(), "status": "error", "reason": "server A is not running"}
                continue
            _run_step(ctx, name, fn, server, results, selected)
    finally:
        ctx.stop_server(server)
    events, bad = parse_events(storage / "events.jsonl")
    _attach_events(results, [name for name, _ in MOCK_STEPS], events)
    ctx.servers["A-mock"]["events"] = {"lines": len(events), "badLines": bad}
    if "pending_poll" in selected:
        try:
            server_b = ctx.start_server("B-mock-pending", storage, "mock",
                                        mock_props(glb, pending_ms=ctx.counts["pending_poll.pendingMs"]))
        except Exception as error:  # noqa: BLE001
            results["pending_poll"] = {**new_result(), "status": "error", "reason": f"server B: {error}"[:500]}
        else:
            try:
                _run_step(ctx, "pending_poll", scenario_pending_poll, server_b, results, selected)
            finally:
                # Spring's task executor waits up to 30 s (spring.lifecycle.timeout-per-shutdown-phase) for the jobs
                # sleeping 600 s; nothing is measured at this stop and the storage is discarded, so do not wait.
                ctx.stop_server(server_b, timeout_s=5.0,
                                note="graceful wait capped at 5 s: PENDING jobs hold the task executor's 30 s shutdown")
    else:
        results["pending_poll"] = skipped("not selected (--only)")
    _cleanup_storage(storage, ctx.keep_storage)
    return results


# ----------------------------------------------------------------------------------------------- real scenarios


def stub_check(stub_python) -> str:
    """None when stub_python can import fastapi, uvicorn and python-multipart; otherwise the reason it cannot."""
    if not stub_python:
        return "no --stub-python"
    if not Path(stub_python).is_file():
        return f"--stub-python not found: {stub_python}"
    try:
        done = subprocess.run([str(stub_python), "-c", "import fastapi, uvicorn, multipart"], capture_output=True,
                              timeout=120)
    except (OSError, subprocess.SubprocessError) as error:
        return f"--stub-python failed: {error}"
    if done.returncode != 0:
        lines = done.stderr.decode("utf-8", errors="replace").strip().splitlines() or ["(no output)"]
        return "--stub-python cannot import fastapi/uvicorn/multipart: " + lines[-1][:200]
    return None


class Stub:
    def __init__(self, proc, port, work):
        self.proc = proc
        self.port = port
        self.work = work

    @property
    def base_url(self):
        return f"http://127.0.0.1:{self.port}"


def start_stub(ctx, stub_python: str, app_dir: Path) -> Stub:
    work = ctx.run_dir / "stub-work"
    work.mkdir(parents=True, exist_ok=True)
    # PYTHONDONTWRITEBYTECODE: the stub runs from the repo tree; leave no __pycache__ there
    env, _removed = procs.clean_env({"BESIDE_SAMPLE_GLB": str(Path(ctx.fixtures["glb_sample"]["path"])),
                                     "BESIDE_WORK_DIR": str(work), "PYTHONDONTWRITEBYTECODE": "1"})
    port = procs.free_port(avoid=ctx.used_ports)
    ctx.used_ports.add(port)
    cmd = [stub_python, "-m", "uvicorn", "app:app", "--host", "127.0.0.1", "--port", str(port)]
    proc = procs.start("stub", cmd, cwd=app_dir, log_path=ctx.run_dir / "logs" / "stub.log", env=env, cpus=None)
    try:
        startup_ms = procs.wait_http_ok(proc, port, "/healthz", timeout_s=90.0)
    except Exception:
        procs.stop(proc, timeout_s=10)
        raise
    ctx.servers["stub"] = {"port": port, "pid": proc.pid, "startupMs": _r3(startup_ms),
                           "log": str(ctx.run_dir / "logs" / "stub.log")}
    return Stub(proc, port, work)


def scenario_real(ctx, name: str, stub: Stub) -> dict:
    """One real_* scenario on its own server C; its storage is removed however the scenario ends."""
    storage = ctx.run_dir / f"C-{name}"
    try:
        return _scenario_real(ctx, name, stub, storage)
    finally:
        _cleanup_storage(storage, ctx.keep_storage)


def _scenario_real(ctx, name: str, stub: Stub, storage: Path) -> dict:
    profiles, transfer, _needs = REAL_SETUP[name]
    c = ctx.counts
    result = new_result()
    started = time.monotonic()
    _photo_name, data = _photo(ctx, "png_large")
    body, content_type = httpbench.multipart([("perf-real.png", data, "image/png")])
    glb = ctx.fixtures["glb_sample"]["bytes"]
    interval = c["real.pollMs"] / 1000.0
    server = ctx.start_server(f"C-{name}", storage, profiles,
                              [f"--inference.base-url={stub.base_url}", f"--inference.transfer={transfer}"])
    flows, batch = [], []
    try:
        result["values"]["bench.startupMs"] = _r3(server.startup_ms)
        with server.client(timeout=300) as client:
            failures_in_row = 0
            for index in range(c["real.warmup"] + c["real.n"]):
                flow = run_flow(client, body, content_type, interval, glb, FLOW_TIMEOUT_S["real"])
                if index >= c["real.warmup"]:
                    flows.append(flow)
                failures_in_row = 0 if flow["ok"] else failures_in_row + 1
                if not flow["ok"] and (not stub.proc.alive() or failures_in_row >= 3):
                    raise RuntimeError(("the stub exited" if not stub.proc.alive() else "3 flows failed in a row")
                                       + f"; last error: {flow['error']}")

        def submit(_i, barrier):
            with server.client(timeout=300) as client:
                client.get(HEALTHZ)
                barrier.wait(timeout=120)
                return post_photos(client, body, content_type)[1]

        ids, thread_errors, _wall = _run_threads(c["real.batch"], submit)
        batch = [job_id for job_id in ids if job_id]
        with server.client(timeout=300) as client:
            final = wait_terminal(client, batch, timeout_s=FLOW_TIMEOUT_S["real"] * 2, interval_s=interval)
        not_completed = sum(1 for s in final.values() if s != "COMPLETED")
        batch_errors = (c["real.batch"] - len(batch)) + not_completed
    finally:
        ctx.stop_server(server)
    errors = 0
    keys = {"flowMs": [], "completeMs": [], "assetMs": [], "postMs": []}
    flow_jobs = []
    for flow in flows:
        if not flow.get("ok"):
            errors += 1
            if flow.get("error") and len(result["notes"]) < 10:
                result["notes"].append(flow["error"])
            continue
        flow_jobs.append(flow["jobId"])
        for key in keys:
            keys[key].append(flow[key])
    for key, values in keys.items():
        result["samples"][key] = _r2(values)
    result["jobGroups"] = {"flowMs": flow_jobs, "queueGapMs": batch}
    result["jobs"] = flow_jobs + batch
    events, bad = parse_events(storage / "events.jsonl")
    flow_events = completed_runs(events, flow_jobs)
    for sample, field in (("serverQueuedMs", "queuedMs"), ("serverProcessingMs", "processingMs"),
                          ("serverTotalMs", "totalMs")):
        result["samples"][sample] = _r2([e[field] for e in flow_events if _number(e.get(field))])
    gaps, gaps_excluded = queue_gaps(events, batch)
    result["samples"]["queueGapMs"] = _r2(gaps)
    result["values"]["bench.queueGapExcluded"] = gaps_excluded
    if batch and not gaps:
        result["notes"].append("queueGapMs: no batch job was waiting when the one before it finished "
                               f"({gaps_excluded} pairs left out)")
    result["server"] = {group: aggregate_events(events, ids) for group, ids in result["jobGroups"].items()}
    ctx.servers[f"C-{name}"]["events"] = {"lines": len(events), "badLines": bad}
    passthrough = result["server"]["flowMs"]["passthrough"]
    if flow_jobs and passthrough != len(flow_events):
        result["notes"].append(f"only {passthrough} of {len(flow_events)} runs were stub passthrough")
    for e in thread_errors:
        if e:
            result["notes"].append("batch thread: " + e)
    _check_errors(result, errors + batch_errors, "failed flows or batch jobs")
    result["durationS"] = round(time.monotonic() - started, 3)
    return result


def run_real(ctx, selected, capabilities, stub_python, stub_reason, app_dir: Path) -> dict:
    """The real_* scenarios: one stub for the suite, one fresh server C per scenario."""
    results = {}
    todo = []
    for name in REAL_SCENARIOS:
        _profiles, _transfer, needs = REAL_SETUP[name]
        if name not in selected:
            results[name] = skipped("not selected (--only)")
        elif any(cap not in capabilities for cap in needs):
            results[name] = skipped("capability")
            results[name]["missing"] = [cap for cap in needs if cap not in capabilities]
        elif stub_reason:
            results[name] = skipped("stub unavailable: " + stub_reason)
            results[name]["reasonCode"] = "stub"
        else:
            todo.append(name)
    if not todo:
        return results
    try:
        stub = start_stub(ctx, stub_python, app_dir)
    except Exception as error:  # noqa: BLE001
        for name in todo:
            results[name] = {**new_result(), "status": "error", "reason": f"stub did not start: {error}"[:500]}
        return results
    try:
        for name in todo:
            try:
                results[name] = scenario_real(ctx, name, stub)
            except Exception as error:  # noqa: BLE001
                results[name] = {**new_result(), "status": "error", "reason": f"{type(error).__name__}: {error}"[:500]}
            if not stub.proc.alive():
                ctx.log("stub exited; remaining real scenarios fail")
            ctx.log(f"{name}: {results[name]['status']}"
                    + (f": {results[name].get('reason')}" if results[name]["status"] != "ok" else ""))
    finally:
        procs.stop(stub.proc, timeout_s=15)
        ctx.servers["stub"]["stop"] = stub.proc.stop_method
        if not ctx.keep_storage:
            shutil.rmtree(stub.work, ignore_errors=True)
    return results


def run_suite(ctx, selected, capabilities, stub_python, stub_reason, app_dir) -> dict:
    """All scenarios of one suite in the fixed order; never raises for a scenario failure."""
    scenarios = {}
    scenarios.update(run_mock(ctx, selected))
    scenarios.update(run_real(ctx, selected, capabilities, stub_python, stub_reason, app_dir))
    return {name: scenarios[name] for name in ALL_SCENARIOS if name in scenarios}
