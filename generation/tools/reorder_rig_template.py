"""Renumber a rigged template's vertices to the vertex order convert_asset.py produces, once, offline.

  python tools/reorder_rig_template.py --template <outside git>/animallift_template_rigged.glb \
      --reference results/<jobId>/base.glb --out <outside git>/template-rigged.glb

Why: rig_transfer.py copies the template's triangles, UVs and skin weights and takes only the new vertex
positions, so it refuses a template whose vertex numbering differs from the base GLB (the vertex-order check,
2026-11-20). The SMAL-derived template made by rigging/tools/rig_animallift.py numbers the UV-seam vertices with
np.unique over (vertex, uv) pairs, while convert_asset.py loads the OBJ with trimesh, which numbers them in
another order. The mesh is the same (same faces in the same order, same UVs); only the vertex numbers differ.
This tool finds that renumbering from any converter output of the same topology and rewrites the template
(every per-vertex attribute, morph targets and the triangle indices). Skin, joints, clips, materials stay.

The template is SMAL-derived (academic licence): keep both files out of git.
Exit code 0 = written, 2 = the meshes do not match (different face order or topology); nothing is written then.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from rig_transfer import Glb, RigError, _triangle_set  # noqa: E402

UV_TOLERANCE = 1e-4


def _primitive(glb: Glb) -> dict:
    meshes = glb.json.get("meshes") or []
    if len(meshes) != 1 or len(meshes[0]["primitives"]) != 1:
        raise RigError("expected exactly one mesh with one primitive")
    return meshes[0]["primitives"][0]


def vertex_permutation(template: Glb, reference: Glb) -> np.ndarray:
    """perm[i] = the reference's number for template vertex i (corner by corner in the shared face order)."""
    tp, rp = _primitive(template), _primitive(reference)
    t_idx = template.read(tp["indices"]).astype(np.int64).reshape(-1, 3)
    r_idx = reference.read(rp["indices"]).astype(np.int64).reshape(-1, 3)
    n = template.json["accessors"][tp["attributes"]["POSITION"]]["count"]
    if reference.json["accessors"][rp["attributes"]["POSITION"]]["count"] != n:
        raise RigError("vertex counts differ")
    if t_idx.shape != r_idx.shape:
        raise RigError("triangle counts differ")
    perm = np.full(n, -1, np.int64)
    for a, b in zip(t_idx.reshape(-1), r_idx.reshape(-1)):
        if perm[a] == -1:
            perm[a] = b
        elif perm[a] != b:
            raise RigError("faces are not in the same order (one template vertex maps to two reference vertices)")
    if (perm < 0).any() or len(np.unique(perm)) != n:
        raise RigError("not a one-to-one renumbering (unused or merged vertices)")
    if "TEXCOORD_0" in tp["attributes"] and "TEXCOORD_0" in rp["attributes"]:
        t_uv = template.read(tp["attributes"]["TEXCOORD_0"]).astype(np.float64)
        r_uv = reference.read(rp["attributes"]["TEXCOORD_0"]).astype(np.float64)
        diff = float(np.abs(t_uv - r_uv[perm]).max())
        if diff > UV_TOLERANCE:
            raise RigError(f"UVs differ after renumbering (max {diff:.2e}): not the same UV layout")
    return perm


def _per_vertex_accessors(prim: dict) -> list[int]:
    ids = list(prim["attributes"].values())
    for target in prim.get("targets", []):
        ids += list(target.values())
    return ids


def reorder(template_path: str, reference_path: str, out_path: str) -> dict:
    template, reference = Glb(template_path), Glb(reference_path)
    perm = vertex_permutation(template, reference)
    prim = _primitive(template)
    accessors = template.json["accessors"]
    views = [accessors[i]["bufferView"] for i in _per_vertex_accessors(prim) + [prim["indices"]]]
    for i in _per_vertex_accessors(prim) + [prim["indices"]]:
        a = accessors[i]
        bv = template.json["bufferViews"][a["bufferView"]]
        if bv.get("byteStride") or views.count(a["bufferView"]) > 1 or "sparse" in a:
            raise RigError("interleaved, shared or sparse vertex data is not supported")
    inverse = np.empty_like(perm)
    inverse[perm] = np.arange(len(perm))          # new vertex j takes the data of template vertex inverse[j]
    for i in _per_vertex_accessors(prim):
        template.write(i, template.read(i)[inverse])
    faces = perm[template.read(prim["indices"]).astype(np.int64)]
    template.write(prim["indices"], faces)
    r_idx = reference.read(_primitive(reference)["indices"]).astype(np.int64).reshape(-1, 3)
    if not np.array_equal(_triangle_set(faces.reshape(-1, 3)), _triangle_set(r_idx)):
        raise RigError("renumbered triangles still differ from the reference")
    size = template.save(out_path)
    return {"vertices": int(len(perm)), "moved": int((perm != np.arange(len(perm))).sum()), "bytes": size}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--template", required=True, help="rigged template GLB (SMAL-derived, outside git)")
    ap.add_argument("--reference", required=True, help="any base.glb written by convert_asset.py (same topology)")
    ap.add_argument("--out", required=True, help="renumbered template GLB to write (outside git)")
    args = ap.parse_args(argv)
    try:
        info = reorder(args.template, args.reference, args.out)
    except RigError as e:
        print(f"REORDER_FAILED: {e}", file=sys.stderr)
        return 2
    print(f"wrote {args.out}: {info['bytes']} bytes, {info['moved']} of {info['vertices']} vertices renumbered")
    return 0


if __name__ == "__main__":
    sys.exit(main())
