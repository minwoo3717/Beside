"""AnimalLift output (mesh.obj + uv.png [+ hair.npz]) -> GLB that satisfies docs/asset/GLB_SPEC.md.

  python tools/convert_asset.py \
    --obj work/<jobId>/raw/mesh.obj --texture work/<jobId>/raw/uv.png [--hair work/<jobId>/raw/hair.npz] \
    --out results/<jobId>/base.glb --metrics results/<jobId>/base.metrics.json \
    [--forward-axis +x] [--normalize-longest-axis 1.0] [--max-triangles 20000] [--max-texture 2048] [--max-bytes 10485760]

GLB_SPEC §1~§4 applied here:
  * single binary .glb, texture embedded, one node named "base", POSITION/NORMAL/TEXCOORD_0
  * Y-up, 1 unit = 1 m, longest bounding-box axis = 1.0 m, lowest point y = 0, centred on x/z
  * face toward +Z. AnimalLift's template (SMAL-style) has the head on +X, so the default --forward-axis is +x
  * PBR metallic 0, roughness 0.9
Violations exit with code 2 and a reason (the inference service reports CONVERSION_FAILED).

With --hair, also writes hair_length.png / hair_flow.png / hair_maps.json next to the GLB for the app's shell fur
(not part of the v1 API yet — see GLB_SPEC 'hair').
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

import numpy as np
import trimesh
from PIL import Image

# rotation (about +Y) that turns the given head axis into +Z, as a function of (x, z) -> (x', z')
_FORWARD = {
    "+z": lambda x, z: (x, z),
    "-z": lambda x, z: (-x, -z),
    "+x": lambda x, z: (-z, x),   # head on +X -> +Z
    "-x": lambda x, z: (z, -x),
}


class ConversionError(Exception):
    pass


def _smooth_normals(vertices: np.ndarray, base_normals: np.ndarray) -> np.ndarray:
    """Average normals of vertices that share a position (UV seams split vertices)."""
    _, inv = np.unique(np.round(vertices, 6), axis=0, return_inverse=True)
    inv = inv.ravel()
    acc = np.zeros((inv.max() + 1, 3))
    np.add.at(acc, inv, base_normals)
    n = acc[inv]
    return n / (np.linalg.norm(n, axis=1, keepdims=True) + 1e-12)


def convert(obj_path: str, texture_path: str, out_path: str, metrics_path: str | None = None,
            hair_npz: str | None = None, forward_axis: str = "+x", longest_axis: float = 1.0,
            max_triangles: int = 20000, max_texture: int = 2048, max_bytes: int = 10 * 1024 * 1024) -> dict:
    """Runs the conversion and returns the metrics dict (GLB_SPEC §5 names). Raises ConversionError."""
    started = time.perf_counter()
    if forward_axis not in _FORWARD:
        raise ConversionError(f"--forward-axis must be one of {sorted(_FORWARD)}")

    try:
        mesh = trimesh.load(obj_path, process=False, force="mesh")
    except Exception as e:  # noqa: BLE001
        raise ConversionError(f"cannot read OBJ: {e}") from e
    if getattr(mesh.visual, "uv", None) is None:
        raise ConversionError("OBJ has no UVs (vt)")
    triangles = int(len(mesh.faces))
    if triangles > max_triangles:
        raise ConversionError(f"{triangles} triangles > limit {max_triangles}")

    try:
        texture = Image.open(texture_path).convert("RGB")
    except Exception as e:  # noqa: BLE001
        raise ConversionError(f"cannot read texture: {e}") from e
    if max(texture.size) > max_texture:
        raise ConversionError(f"texture {texture.size} > limit {max_texture}")

    # --- orientation, scale, origin (GLB_SPEC §2)
    v = np.asarray(mesh.vertices, dtype=np.float64).copy()
    x, z = _FORWARD[forward_axis](v[:, 0].copy(), v[:, 2].copy())
    v[:, 0], v[:, 2] = x, z
    lo, hi = v.min(0), v.max(0)
    longest = float((hi - lo).max())
    if longest <= 1e-9:
        raise ConversionError("degenerate mesh (zero size)")
    scale = longest_axis / longest
    v = (v - np.array([(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2])) * scale

    rotated = trimesh.Trimesh(vertices=v, faces=mesh.faces, process=False)
    normals = _smooth_normals(v, rotated.vertex_normals)

    material = trimesh.visual.material.PBRMaterial(
        name="base", baseColorTexture=texture, metallicFactor=0.0, roughnessFactor=0.9)
    out_mesh = trimesh.Trimesh(vertices=v, faces=mesh.faces, vertex_normals=normals, process=False,
                               visual=trimesh.visual.TextureVisuals(uv=mesh.visual.uv, material=material))

    scene = trimesh.Scene()
    scene.add_geometry(out_mesh, node_name="base", geom_name="base")
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    data = scene.export(file_type="glb")
    if not data[:4] == b"glTF":
        raise ConversionError("exporter did not produce a glTF binary")
    if len(data) > max_bytes:
        raise ConversionError(f"GLB {len(data)} bytes > limit {max_bytes}")
    with open(out_path, "wb") as f:
        f.write(data)

    metrics = {
        "vertices": int(len(v)),
        "triangles": triangles,
        "textureSize": int(max(texture.size)),
        "bytes": len(data),
        "convertMs": 0,
    }

    if hair_npz:
        out_dir = os.path.dirname(os.path.abspath(out_path))
        fur = hair_to_maps(hair_npz, os.path.join(out_dir, "hair_length.png"), os.path.join(out_dir, "hair_flow.png"))
        with open(os.path.join(out_dir, "hair_maps.json"), "w") as f:
            json.dump({"furLength": round(fur * scale, 6), "lengthMap": "hair_length.png", "flowMap": "hair_flow.png"}, f)

    metrics["convertMs"] = int((time.perf_counter() - started) * 1000)
    if metrics_path:
        os.makedirs(os.path.dirname(os.path.abspath(metrics_path)), exist_ok=True)
        with open(metrics_path, "w") as f:
            json.dump(metrics, f)
    return metrics


def hair_to_maps(npz_path: str, length_path: str, flow_path: str) -> float:
    """hair.npz (per-texel strand offsets, local frame z = normal) -> length / flow PNGs. Returns max fur length."""
    d = np.load(npz_path)
    off = d["hair_offsets_local_q"].astype(np.float32) / 127.0 * d["offset_scale"]
    tip = off[:, :, -1]
    length = np.linalg.norm(tip, axis=-1)
    ref = float(np.percentile(length, 99.5)) or 1e-6
    safe = np.maximum(length, 1e-6)[..., None]
    flow = np.clip(tip[..., :2] / safe, -1, 1)
    lift = np.clip(tip[..., 2:3] / safe, -1, 1)
    length01 = np.clip(length / ref, 0, 1)
    # arrays are stored with row 0 = v 0; flip to image convention (checked against UV seams)
    length01 = length01[::-1]
    flow = flow[::-1].copy()
    flow[..., 1] *= -1
    lift = lift[::-1]
    Image.fromarray((length01 * 255 + 0.5).astype(np.uint8), "L").save(length_path)
    rgb = np.concatenate([flow * 0.5 + 0.5, lift * 0.5 + 0.5], axis=-1)
    Image.fromarray((rgb * 255 + 0.5).astype(np.uint8), "RGB").save(flow_path)
    return ref


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--obj", required=True)
    ap.add_argument("--texture", required=True)
    ap.add_argument("--hair")
    ap.add_argument("--out", required=True)
    ap.add_argument("--metrics")
    ap.add_argument("--forward-axis", default="+x", choices=sorted(_FORWARD))
    ap.add_argument("--normalize-longest-axis", type=float, default=1.0)
    ap.add_argument("--max-triangles", type=int, default=20000)
    ap.add_argument("--max-texture", type=int, default=2048)
    ap.add_argument("--max-bytes", type=int, default=10 * 1024 * 1024)
    a = ap.parse_args(argv)
    try:
        m = convert(a.obj, a.texture, a.out, a.metrics, a.hair, a.forward_axis, a.normalize_longest_axis,
                    a.max_triangles, a.max_texture, a.max_bytes)
    except ConversionError as e:
        print(f"CONVERSION_FAILED: {e}", file=sys.stderr)
        return 2
    print(json.dumps(m))
    return 0


if __name__ == "__main__":
    sys.exit(main())
