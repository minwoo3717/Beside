"""AnimalLift output (mesh.obj + uv.png [+ hair.npz]) -> GLB that satisfies docs/asset/GLB_SPEC.md.

  python tools/convert_asset.py \
    --obj work/<jobId>/raw/mesh.obj --texture work/<jobId>/raw/uv.png [--hair work/<jobId>/raw/hair.npz] \
    --out results/<jobId>/base.glb --metrics results/<jobId>/base.metrics.json \
    [--forward-axis +x] [--normalize-longest-axis 1.0] [--max-triangles 20000] [--max-texture 2048] [--max-bytes 10485760] \
    [--hair-meta <AnimalLift>/assets/hair_map_meta_uvanchor.npz] [--pad-texture]

GLB_SPEC §1~§4 applied here:
  * single binary .glb, texture embedded, one node named "base", POSITION/NORMAL/TEXCOORD_0
  * Y-up, 1 unit = 1 m, longest bounding-box axis = 1.0 m, lowest point y = 0, centred on x/z
  * face toward +Z. AnimalLift's template (SMAL-style) has the head on +X, so the default --forward-axis is +x
  * PBR metallic 0, roughness 0.9
Violations exit with code 2 and a reason (the inference service reports CONVERSION_FAILED).

With --hair, the fur maps for the app's shell fur go inside the GLB (GLB_SPEC §4.1): two PNG images in the binary
chunk, named by the root extras.beside = {hairLengthImage, hairFlowImage, furLength} that the app reads
(RuntimeModelLoader.TryReadEmbeddedHair). They are images only, no glTF texture uses them. The same maps are also
written next to the GLB as hair_length.png / hair_flow.png / hair_maps.json for inspection.

Two opt-in extras from the branch Unity (2d2e235), both off by default so the default GLB is unchanged (need scipy):
  --hair-meta  rebuild the fur maps from AnimalLift's guide texels only (hair_map_meta_uvanchor.npz), no fur on the
               nose leather and paw pads (needs base_texture.png next to the meta file), seams padded 3 texels;
               furLength is then the 99.5th percentile over texels that have fur. Also writes hair_region.png
               (guide group per texel, 255 = none) next to the GLB. An unreadable meta file falls back to the raw maps.
  --pad-texture  fill uv.png's pure-green background with the nearest UV island colour (no green fringes at ears
               and seams) for the GLB's base colour, and write it as texture_padded.png for rig_transfer.
The fur map layout (keys, image names, flow G sign) is the same either way.
"""
from __future__ import annotations

import argparse
import io
import json
import logging
import os
import struct
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

MAX_GLB_BYTES = 10 * 1024 * 1024   # GLB_SPEC §1 file size limit; the inference service checks a rigged GLB against it too

log = logging.getLogger("convert_asset")


class ConversionError(Exception):
    pass


def pad_texture(img: Image.Image) -> Image.Image:
    """AnimalLift's uv.png has a pure green background between the UV islands. Bilinear filtering and mip-maps
    pull that green into the edges of every island (green seams on ears, green fringes on the fur).
    Replace each background pixel with the color of the nearest island pixel; island pixels stay as they are."""
    a = np.asarray(img.convert("RGB"), np.float32) / 255.0
    green = (a[..., 1] > 0.55) & (a[..., 0] < 0.4) & (a[..., 2] < 0.4) & (a[..., 1] - np.maximum(a[..., 0], a[..., 2]) > 0.3)
    if not green.any() or green.all():
        return img.convert("RGB")
    try:
        from scipy import ndimage
    except ImportError as e:
        raise ConversionError("--pad-texture needs scipy") from e
    _, (iy, ix) = ndimage.distance_transform_edt(green, return_indices=True)
    return Image.fromarray((a[iy, ix] * 255 + 0.5).astype(np.uint8), "RGB")


