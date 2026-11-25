"""3D A/B evaluation, step 2 of 3 (runs on any CPU PC): automatic metrics per (photo, arm) from eval_arms.py's output.

  python generation/tools/eval_metrics.py --eval-dir <eval_arms --out> --photos-dir <preprocessBatch output> \
      [--baseline remove] [--out <eval-dir>/metrics]

Writes metrics.csv (one row per photo x arm), summary.csv and summary.md (per arm means, paired differences vs the
baseline arm with improve/same/worse counts and a two-sided sign test). Needs numpy and Pillow only.

Metrics (all decided before the run, generation/experiments/2026-11-26_exp-04_ab-input-texture.md):
  furColorDeltaE        CIEDE2000 between the Lab median of the photo foreground (cutout.png alpha > 0.5, eroded 3 px)
                        and the Lab median of the texture's UV-island texels (island = not the pure-green background,
                        the same test as convert_asset.pad_texture, taken from the arm's unpadded uv.png)
  furHueErrorDeg        difference of the two medians' hue angles atan2(b, a), 0..180
  seamGreenRatio@mipN   the texture embedded in base.glb (padded for +pad arms) box-filtered by 2/4/8; among island
                        texels (coverage >= 0.5) within 4 texels of a background texel, the fraction with
                        G - max(R, B) > 0.15. exp-04's primary metric
  shapeDeltaPct         mean vertex distance to the baseline arm's mesh.obj of the same photo / the baseline's bounding
                        box diagonal, in % (AnimalLift has one fixed topology: vertex i matches vertex i)
  sameDogId             within an arm, leave-one-out nearest neighbour on the texture Lab median (CIEDE2000): 1 if the
                        nearest other output is the same dog. Only photos whose dog has another photo in that arm
                        are asked; chance = 1 / number of dogs, chanceNN = the exact chance of a random neighbour
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import math
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from eval_arms import (ARM_INPUTS, BASELINE, FOREGROUND_CUTOUT, dog_of, find_photos,  # noqa: E402
                       split_variant)

MIP_FACTORS = (2, 4, 8)            # mip1, mip2, mip3
SEAM_BAND = 4                      # texels from an island border, in the downscaled image
SEAM_GREEN = 0.15                  # G - max(R, B) above this = visibly green
ERODE_PX = 3                       # photo foreground erosion (cutout edges carry background colour)

# Pre-decided "same" bands for the paired comparison (a difference within the band counts as same):
# CIEDE2000 1.0 = about one just-noticeable difference; hue 2 degrees; seam ratio 0.5 percentage points.
METRICS_PAIRED = [            # (column, lower is better, tolerance)
    ("furColorDeltaE", True, 1.0),
    ("furHueErrorDeg", True, 2.0),
    ("seamGreenRatio@mip1", True, 0.005),
    ("seamGreenRatio@mip2", True, 0.005),
    ("seamGreenRatio@mip3", True, 0.005),
    ("sameDogId", False, 0.0),
]

METRICS_COLUMNS = ["photo", "dog", "arm", "inputArm", "pad", "furColorDeltaE", "furHueErrorDeg",
                   "seamGreenRatio@mip1", "seamGreenRatio@mip2", "seamGreenRatio@mip3", "shapeDeltaPct", "sameDogId",
                   "photoL", "photoA", "photoB", "texL", "texA", "texB", "islandFraction", "textureSize", "error"]

# The green test of convert_asset.pad_texture, as text: test_eval3d checks both files still contain it.
GREEN_TEST = ("(a[..., 1] > 0.55) & (a[..., 0] < 0.4) & (a[..., 2] < 0.4) "
              "& (a[..., 1] - np.maximum(a[..., 0], a[..., 2]) > 0.3)")


# --------------------------------------------------------------------------- colour


def srgb_to_lab(rgb) -> np.ndarray:
    """sRGB in 0..1 (..., 3) -> CIE L*a*b* (D65, 2 degree observer), same shape."""
    c = np.asarray(rgb, dtype=np.float64)
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    m = np.array([[0.4124564, 0.3575761, 0.1804375],
                  [0.2126729, 0.7151522, 0.0721750],
                  [0.0193339, 0.1191920, 0.9503041]])
    xyz = lin @ m.T / np.array([0.95047, 1.0, 1.08883])
    d = 6.0 / 29.0
    f = np.where(xyz > d ** 3, np.cbrt(xyz), xyz / (3 * d * d) + 4.0 / 29.0)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], axis=-1)


def ciede2000(lab1, lab2):
    """CIEDE2000 colour difference (kL = kC = kH = 1), Sharma, Wu & Dalal 2005. Broadcasts; float for one pair."""
    L1, a1, b1 = np.moveaxis(np.asarray(lab1, dtype=np.float64), -1, 0)
    L2, a2, b2 = np.moveaxis(np.asarray(lab2, dtype=np.float64), -1, 0)
    c_bar = (np.hypot(a1, b1) + np.hypot(a2, b2)) / 2
    g = 0.5 * (1 - np.sqrt(c_bar ** 7 / (c_bar ** 7 + 25.0 ** 7)))
    a1p, a2p = (1 + g) * a1, (1 + g) * a2
    c1p, c2p = np.hypot(a1p, b1), np.hypot(a2p, b2)
    h1p = np.degrees(np.arctan2(b1, a1p)) % 360
    h2p = np.degrees(np.arctan2(b2, a2p)) % 360
    prod = c1p * c2p

    dl = L2 - L1
    dc = c2p - c1p
    dh = h2p - h1p
    dh = np.where(dh > 180, dh - 360, np.where(dh < -180, dh + 360, dh))
    dh = np.where(prod == 0, 0.0, dh)
    dH = 2 * np.sqrt(prod) * np.sin(np.radians(dh) / 2)

    l_bar = (L1 + L2) / 2
    cp_bar = (c1p + c2p) / 2
    hsum = h1p + h2p
    h_bar = np.where(np.abs(h1p - h2p) <= 180, hsum / 2, np.where(hsum < 360, (hsum + 360) / 2, (hsum - 360) / 2))
    h_bar = np.where(prod == 0, hsum, h_bar)
    t = (1 - 0.17 * np.cos(np.radians(h_bar - 30)) + 0.24 * np.cos(np.radians(2 * h_bar))
         + 0.32 * np.cos(np.radians(3 * h_bar + 6)) - 0.20 * np.cos(np.radians(4 * h_bar - 63)))
    d_theta = 30 * np.exp(-(((h_bar - 275) / 25) ** 2))
    rc = 2 * np.sqrt(cp_bar ** 7 / (cp_bar ** 7 + 25.0 ** 7))
    sl = 1 + 0.015 * (l_bar - 50) ** 2 / np.sqrt(20 + (l_bar - 50) ** 2)
    sc = 1 + 0.045 * cp_bar
    sh = 1 + 0.015 * cp_bar * t
    rt = -np.sin(np.radians(2 * d_theta)) * rc
    de = np.sqrt((dl / sl) ** 2 + (dc / sc) ** 2 + (dH / sh) ** 2 + rt * (dc / sc) * (dH / sh))
    return float(de) if np.ndim(de) == 0 else de


def hue_deg(lab) -> float:
    lab = np.asarray(lab, dtype=np.float64)
    return float(np.degrees(np.arctan2(lab[2], lab[1])) % 360)


def hue_diff(h1: float, h2: float) -> float:
    d = abs(h1 - h2) % 360
    return float(min(d, 360 - d))


def lab_median(rgb01) -> np.ndarray:
    """Per-channel median of the Lab values of (N, 3) sRGB pixels in 0..1."""
    return np.median(srgb_to_lab(np.asarray(rgb01).reshape(-1, 3)), axis=0)


# --------------------------------------------------------------------------- masks and texture


def green_mask(a: np.ndarray) -> np.ndarray:
    """AnimalLift's pure-green UV background, the exact test of convert_asset.pad_texture (a = RGB in 0..1)."""
    return (a[..., 1] > 0.55) & (a[..., 0] < 0.4) & (a[..., 2] < 0.4) & (a[..., 1] - np.maximum(a[..., 0], a[..., 2]) > 0.3)


