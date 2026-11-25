"""3D A/B evaluation, step 1 of 3 (runs on the GPU Pod, next to AnimalLift): load the model once, then predict and
convert every photo x input arm.

  python generation/tools/eval_arms.py --photos-dir <preprocessBatch output> --out <folder> --model-dir <AnimalLift> \
      [--checkpoint <ckpt>] [--arms off,keep,remove] [--seed 42] [--device cuda] [--no-warmup] [--force] \
      [--include-refused] [--list]

Input arms = files the backend tool preprocessBatch writes per photo (backend/README.md, tool/PreprocessBatch.java):
  off     model_in_before.png  the whole photo stretched to a square (= what the runner's Resize((ctrl, ctrl)) does)
  keep    crop_pad.png         crop around the animal + square pad, background kept
  remove  cutout.png           crop + background removed (RGBA, the runner composites it on white) = production input
Each prediction is written to <out>/<arm>/<photo>/ (mesh.obj, uv.png, hair.npz, predict.json) and converted twice with
convert_asset.convert, rigging off, fur maps embedded as in production:
  <out>/<arm>/<photo>/base.glb       padded_texture=False (production setting)
  <out>/<arm>+pad/<photo>/base.glb   padded_texture=True  (exp-04)
<out>/runs.csv has one row per (photo, arm, pad) with the fixed metric names (generation/CLAUDE.md); the two pad rows
of a photo/arm share one prediction, so they carry the same inferMs/gpuPeakMB. <out>/env.json records GPU, driver,
torch, model version, ctrl size and seed.

The runner re-seeds before every predict (seed 42), so one run per photo/arm is enough. A rerun skips predictions that
already have predict.json (resume after a cut-off Pod session); --force redoes them. Photos that report.csv marks as
refused by the photo check are skipped unless --include-refused (production would not have made a model for them).
Importable without torch: the model and convert_asset (trimesh) are imported only when they are used.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import subprocess
import sys
import time
import traceback
from dataclasses import dataclass
from pathlib import Path

ARM_INPUTS = {"off": "model_in_before.png", "keep": "crop_pad.png", "remove": "cutout.png"}
DEFAULT_ARMS = ("off", "keep", "remove")
BASELINE = "remove"            # production: background-removed cutout, pad-texture off, rigging off
PAD_SUFFIX = "+pad"
REFERENCE_PHOTO = "original.jpg"   # what the rater sees next to the model (eval_board)
FOREGROUND_CUTOUT = "cutout.png"   # photo foreground for furColorDeltaE (eval_metrics)

# first 11 = the names asked for in the plan (inferMs ... from generation/CLAUDE.md, glbBytes from METRICS), then extras
RUNS_COLUMNS = ["photo", "dog", "arm", "pad", "inferMs", "gpuPeakMB", "convertMs", "outputVertices", "outputTriangles",
                "textureSize", "glbBytes", "variant", "modelParams", "modelVersion", "input", "error"]

_FOLDER = re.compile(r"^(\d+)_(.+)$")   # preprocessBatch: "%02d_<safe stem>", numbered in sorted file order


@dataclass
class PhotoInput:
    name: str          # photo stem as preprocessBatch wrote it, e.g. coco_2
    dog: str           # coco
    folder: Path       # <photos-dir>/03_coco_2
    decision: str      # report.csv decision (PASS, IMAGE_TOO_BLURRY, ...) or "?" without report.csv


def photo_name(folder_name: str) -> str | None:
    """'03_coco_2' -> 'coco_2'; None for folders preprocessBatch did not make."""
    m = _FOLDER.match(folder_name)
    return m.group(2) if m else None


def dog_of(photo: str) -> str:
    """Photos are named <dog>_<n>: 'coco_2' -> 'coco'. A name without _<n> is its own dog ('dog-single-black')."""
    return re.sub(r"_\d+$", "", photo)


def variant_name(arm: str, pad: bool) -> str:
    return arm + PAD_SUFFIX if pad else arm


def split_variant(variant: str) -> tuple[str, bool]:
    """'remove+pad' -> ('remove', True); 'keep' -> ('keep', False)."""
    if variant.endswith(PAD_SUFFIX):
        return variant[:-len(PAD_SUFFIX)], True
    return variant, False


def read_decisions(photos_dir: Path) -> dict[int, str]:
    """report.csv rows are in the same order as the numbered folders: folder number n <-> row n."""
    report = Path(photos_dir) / "report.csv"
    if not report.is_file():
        return {}
    with open(report, newline="", encoding="utf-8-sig") as f:
        return {i + 1: (row.get("decision") or "?") for i, row in enumerate(csv.DictReader(f))}


def find_photos(photos_dir, include_refused: bool = False, log=print) -> list[PhotoInput]:
    """The photo folders of a preprocessBatch output, by folder number. Refused photos are left out (with a line in
    the log) unless include_refused."""
    root = Path(photos_dir)
    if not root.is_dir():
        raise SystemExit(f"--photos-dir is not a folder: {root}")
    decisions = read_decisions(root)
    photos, seen = [], {}
    for folder in sorted((p for p in root.iterdir() if p.is_dir()), key=lambda p: p.name):
        name = photo_name(folder.name)
        if name is None:
            continue
        decision = decisions.get(int(_FOLDER.match(folder.name).group(1)), "?")
        if decision not in ("PASS", "?") and not include_refused:
            log(f"skip {folder.name}: photo check {decision} (--include-refused to keep it)")
            continue
        if name in seen:
            raise SystemExit(f"two folders for photo {name}: {seen[name]} and {folder.name}")
        seen[name] = folder.name
        photos.append(PhotoInput(name, dog_of(name), folder, decision))
    return photos


def _read_json(path: Path) -> dict | None:
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def _write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)


def run_eval(photos: list[PhotoInput], arms, out, runner, convert_fn, *, warmup: bool = True, force: bool = False,
             hair: bool = True, forward_axis: str = "+x", log=print) -> list[dict]:
    """predict each photo x arm once, convert it with and without pad-texture; returns the runs.csv rows.
    runner: has predict(image_path, out_dir) -> object with mesh_obj, uv_png, hair_npz, infer_ms, gpu_peak_mb
    (animallift_runner.Prediction). convert_fn: convert_asset.convert. A failure is a row with `error`, not a stop."""
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    version = getattr(runner, "version", "")
    params = getattr(runner, "model_params", "")
    rows: list[dict] = []

    if warmup and photos and not _nothing_to_predict(photos, arms, out, force):
        first = next((p.folder / ARM_INPUTS[a] for p in photos for a in arms if (p.folder / ARM_INPUTS[a]).is_file()), None)
        if first is not None:   # the first CUDA call pays kernel set-up; keep it out of inferMs
            started = time.perf_counter()
            try:
                runner.predict(str(first), str(out / "_warmup"))
                log(f"warm-up {first.parent.name}/{first.name}: {int((time.perf_counter() - started) * 1000)} ms")
            except Exception as e:  # noqa: BLE001  the real runs will report it per photo
                log(f"warm-up failed: {type(e).__name__}: {e}")

    total = len(photos) * len(arms)
    done = 0
    for photo in photos:
        for arm in arms:
            done += 1
            src = photo.folder / ARM_INPUTS[arm]
            pred_dir = out / arm / photo.name
            base = {"photo": photo.name, "dog": photo.dog, "arm": arm, "modelParams": params, "modelVersion": version,
                    "input": f"{photo.folder.name}/{src.name}"}
            if not src.is_file():
                log(f"[{done}/{total}] {photo.name} {arm}: no {src.name} (preprocess fell back?), skipped")
                for pad in (False, True):
                    rows.append({**base, "pad": int(pad), "variant": variant_name(arm, pad), "error": f"missing {src.name}"})
                continue

            pred = _read_json(pred_dir / "predict.json")
            reused = bool(pred) and not force and (pred_dir / "mesh.obj").is_file() and (pred_dir / "uv.png").is_file()
            if not reused:
                try:
                    p = runner.predict(str(src), str(pred_dir))
                except Exception as e:  # noqa: BLE001  OOM, unreadable image: record and go on
                    log(f"[{done}/{total}] {photo.name} {arm}: predict failed: {type(e).__name__}: {e}")
                    traceback.print_exc()
                    for pad in (False, True):
                        rows.append({**base, "pad": int(pad), "variant": variant_name(arm, pad),
                                     "error": f"predict: {type(e).__name__}: {e}"})
                    continue
                pred = {"inferMs": p.infer_ms, "gpuPeakMB": p.gpu_peak_mb, "input": base["input"],
                        "meshObj": Path(p.mesh_obj).name, "uvPng": Path(p.uv_png).name,
                        "hairNpz": Path(p.hair_npz).name if p.hair_npz else None, "modelVersion": version}
                _write_json(pred_dir / "predict.json", pred)
            mesh_obj, uv_png = pred_dir / pred.get("meshObj", "mesh.obj"), pred_dir / pred.get("uvPng", "uv.png")
            hair_npz = pred_dir / pred["hairNpz"] if hair and pred.get("hairNpz") else None

            for pad in (False, True):
                variant = variant_name(arm, pad)
                vdir = out / variant / photo.name
                glb, metrics_path = vdir / "base.glb", vdir / "base.metrics.json"
                row = {**base, "pad": int(pad), "variant": variant, "inferMs": pred.get("inferMs"),
                       "gpuPeakMB": pred.get("gpuPeakMB")}
                m = _read_json(metrics_path) if reused and glb.is_file() else None
                if m is None:
                    vdir.mkdir(parents=True, exist_ok=True)
                    try:
                        m = convert_fn(str(mesh_obj), str(uv_png), str(glb), str(metrics_path),
                                       hair_npz=str(hair_npz) if hair_npz else None, forward_axis=forward_axis,
                                       padded_texture=pad)
                    except Exception as e:  # noqa: BLE001  ConversionError and anything else
                        log(f"[{done}/{total}] {photo.name} {variant}: convert failed: {type(e).__name__}: {e}")
                        rows.append({**row, "error": f"convert: {type(e).__name__}: {e}"})
                        continue
                row.update(convertMs=m.get("convertMs"), outputVertices=m.get("vertices"),
                           outputTriangles=m.get("triangles"), textureSize=m.get("textureSize"), glbBytes=m.get("bytes"))
                rows.append(row)
            log(f"[{done}/{total}] {photo.name} {arm}: inferMs {pred.get('inferMs')}"
                f"{' (reused)' if reused else ''}, glb {rows[-2].get('glbBytes')} / +pad {rows[-1].get('glbBytes')} B")
    return rows


def _nothing_to_predict(photos, arms, out: Path, force: bool) -> bool:
    """True when a rerun has nothing to predict (every predict.json is there): no warm-up needed then."""
    if force:
        return False
    return all((out / a / p.name / "predict.json").is_file() for p in photos for a in arms
               if (p.folder / ARM_INPUTS[a]).is_file())


def write_runs_csv(path, rows: list[dict]) -> None:
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=RUNS_COLUMNS, extrasaction="ignore")
        w.writeheader()
        for row in rows:
            w.writerow({k: ("" if row.get(k) is None else row.get(k)) for k in RUNS_COLUMNS})


def _cmd(args: list[str]) -> str:
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception:  # noqa: BLE001
        return ""


def environment(runner, args) -> dict:
    """What the experiment record's environment row needs (TEMPLATE.md)."""
    torch = getattr(runner, "torch", None)
    rt = getattr(runner, "rt", None)
    repo = Path(__file__).resolve().parents[2]
    return {
        "startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "gpu": _cmd(["nvidia-smi", "--query-gpu=name,driver_version,memory.total", "--format=csv,noheader"]),
        "torch": getattr(torch, "__version__", None),
        "cuda": getattr(getattr(torch, "version", None), "cuda", None),
        "device": str(getattr(runner, "device", "")),
        "modelVersion": getattr(runner, "version", None),
        "modelParams": getattr(runner, "model_params", None),
        "ctrlSize": getattr(rt, "ctrl_size", None),
        "nfe": getattr(rt, "val_nfe", None),
        "cfgScale": getattr(rt, "val_cfg_scale", None),
        "seed": args.seed,
        "arms": args.arms,
        "besideCommit": _cmd(["git", "-C", str(repo), "rev-parse", "--short", "HEAD"]),
        "python": sys.version.split()[0],
    }


