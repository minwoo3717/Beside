"""Beside inference service — FastAPI stub (internal API, called only by the Spring real worker).

[구현됨] request/response schemas, GET /healthz, passthrough mode (BESIDE_SAMPLE_GLB) for wiring tests,
         AnimalLift inference (animallift_runner.py) + OBJ/PNG -> GLB via tools/convert_asset.py.

Model mode (set BESIDE_ANIMALLIFT_DIR, run in the AnimalLift Python env):
    BESIDE_ANIMALLIFT_DIR=C:/beside/AnimalLift uvicorn app:app --host 127.0.0.1 --port 8001
  optional: BESIDE_ANIMALLIFT_CKPT (default <dir>/ckpt/latest.pt), BESIDE_DEVICE (cuda), BESIDE_NFE, BESIDE_CFG_SCALE,
            BESIDE_WORK_DIR (default ./work), BESIDE_VAE_LOCAL_PATH
  Spring on Windows, this service in WSL2 Ubuntu: BESIDE_PATH_MAP='C:\\=/mnt/c/' and BESIDE_WORK_DIR=/mnt/c/beside/work
  rigging: BESIDE_RIG_TEMPLATE=<rigged template .glb> -> skeleton + Idle/Walk/Sit/TailWag/HeadTilt on every result
  swapping the model (no Spring/Unity change):
            BESIDE_MODEL_DIR (alias of BESIDE_ANIMALLIFT_DIR), BESIDE_MODEL_NAME (modelVersion label, default animallift-baseline),
            BESIDE_RUNNER (module:Class, default animallift_runner:AnimalLiftRunner), BESIDE_FORWARD_AXIS (+x for SMAL/AnimalLift)
  The model loads in the background at startup; /healthz modelLoaded turns true when ready, /infer answers 503 until then.
  A load that fails is not retried: /healthz loadError names it until the service is restarted.

Run (GPU PC; Spring runs on the same host, so 127.0.0.1 is enough — scripts/run_real.ps1 does this):
    pip install -r requirements.txt
    uvicorn app:app --host 127.0.0.1 --port 8001
Passthrough (no model yet, returns a fixed sample GLB so Spring's real worker can be integrated first):
    BESIDE_SAMPLE_GLB=/path/to/sample.glb uvicorn app:app --host 127.0.0.1 --port 8001
  load-test knobs (passthrough only, read per request, all unset = the instant answer above; README '패스스루 부하 설정'):
            BESIDE_PASSTHROUGH_DELAY_MS + _JITTER_MS (simulated generation time), _SLOTS (concurrent generations, default 1),
            _FAIL_RATE (0..1) / _FAIL_TOKEN (photo name substring) -> 500 INFERENCE_FAILED, _SLOW_TOKEN + _SLOW_MS

Two transfer modes (README '전송 모드', docs/CONTRACT.md 6.1):
  path   POST /infer with absolute paths (Spring on the same PC or a shared volume).
  upload POST /infer/upload with the photo files, GET /artifacts/{jobId}/base.glb, DELETE /artifacts/{jobId}
         (Spring on a cloud VM, this service on the GPU PC over Tailscale: no shared disk).
  BESIDE_INFERENCE_TOKEN=<secret>: /infer, /infer/upload and /artifacts/* then need the X-Inference-Token header
         (401 otherwise); /healthz stays open. Set it whenever the service listens beyond 127.0.0.1.
  GET /healthz also answers tokenOk (only when X-Inference-Token is sent: would /infer take it) and busyForMs (age
         of the oldest predict / simulated generation still running, 0 when idle) for Spring's monitor alerts, and
         loadError (why the model failed to load, one short line without paths; null while loading or loaded).
  DELETE /artifacts/{jobId} also cancels a run of that job still on this machine (Spring gave up on it): the run
         stops at its next step and removes whatever it wrote after the DELETE (README 'DELETE /artifacts/{jobId}').
  BESIDE_WORK_SWEEP_MINUTES=<N> (default unset = off): at startup and every min(N, 10) minutes, remove
         {WORK_DIR}/<jobId> folders untouched for N minutes that no run is using (leftovers of a killed service).
"""
from __future__ import annotations

import contextlib
import errno
import hmac
import itertools
import json
import logging
import os
import pathlib
import random
import re
import shutil
import sys
import threading
import time
from dataclasses import dataclass
from typing import List, Optional

from fastapi import FastAPI, File, Form, HTTPException, Request, Response, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field, ValidationError

SAMPLE_GLB = os.environ.get("BESIDE_SAMPLE_GLB")  # passthrough mode when set
ANIMALLIFT_DIR = os.environ.get("BESIDE_MODEL_DIR") or os.environ.get("BESIDE_ANIMALLIFT_DIR")  # model mode when set
# Swappable model: "module:Class" implementing the runner interface (see RUNNER_INTERFACE in animallift_runner.py).
RUNNER_SPEC = os.environ.get("BESIDE_RUNNER", "animallift_runner:AnimalLiftRunner")
MODEL_NAME = os.environ.get("BESIDE_MODEL_NAME", "animallift-baseline")      # goes into modelVersion / job_runs
FORWARD_AXIS = os.environ.get("BESIDE_FORWARD_AXIS", "+x")                   # head axis of the model's OBJ output
# Optional: rigged template GLB (SMAL-derived, keep out of git). When set, every result gets its skeleton + clips
# (tools/rig_transfer.py). If the transfer fails or the rigged GLB is over the GLB_SPEC §1 size limit, the static GLB
# is delivered, the job does not fail.
RIG_TEMPLATE = os.environ.get("BESIDE_RIG_TEMPLATE")

# Spring on Windows + this service in WSL2 Ubuntu on the same PC: translate paths both ways.
#   BESIDE_PATH_MAP="C:\\=/mnt/c/"   (several pairs separated by ';')
# imagePaths from Spring "C:\beside\...\x.jpg" -> "/mnt/c/beside/.../x.jpg"; glbPath back to "C:\...".
PATH_MAP = [tuple(pair.split("=", 1)) for pair in os.environ.get("BESIDE_PATH_MAP", "").split(";") if "=" in pair]


