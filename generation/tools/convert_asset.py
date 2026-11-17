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


def pad_texture(img: Image.Image) -> Image.Image:
    """AnimalLift's uv.png has a pure green background between the UV islands. Bilinear filtering and mip-maps
    pull that green into the edges of every island (green seams on ears, green fringes on the fur).
    Replace each background pixel with the color of the nearest island pixel."""
    from scipy import ndimage

    a = np.asarray(img.convert("RGB"), np.float32) / 255.0
    green = (a[..., 1] > 0.55) & (a[..., 0] < 0.4) & (a[..., 2] < 0.4) & (a[..., 1] - np.maximum(a[..., 0], a[..., 2]) > 0.3)
    if not green.any() or green.all():
        return img.convert("RGB")
    _, (iy, ix) = ndimage.distance_transform_edt(green, return_indices=True)
    out = a[iy, ix]
    return Image.fromarray((out * 255 + 0.5).astype(np.uint8), "RGB")


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
            max_triangles: int = 20000, max_texture: int = 2048, max_bytes: int = 10 * 1024 * 1024,
            hair_meta: str | None = None) -> dict:
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
    texture = pad_texture(texture)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    texture.save(os.path.join(os.path.dirname(os.path.abspath(out_path)), "texture_padded.png"))   # rig_transfer uses it

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
        fur = hair_to_maps(hair_npz, os.path.join(out_dir, "hair_length.png"), os.path.join(out_dir, "hair_flow.png"),
                           meta_path=hair_meta)
        with open(os.path.join(out_dir, "hair_maps.json"), "w") as f:
            json.dump({"furLength": round(fur * scale, 6), "lengthMap": "hair_length.png", "flowMap": "hair_flow.png"}, f)

    metrics["convertMs"] = int((time.perf_counter() - started) * 1000)
    if metrics_path:
        os.makedirs(os.path.dirname(os.path.abspath(metrics_path)), exist_ok=True)
        with open(metrics_path, "w") as f:
            json.dump(metrics, f)
    return metrics


# Template-UV boxes (hair-map pixel coords: x right, y DOWN from the top row = v 1) where AnimalLift's template
# has bare skin: the nose leather and the four paw-pad islands. Within them the dark pixels of the template's
# base_texture.png are the skin. Same UV layout for every AnimalLift dog, so this is fixed.
_BARE_BOXES = [(232, 166, 290, 218),                          # nose
               (12, 292, 80, 362), (440, 280, 510, 348),       # paw pads (front)
               (8, 438, 80, 508), (438, 438, 510, 510)]        # paw pads (hind)


def _hairless_mask(meta_dir: str | None, shape) -> np.ndarray:
    """True where there must be no fur (nose leather, paw pads). Needs AnimalLift assets/base_texture.png;
    without it, nothing is masked."""
    from scipy import ndimage

    mask = np.zeros(shape, bool)
    tex = os.path.join(meta_dir, "base_texture.png") if meta_dir else None
    if not tex or not os.path.isfile(tex):
        return mask
    im = np.asarray(Image.open(tex).convert("RGB").resize((shape[1], shape[0]), Image.BILINEAR), np.float32) / 255
    lum = im @ np.array([0.299, 0.587, 0.114], np.float32)
    dark = lum < 0.33
    boxes = np.zeros(shape, bool)
    for x0, y0, x1, y1 in _BARE_BOXES:
        boxes[y0:y1, x0:x1] = True
    img_mask = ndimage.binary_dilation(ndimage.binary_opening(dark & boxes, iterations=1), iterations=1)
    return img_mask[::-1]          # image rows (top = v 1) -> array rows (row 0 = v 0), like the hair arrays


