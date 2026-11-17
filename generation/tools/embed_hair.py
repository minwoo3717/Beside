"""Put the per-dog hair maps (and mesh tangents for the hair direction) (hair_length.png / hair_flow.png from convert_asset.py) inside the final GLB.

  python tools/embed_hair.py --glb results/<jobId>/base.glb --maps results/<jobId>/hair_maps.json

Run it LAST (after rig_transfer.py, which rebuilds the GLB from the template and would drop them).
The app (RuntimeModelLoader.TryReadEmbeddedHair) reads:
    extras.beside = {"hairLengthImage": <image index>, "hairFlowImage": <image index>, "furLength": <GLB units>}
The two images live in the binary chunk (bufferView + mimeType) and are not used by any material, so
glTF viewers ignore them; the app gives them to its fur shaders. Re-running replaces the previous maps.
"""
from __future__ import annotations

import argparse
import json
import os
import struct
import sys

import numpy as np


class EmbedError(Exception):
    pass


def _read(path: str):
    data = open(path, "rb").read()
    if len(data) < 20 or data[:4] != b"glTF":
        raise EmbedError(f"not a GLB: {path}")
    jlen = struct.unpack_from("<I", data, 12)[0]
    gltf = json.loads(data[20:20 + jlen])
    off = 20 + jlen
    if off + 8 <= len(data):
        blen, btype = struct.unpack_from("<I4s", data, off)
        binary = bytearray(data[off + 8:off + 8 + blen]) if btype == b"BIN\x00" else bytearray()
    else:
        binary = bytearray()
    return gltf, binary


def _write(path: str, gltf: dict, binary: bytearray) -> int:
    while len(binary) % 4:
        binary.append(0)
    if gltf.get("buffers"):
        gltf["buffers"][0]["byteLength"] = len(binary)
    else:
        gltf["buffers"] = [{"byteLength": len(binary)}]
    js = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    js += b" " * ((4 - len(js) % 4) % 4)
    blob = struct.pack("<4sII", b"glTF", 2, 12 + 8 + len(js) + 8 + len(binary)) \
        + struct.pack("<I4s", len(js), b"JSON") + js \
        + struct.pack("<I4s", len(binary), b"BIN\x00") + bytes(binary)
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(blob)
    os.replace(tmp, path)
    return len(blob)


_COMP = {5126: np.float32, 5125: np.uint32, 5123: np.uint16, 5121: np.uint8}
_NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}


def _accessor(gltf: dict, binary: bytearray, idx: int) -> np.ndarray:
    a = gltf["accessors"][idx]
    bv = gltf["bufferViews"][a["bufferView"]]
    dt = np.dtype(_COMP[a["componentType"]])
    n = _NCOMP[a["type"]]
    start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    stride = bv.get("byteStride") or dt.itemsize * n
    raw = np.frombuffer(bytes(binary), np.uint8)
    rows = np.lib.stride_tricks.as_strided(raw[start:], shape=(a["count"], dt.itemsize * n), strides=(stride, 1))
    return rows.copy().view(dt).reshape(a["count"], n).astype(np.float64 if dt == np.float32 else np.int64)


