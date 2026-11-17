"""Load-test input files, generated deterministically with scripts/perf/fixtures.py (standard library only).

    python scripts/load/make_fixtures.py                 # -> scripts/load/out/fixtures/ (gitignored)
    python scripts/load/make_fixtures.py --out /tmp/fx

  photo-400k.png  ~0.40 MB noise PNG: the 0.4 MB phone photo of docs/perf/load/TRAFFIC_MODEL.md §2 (2 per job =
                  0.8 MB)                                                                         k6 PHOTOS default
  photo-5mb.png   ~4.99 MB noise PNG: under the 5 MiB per-photo limit; 4 of them stay under 20 MiB  upload.js SIZE=large
  glb-1.5mb.glb   1,500,000 B synthetic glTF 2.0: the asset the mock (A series) and the inference stub (G/F series)
                  serve (the repository's sample-dog.glb is 0 B, which would make A4 measure nothing). It is the one
                  load-test GLB: byte-identical to scripts/perf fixtures glb_synthetic(1_500_000, seed=14), the
                  /tmp/lt-sample-1500000.glb of deploy/loadtest/README.md, sha256 03a0c8f2b05aee20...2fc0ee9

Noise does not compress, so the upload sizes are what they say. The server checks only the image signature
(upload.verify-magic), so these are valid uploads. manifest.json lists bytes and SHA-256 for run.json.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PERF_FIXTURES = HERE.parent / "perf" / "fixtures.py"

# name -> (generator, arguments). The photo seeds differ from scripts/perf so they are not confused with its cache;
# the GLB deliberately uses scripts/perf's glb_sample arguments (seed 14) so there is exactly one load-test GLB.
FILES = {
    "photo-400k.png": ("png_noise", {"width": 365, "height": 365, "seed": 21}),
    "photo-5mb.png": ("png_noise", {"width": 1290, "height": 1290, "seed": 22}),
    "glb-1.5mb.glb": ("glb_synthetic", {"total_bytes": 1_500_000, "seed": 14}),
}


def perf_fixtures():
    spec = importlib.util.spec_from_file_location("beside_perf_fixtures", PERF_FIXTURES)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def make(out_dir) -> dict:
    gen = perf_fixtures()
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    manifest = {"generator": "scripts/load/make_fixtures.py (scripts/perf/fixtures.py v%d)" % gen.GENERATOR_VERSION, "files": {}}
    for name, (generator, args) in FILES.items():
        path = out / name
        data = gen.GENERATORS[generator](**args)
        digest = hashlib.sha256(data).hexdigest()
        if not (path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == digest):
            path.write_bytes(data)
        manifest["files"][name] = {"bytes": len(data), "sha256": digest, "generator": generator, "args": args}
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default=str(HERE / "out" / "fixtures"))
    args = ap.parse_args(argv)
    manifest = make(args.out)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    for name, info in manifest["files"].items():
        print(f"{name:16s} {info['bytes']:>10,d} B  sha256 {info['sha256'][:16]}  -> {Path(args.out) / name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