def _to_local(path: str) -> str:
    for win, nix in PATH_MAP:
        if path.lower().startswith(win.lower()):
            return nix + path[len(win):].replace("\\", "/")
    return path


def _to_remote(path: str) -> str:
    for win, nix in PATH_MAP:
        if path.startswith(nix):
            return win + path[len(nix):].replace("/", "\\")
    return path
WORK_DIR = pathlib.Path(os.environ.get("BESIDE_WORK_DIR", "work")).resolve()
# Shared secret for /infer, /infer/upload and /artifacts/* (header X-Inference-Token). Unset or empty: no check.
# Surrounding whitespace is dropped, as Spring does with inference.token, so a stray newline in an env file still matches.
INFERENCE_TOKEN = (os.environ.get("BESIDE_INFERENCE_TOKEN") or "").strip() or None
JOB_ID = re.compile(r"^[A-Za-z0-9-]{1,64}$")   # both modes: jobId names a folder under WORK_DIR
ARTIFACTS = frozenset({"base.glb"})             # files GET /artifacts/{jobId}/{name} may serve
MAX_PHOTOS = 10
MODEL = None             # AnimalLiftRunner, loaded once in the background at startup
MODEL_ERROR: Optional[str] = None       # a failed load, in full: /infer's 503 message (behind the token) and the log
MODEL_LOAD_ERROR: Optional[str] = None  # the same, short and without paths or the token: the open /healthz loadError
LOAD_ERROR_MAX = 200                    # characters of loadError
# Anything with a slash or backslash (POSIX, Windows, relative paths) up to a space or quote: where files are stays in
# the log, /healthz (open) says only what went wrong.
_PATHISH = re.compile(r"""(?:[A-Za-z]:|[\w.~-]+)?[\\/][^\s'"]*""")
MODEL_LOCK =threading.Lock()  # one GPU job at a time (Spring also limits to inference.max-concurrency=1)

log = logging.getLogger("beside.inference")
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "tools"))   # convert_asset.py

app = FastAPI(
    title="Beside Inference Service (internal)",
    version="0.1.0",
    description="Internal-only. Contract: generation/inference_service/README.md. Not called by Unity.",
)


def _needs_token(path: str) -> bool:
    return path == "/infer" or path.startswith("/infer/") or path == "/artifacts" or path.startswith("/artifacts/")


def _token_matches(given: str) -> bool:
    """True when /infer would take this X-Inference-Token value: no token configured (no check) or the same value
    (constant-time compare). The token check below and /healthz tokenOk both use it, so tokenOk predicts the 401."""
    if not INFERENCE_TOKEN:
        return True
    return hmac.compare_digest(given.encode("utf-8"), INFERENCE_TOKEN.encode("utf-8"))


@app.middleware("http")
async def _check_token(request: Request, call_next):
    """X-Inference-Token, checked before the body is read (an unauthenticated upload is never stored).

    The path is the one the router matches (scope["path"]), not request.url.path: that one is rebuilt from the Host
    header, so a Host such as "a?" or "a/b" would hide /artifacts/... from this check on older Starlette versions.
    """
    if INFERENCE_TOKEN and _needs_token(request.scope.get("path", "")):
        if not _token_matches(request.headers.get("x-inference-token", "")):
            return JSONResponse(
                status_code=401,
                content={"detail": {"code": "INFERENCE_UNAVAILABLE", "message": "missing or wrong X-Inference-Token"}},
            )
    return await call_next(request)


# --------------------------------------------------------------------------- per-request timing (load tests, opt-in)
# BESIDE_REQUEST_TIMING=1 writes one stderr line per HTTP request with the times this service can see. 2026-11-19
# load test round 3 (docs/perf/load/2026-11-19_vm-3rd.md section 4): with two jobs at once the response/GLB download
# after conversion grew from 1.9 s to 4.3~6.7 s; these lines say when this side answered and handed the bytes over,
# so the rest of Spring's time is the path (tailscaled, the tailnet, the VM). Off by default: no middleware, no output.
REQUEST_TIMING = (os.environ.get("BESIDE_REQUEST_TIMING") or "").strip() == "1"
timing_log = logging.getLogger("beside.timing")


