"""Beside inference service — FastAPI stub (internal API, called only by the Spring real worker).

[구현됨] request/response schemas, GET /healthz, passthrough mode (BESIDE_SAMPLE_GLB) for wiring tests.
[계획]   AnimalLift model loading and inference, OBJ/PNG -> GLB conversion via tools/convert_asset.py (TODO below).

Run (GPU PC; Spring runs on the same host, so 127.0.0.1 is enough — scripts/run_real.ps1 does this):
    pip install -r requirements.txt
    uvicorn app:app --host 127.0.0.1 --port 8001
Passthrough (no model yet, returns a fixed sample GLB so Spring's real worker can be integrated first):
    BESIDE_SAMPLE_GLB=/path/to/sample.glb uvicorn app:app --host 127.0.0.1 --port 8001
"""
from __future__ import annotations

import os
import pathlib
import time
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

SAMPLE_GLB = os.environ.get("BESIDE_SAMPLE_GLB")  # passthrough mode when set
MODEL = None  # TODO(PLAN stage 3/4): AnimalLift model handle, loaded once at startup

app = FastAPI(
    title="Beside Inference Service (internal)",
    version="0.1.0",
    description="Internal-only. Contract: generation/inference_service/README.md. Not called by Unity.",
)


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


# --------------------------------------------------------------------------- endpoints


@app.get("/healthz", response_model=HealthResponse)
def healthz() -> HealthResponse:
    return HealthResponse(
        status="ok",
        device=_device(),
        modelLoaded=MODEL is not None,
        passthrough=bool(SAMPLE_GLB),
    )


@app.post("/infer", response_model=InferResponse)
def infer(request: InferRequest) -> InferResponse:
    for image in request.imagePaths:
        if not pathlib.Path(image).is_file():
            raise HTTPException(status_code=400, detail={"code": "INFERENCE_FAILED", "message": f"image not found: {image}"})

    if SAMPLE_GLB:
        started = time.perf_counter()
        sample = pathlib.Path(SAMPLE_GLB)
        if not sample.is_file():
            raise HTTPException(status_code=500, detail={"code": "CONVERSION_FAILED", "message": f"BESIDE_SAMPLE_GLB not found: {SAMPLE_GLB}"})
        return InferResponse(
            glbPath=str(sample.resolve()),
            hairGlbPath=None,
            metrics=InferMetrics(inferMs=int((time.perf_counter() - started) * 1000), convertMs=0),
            passthrough=True,
        )

    # TODO(PLAN stage 3 -> 4):
    #   1. preprocess request.imagePaths (crop/segment the animal, resize) and run AnimalLift -> mesh.obj + uv.png (+ hair.npz)
    #      measure inferMs, gpuPeakMB (torch.cuda.max_memory_allocated), modelParams
    #   2. run tools/convert_asset.py -> {jobId}/base.glb (+ hair.glb) + base.metrics.json (vertices, triangles, textureSize, bytes, convertMs)
    #      enforce docs/asset/GLB_SPEC.md; on violation raise 500 with code CONVERSION_FAILED
    #   3. return InferResponse(glbPath=..., hairGlbPath=..., metrics=..., modelVersion="animallift-baseline@<commit>")
    raise HTTPException(
        status_code=501,
        detail={
            "code": "INFERENCE_UNAVAILABLE",
            "message": "AnimalLift inference is not implemented yet (TODO). Set BESIDE_SAMPLE_GLB for passthrough mode.",
        },
    )