def _smooth_normals(vertices: np.ndarray, base_normals: np.ndarray) -> np.ndarray:
    """Average normals of vertices that share a position (UV seams split vertices)."""
    _, inv = np.unique(np.round(vertices, 6), axis=0, return_inverse=True)
    inv = inv.ravel()
    acc = np.zeros((inv.max() + 1, 3))
    np.add.at(acc, inv, base_normals)
    n = acc[inv]
    return n / (np.linalg.norm(n, axis=1, keepdims=True) + 1e-12)


def _out_dir(path: str, make_dirs: bool) -> str:
    """The folder of an output file: created when make_dirs, else it must still exist (ConversionError)."""
    folder = os.path.dirname(os.path.abspath(path))
    if make_dirs:
        os.makedirs(folder, exist_ok=True)
    elif not os.path.isdir(folder):
        raise ConversionError(f"output folder is gone: {folder}")
    return folder


def convert(obj_path: str, texture_path: str, out_path: str, metrics_path: str | None = None,
            hair_npz: str | None = None, forward_axis: str = "+x", longest_axis: float = 1.0,
            max_triangles: int = 20000, max_texture: int = 2048, max_bytes: int = MAX_GLB_BYTES,
            make_dirs: bool = True, hair_meta: str | None = None, padded_texture: bool = False) -> dict:
    """Runs the conversion and returns the metrics dict (GLB_SPEC §5 names). Raises ConversionError.

    make_dirs=False: never create the output folders; a missing one is a ConversionError. The inference service
    passes it, so a job folder removed by DELETE /artifacts during the conversion is not recreated (2026-11-19 F1b-real
    left folders holding only base.glb that way). Every file below is written after that check.
    hair_meta / padded_texture: the opt-in extras of the module docstring (default off: output unchanged)."""
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
    padded_png = None
    if padded_texture:
        texture = pad_texture(texture)
        padded_png = _png(texture)   # texture_padded.png: what rig_transfer then puts into the rigged GLB
    # (length PNG, flow PNG, fur length in OBJ units, region PNG or None)
    hair = _hair_maps(hair_npz, max_texture, hair_meta) if hair_npz else None

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
    data = scene.export(file_type="glb")
    if not data[:4] == b"glTF":
        raise ConversionError("exporter did not produce a glTF binary")
    fur_length = round(hair[2] * scale, 6) if hair else None   # metres in the GLB, like the vertices
    if hair:
        data = embed_hair(data, hair[0], hair[1], fur_length)
    if len(data) > max_bytes:
        raise ConversionError(f"GLB {len(data)} bytes > limit {max_bytes}")
    out_dir = _out_dir(out_path, make_dirs)   # checked right before the write
    with open(out_path, "wb") as f:
        f.write(data)

    metrics = {
        "vertices": int(len(v)),
        "triangles": triangles,
        "textureSize": int(max(texture.size)),
        "bytes": len(data),
        "convertMs": 0,
    }

    if hair:   # copies of what the GLB carries, for looking at the maps
        for name, png in (("hair_length.png", hair[0]), ("hair_flow.png", hair[1])):
            with open(os.path.join(out_dir, name), "wb") as f:
                f.write(png)
        with open(os.path.join(out_dir, "hair_maps.json"), "w") as f:
            json.dump({"furLength": fur_length, "lengthMap": "hair_length.png", "flowMap": "hair_flow.png"}, f)
        if hair[3] is not None:   # --hair-meta only
            with open(os.path.join(out_dir, "hair_region.png"), "wb") as f:
                f.write(hair[3])
    if padded_png is not None:
        with open(os.path.join(out_dir, "texture_padded.png"), "wb") as f:
            f.write(padded_png)

    metrics["convertMs"] = int((time.perf_counter() - started) * 1000)
    if metrics_path:
        _out_dir(metrics_path, make_dirs)
        with open(metrics_path, "w") as f:
            json.dump(metrics, f)
    return metrics