class _RequestTiming:
    """Pure ASGI middleware, the outermost user middleware (inside Starlette's ServerErrorMiddleware), so it wraps the
    token check and sees the raw body chunks.

    One line per request: "timing <method> <path> <status> client=<ip>:<port> in=<request body bytes>
    body_ms=<last request body chunk received> start_ms=<response headers handed to the server> end_ms=<last response
    chunk handed to the server> gone_ms=<client disconnect seen> out=<response body bytes> err=<exception> at=<UTC
    arrival>". Times are ms from the arrival of the request (headers read); "-" when it did not happen: body_ms for a
    request without body bytes, start_ms/end_ms for an exception (status 500, sent by Starlette outside this layer).
    end_ms is when uvicorn took the last chunk, not when the bytes left the machine: on Linux a GLB of ~1.3 MB fits in
    the socket buffers (and on the Pod in the userspace tailscaled reading the loopback), so end_ms - start_ms is about
    0 there. The transfer time is Spring's: its receive end minus this line's arrival + end_ms. An answer finished after
    the client left is logged like a normal one (uvicorn drops it silently); uvicorn then writes no access-log line.
    On the Pod, client is 127.0.0.1:<port tailscaled opened>: the same port again means a reused tailnet connection."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        t0, wall = time.perf_counter(), time.time()
        rec = {"in": 0, "out": 0, "status": None, "body": None, "start": None, "end": None, "gone": None}

        async def timed_receive():
            message = await receive()
            if message["type"] == "http.request":
                chunk = len(message.get("body", b""))
                rec["in"] += chunk
                if chunk and not message.get("more_body", False) and rec["body"] is None:
                    rec["body"] = time.perf_counter()
            elif message["type"] == "http.disconnect" and rec["end"] is None and rec["gone"] is None:
                rec["gone"] = time.perf_counter()
            return message

        async def timed_send(message):
            if message["type"] == "http.response.start":
                rec["status"], rec["start"] = message["status"], time.perf_counter()
            elif message["type"] == "http.response.body":
                rec["out"] += len(message.get("body", b""))
            await send(message)
            if message["type"] == "http.response.body" and not message.get("more_body", False):
                rec["end"] = time.perf_counter()

        error = "-"
        try:
            await self.app(scope, timed_receive, timed_send)
        except BaseException as exc:   # logged, then raised unchanged (Starlette answers 500 outside this layer)
            error = type(exc).__name__
            raise
        finally:
            def ms(t):
                return "-" if t is None else f"{(t - t0) * 1000:.1f}"

            client = scope.get("client") or ("-", "-")
            status = rec["status"] or (500 if error != "-" else "-")
            at = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(wall)) + f".{int(wall % 1 * 1000):03d}Z"
            timing_log.info("timing %s %s %s client=%s:%s in=%d body_ms=%s start_ms=%s end_ms=%s gone_ms=%s out=%d "
                            "err=%s at=%s", scope.get("method", "-"), scope.get("path", "-"), status, client[0],
                            client[1], rec["in"], ms(rec["body"]), ms(rec["start"]), ms(rec["end"]), ms(rec["gone"]),
                            rec["out"], error, at)


if REQUEST_TIMING:
    _timing_handler = logging.StreamHandler(sys.stderr)
    _timing_handler.setFormatter(logging.Formatter("%(message)s"))
    timing_log.addHandler(_timing_handler)
    timing_log.setLevel(logging.INFO)
    timing_log.propagate = False
    app.add_middleware(_RequestTiming)   # added after _check_token, so it wraps it


def _short_load_error(e: BaseException) -> str:
    """/healthz loadError: '<ExceptionType>: <message>' on one line of at most LOAD_ERROR_MAX characters, every path
    replaced by <path> and the token by <token> (failure drill FD18: a wrong BESIDE_ANIMALLIFT_CKPT)."""
    try:
        message = str(e)
    except Exception:  # noqa: BLE001  a broken __str__ still leaves the type
        message = ""
    if INFERENCE_TOKEN:
        message = message.replace(INFERENCE_TOKEN, "<token>")
    message = " ".join(_PATHISH.sub("<path>", message).split())
    return (f"{type(e).__name__}: {message}" if message else type(e).__name__)[:LOAD_ERROR_MAX]


def _load_model() -> None:
    global MODEL, MODEL_ERROR, MODEL_LOAD_ERROR
    try:
        import importlib

        module_name, _, class_name = RUNNER_SPEC.partition(":")
        runner_cls = getattr(importlib.import_module(module_name), class_name)

        started = time.perf_counter()
        MODEL = runner_cls(
            ANIMALLIFT_DIR,
            model_name=MODEL_NAME,
            checkpoint=os.environ.get("BESIDE_ANIMALLIFT_CKPT") or None,
            device=os.environ.get("BESIDE_DEVICE", "cuda"),
            nfe=int(os.environ["BESIDE_NFE"]) if os.environ.get("BESIDE_NFE") else None,
            cfg_scale=float(os.environ["BESIDE_CFG_SCALE"]) if os.environ.get("BESIDE_CFG_SCALE") else None,
            vae_local_path=os.environ.get("BESIDE_VAE_LOCAL_PATH") or None,
        )
        log.warning("model loaded in %.1fs on %s (%s via %s)", time.perf_counter() - started, MODEL.device, MODEL.version, RUNNER_SPEC)
    except Exception as e:  # noqa: BLE001  keep serving /healthz so the problem is visible
        MODEL_ERROR = f"{type(e).__name__}: {e}"
        MODEL_LOAD_ERROR = _short_load_error(e)
        log.exception("model failed to load (%s)", RUNNER_SPEC)


@app.on_event("startup")
def _startup() -> None:
    if ANIMALLIFT_DIR and not SAMPLE_GLB:
        threading.Thread(target=_load_model, name="animallift-load", daemon=True).start()
    minutes = _sweep_minutes()
    if minutes > 0:
        threading.Thread(target=_sweep_loop, args=(minutes,), name="work-sweep", daemon=True).start()


# --------------------------------------------------------------------------- schemas (contract v0)


class InferOptions(BaseModel):
    hair: bool = Field(default=False, description="Hair variant ({jobId}/hair.glb) is [계획]: accepted but ignored today. "
                       "The hair_length/flow PNGs are written next to base.glb whenever the model writes hair.npz "
                       "(AnimalLift: every job), whatever this flag")


class InferRequest(BaseModel):
    # Same pattern as upload mode: _run builds WORK_DIR / jobId, and '../x' or an absolute id would leave WORK_DIR.
    jobId: str = Field(..., pattern=JOB_ID.pattern,
                       description="Spring job id ([A-Za-z0-9-]{1,64}); names {WORK_DIR}/{jobId}, used in logs")
    imagePaths: List[str] = Field(..., min_length=1, max_length=10, description="Absolute paths readable by this service")
    options: InferOptions = Field(default_factory=InferOptions)


class InferMetrics(BaseModel):
    """Names are fixed by docs/METRICS.md section 2."""

    inferMs: Optional[int] = None
    gpuPeakMB: Optional[int] = None
    modelParams: Optional[int] = None
    outputVertices: Optional[int] = None
    outputTriangles: Optional[int] = None
    convertMs: Optional[int] = None


class InferResponse(BaseModel):
    glbPath: str = Field(..., description="Absolute path of {jobId}/base.glb (same host as Spring, which copies it to storage/results/{jobId}/base.glb)")
    hairGlbPath: Optional[str] = Field(default=None, description="Always null today: the hair variant GLB "
                                       "({jobId}/hair.glb for options.hair) is [계획]")
    metrics: InferMetrics
    passthrough: bool = Field(default=False, description="True when the sample GLB was returned instead of a real result")
    modelVersion: Optional[str] = Field(
        default=None,
        description="Model/weights that produced the GLB, e.g. animallift-baseline@<commit>. Optional (contract v0 addition, "
        "2026-10-06); None in passthrough. Spring stores it per run (job_runs.model_version) to tell baseline from improved models.",
    )


class InferUploadRequest(BaseModel):
    """The `request` part of POST /infer/upload; the photos come as file parts."""

    jobId: str = Field(..., pattern=JOB_ID.pattern, description="Spring job id ([A-Za-z0-9-]{1,64}); names {WORK_DIR}/{jobId}")
    options: InferOptions = Field(default_factory=InferOptions)


class InferUploadResponse(InferResponse):
    glbUrl: str = Field(..., description="GLB path relative to this service, /artifacts/{jobId}/base.glb; Spring GETs it from its inference.base-url")


class HealthResponse(BaseModel):
    status: str
    device: str
    modelLoaded: bool
    passthrough: bool
    # Additions of 2026-11-20 (docs/CONTRACT.md section 6): Spring's InferenceHealthMonitor alerts on them.
    tokenOk: Optional[bool] = Field(
        default=None,
        description="Only when the request carries X-Inference-Token: whether /infer would take it (true also when "
        "the service checks no token). null without the header. /healthz itself never answers 401.",
    )
    busyForMs: int = Field(
        default=0,
        description="ms since the oldest model predict or passthrough simulated generation still running began; "
        "0 when none runs. Waiting for MODEL_LOCK or a passthrough slot does not count.",
    )
    # Addition of 2026-11-20 (failure drill FD18): tells a failed model load from one still loading.
    loadError: Optional[str] = Field(
        default=None,
        description="Why the model failed to load: exception type and a short one-line message, paths and the token "
        "left out (the full text is in the service log). null while it loads, once it is loaded, in passthrough and "
        "with no model configured. modelLoaded stays false and status ok, as before; the service does not retry.",
    )


# --------------------------------------------------------------------------- helpers


def _device() -> str:
    try:
        import torch  # type: ignore

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:  # torch not installed on this machine
        return "unknown"


def _safe_photo_name(index: int, filename: Optional[str]) -> str:
    """"00_dog.jpg": no directory part, ASCII letters/digits/._- only (image loaders choke on Korean paths)."""
    base = re.split(r"[\\/]", filename or "")[-1]
    base = re.sub(r"[^A-Za-z0-9._-]", "_", base).lstrip(".")[:100] or "photo"
    return f"{index:02d}_{base}"


def _job_dir(job_id: str) -> pathlib.Path:
    """{WORK_DIR}/{jobId} for an id that matches JOB_ID; 404 otherwise, so no path ever leaves WORK_DIR."""
    if not JOB_ID.match(job_id):
        raise HTTPException(status_code=404, detail="no such artifact")
    job_dir = WORK_DIR / job_id
    if job_dir.resolve().parent != WORK_DIR:
        raise HTTPException(status_code=404, detail="no such artifact")
    return job_dir


@dataclass
class _Result:
    """What one inference produced, before each endpoint shapes its answer."""

    glb: pathlib.Path           # the GLB on this machine (the sample itself in passthrough)
    metrics: InferMetrics
    passthrough: bool
    model_version: Optional[str]


def _inference_failed(message: str) -> HTTPException:
    """500 {code: INFERENCE_FAILED, message}: the model failure answer. The passthrough failure knobs reuse it so a
    load test sees exactly what Spring sees when the model fails (Spring records detail.code: README error table)."""
    return HTTPException(status_code=500, detail={"code": "INFERENCE_FAILED", "message": message})


# --------------------------------------------------------------------------- job attempts: DELETE cancels a run
# Spring sends DELETE /artifacts/{jobId} when it is done with a job, also when it gave up waiting (inference.timeout-ms)
# or lost the connection. The run it gave up on is still here: waiting for MODEL_LOCK, inside MODEL.predict (which
# cannot be interrupted) or converting. Before this, such a run converted anyway, and convert_asset's makedirs
# recreated the deleted folder (2026-11-19 F1b-real: 3 folders left on the Pod's volume, one DELETE answered 500).
# Now every POST registers an attempt of its jobId; DELETE marks it cancelled and a retry (a new POST of the same
# jobId) replaces it. A run checks between its steps that it is still the job's attempt and otherwise stops with the
# model failure answer (nobody reads it), and a cancelled run removes the job folder again when it ends.

_JOBS_GUARD = threading.Lock()        # guards _CURRENT, _CANCELLED and every job folder removal (DELETE, cleanup, sweep)
_CURRENT: dict = {}                   # jobId -> number of the attempt that may still write {WORK_DIR}/{jobId}
# jobId -> number of the attempt a DELETE took away while it ran (until it ends or a retry registers). Kept apart from
# "no entry in _CURRENT": that also happens when a newer attempt of the job finished, and that attempt's folder (its
# base.glb before Spring's GET, path mode's kept outputs) must survive an older attempt that ends later.
_CANCELLED: dict = {}
_ATTEMPT_NUMBERS = itertools.count(1)
_REMOVE_TRIES = 3                     # rmtree tries when a run still writes into the folder
_REMOVE_PAUSE_S = 0.05


@dataclass(frozen=True)
class _Attempt:
    """One POST /infer or POST /infer/upload of a job, registered by _job_attempt."""

    job_id: str
    number: int

    def state(self) -> str:
        """'live', 'cancelled' (DELETE /artifacts since, no retry since) or 'superseded' (a newer POST of the same
        jobId since, whether that attempt still runs or already ended)."""
        with _JOBS_GUARD:
            return self._state_locked()

    def _state_locked(self) -> str:
        if _CURRENT.get(self.job_id) == self.number:
            return "live"
        if _CANCELLED.get(self.job_id) == self.number:
            return "cancelled"
        return "superseded"


def _deleted_during_run(job_id: str, at: str) -> HTTPException:
    log.warning("job %s: work folder deleted during the run (DELETE /artifacts after the client gave up), stopping %s, "
                "answering INFERENCE_FAILED", job_id, at)
    return _inference_failed("job work folder deleted during the run (client gave up, DELETE /artifacts)")


def _stop_if_cancelled(attempt: Optional[_Attempt], at: str) -> None:
    """Raises the model failure answer when attempt is no longer its job's attempt, so the run writes nothing more.
    attempt None (tests calling _run directly): no check."""
    if attempt is None:
        return
    state = attempt.state()
    if state == "cancelled":
        raise _deleted_during_run(attempt.job_id, at)
    if state == "superseded":
        log.warning("job %s: a newer attempt of the same job started during the run, stopping %s, "
                    "answering INFERENCE_FAILED", attempt.job_id, at)
        raise _inference_failed("a newer attempt of the same job started during the run")


def _remove_job_dir(job_dir: pathlib.Path) -> bool:
    """rmtree that survives a run still writing into the folder (call with _JOBS_GUARD held). A file created while
    rmtree walks makes it fail with 'Directory not empty' (F1b-real, answered 500), so try again. True when the folder
    was there and is gone; raises the last error if it is still there after _REMOVE_TRIES tries."""
    if not job_dir.exists():
        return False
    error: Optional[OSError] = None
    for tries in range(1, _REMOVE_TRIES + 1):
        try:
            shutil.rmtree(job_dir)
        except OSError as e:   # ENOTEMPTY: a writer added a file; ENOENT: an entry vanished during the walk
            error = e
        if not job_dir.exists():
            return True
        if tries < _REMOVE_TRIES:
            time.sleep(_REMOVE_PAUSE_S)
    raise error or OSError(errno.ENOTEMPTY, "job folder came back after removal", str(job_dir))


@contextlib.contextmanager
def _job_attempt(job_id: str):
    """Registers a new attempt of job_id for the with block (it takes over from an older attempt of the same job).
    On exit a live attempt is forgotten; a cancelled one (DELETE and no retry since) removes the job folder again,
    because the steps it cannot interrupt (MODEL.predict, a conversion under way) may have written after the DELETE.
    A superseded one removes nothing: the folder is the newer attempt's, also when that attempt already ended."""
    with _JOBS_GUARD:
        attempt = _Attempt(job_id, next(_ATTEMPT_NUMBERS))
        _CURRENT[job_id] = attempt.number
        _CANCELLED.pop(job_id, None)   # the retry takes the folder over from a cancelled attempt
    try:
        yield attempt
    finally:
        with _JOBS_GUARD:
            state = attempt._state_locked()
            if state == "live":
                del _CURRENT[job_id]
            elif state == "cancelled":
                del _CANCELLED[job_id]
                try:
                    if _remove_job_dir(WORK_DIR / job_id):
                        log.warning("job %s: removed what the cancelled run wrote after DELETE /artifacts", job_id)
                except OSError:
                    log.exception("job %s: could not remove the work folder of a cancelled run", job_id)