def _from_guides(tip: np.ndarray, meta, out_dir: str, meta_dir: str | None = None) -> np.ndarray:
    """AnimalLift only defines hair at its guide texels (guide_group_id_map >= 0: ~56k random texels, 21 %);
    Blender then grows child hairs between them. The other texels hold untrained values. Rebuild a smooth map
    from the guides only (Gaussian normalized convolution), inside the area the guides cover; 0 elsewhere
    (nose, eyes, pads, UV padding). Also writes hair_region.png (group id per texel, nearest guide; 255 = none)
    for per-region fur later."""
    from scipy import ndimage

    group = meta["guide_group_id_map"]
    if group.shape != tip.shape[:2]:
        return tip
    guide = (group >= 0).astype(np.float64)
    occupied = meta["uv_face_index_map"] >= 0 if "uv_face_index_map" in meta.files else np.ones_like(guide, bool)
    sigma = 1.5
    den = ndimage.gaussian_filter(guide, sigma)
    out = np.zeros_like(tip)
    for k in range(3):
        out[..., k] = ndimage.gaussian_filter(tip[..., k] * guide, sigma) / np.maximum(den, 1e-6)
    covered = (ndimage.gaussian_filter(guide, 3.0) > 0.06) & occupied
    covered &= ~_hairless_mask(meta_dir, group.shape)
    # shorten the fur toward hairless spots inside the body (nose, eyes, paw pads) — not at UV island borders
    bare = occupied & ~covered
    if bare.any():
        fade = np.clip(ndimage.distance_transform_edt(~bare) / 4.0, 0.0, 1.0)   # 0 at the spot, 1 after 4 texels
        out *= fade[..., None]
    out[~covered] = 0.0
    # pad 3 texels past the UV island borders with the nearest fur value, so bilinear sampling at seams
    # does not thin the fur along every seam
    dist, (py, px) = ndimage.distance_transform_edt(~covered, return_indices=True)
    pad = ~occupied & (dist <= 3)
    out[pad] = out[py[pad], px[pad]]

    _, (iy, ix) = ndimage.distance_transform_edt(group < 0, return_indices=True)
    region = np.where(covered, group[iy, ix], 255).astype(np.uint8)
    Image.fromarray(region[::-1].copy(), "L").save(os.path.join(out_dir, "hair_region.png"))
    return out


def hair_to_maps(npz_path: str, length_path: str, flow_path: str, meta_path: str | None = None) -> float:
    """hair.npz (per-texel strand offsets) -> length / flow PNGs. Returns the fur length that length=1.0 means.

    AnimalLift's local frame per texel (load_blender.py reconstruct_surface_points_and_frames):
        x = dP/du, y = dP/dv (OBJ UV, v up), z = face normal
    so flow.rg = the comb direction in (+u, +v) tangent space = Unity's (tangent, cross(normal, tangent) * w)
    once the GLB carries TANGENT (embed_hair.py adds it). flow.b = how much the hair stands up.
    meta_path = AnimalLift assets/hair_map_meta_uvanchor.npz: rebuild the maps from the guide texels only (see _from_guides).
    """
    d = np.load(npz_path)
    off = d["hair_offsets_local_q"].astype(np.float32) / 127.0 * d["offset_scale"]
    tip = off[:, :, -1].astype(np.float64)
    if meta_path and os.path.isfile(meta_path):
        tip = _from_guides(tip, np.load(meta_path, allow_pickle=True), os.path.dirname(os.path.abspath(length_path)),
                           os.path.dirname(os.path.abspath(meta_path)))
    length = np.linalg.norm(tip, axis=-1)
    grown = length[length > 0]
    ref = float(np.percentile(grown, 99.5)) if grown.size else 1e-6
    ref = ref or 1e-6
    safe = np.maximum(length, 1e-6)[..., None]
    flow = np.clip(tip[..., :2] / safe, -1, 1)
    lift = np.clip(tip[..., 2:3] / safe, -1, 1)
    flow[length <= 0] = 0.0          # neutral where there is no hair
    lift[length <= 0] = 1.0
    length01 = np.clip(length / ref, 0, 1)
    # arrays are stored with row 0 = v 0 (checked against reference.obj UVs: IoU 0.98 vs 0.45 flipped);
    # PNG row 0 is the top (v 1), so flip the rows. Vectors are not touched: flipping rows does not change them.
    length01 = length01[::-1]
    flow = flow[::-1].copy()
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
    ap.add_argument("--hair-meta", help="AnimalLift assets/hair_map_meta_uvanchor.npz (hair only where a guide group is)")
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
                    a.max_triangles, a.max_texture, a.max_bytes, a.hair_meta)
    except ConversionError as e:
        print(f"CONVERSION_FAILED: {e}", file=sys.stderr)
        return 2
    print(json.dumps(m))
    return 0


if __name__ == "__main__":
    sys.exit(main())
