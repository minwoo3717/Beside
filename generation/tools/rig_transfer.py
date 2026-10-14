"""Give a freshly generated AnimalLift GLB the skeleton and animations of a rigged template.

  python tools/rig_transfer.py --base results/<jobId>/base.glb --texture work/<jobId>/raw/uv.png \
      --template rig/template-rigged.glb --out results/<jobId>/base.glb

Why this works without re-rigging: every AnimalLift output uses the same template topology (SMAL-style,
4310 vertices with UV seams, same order), and convert_asset.py normalizes it the same way. So per-vertex skin
weights carry over unchanged; only the joint positions have to follow the new body shape.

  1. skin weights, triangles, UVs, materials, animation clips: copied from the template
  2. vertex positions + normals: taken from the new base.glb (same vertex order — checked)
  3. joints: each joint is an affine combination of nearby template vertices (fit once on the template, exact
     under any affine change of the body), re-evaluated on the new vertices -> new bind pose + inverse binds
  4. texture: replaced by the new uv.png (same UV layout as the template)
  5. root translation keys (body bob / sitting down) scaled by the pelvis-height ratio

The template GLB is SMAL-derived (academic licence): keep it out of git, pass its path at runtime.
Exit code 2 = cannot transfer (different topology etc.); the caller should then keep the static GLB.
"""
from __future__ import annotations

import argparse
import json
import struct
import sys

import numpy as np

_COMP = {5126: np.float32, 5125: np.uint32, 5123: np.uint16, 5121: np.uint8}
_NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


class RigError(Exception):
    pass


class Glb:
    """Minimal glTF binary reader/writer (one buffer)."""

    def __init__(self, path: str):
        data = open(path, "rb").read()
        if not data:
            raise RigError(f"empty file (0 bytes): {path}")
        if data[:4] != b"glTF":
            raise RigError(f"not a GLB: {path}")
        jlen = struct.unpack_from("<I", data, 12)[0]
        self.json = json.loads(data[20:20 + jlen])
        off = 20 + jlen
        blen = struct.unpack_from("<I", data, off)[0]
        self.bin = bytearray(data[off + 8:off + 8 + blen])
        self.views = []   # per bufferView bytes, so views can change size
        for bv in self.json["bufferViews"]:
            s = bv.get("byteOffset", 0)
            self.views.append(bytes(self.bin[s:s + bv["byteLength"]]))

    def read(self, accessor: int) -> np.ndarray:
        a = self.json["accessors"][accessor]
        bv = self.json["bufferViews"][a["bufferView"]]
        dt = np.dtype(_COMP[a["componentType"]])
        n = _NCOMP[a["type"]]
        raw = self.views[a["bufferView"]]
        stride = bv.get("byteStride") or dt.itemsize * n
        start = a.get("byteOffset", 0)
        rows = [np.frombuffer(raw, dt, n, start + i * stride) for i in range(a["count"])] if stride != dt.itemsize * n \
            else [np.frombuffer(raw, dt, a["count"] * n, start).reshape(a["count"], n)]
        return np.vstack(rows) if stride != dt.itemsize * n else rows[0].copy()

    def write(self, accessor: int, values: np.ndarray) -> None:
        """Overwrite an accessor that owns a tightly packed bufferView (same count and type)."""
        a = self.json["accessors"][accessor]
        dt = np.dtype(_COMP[a["componentType"]])
        arr = np.ascontiguousarray(values, dtype=dt).reshape(a["count"], _NCOMP[a["type"]])
        view = bytearray(self.views[a["bufferView"]])
        start = a.get("byteOffset", 0)
        view[start:start + arr.nbytes] = arr.tobytes()
        self.views[a["bufferView"]] = bytes(view)
        if "min" in a and a["type"] == "VEC3":
            a["min"], a["max"] = arr.min(0).tolist(), arr.max(0).tolist()

    def save(self, path: str) -> int:
        out = bytearray()
        for bv, data in zip(self.json["bufferViews"], self.views):
            while len(out) % 4:
                out.append(0)
            bv["byteOffset"] = len(out)
            bv["byteLength"] = len(data)
            out += data
        while len(out) % 4:
            out.append(0)
        self.json["buffers"] = [{"byteLength": len(out)}]
        js = json.dumps(self.json, separators=(",", ":")).encode()
        js += b" " * ((4 - len(js) % 4) % 4)
        total = 12 + 8 + len(js) + 8 + len(out)
        blob = struct.pack("<4sII", b"glTF", 2, total) + struct.pack("<I4s", len(js), b"JSON") + js \
            + struct.pack("<I4s", len(out), b"BIN\x00") + bytes(out)
        with open(path, "wb") as f:
            f.write(blob)
        return len(blob)


def _local_matrix(node: dict) -> np.ndarray:
    if "matrix" in node:
        return np.array(node["matrix"], dtype=np.float64).reshape(4, 4).T
    t = np.array(node.get("translation", [0, 0, 0]), dtype=np.float64)
    x, y, z, w = node.get("rotation", [0, 0, 0, 1])
    s = np.array(node.get("scale", [1, 1, 1]), dtype=np.float64)
    r = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    m = np.eye(4)
    m[:3, :3] = r * s
    m[:3, 3] = t
    return m