def _sweep_minutes() -> float:
    """BESIDE_WORK_SWEEP_MINUTES as a number of minutes; unset, empty, unreadable or <= 0 = 0 (no sweep)."""
    raw = (os.environ.get("BESIDE_WORK_SWEEP_MINUTES") or "").strip()
    if not raw:
        return 0.0
    try:
        return max(0.0, float(raw))
    except ValueError:
        log.warning("BESIDE_WORK_SWEEP_MINUTES=%r is not a number, no sweep", raw)
        return 0.0


def _newest_mtime(folder: pathlib.Path) -> float:
    newest = folder.stat().st_mtime
    for root, dirs, files in os.walk(folder):
        for name in dirs + files:
            try:
                newest = max(newest, os.stat(os.path.join(root, name)).st_mtime)
            except OSError:   # removed meanwhile
                pass
    return newest


def _sweep_work_dir(max_age_s: float, now: Optional[float] = None) -> List[str]:
    """Removes the {WORK_DIR}/<jobId> folders whose newest entry is older than max_age_s and that no run is using.
    The backstop for folders nothing else removes: the service killed mid-run, a DELETE that never arrived."""
    now = time.time() if now is None else now
    removed: List[str] = []
    if not WORK_DIR.is_dir():
        return removed
    for job_dir in sorted(WORK_DIR.iterdir()):
        if job_dir.is_symlink() or not job_dir.is_dir() or not JOB_ID.match(job_dir.name):
            continue
        try:
            if now - _newest_mtime(job_dir) < max_age_s:
                continue
        except OSError:   # the folder itself went away
            continue
        with _JOBS_GUARD:
            if job_dir.name in _CURRENT:
                continue
            try:
                if _remove_job_dir(job_dir):
                    removed.append(job_dir.name)
            except OSError:
                log.exception("work sweep: could not remove %s", job_dir)
    if removed:
        log.warning("work sweep: removed %d job folder(s) untouched for %.0f min: %s",
                    len(removed), max_age_s / 60, ", ".join(removed))
    return removed