def hair_maps(npz_path: str, max_size: int = 2048, meta_path: str | None = None) -> tuple[bytes, bytes, float]:
    """hair.npz (per-texel strand offsets, local frame z = normal) -> (length PNG, flow PNG, max fur length).
    The length is in the OBJ's units; convert scales it with the mesh. meta_path: see _hair_maps."""
    return _hair_maps(npz_path, max_size, meta_path)[:3]


def _hair_maps(npz_path: str, max_size: int = 2048,
               meta_path: str | None = None) -> tuple[bytes, bytes, float, bytes | None]:
    """hair_maps plus the guide group map. meta_path (--hair-meta, opt-in): rebuild the maps from AnimalLift's guide
    texels first (_from_guides); then furLength is the 99.5th percentile over texels with fur, texels without fur get
    a neutral flow, and the 4th value is hair_region.png. Without it (or when the meta cannot be used) the maps are
    exactly the raw ones and the 4th value is None."""
    try:
        with np.load(npz_path) as d:
            off = d["hair_offsets_local_q"].astype(np.float32) / 127.0 * d["offset_scale"]
    except Exception as e:  # noqa: BLE001  missing file, other keys, not an npz
        raise ConversionError(f"cannot read hair: {e}") from e
    if off.ndim != 4 or off.shape[-1] != 3:
        raise ConversionError(f"hair offsets have shape {off.shape}, expected (height, width, points, 3)")
    if max(off.shape[:2]) > max_size:
        raise ConversionError(f"hair maps {off.shape[1]}x{off.shape[0]} > limit {max_size}")
    tip = off[:, :, -1]
    region = None
    guides = _load_guides(meta_path, tip.shape[:2]) if meta_path else None
    if guides is not None:
        tip, region = _from_guides(tip.astype(np.float64), *guides)
    length = np.linalg.norm(tip, axis=-1)
    if region is None:
        ref = float(np.percentile(length, 99.5)) or 1e-6
    else:   # bare texels (nose, pads, UV padding) are 0 now and would pull the percentile down
        grown = length[length > 0]
        ref = (float(np.percentile(grown, 99.5)) if grown.size else 0.0) or 1e-6
    safe = np.maximum(length, 1e-6)[..., None]
    flow = np.clip(tip[..., :2] / safe, -1, 1)
    lift = np.clip(tip[..., 2:3] / safe, -1, 1)
    if region is not None:
        flow[length <= 0] = 0.0   # neutral where there is no fur
        lift[length <= 0] = 1.0
    length01 = np.clip(length / ref, 0, 1)
    # arrays are stored with row 0 = v 0; flip to image convention (checked against UV seams)
    length01 = length01[::-1]
    flow = flow[::-1].copy()
    flow[..., 1] *= -1
    lift = lift[::-1]
    rgb = np.concatenate([flow * 0.5 + 0.5, lift * 0.5 + 0.5], axis=-1)
    region_png = _png(Image.fromarray(region[::-1].copy(), "L")) if region is not None else None
    return (_png(Image.fromarray((length01 * 255 + 0.5).astype(np.uint8), "L")),
            _png(Image.fromarray((rgb * 255 + 0.5).astype(np.uint8), "RGB")), ref, region_png)


# Template-UV boxes (hair-map pixel coords of AnimalLift's 512 x 512 grid: x right, y DOWN from the top row = v 1)
# where the template has bare skin: the nose leather and the four paw-pad islands. Within them the dark pixels of the
# template's base_texture.png are the skin. Same UV layout for every AnimalLift dog (branch Unity 2d2e235).
_BARE_BOXES = [(232, 166, 290, 218),                          # nose
               (12, 292, 80, 362), (440, 280, 510, 348),       # paw pads (front)
               (8, 438, 80, 508), (438, 438, 510, 510)]        # paw pads (hind)