def load_runner(model_dir: str, checkpoint: str | None, device: str, seed: int):
    """animallift_runner.AnimalLiftRunner, imported here so this module loads without torch."""
    service = Path(__file__).resolve().parents[1] / "inference_service"
    if str(service) not in sys.path:
        sys.path.insert(0, str(service))
    from animallift_runner import AnimalLiftRunner  # noqa: WPS433

    return AnimalLiftRunner(model_dir, checkpoint=checkpoint, device=device, seed=seed)


def load_convert():
    tools = Path(__file__).resolve().parent
    if str(tools) not in sys.path:
        sys.path.insert(0, str(tools))
    from convert_asset import convert  # noqa: WPS433  (imports trimesh)

    return convert


def parse_arms(text: str) -> list[str]:
    arms = [a.strip() for a in text.split(",") if a.strip()]
    bad = [a for a in arms if a not in ARM_INPUTS]
    if bad or not arms:
        raise SystemExit(f"--arms: unknown {bad}; choose from {sorted(ARM_INPUTS)}")
    return arms


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--photos-dir", required=True, help="preprocessBatch --out folder (NN_<photo>/ + report.csv)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--model-dir", help="AnimalLift checkout (assets/, ckpt/latest.pt); not needed with --list")
    ap.add_argument("--checkpoint", help="default <model-dir>/ckpt/latest.pt")
    ap.add_argument("--arms", default=",".join(DEFAULT_ARMS))
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--no-warmup", action="store_true", help="do not run one discarded predict before measuring")
    ap.add_argument("--force", action="store_true", help="predict again even where predict.json exists")
    ap.add_argument("--include-refused", action="store_true", help="also photos the photo check refused")
    ap.add_argument("--list", action="store_true", help="only print the photos and arms that would run")
    a = ap.parse_args(argv)
    arms = parse_arms(a.arms)
    photos = find_photos(a.photos_dir, a.include_refused)
    if not photos:
        raise SystemExit(f"no photo folders in {a.photos_dir}")
    dogs = sorted({p.dog for p in photos})
    print(f"{len(photos)} photos of {len(dogs)} dogs ({', '.join(dogs)}), arms {','.join(arms)} (+pad each)")
    for p in photos:
        missing = [ARM_INPUTS[x] for x in arms if not (p.folder / ARM_INPUTS[x]).is_file()]
        print(f"  {p.folder.name:32s} dog {p.dog:20s} {p.decision:10s} {'missing ' + ','.join(missing) if missing else ''}")
    if a.list:
        return 0
    if not a.model_dir:
        raise SystemExit("--model-dir is required (AnimalLift checkout)")

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    started = time.perf_counter()
    runner = load_runner(a.model_dir, a.checkpoint, a.device, a.seed)
    load_ms = int((time.perf_counter() - started) * 1000)
    convert = load_convert()
    env = {**environment(runner, a), "modelLoadMs": load_ms, "photos": [p.folder.name for p in photos]}
    _write_json(out / "env.json", env)
    print(f"model {env['modelVersion']} loaded in {load_ms} ms on {env['device']} ({env['gpu']}), ctrl {env['ctrlSize']}")

    rows = run_eval(photos, arms, out, runner, convert, warmup=not a.no_warmup, force=a.force)
    write_runs_csv(out / "runs.csv", rows)
    errors = [r for r in rows if r.get("error")]
    env["finishedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    env["totalMs"] = int((time.perf_counter() - started) * 1000)
    env["rows"], env["errors"] = len(rows), len(errors)
    _write_json(out / "env.json", env)
    print(f"{len(rows)} rows ({len(errors)} with error) -> {out / 'runs.csv'} in {env['totalMs'] / 1000:.0f} s")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