def _sweep_loop(minutes: float) -> None:
    log.warning("work sweep on: job folders untouched for %.0f min are removed", minutes)
    while True:
        try:
            _sweep_work_dir(minutes * 60)
        except Exception:  # noqa: BLE001  keep sweeping
            log.exception("work sweep failed")
        time.sleep(min(minutes, 10.0) * 60)


# --------------------------------------------------------------------------- busy clock: /healthz busyForMs
# FD13 (2026-11-20 failure drills): /healthz answers UP while a predict hangs, so Spring kept sending jobs that each
# timed out after inference.timeout-ms. Every MODEL.predict call and every passthrough simulated generation registers
# here for exactly as long as it runs (inside MODEL_LOCK / the slot, so waiting for them does not count), whatever
# its attempt's state: a predict Spring gave up on still holds the GPU. busyForMs is the age of the oldest one.

_BUSY_GUARD = threading.Lock()    # guards _BUSY
_BUSY: dict = {}                  # work number -> time.monotonic() when it began (insertion order = start order)
_BUSY_NUMBERS = itertools.count(1)


@contextlib.contextmanager
def _busy_work():
    """Registers one piece of GPU work (or its passthrough stand-in) for the with block, also when it raises."""
    with _BUSY_GUARD:
        number = next(_BUSY_NUMBERS)
        _BUSY[number] = time.monotonic()
    try:
        yield
    finally:
        with _BUSY_GUARD:
            del _BUSY[number]