def _load_guides(meta_path: str, shape) -> tuple | None:
    """(guide group map, occupied texels, hairless texels) from AnimalLift's assets/hair_map_meta_uvanchor.npz, or None
    with a warning when it cannot be used (missing file, other keys, other grid size, no scipy): the rebuild is an
    option, the raw maps are still valid. Pickled arrays are never loaded."""
    try:
        from scipy import ndimage  # noqa: F401  _from_guides and _hairless_mask need it
        with np.load(meta_path) as m:
            group = np.asarray(m["guide_group_id_map"])
            occupied = (np.asarray(m["uv_face_index_map"]) >= 0) if "uv_face_index_map" in m.files \
                else np.ones(group.shape, bool)
    except Exception as e:  # noqa: BLE001
        log.warning("hair meta not used, raw fur maps (%s: %s)", type(e).__name__, e)
        return None
    if group.shape != tuple(shape) or occupied.shape != group.shape:
        log.warning("hair meta not used, raw fur maps (grid %s, hair.npz %s)", group.shape, tuple(shape))
        return None
    return group, occupied, _hairless_mask(os.path.dirname(os.path.abspath(meta_path)), group.shape)


def _hairless_mask(meta_dir: str, shape) -> np.ndarray:
    """True where there must be no fur (nose leather, paw pads), in hair-array rows (row 0 = v 0). Needs AnimalLift
    assets/base_texture.png next to the meta file; without it nothing is masked."""
    from scipy import ndimage

    mask = np.zeros(shape, bool)
    tex = os.path.join(meta_dir, "base_texture.png")
    if not os.path.isfile(tex):
        return mask
    try:
        im = np.asarray(Image.open(tex).convert("RGB").resize((shape[1], shape[0]), Image.BILINEAR), np.float32) / 255
    except Exception as e:  # noqa: BLE001
        log.warning("base_texture.png not used, nose and paw pads keep their fur (%s: %s)", type(e).__name__, e)
        return mask
    dark = im @ np.array([0.299, 0.587, 0.114], np.float32) < 0.33
    boxes = np.zeros(shape, bool)
    sy, sx = shape[0] / 512.0, shape[1] / 512.0   # the boxes are in 512 x 512 template pixels
    for x0, y0, x1, y1 in _BARE_BOXES:
        boxes[int(y0 * sy):int(round(y1 * sy)), int(x0 * sx):int(round(x1 * sx))] = True
    img_mask = ndimage.binary_dilation(ndimage.binary_opening(dark & boxes, iterations=1), iterations=1)
    return img_mask[::-1]          # image rows (top = v 1) -> array rows (row 0 = v 0), like the hair arrays