def add_tangents(gltf: dict, binary: bytearray) -> int:
    """Adds a TANGENT attribute (glTF convention: xyz along +u, w = handedness) to every primitive that lacks one.
    The fur shaders comb the hair along (tangent, bitangent); without tangents they guess a direction.
    Returns the number of primitives changed."""
    changed = 0
    for mesh in gltf.get("meshes", []):
        for prim in mesh["primitives"]:
            at = prim["attributes"]
            if "TANGENT" in at or not all(k in at for k in ("POSITION", "NORMAL", "TEXCOORD_0")):
                continue
            pos = _accessor(gltf, binary, at["POSITION"])
            nrm = _accessor(gltf, binary, at["NORMAL"])
            uv = _accessor(gltf, binary, at["TEXCOORD_0"])
            if "indices" in prim:
                tri = _accessor(gltf, binary, prim["indices"]).reshape(-1, 3)
            else:
                tri = np.arange(len(pos)).reshape(-1, 3)
            p0, p1, p2 = pos[tri[:, 0]], pos[tri[:, 1]], pos[tri[:, 2]]
            w0, w1, w2 = uv[tri[:, 0]], uv[tri[:, 1]], uv[tri[:, 2]]
            e1, e2 = p1 - p0, p2 - p0
            d1, d2 = w1 - w0, w2 - w0
            r = d1[:, 0] * d2[:, 1] - d2[:, 0] * d1[:, 1]
            r = np.where(np.abs(r) < 1e-12, 1e-12, r)[:, None]
            sdir = (e1 * d2[:, 1:2] - e2 * d1[:, 1:2]) / r      # dP/du
            tdir = (e2 * d1[:, 0:1] - e1 * d2[:, 0:1]) / r      # dP/dv (glTF texcoord space)
            T = np.zeros_like(pos)
            B = np.zeros_like(pos)
            for k in range(3):
                np.add.at(T, tri[:, k], sdir)
                np.add.at(B, tri[:, k], tdir)
            n = nrm / (np.linalg.norm(nrm, axis=1, keepdims=True) + 1e-12)
            t = T - n * np.sum(n * T, axis=1, keepdims=True)                 # Gram-Schmidt
            bad = np.linalg.norm(t, axis=1) < 1e-9
            if bad.any():
                alt = np.cross(n[bad], np.where(np.abs(n[bad, 1:2]) < 0.99, [[0, 1, 0]], [[1, 0, 0]]))
                t[bad] = alt
            t /= np.linalg.norm(t, axis=1, keepdims=True) + 1e-12
            w = np.where(np.sum(np.cross(n, t) * B, axis=1) < 0, -1.0, 1.0)
            tan = np.concatenate([t, w[:, None]], axis=1).astype(np.float32)

            while len(binary) % 4:
                binary.append(0)
            gltf["bufferViews"].append({"buffer": 0, "byteOffset": len(binary), "byteLength": tan.nbytes, "target": 34962})
            binary.extend(tan.tobytes())
            gltf["accessors"].append({"bufferView": len(gltf["bufferViews"]) - 1, "componentType": 5126,
                                      "count": int(len(tan)), "type": "VEC4"})
            at["TANGENT"] = len(gltf["accessors"]) - 1
            changed += 1
    return changed


def embed(glb_path: str, length_png: str, flow_png: str | None, fur_length: float) -> dict:
    """Appends the PNGs to the GLB in place. Returns {"bytes", "furLength", "lengthBytes", "flowBytes"}."""
    gltf, binary = _read(glb_path)
    old = (gltf.get("extras") or {}).get("beside")
    if old:
        # an earlier run: drop its images from the list only if they are the last ones (normal case)
        idx = sorted(i for i in (old.get("hairLengthImage"), old.get("hairFlowImage")) if i is not None)
        imgs = gltf.get("images", [])
        if idx and idx[-1] == len(imgs) - 1 and all(imgs[i].get("name", "").startswith("beside_hair") for i in idx):
            views = gltf.get("bufferViews", [])
            vidx = sorted(imgs[i]["bufferView"] for i in idx)
            for i in reversed(idx):
                imgs.pop(i)
            if vidx == list(range(len(views) - len(vidx), len(views))):   # their data sits at the end: cut it off
                cut = views[vidx[0]]["byteOffset"]
                del views[vidx[0]:]
                del binary[cut:]

    def add(png_path: str, name: str) -> int:
        png = open(png_path, "rb").read()
        if png[:8] != b"\x89PNG\r\n\x1a\n":
            raise EmbedError(f"not a PNG: {png_path}")
        while len(binary) % 4:
            binary.append(0)
        gltf.setdefault("bufferViews", []).append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(png)})
        binary.extend(png)
        gltf.setdefault("images", []).append({"name": name, "mimeType": "image/png", "bufferView": len(gltf["bufferViews"]) - 1})
        return len(gltf["images"]) - 1

    tangents = add_tangents(gltf, binary)
    li = add(length_png, "beside_hair_length")
    beside = {"hairLengthImage": li, "furLength": round(float(fur_length), 6)}
    if flow_png:
        beside["hairFlowImage"] = add(flow_png, "beside_hair_flow")
    gltf.setdefault("extras", {})["beside"] = beside
    size = _write(glb_path, gltf, binary)
    return {"bytes": size, "furLength": beside["furLength"], "tangents": tangents,
            "lengthBytes": os.path.getsize(length_png), "flowBytes": os.path.getsize(flow_png) if flow_png else 0}


def embed_from_json(glb_path: str, maps_json: str) -> dict:
    """Uses hair_maps.json written by convert_asset.py (paths relative to the json)."""
    meta = json.load(open(maps_json))
    base = os.path.dirname(os.path.abspath(maps_json))
    length = os.path.join(base, meta["lengthMap"])
    flow = os.path.join(base, meta["flowMap"]) if meta.get("flowMap") else None
    if flow and not os.path.isfile(flow):
        flow = None
    return embed(glb_path, length, flow, meta["furLength"])


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--glb", required=True)
    ap.add_argument("--maps", required=True, help="hair_maps.json from convert_asset.py")
    a = ap.parse_args(argv)
    try:
        print(json.dumps(embed_from_json(a.glb, a.maps)))
    except (EmbedError, OSError, KeyError, ValueError) as e:
        print(f"EMBED_FAILED: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