def _busy_for_ms(now: Optional[float] = None) -> int:
    """ms since the oldest registered work began (now: a time.monotonic() value, for tests); 0 when none runs."""
    with _BUSY_GUARD:
        oldest = min(_BUSY.values(), default=None)
    if oldest is None:
        return 0
    now = time.monotonic() if now is None else now
    return max(0, int((now - oldest) * 1000))


# --------------------------------------------------------------------------- passthrough load-test knobs
# Passthrough only (BESIDE_SAMPLE_GLB). They turn the instant sample answer into a simulated GPU: a generation time,
# a bounded number of concurrent generations, failures and slow jobs (docs: README '패스스루 부하 설정').
# Read on every request, so a test or a restarted stub can change them; all unset = no sleep and no slot wait, the
# passthrough answers as fast as before (scripts/perf measures that path).


@dataclass
class _Knobs:
    delay_ms: int      # BESIDE_PASSTHROUGH_DELAY_MS: simulated generation time
    jitter_ms: int     # BESIDE_PASSTHROUGH_JITTER_MS: + uniform 0..jitter on top of delay
    slots: int         # BESIDE_PASSTHROUGH_SLOTS: concurrent simulated generations (1 = one GPU)
    fail_rate: float   # BESIDE_PASSTHROUGH_FAIL_RATE: chance 0..1 of INFERENCE_FAILED
    fail_token: str    # BESIDE_PASSTHROUGH_FAIL_TOKEN: first photo name contains it -> INFERENCE_FAILED
    slow_token: str    # BESIDE_PASSTHROUGH_SLOW_TOKEN: first photo name contains it -> sleep slow_ms instead
    slow_ms: int       # BESIDE_PASSTHROUGH_SLOW_MS

    @property
    def active(self) -> bool:
        """False when nothing would be simulated (SLOTS alone bounds nothing): the plain passthrough answer."""
        return bool(self.delay_ms or self.jitter_ms or self.fail_rate or self.fail_token
                    or (self.slow_token and self.slow_ms))


def _env_number(name: str, cast, default, low, high=None):
    """BESIDE_PASSTHROUGH_<name> as cast, clamped to [low, high]; unset or empty = default, unreadable = default + log."""
    raw = (os.environ.get("BESIDE_PASSTHROUGH_" + name) or "").strip()
    if not raw:
        return default
    try:
        value = cast(raw)
    except ValueError:
        log.warning("BESIDE_PASSTHROUGH_%s=%r is not a number, using %s", name, raw, default)
        return default
    value = max(low, value)
    return value if high is None else min(high, value)


def _passthrough_knobs() -> _Knobs:
    return _Knobs(
        delay_ms=_env_number("DELAY_MS", int, 0, 0),
        jitter_ms=_env_number("JITTER_MS", int, 0, 0),
        slots=_env_number("SLOTS", int, 1, 1),
        fail_rate=_env_number("FAIL_RATE", float, 0.0, 0.0, 1.0),
        fail_token=os.environ.get("BESIDE_PASSTHROUGH_FAIL_TOKEN") or "",
        slow_token=os.environ.get("BESIDE_PASSTHROUGH_SLOW_TOKEN") or "",
        slow_ms=_env_number("SLOW_MS", int, 0, 0),
    )


_SLOTS_GUARD = threading.Lock()
_SLOTS: Optional[threading.BoundedSemaphore] = None
_SLOTS_SIZE = 0


def _passthrough_slots(size: int) -> threading.BoundedSemaphore:
    """The semaphore that bounds simulated generations, made on first use and remade when SLOTS changes (a run still
    holding the old one releases the old one, so for a moment old + new may overlap; the knob is for tests)."""
    global _SLOTS, _SLOTS_SIZE
    with _SLOTS_GUARD:
        if _SLOTS is None or _SLOTS_SIZE != size:
            _SLOTS, _SLOTS_SIZE = threading.BoundedSemaphore(size), size
        return _SLOTS


def _sleep(seconds: float) -> None:
    """time.sleep behind a name the tests replace."""
    time.sleep(seconds)


def _simulate_generation(job_id: str, image_paths: List[str], attempt: Optional[_Attempt] = None) -> Optional[int]:
    """The knobs' simulated generation for one passthrough run. None when no knob is set (nothing happens), otherwise
    the generation time in ms (the time inside a slot, not the wait for one, as inferMs excludes MODEL_LOCK waits).
    Raises the model failure answer for FAIL_TOKEN / FAIL_RATE, after the generation time like a failing model, and
    for a job cancelled while it waited for a slot (no sleep, like a model run cancelled while waiting for MODEL_LOCK)."""
    knobs = _passthrough_knobs()
    if not knobs.active:
        return None
    name = re.split(r"[\\/]", image_paths[0])[-1]   # the first photo's file name (upload mode: 00_<sanitized name>)
    slow = bool(knobs.slow_token and knobs.slow_ms and knobs.slow_token in name)
    slots = _passthrough_slots(knobs.slots)
    asked = time.perf_counter()
    with slots:
        _stop_if_cancelled(attempt, "before the simulated generation")
        waited_ms = int((time.perf_counter() - asked) * 1000)
        if slow:
            sleep_ms = float(knobs.slow_ms)
        else:
            sleep_ms = knobs.delay_ms + (random.uniform(0, knobs.jitter_ms) if knobs.jitter_ms else 0)
        started = time.perf_counter()
        with _busy_work():   # /healthz busyForMs: the FD13 drill's stuck run is this sleep
            if sleep_ms > 0:
                _sleep(sleep_ms / 1000)
        if knobs.fail_token and knobs.fail_token in name:
            failure = "fail token"
        elif knobs.fail_rate and random.random() < knobs.fail_rate:
            failure = "fail rate"
        else:
            failure = None
        generation_ms = int((time.perf_counter() - started) * 1000)
    tags = [tag for tag in (failure, "slow token" if slow else None) if tag]
    outcome = ("failed" if failure else "ok") + (f" ({', '.join(tags)})" if tags else "")
    log.warning("job %s: passthrough sim %d ms (planned %.0f), slot wait %d ms of %d slot(s), outcome %s",
                job_id, generation_ms, sleep_ms, waited_ms, knobs.slots, outcome)
    if failure:
        raise _inference_failed(f"passthrough simulated failure (BESIDE_PASSTHROUGH_{failure.replace(' ', '_').upper()})")
    return generation_ms