def _morph(mask: np.ndarray, steps: int, grow: bool) -> np.ndarray:
    """8-neighbour dilation (grow) or erosion, `steps` times; outside the image counts as False."""
    m = np.asarray(mask, dtype=bool)
    h, w = m.shape
    for _ in range(steps):
        p = np.pad(m, 1, constant_values=False)
        acc = p[1:h + 1, 1:w + 1].copy()
        for dy in range(3):
            for dx in range(3):
                if grow:
                    acc |= p[dy:dy + h, dx:dx + w]
                else:
                    acc &= p[dy:dy + h, dx:dx + w]
        m = acc
    return m


def dilate(mask, steps: int) -> np.ndarray:
    return _morph(mask, steps, True)


def erode(mask, steps: int) -> np.ndarray:
    return _morph(mask, steps, False)


def box_downscale(arr: np.ndarray, factor: int) -> np.ndarray:
    """Mean of factor x factor blocks (one mip step per factor 2); the ragged edge is dropped."""
    a = np.asarray(arr, dtype=np.float64)
    h, w = a.shape[0] // factor * factor, a.shape[1] // factor * factor
    a = a[:h, :w]
    return a.reshape(h // factor, factor, w // factor, factor, *a.shape[2:]).mean(axis=(1, 3))


def seam_green_ratio(texture01: np.ndarray, island: np.ndarray, factor: int, band: int = SEAM_BAND,
                     threshold: float = SEAM_GREEN) -> float:
    """Fraction of island texels near an island border that look green after box-filtering by `factor`.
    island: full-resolution mask (True = UV island). NaN when there is no border."""
    tex = box_downscale(texture01[..., :3], factor)
    isl = box_downscale(np.asarray(island, dtype=np.float64), factor) >= 0.5
    near = isl & dilate(~isl, band)
    if not near.any():
        return float("nan")
    g = tex[..., 1] - np.maximum(tex[..., 0], tex[..., 2])
    return float((g[near] > threshold).mean())


# --------------------------------------------------------------------------- files


def load_rgba01(path) -> np.ndarray:
    from PIL import Image  # noqa: WPS433

    with Image.open(path) as im:
        return np.asarray(im.convert("RGBA"), dtype=np.float64) / 255.0


def photo_foreground_lab(cutout_path) -> np.ndarray | None:
    """Lab median of cutout.png where alpha > 0.5, eroded ERODE_PX px; None when nothing is left."""
    a = load_rgba01(cutout_path)
    fg = erode(a[..., 3] > 0.5, ERODE_PX)
    if not fg.any():
        return None
    return lab_median(a[..., :3][fg])


def embedded_base_color(glb_path):
    """The base colour image of the first material, read out of the GLB's binary chunk (PIL image, RGB)."""
    from PIL import Image  # noqa: WPS433

    data = Path(glb_path).read_bytes()
    magic, _version, _length = struct.unpack_from("<4sII", data, 0)
    if magic != b"glTF":
        raise ValueError(f"not a GLB: {glb_path}")
    json_len, kind = struct.unpack_from("<I4s", data, 12)
    if kind != b"JSON":
        raise ValueError("first GLB chunk is not JSON")
    gltf = json.loads(data[20:20 + json_len])
    bin_at = 20 + json_len
    bin_len, kind = struct.unpack_from("<I4s", data, bin_at)
    if kind != b"BIN\x00":
        raise ValueError("second GLB chunk is not BIN")
    binary = data[bin_at + 8:bin_at + 8 + bin_len]
    try:
        tex_index = gltf["materials"][0]["pbrMetallicRoughness"]["baseColorTexture"]["index"]
        image = gltf["images"][gltf["textures"][tex_index]["source"]]
        view = gltf["bufferViews"][image["bufferView"]]
    except (KeyError, IndexError, TypeError) as e:
        raise ValueError(f"no embedded base colour texture in {Path(glb_path).name} ({type(e).__name__}: {e})") from e
    start = view.get("byteOffset", 0)
    return Image.open(io.BytesIO(binary[start:start + view["byteLength"]])).convert("RGB")


def read_obj_vertices(path) -> np.ndarray:
    """The `v x y z` lines of an OBJ, in file order (vt/vn/f ignored)."""
    verts = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("v "):
                verts.append([float(x) for x in line.split()[1:4]])
    return np.asarray(verts, dtype=np.float64).reshape(-1, 3)


def shape_delta_pct(verts: np.ndarray, base: np.ndarray) -> float:
    """Mean distance between matching vertices / the base mesh's bounding-box diagonal, in %."""
    if verts.shape != base.shape or len(base) == 0:
        return float("nan")
    diag = float(np.linalg.norm(base.max(0) - base.min(0)))
    if diag <= 0:
        return float("nan")
    return float(100.0 * np.linalg.norm(verts - base, axis=1).mean() / diag)


# --------------------------------------------------------------------------- statistics


def sign_test(improve: int, worse: int) -> float:
    """Two-sided exact sign test (ties already dropped). 1.0 when there is nothing to test."""
    n = improve + worse
    if n == 0:
        return 1.0
    k = min(improve, worse)
    return min(1.0, 2.0 * sum(math.comb(n, i) for i in range(k + 1)) / 2.0 ** n)


def paired(values: dict, base: dict, lower_better: bool, tol: float) -> dict:
    """values/base: photo -> number (NaN/None skipped). Differences are value - base; 'improve' means better by more
    than tol in the metric's direction."""
    diffs = []
    for photo, v in values.items():
        b = base.get(photo)
        if _num(v) and _num(b):
            diffs.append(float(v) - float(b))
    better = [(-d if lower_better else d) for d in diffs]
    improve = sum(1 for d in better if d > tol + 1e-12)
    worse = sum(1 for d in better if d < -tol - 1e-12)
    return {"n": len(diffs), "meanDiff": float(np.mean(diffs)) if diffs else float("nan"),
            "improve": improve, "same": len(diffs) - improve - worse, "worse": worse, "signP": sign_test(improve, worse)}


def _num(v) -> bool:
    return v is not None and v != "" and not (isinstance(v, float) and math.isnan(v))


def same_dog_id(labs: dict, dogs: dict) -> tuple[dict, float, float]:
    """labs: photo -> Lab median of its output texture (one arm). Returns (photo -> 1/0 for photos whose dog has
    another photo here, chance = 1/number of dogs, chanceNN = mean share of same-dog neighbours)."""
    photos = [p for p in sorted(labs) if labs[p] is not None]
    n_dogs = len({dogs[p] for p in photos})
    result, shares = {}, []
    for p in photos:
        others = [q for q in photos if q != p]
        same = [q for q in others if dogs[q] == dogs[p]]
        if not same:
            continue
        dist = [ciede2000(labs[p], labs[q]) for q in others]
        nearest = others[int(np.argmin(dist))]
        result[p] = int(dogs[nearest] == dogs[p])
        shares.append(len(same) / len(others))
    chance = 1.0 / n_dogs if n_dogs else float("nan")
    return result, chance, float(np.mean(shares)) if shares else float("nan")


# --------------------------------------------------------------------------- the evaluation


def find_variants(eval_dir: Path) -> list[str]:
    """Arm folders of eval_arms' output (off, keep, remove and their +pad), in a stable order."""
    found = []
    for d in Path(eval_dir).iterdir():
        arm, _ = split_variant(d.name)
        if d.is_dir() and arm in ARM_INPUTS and any((d / p / "base.glb").is_file() for p in _subdirs(d)):
            found.append(d.name)
    order = list(ARM_INPUTS)
    return sorted(found, key=lambda v: (order.index(split_variant(v)[0]), split_variant(v)[1]))


def _subdirs(d: Path) -> list[str]:
    return sorted(p.name for p in d.iterdir() if p.is_dir())


def measure_one(eval_dir: Path, variant: str, photo: str, photo_lab, base_verts, log=print) -> dict:
    arm, pad = split_variant(variant)
    row = {"photo": photo, "dog": dog_of(photo), "arm": variant, "inputArm": arm, "pad": int(pad)}
    if photo_lab is not None:
        row.update(photoL=photo_lab[0], photoA=photo_lab[1], photoB=photo_lab[2])
    glb = eval_dir / variant / photo / "base.glb"
    pred_dir = eval_dir / arm / photo                 # mesh.obj and the unpadded uv.png live with the prediction
    try:
        tex = np.asarray(embedded_base_color(glb), dtype=np.float64) / 255.0
        uv = pred_dir / "uv.png"
        raw = load_rgba01(uv)[..., :3] if uv.is_file() else tex
        if raw.shape[:2] != tex.shape[:2]:
            raise ValueError(f"uv.png {raw.shape[:2]} and embedded texture {tex.shape[:2]} differ")
        island = ~green_mask(raw)
        row["textureSize"] = int(max(tex.shape[:2]))
        row["islandFraction"] = float(island.mean())
        tex_lab = lab_median(tex[island]) if island.any() else None
        if tex_lab is not None:
            row.update(texL=tex_lab[0], texA=tex_lab[1], texB=tex_lab[2])
        if tex_lab is not None and photo_lab is not None:
            row["furColorDeltaE"] = ciede2000(photo_lab, tex_lab)
            row["furHueErrorDeg"] = hue_diff(hue_deg(photo_lab), hue_deg(tex_lab))
        for level, factor in enumerate(MIP_FACTORS, start=1):
            row[f"seamGreenRatio@mip{level}"] = seam_green_ratio(tex, island, factor)
        obj = pred_dir / "mesh.obj"
        if base_verts is not None and obj.is_file():
            row["shapeDeltaPct"] = shape_delta_pct(read_obj_vertices(obj), base_verts)
    except Exception as e:  # noqa: BLE001  one broken output must not stop the table
        row["error"] = f"{type(e).__name__}: {e}"
        log(f"{variant}/{photo}: {row['error']}")
    return row


def evaluate(eval_dir, photos_dir, baseline: str = BASELINE, log=print) -> tuple[list[dict], dict]:
    """All metric rows, plus per-variant sameDogId chance values {variant: (chance, chanceNN)}."""
    eval_dir = Path(eval_dir)
    variants = find_variants(eval_dir)
    if not variants:
        raise SystemExit(f"no arm folders with base.glb in {eval_dir}")
    folders = {p.name: p.folder for p in find_photos(photos_dir, include_refused=True, log=lambda *_: None)}
    photos = sorted({p for v in variants for p in _subdirs(eval_dir / v) if (eval_dir / v / p / "base.glb").is_file()})

    rows = []
    for photo in photos:
        photo_lab = None
        cut = folders.get(photo, Path("-")) / FOREGROUND_CUTOUT
        if cut.is_file():
            photo_lab = photo_foreground_lab(cut)
        else:
            log(f"{photo}: no {FOREGROUND_CUTOUT} in --photos-dir, furColorDeltaE left empty")
        base_obj = eval_dir / baseline / photo / "mesh.obj"
        base_verts = read_obj_vertices(base_obj) if base_obj.is_file() else None
        for variant in variants:
            if (eval_dir / variant / photo / "base.glb").is_file():
                rows.append(measure_one(eval_dir, variant, photo, photo_lab, base_verts, log))

    chances = {}
    for variant in variants:
        mine = [r for r in rows if r["arm"] == variant]
        labs = {r["photo"]: (np.array([r["texL"], r["texA"], r["texB"]]) if _num(r.get("texL")) else None) for r in mine}
        ids, chance, chance_nn = same_dog_id(labs, {r["photo"]: r["dog"] for r in mine})
        for r in mine:
            if r["photo"] in ids:
                r["sameDogId"] = ids[r["photo"]]
        chances[variant] = (chance, chance_nn)
    return rows, chances


def summarize(rows: list[dict], chances: dict, baseline: str) -> list[dict]:
    """Long-format summary: one 'mean' row per arm x metric, one 'paired' row per non-baseline arm x metric."""
    variants = list(dict.fromkeys(r["arm"] for r in rows))
    by = {v: {r["photo"]: r for r in rows if r["arm"] == v} for v in variants}
    cols = [c for c, _, _ in METRICS_PAIRED] + ["shapeDeltaPct"]
    out = []
    for v in variants:
        for c in cols:
            vals = [float(r[c]) for r in by[v].values() if _num(r.get(c))]
            out.append({"section": "mean", "arm": v, "metric": c, "n": len(vals),
                        "mean": float(np.mean(vals)) if vals else float("nan"),
                        "chance": chances.get(v, (None, None))[0] if c == "sameDogId" else "",
                        "chanceNN": chances.get(v, (None, None))[1] if c == "sameDogId" else ""})
    if baseline in by:
        for v in variants:
            if v == baseline:
                continue
            for c, lower, tol in METRICS_PAIRED:
                p = paired({k: r.get(c) for k, r in by[v].items()}, {k: r.get(c) for k, r in by[baseline].items()},
                           lower, tol)
                out.append({"section": "paired", "arm": v, "metric": c, "vs": baseline, "tolerance": tol,
                            "lowerIsBetter": int(lower), **p})
    return out


def _fmt(v, digits=3) -> str:
    if v is None or v == "":
        return ""
    if isinstance(v, float):
        return "" if math.isnan(v) else f"{v:.{digits}f}"
    return str(v)


def summary_markdown(summary: list[dict], baseline: str) -> str:
    means = [s for s in summary if s["section"] == "mean"]
    variants = list(dict.fromkeys(s["arm"] for s in means))
    metrics = list(dict.fromkeys(s["metric"] for s in means))
    lines = ["| arm | n | " + " | ".join(metrics) + " | chance (1/dogs) |",
             "|---|---|" + "---|" * len(metrics) + "---|"]
    for v in variants:
        cells = {s["metric"]: s for s in means if s["arm"] == v}
        n = max((s["n"] for s in cells.values()), default=0)
        lines.append(f"| {v}{' (baseline)' if v == baseline else ''} | {n} | "
                     + " | ".join(_fmt(cells[m]["mean"]) for m in metrics)
                     + f" | {_fmt(cells.get('sameDogId', {}).get('chance'))} |")
    pairs = [s for s in summary if s["section"] == "paired"]
    if pairs:
        lines += ["", f"Paired per photo vs {baseline} (diff = arm - {baseline}; improve/same/worse in the metric's "
                      "direction, 'same' = within the tolerance; two-sided sign test on improve vs worse):", "",
                  "| arm | metric | n | mean diff | improve | same | worse | sign p |", "|---|---|---|---|---|---|---|---|"]
        for s in pairs:
            lines.append(f"| {s['arm']} | {s['metric']} | {s['n']} | {_fmt(s['meanDiff'], 4)} | {s['improve']} | "
                         f"{s['same']} | {s['worse']} | {_fmt(s['signP'], 4)} |")
    return "\n".join(lines) + "\n"


def write_csv(path, rows: list[dict], columns: list[str]) -> None:
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=columns, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow({k: _cell(r.get(k)) for k in columns})


def _cell(v):
    if v is None:
        return ""
    if isinstance(v, (float, np.floating)):
        return "" if math.isnan(v) else f"{float(v):.6g}"
    return v


SUMMARY_COLUMNS = ["section", "arm", "metric", "vs", "n", "mean", "chance", "chanceNN", "meanDiff", "improve", "same",
                   "worse", "signP", "tolerance", "lowerIsBetter"]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--eval-dir", required=True, help="eval_arms.py --out folder")
    ap.add_argument("--photos-dir", required=True, help="preprocessBatch --out folder (cutout.png per photo)")
    ap.add_argument("--baseline", default=BASELINE)
    ap.add_argument("--out", help="default <eval-dir>/metrics")
    a = ap.parse_args(argv)
    out = Path(a.out) if a.out else Path(a.eval_dir) / "metrics"
    out.mkdir(parents=True, exist_ok=True)
    rows, chances = evaluate(a.eval_dir, a.photos_dir, a.baseline)
    write_csv(out / "metrics.csv", rows, METRICS_COLUMNS)
    summary = summarize(rows, chances, a.baseline)
    write_csv(out / "summary.csv", summary, SUMMARY_COLUMNS)
    md = summary_markdown(summary, a.baseline)
    (out / "summary.md").write_text(md, encoding="utf-8")
    print(md)
    print(f"{len(rows)} rows -> {out / 'metrics.csv'}, {out / 'summary.csv'}, {out / 'summary.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