def _from_guides(tip: np.ndarray, group: np.ndarray, occupied: np.ndarray,
                 hairless: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """AnimalLift only defines hair at its guide texels (guide_group_id_map >= 0: ~56k random texels, 21 %);
    Blender then grows child hairs between them. The other texels hold untrained values. Rebuild a smooth map
    from the guides only (Gaussian normalized convolution), inside the area the guides cover; 0 elsewhere
    (nose, eyes, pads, UV padding). Returns (tip vectors, region = guide group per texel, nearest guide; 255 = none)."""
    from scipy import ndimage

    guide = (group >= 0).astype(np.float64)
    sigma = 1.5
    den = ndimage.gaussian_filter(guide, sigma)
    out = np.zeros_like(tip)
    for k in range(3):
        out[..., k] = ndimage.gaussian_filter(tip[..., k] * guide, sigma) / np.maximum(den, 1e-6)
    covered = (ndimage.gaussian_filter(guide, 3.0) > 0.06) & occupied & ~hairless
    # shorten the fur toward hairless spots inside the body (nose, eyes, paw pads) — not at UV island borders
    bare = occupied & ~covered
    if bare.any():
        fade = np.clip(ndimage.distance_transform_edt(~bare) / 4.0, 0.0, 1.0)   # 0 at the spot, 1 after 4 texels
        out *= fade[..., None]
    out[~covered] = 0.0
    region = np.full(group.shape, 255, np.uint8)
    if covered.any():
        # pad 3 texels past the UV island borders with the nearest fur value, so bilinear sampling at seams
        # does not thin the fur along every seam
        dist, (py, px) = ndimage.distance_transform_edt(~covered, return_indices=True)
        pad = ~occupied & (dist <= 3)
        out[pad] = out[py[pad], px[pad]]
        if (group >= 0).any():
            _, (iy, ix) = ndimage.distance_transform_edt(group < 0, return_indices=True)
            nearest = np.clip(group[iy, ix], 0, 254)   # 255 stays "no fur"
            region = np.where(covered, nearest, 255).astype(np.uint8)
    return out, region


def _png(image: Image.Image) -> bytes:
    out = io.BytesIO()
    image.save(out, format="PNG")
    return out.getvalue()


def embed_hair(glb: bytes, length_png: bytes, flow_png: bytes, fur_length: float) -> bytes:
    """Adds the fur maps to a single-buffer GLB as the app reads them (RuntimeModelLoader.TryReadEmbeddedHair): two
    PNG images whose bufferViews sit in the binary chunk, and root extras.beside = {hairLengthImage, hairFlowImage,
    furLength}. No texture or material points at them, so texture 0 stays the body texture."""
    json_len = struct.unpack_from("<I", glb, 12)[0]
    gltf = json.loads(glb[20:20 + json_len])
    bin_at = 20 + json_len
    bin_len, kind = struct.unpack_from("<I4s", glb, bin_at)
    if kind != b"BIN\x00" or len(gltf.get("buffers", [])) != 1:
        raise ConversionError("cannot add fur maps: the GLB has no single binary buffer")
    binary = bytearray(glb[bin_at + 8:bin_at + 8 + bin_len])
    views, images = gltf.setdefault("bufferViews", []), gltf.setdefault("images", [])
    beside = {}
    for key, name, png in (("hairLengthImage", "hair_length", length_png), ("hairFlowImage", "hair_flow", flow_png)):
        binary += b"\0" * (-len(binary) % 4)
        views.append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(png)})
        binary += png
        images.append({"name": name, "mimeType": "image/png", "bufferView": len(views) - 1})
        beside[key] = len(images) - 1
    beside["furLength"] = fur_length
    binary += b"\0" * (-len(binary) % 4)
    gltf["buffers"][0]["byteLength"] = len(binary)
    extras = gltf.get("extras") if isinstance(gltf.get("extras"), dict) else {}
    extras["beside"] = beside
    gltf["extras"] = extras
    js = json.dumps(gltf, separators=(",", ":")).encode()
    js += b" " * (-len(js) % 4)
    return (struct.pack("<4sII", b"glTF", 2, 12 + 8 + len(js) + 8 + len(binary))
            + struct.pack("<I4s", len(js), b"JSON") + js + struct.pack("<I4s", len(binary), b"BIN\x00") + bytes(binary))


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
    ap.add_argument("--max-bytes", type=int, default=MAX_GLB_BYTES)
    ap.add_argument("--hair-meta", help="AnimalLift assets/hair_map_meta_uvanchor.npz: fur only where guide texels are "
                                        "(opt-in, needs scipy)")
    ap.add_argument("--pad-texture", action="store_true",
                    help="fill uv.png's green background with the nearest island colour, also texture_padded.png")
    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
    try:
        m = convert(a.obj, a.texture, a.out, a.metrics, a.hair, a.forward_axis, a.normalize_longest_axis,
                    a.max_triangles, a.max_texture, a.max_bytes, hair_meta=a.hair_meta, padded_texture=a.pad_texture)
    except ConversionError as e:
        print(f"CONVERSION_FAILED: {e}", file=sys.stderr)
        return 2
    print(json.dumps(m))
    return 0


if __name__ == "__main__":
    sys.exit(main())