def _run(job_id: str, image_paths: List[str], options: InferOptions, attempt: Optional[_Attempt] = None) -> _Result:
    """The body of /infer, shared with /infer/upload: local image paths in, the GLB on this machine out.
    Raises HTTPException with the contract's {code, message} detail (README error table). With an attempt (both
    endpoints), stops between steps once DELETE /artifacts or a retry took the job away from it (_stop_if_cancelled)."""
    _stop_if_cancelled(attempt, "before the run")
    for image in image_paths:
        if not pathlib.Path(image).is_file():
            raise HTTPException(status_code=400, detail={"code": "INFERENCE_FAILED", "message": f"image not found: {image}"})

    if SAMPLE_GLB:
        started = time.perf_counter()
        sample = pathlib.Path(SAMPLE_GLB)
        if not sample.is_file():
            raise HTTPException(status_code=500, detail={"code": "CONVERSION_FAILED", "message": f"BESIDE_SAMPLE_GLB not found: {SAMPLE_GLB}"})
        simulated_ms = _simulate_generation(job_id, image_paths, attempt)   # None unless a BESIDE_PASSTHROUGH_* knob is set
        return _Result(
            glb=sample.resolve(),
            metrics=InferMetrics(inferMs=simulated_ms if simulated_ms is not None else int((time.perf_counter() - started) * 1000),
                                 convertMs=0),
            passthrough=True,
            model_version=None,
        )

    if not ANIMALLIFT_DIR:
        raise HTTPException(
            status_code=501,
            detail={"code": "INFERENCE_UNAVAILABLE",
                    "message": "No model configured. Set BESIDE_ANIMALLIFT_DIR (model) or BESIDE_SAMPLE_GLB (passthrough)."},
        )
    if MODEL is None:
        msg = f"AnimalLift failed to load: {MODEL_ERROR}" if MODEL_ERROR else "AnimalLift is still loading"
        raise HTTPException(status_code=503, detail={"code": "INFERENCE_UNAVAILABLE", "message": msg})

    from convert_asset import ConversionError, convert

    job_dir = WORK_DIR / job_id
    raw_dir = job_dir / "raw"
    image = image_paths[0]   # AnimalLift is single-image; extra photos are ignored for now
    if len(image_paths) > 1:
        log.warning("job %s: %d photos, using the first one", job_id, len(image_paths))

    with MODEL_LOCK:
        _stop_if_cancelled(attempt, "before the model")   # waited here after its client gave up: no GPU time for it
        try:
            with _busy_work():   # /healthz busyForMs
                pred = MODEL.predict(image, str(raw_dir))
        except Exception as e:  # noqa: BLE001  OOM, unreadable image, model errors
            _stop_if_cancelled(attempt, "after a model error")   # e.g. its folder was deleted under it
            log.exception("job %s: inference failed", job_id)
            raise _inference_failed(f"{type(e).__name__}: {e}")

    _stop_if_cancelled(attempt, "before conversion")
    glb = job_dir / "base.glb"
    try:
        # make_dirs=False: a job folder that DELETE removed during the conversion is not recreated
        m = convert(str(pred.mesh_obj), str(pred.uv_png), str(glb), str(job_dir / "base.metrics.json"),
                    hair_npz=str(pred.hair_npz) if pred.hair_npz else None, forward_axis=FORWARD_AXIS, make_dirs=False)
    except ConversionError as e:
        _stop_if_cancelled(attempt, "after a conversion error")
        raise HTTPException(status_code=500, detail={"code": "CONVERSION_FAILED", "message": str(e)})
    except Exception as e:  # noqa: BLE001
        _stop_if_cancelled(attempt, "after a conversion error")
        log.exception("job %s: conversion crashed", job_id)
        raise HTTPException(status_code=500, detail={"code": "CONVERSION_FAILED", "message": f"{type(e).__name__}: {e}"})

    rigged = False
    if RIG_TEMPLATE:
        from convert_asset import MAX_GLB_BYTES
        from rig_transfer import RigError, transfer

        _stop_if_cancelled(attempt, "before rigging")
        rig_started = time.perf_counter()
        tmp = job_dir / "base.rigged.glb"
        try:
            info = transfer(str(glb), str(pred.uv_png), RIG_TEMPLATE, str(tmp))
            # the rigged file (template skin + clips + raw uv.png) never went through convert's size check
            if info["bytes"] > MAX_GLB_BYTES:
                raise RigError(f"rigged GLB {info['bytes']} bytes > limit {MAX_GLB_BYTES}")
            os.replace(tmp, glb)
            m["bytes"] = info["bytes"]
            m["convertMs"] += int((time.perf_counter() - rig_started) * 1000)
            rigged = True
            log.warning("job %s: rigged (%d joints, clips %s)", job_id, info["joints"], ",".join(info["clips"]))
        except (RigError, Exception) as e:  # noqa: BLE001  static model is still a valid result
            log.warning("job %s: rig transfer skipped: %s", job_id, e)
            if tmp.exists():
                tmp.unlink()
        if rigged:   # base.metrics.json describes the GLB served (GLB_SPEC §5); convert wrote the static one's
            try:
                with open(job_dir / "base.metrics.json", "w") as f:   # no makedirs: a DELETEd folder stays gone
                    json.dump(m, f)
            except OSError as e:   # folder removed meanwhile: _stop_if_cancelled below answers
                log.warning("job %s: could not rewrite base.metrics.json after rigging: %s", job_id, e)

    _stop_if_cancelled(attempt, "before answering")   # the GLB it would point to is gone or is the retry's
    log.warning("job %s: infer %d ms, convert %d ms, %d tris, %d bytes", job_id, pred.infer_ms, m["convertMs"], m["triangles"], m["bytes"])
    return _Result(
        glb=glb.resolve(),
        metrics=InferMetrics(inferMs=pred.infer_ms, gpuPeakMB=pred.gpu_peak_mb, modelParams=MODEL.model_params,
                             outputVertices=m["vertices"], outputTriangles=m["triangles"], convertMs=m["convertMs"]),
        passthrough=False,
        model_version=(MODEL.version + ("+rig" if rigged else ""))[:128],
    )


