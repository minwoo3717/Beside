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

Run (GPU PC; Spring runs on the same host, so 127.0.0.1 is enough — scripts/run_real.ps1 does this):
    pip install -r requirements.txt
    uvicorn app:app --host 127.0.0.1 --port 8001
Passthrough (no model yet, returns a fixed sample GLB so Spring's real worker can be integrated first):
    BESIDE_SAMPLE_GLB=/path/to/sample.glb uvicorn app:app --host 127.0.0.1 --port 8001

Two transfer modes (README '전송 모드', docs/CONTRACT.md 6.1):
  path   POST /infer with absolute paths (Spring on the same PC or a shared volume).
  upload POST /infer/upload with the photo files, GET /artifacts/{jobId}/base.glb, DELETE /artifacts/{jobId}
         (Spring on a cloud VM, this service on the GPU PC over Tailscale: no shared disk).
  BESIDE_INFERENCE_TOKEN=<secret>: /infer, /infer/upload and /artifacts/* then need the X-Inference-Token header
         (401 otherwise); /healthz stays open. Set it whenever the service listens beyond 127.0.0.1.
"""
from __future__ import annotations

import hmac
import json
import logging
import os
import pathlib
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
# (tools/rig_transfer.py). If the transfer fails the static GLB is delivered, the job does not fail.
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
INFERENCE_TOKEN = os.environ.get("BESIDE_INFERENCE_TOKEN") or None
JOB_ID = re.compile(r"^[A-Za-z0-9-]{1,64}$")   # upload mode: jobId names a folder under WORK_DIR
ARTIFACTS = frozenset({"base.glb"})             # files GET /artifacts/{jobId}/{name} may serve
MAX_PHOTOS = 10
MODEL = None             # AnimalLiftRunner, loaded once in the background at startup
MODEL_ERROR: Optional[str] = None
MODEL_LOCK = threading.Lock()  # one GPU job at a time (Spring also limits to inference.max-concurrency=1)

log = logging.getLogger("beside.inference")
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "tools"))   # convert_asset.py

app = FastAPI(
    title="Beside Inference Service (internal)",
    version="0.1.0",
    description="Internal-only. Contract: generation/inference_service/README.md. Not called by Unity.",
)


def _needs_token(path: str) -> bool:
    return path == "/infer" or path.startswith("/infer/") or path == "/artifacts" or path.startswith("/artifacts/")


@app.middleware("http")
async def _check_token(request: Request, call_next):
    """X-Inference-Token, checked before the body is read (an unauthenticated upload is never stored)."""
    if INFERENCE_TOKEN and _needs_token(request.url.path):
        given = request.headers.get("x-inference-token", "")
        if not hmac.compare_digest(given.encode("utf-8"), INFERENCE_TOKEN.encode("utf-8")):
            return JSONResponse(
                status_code=401,
                content={"detail": {"code": "INFERENCE_UNAVAILABLE", "message": "missing or wrong X-Inference-Token"}},
            )
    return await call_next(request)


def _load_model() -> None:
    global MODEL, MODEL_ERROR
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
        log.exception("model failed to load (%s)", RUNNER_SPEC)


@app.on_event("startup")
def _startup() -> None:
    if ANIMALLIFT_DIR and not SAMPLE_GLB:
        threading.Thread(target=_load_model, name="animallift-load", daemon=True).start()


# --------------------------------------------------------------------------- schemas (contract v0)


class InferOptions(BaseModel):
    hair: bool = Field(default=False, description="Also produce the hair variant (hair.npz -> {jobId}/hair.glb)")


class InferRequest(BaseModel):
    jobId: str = Field(..., description="Spring job id; used for output file names and logs")
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
    hairGlbPath: Optional[str] = Field(default=None, description="Absolute path of {jobId}/hair.glb when options.hair")
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


def _run(job_id: str, image_paths: List[str], options: InferOptions) -> _Result:
    """The body of /infer, shared with /infer/upload: local image paths in, the GLB on this machine out.
    Raises HTTPException with the contract's {code, message} detail (README error table)."""
    for image in image_paths:
        if not pathlib.Path(image).is_file():
            raise HTTPException(status_code=400, detail={"code": "INFERENCE_FAILED", "message": f"image not found: {image}"})

    if SAMPLE_GLB:
        started = time.perf_counter()
        sample = pathlib.Path(SAMPLE_GLB)
        if not sample.is_file():
            raise HTTPException(status_code=500, detail={"code": "CONVERSION_FAILED", "message": f"BESIDE_SAMPLE_GLB not found: {SAMPLE_GLB}"})
        return _Result(
            glb=sample.resolve(),
            metrics=InferMetrics(inferMs=int((time.perf_counter() - started) * 1000), convertMs=0),
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
        try:
            pred = MODEL.predict(image, str(raw_dir))
        except Exception as e:  # noqa: BLE001  OOM, unreadable image, model errors
            log.exception("job %s: inference failed", job_id)
            raise HTTPException(status_code=500, detail={"code": "INFERENCE_FAILED", "message": f"{type(e).__name__}: {e}"})

    glb = job_dir / "base.glb"
    try:
        m = convert(str(pred.mesh_obj), str(pred.uv_png), str(glb), str(job_dir / "base.metrics.json"),
                    hair_npz=str(pred.hair_npz) if pred.hair_npz else None, forward_axis=FORWARD_AXIS)
    except ConversionError as e:
        raise HTTPException(status_code=500, detail={"code": "CONVERSION_FAILED", "message": str(e)})
    except Exception as e:  # noqa: BLE001
        log.exception("job %s: conversion crashed", job_id)
        raise HTTPException(status_code=500, detail={"code": "CONVERSION_FAILED", "message": f"{type(e).__name__}: {e}"})

    rigged = False
    if RIG_TEMPLATE:
        from rig_transfer import RigError, transfer

        rig_started = time.perf_counter()
        tmp = job_dir / "base.rigged.glb"
        try:
            info = transfer(str(glb), str(pred.uv_png), RIG_TEMPLATE, str(tmp))
            os.replace(tmp, glb)
            m["bytes"] = info["bytes"]
            m["convertMs"] += int((time.perf_counter() - rig_started) * 1000)
            rigged = True
            log.warning("job %s: rigged (%d joints, clips %s)", job_id, info["joints"], ",".join(info["clips"]))
        except (RigError, Exception) as e:  # noqa: BLE001  static model is still a valid result
            log.warning("job %s: rig transfer skipped: %s", job_id, e)
            if tmp.exists():
                tmp.unlink()

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
def healthz() -> HealthResponse:
    return HealthResponse(
        status="ok",
        device=_device(),
        modelLoaded=MODEL is not None,  # false while loading or after a load error (see the service log)
        passthrough=bool(SAMPLE_GLB),
    )


@app.post("/infer", response_model=InferResponse)
def infer(request: InferRequest) -> InferResponse:
    """Path mode: Spring and this service share a filesystem."""
    request.imagePaths = [_to_local(p) for p in request.imagePaths]
    result = _run(request.jobId, request.imagePaths, request.options)
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

    result = _run(parsed.jobId, saved, parsed.options)
    artifact = job_dir / "base.glb"
    if result.glb != artifact.resolve():   # passthrough: serve a copy of the sample as this job's artifact
        shutil.copyfile(result.glb, artifact)
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
    """Removes {WORK_DIR}/{jobId} (uploaded photos, intermediate files, the GLB). Idempotent: 204 when already gone."""
    job_dir = _job_dir(job_id)
    if job_dir.exists():
        shutil.rmtree(job_dir)
        log.warning("job %s: work folder deleted", job_id)
    return Response(status_code=204)