def _fit_regressor(verts: np.ndarray, joint_pos: np.ndarray, weights: np.ndarray, jidx: np.ndarray,
                   k: int = 24, lam: float = 1e-7):
    """For each joint: indices + affine weights (sum 1) over nearby vertices reproducing the joint position."""
    reg = []
    infl = np.zeros((len(verts), len(joint_pos)))
    for c in range(4):
        np.add.at(infl, (np.arange(len(verts)), jidx[:, c]), weights[:, c])
    for j, p in enumerate(joint_pos):
        cand = np.where(infl[:, j] > 0.2)[0]
        d = np.linalg.norm(verts - p, axis=1)
        if len(cand) < 6:
            cand = np.argsort(d)[:k]
        else:
            cand = cand[np.argsort(d[cand])[:k]]
        V = verts[cand]                                   # (m,3)
        m = len(cand)
        # minimise |V^T a - p|^2 + lam |a - 1/m|^2  subject to sum(a) = 1   (KKT system)
        A = np.zeros((m + 1, m + 1))
        A[:m, :m] = V @ V.T + lam * np.eye(m)
        A[:m, m] = 1
        A[m, :m] = 1
        rhs = np.concatenate([V @ p + lam / m, [1.0]])
        a = np.linalg.solve(A, rhs)[:m]
        reg.append((cand, a))
    return reg


def transfer(base_glb: str, texture_png: str | None, template_glb: str, out_glb: str) -> dict:
    tpl = Glb(template_glb)
    new = Glb(base_glb)
    tj, nj = tpl.json, new.json
    if not tj.get("skins") or not tj.get("animations"):
        raise RigError("template has no skin/animations")
    tprim = tj["meshes"][0]["primitives"][0]
    nprim = nj["meshes"][0]["primitives"][0]

    t_pos = tpl.read(tprim["attributes"]["POSITION"]).astype(np.float64)
    n_pos = new.read(nprim["attributes"]["POSITION"]).astype(np.float64)
    n_nrm = new.read(nprim["attributes"]["NORMAL"]).astype(np.float64)
    if t_pos.shape != n_pos.shape:
        raise RigError(f"vertex count differs (template {len(t_pos)}, model {len(n_pos)}): not the same topology")
    if len(tpl.read(tprim["indices"])) != len(new.read(nprim["indices"])):
        raise RigError("triangle count differs: not the same topology")

    # ---- template skeleton (bind pose world matrices)
    skin = tj["skins"][0]
    joints = skin["joints"]
    nodes = tj["nodes"]
    parent = {c: i for i, n in enumerate(nodes) for c in n.get("children", [])}

    def world(i, cache={}):
        if i not in cache:
            m = _local_matrix(nodes[i])
            cache[i] = world(parent[i]) @ m if i in parent else m
        return cache[i]

    t_world = {j: world(j) for j in joints}
    t_jpos = np.array([t_world[j][:3, 3] for j in joints])

    w = tpl.read(tprim["attributes"]["WEIGHTS_0"]).astype(np.float64)
    ji = tpl.read(tprim["attributes"]["JOINTS_0"]).astype(np.int64)
    reg = _fit_regressor(t_pos, t_jpos, w, ji)
    fit_err = max(np.linalg.norm(a @ t_pos[c] - p) for (c, a), p in zip(reg, t_jpos))

    # ---- new joint positions (root joints stay put: the model origin is already the feet centre)
    n_jpos = np.array([a @ n_pos[c] for c, a in reg])
    for k, j in enumerate(joints):
        if j not in parent or parent[j] not in joints:
            n_jpos[k] = t_jpos[k]

    # ---- rewrite bind pose: keep template rotations, move translations
    n_world = {}
    for k, j in enumerate(joints):
        m = t_world[j].copy()
        m[:3, 3] = n_jpos[k]
        n_world[j] = m
    for j in joints:
        if j in parent and parent[j] in n_world:
            local = np.linalg.inv(n_world[parent[j]]) @ n_world[j]
            nodes[j]["translation"] = local[:3, 3].tolist()
            nodes[j].pop("matrix", None)
    ibm = np.array([np.linalg.inv(n_world[j]).T.reshape(16) for j in joints])   # column-major
    tpl.write(skin["inverseBindMatrices"], ibm)

    # ---- geometry + texture
    tpl.write(tprim["attributes"]["POSITION"], n_pos)
    tpl.write(tprim["attributes"]["NORMAL"], n_nrm)
    if texture_png:
        img = tj["images"][tj["textures"][tj["materials"][tprim["material"]]["pbrMetallicRoughness"]["baseColorTexture"]["index"]]["source"]]
        tpl.views[img["bufferView"]] = open(texture_png, "rb").read()
        img["mimeType"] = "image/png"

    # ---- root motion keys follow body size
    names = [n.get("name") for n in nodes]
    pelvis = names.index("Pelvis") if "Pelvis" in names else None
    ratio = 1.0
    if pelvis is not None and pelvis in joints:
        k = joints.index(pelvis)
        ratio = float(n_jpos[k][1] / max(t_jpos[k][1], 1e-6))
    for anim in tj["animations"]:
        for ch in anim["channels"]:
            if ch["target"]["path"] == "translation":
                acc = anim["samplers"][ch["sampler"]]["output"]
                tpl.write(acc, tpl.read(acc) * ratio)

    size = tpl.save(out_glb)
    return {"joints": len(joints), "fitErrorM": round(float(fit_err), 6), "heightRatio": round(ratio, 4),
            "clips": [a.get("name") for a in tj["animations"]], "bytes": size}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True, help="static GLB from convert_asset.py")
    ap.add_argument("--texture", help="new uv.png (default: keep the base GLB texture slot of the template)")
    ap.add_argument("--template", required=True, help="rigged template GLB (SMAL-derived, not in git)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    try:
        info = transfer(a.base, a.texture, a.template, a.out)
    except RigError as e:
        print(f"RIG_FAILED: {e}", file=sys.stderr)
        return 2
    print(json.dumps(info))
    return 0


if __name__ == "__main__":
    sys.exit(main())