# --------------------------------------------------------------------------- endpoints


@app.get("/healthz", response_model=HealthResponse)
def healthz(request: Request) -> HealthResponse:
    """Open (no token needed) and always 200 while the service answers. X-Inference-Token, when sent, is only
    compared (tokenOk), never echoed or logged."""
    given = request.headers.get("x-inference-token")
    return HealthResponse(
        status="ok",
        device=_device(),
        modelLoaded=MODEL is not None,  # false while loading or after a load error (loadError, and the service log)
        passthrough=bool(SAMPLE_GLB),
        tokenOk=None if given is None else _token_matches(given),
        busyForMs=_busy_for_ms(),
        loadError=None if MODEL is not None else MODEL_LOAD_ERROR,
    )


@app.post("/infer", response_model=InferResponse)
def infer(request: InferRequest) -> InferResponse:
    """Path mode: Spring and this service share a filesystem."""
    request.imagePaths = [_to_local(p) for p in request.imagePaths]
    with _job_attempt(request.jobId) as attempt:
        result = _run(request.jobId, request.imagePaths, request.options, attempt)
    return InferResponse(
        # passthrough answers the sample's own path, a model result the path Spring can open (BESIDE_PATH_MAP)
        glbPath=str(result.glb) if result.passthrough else _to_remote(str(result.glb)),
        hairGlbPath=None,   # hair variant GLB is [계획]; hair_length/flow PNGs are written next to base.glb
        metrics=result.metrics,
        passthrough=result.passthrough,
        modelVersion=result.model_version,
    )


@app.post("/infer/upload", response_model=InferUploadResponse)
def infer_upload(request: str = Form(..., description="JSON {jobId, options}"),
                 photos: List[UploadFile] = File(..., description="1 to 10 photo files")) -> InferUploadResponse:
    """Upload mode: no shared disk. Photos go to {WORK_DIR}/{jobId}/input/, the GLB is served from /artifacts/."""
    try:
        parsed = InferUploadRequest.model_validate_json(request)
    except ValidationError as e:
        raise HTTPException(status_code=422, detail=json.loads(e.json(include_url=False)))
    if not 1 <= len(photos) <= MAX_PHOTOS:
        raise HTTPException(status_code=422, detail=[{"loc": ["body", "photos"], "msg": f"1 to {MAX_PHOTOS} photos, got {len(photos)}"}])

    job_dir = _job_dir(parsed.jobId)
    with _job_attempt(parsed.jobId) as attempt:   # registered before the photos land, so a DELETE from now on cancels
        input_dir = job_dir / "input"
        if input_dir.exists():   # a retry of the same job: only this attempt's photos
            shutil.rmtree(input_dir)
        input_dir.mkdir(parents=True)
        saved: List[str] = []
        for index, photo in enumerate(photos):
            target = input_dir / _safe_photo_name(index, photo.filename)
            with target.open("wb") as out:
                shutil.copyfileobj(photo.file, out)
            saved.append(str(target))

        result = _run(parsed.jobId, saved, parsed.options, attempt)
        artifact = job_dir / "base.glb"
        if result.glb != artifact.resolve():   # passthrough: serve a copy of the sample as this job's artifact
            # Spring gave up (inference.timeout-ms) and its cleanup DELETE /artifacts/{jobId} removed the folder while
            # this run still slept in its slot (README '패스스루 부하 설정', F1b): answer the contract's failure (nobody
            # reads it) instead of copying, and never recreate the folder.
            _stop_if_cancelled(attempt, "before copying the sample")
            try:
                shutil.copyfile(result.glb, artifact)
            except FileNotFoundError:
                if job_dir.is_dir():   # the folder is there, so something else is missing: not the case below
                    raise
                raise _deleted_during_run(parsed.jobId, "while copying the sample")   # DELETE right after the check
    log.warning("job %s: upload mode, %d photo(s) in, %s out%s", parsed.jobId, len(saved), artifact,
                " (passthrough sample)" if result.passthrough else "")
    return InferUploadResponse(
        glbPath=str(artifact.resolve()),
        glbUrl=f"/artifacts/{parsed.jobId}/base.glb",
        hairGlbPath=None,
        metrics=result.metrics,
        passthrough=result.passthrough,
        modelVersion=result.model_version,
    )


@app.get("/artifacts/{job_id}/{name}", response_class=FileResponse,
         responses={200: {"content": {"model/gltf-binary": {}}}, 404: {"description": "unknown job, file or name"}})
def get_artifact(job_id: str, name: str) -> FileResponse:
    """A result file of an upload mode job. Only names in ARTIFACTS, only inside {WORK_DIR}/{jobId}."""
    job_dir = _job_dir(job_id)
    path = job_dir / name
    if name not in ARTIFACTS or not path.is_file():
        raise HTTPException(status_code=404, detail="no such artifact")
    return FileResponse(path, media_type="model/gltf-binary", filename=name)


@app.delete("/artifacts/{job_id}", status_code=204, response_class=Response)
def delete_artifacts(job_id: str) -> Response:
    """Removes {WORK_DIR}/{jobId} (uploaded photos, intermediate files, the GLB). Idempotent: 204 when already gone.
    Also cancels a run of this job still on this machine (Spring gave up on it): it stops at its next step, answers
    INFERENCE_FAILED to nobody and removes what it wrote after this (_job_attempt). A retry of the job is a new attempt."""
    job_dir = _job_dir(job_id)
    with _JOBS_GUARD:   # a retry's POST registering now waits, so its new photos are not removed with the old folder
        number = _CURRENT.pop(job_id, None)
        running = number is not None
        if running:
            _CANCELLED[job_id] = number
        removed = _remove_job_dir(job_dir)
    if removed or running:
        log.warning("job %s: %s%s", job_id, "work folder deleted" if removed else "no work folder",
                    ", cancelled the run still in progress" if running else "")
    return Response(status_code=204)
