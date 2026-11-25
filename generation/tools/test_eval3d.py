"""Tests of the 3D A/B evaluation tools (eval_arms, eval_metrics, eval_board). No torch, no GPU, no trimesh:
  python -I -m unittest discover -s generation/tools -p "test_eval3d.py"
numpy is required; the tests that write or read images are skipped without Pillow."""
from __future__ import annotations

import contextlib
import csv
import importlib
import inspect
import io
import json
import math
import struct
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import eval_arms  # noqa: E402
import eval_metrics as em  # noqa: E402

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    Image = None

needs_pil = unittest.skipUnless(Image is not None, "Pillow not installed")

# Sharma, Wu & Dalal (2005), "The CIEDE2000 color-difference formula: implementation notes, supplementary test data
# and mathematical observations", Table 1: (L1, a1, b1), (L2, a2, b2), dE00. Pairs 1, 4, 7, 9-16 (hue wrap and mean
# hue branches), 17, 25, 33, 34.
SHARMA = [
    ((50.0000, 2.6772, -79.7751), (50.0000, 0.0000, -82.7485), 2.0425),
    ((50.0000, -1.3802, -84.2814), (50.0000, 0.0000, -82.7485), 1.0000),
    ((50.0000, 0.0000, 0.0000), (50.0000, -1.0000, 2.0000), 2.3669),
    ((50.0000, 2.4900, -0.0010), (50.0000, -2.4900, 0.0009), 7.1792),
    ((50.0000, 2.4900, -0.0010), (50.0000, -2.4900, 0.0010), 7.1792),
    ((50.0000, 2.4900, -0.0010), (50.0000, -2.4900, 0.0011), 7.2195),
    ((50.0000, 2.4900, -0.0010), (50.0000, -2.4900, 0.0012), 7.2195),
    ((50.0000, -0.0010, 2.4900), (50.0000, 0.0009, -2.4900), 4.8045),
    ((50.0000, -0.0010, 2.4900), (50.0000, 0.0010, -2.4900), 4.8045),
    ((50.0000, -0.0010, 2.4900), (50.0000, 0.0011, -2.4900), 4.7461),
    ((50.0000, 2.5000, 0.0000), (50.0000, 0.0000, -2.5000), 4.3065),
    ((50.0000, 2.5000, 0.0000), (73.0000, 25.0000, -18.0000), 27.1492),
    ((60.2574, -34.0099, 36.2677), (60.4626, -34.1751, 39.4387), 1.2644),
    ((6.7747, -0.2908, -2.4247), (5.8714, -0.0985, -2.2286), 0.6377),
    ((2.0776, 0.0795, -1.1350), (0.9033, -0.0636, -0.5514), 0.9082),
]

FUR = (0.6, 0.4, 0.2)
GREEN = (0.0, 1.0, 0.0)


class ColorTest(unittest.TestCase):
    def test_ciede2000_matches_sharma_table(self):
        for lab1, lab2, expected in SHARMA:
            with self.subTest(lab1=lab1, lab2=lab2):
                self.assertAlmostEqual(em.ciede2000(lab1, lab2), expected, places=4)
                self.assertAlmostEqual(em.ciede2000(lab2, lab1), expected, places=4)   # symmetric

    def test_ciede2000_broadcasts(self):
        got = em.ciede2000(np.array([r[0] for r in SHARMA]), np.array([r[1] for r in SHARMA]))
        np.testing.assert_allclose(got, [r[2] for r in SHARMA], atol=1e-4)
        self.assertEqual(em.ciede2000((50, 10, 10), (50, 10, 10)), 0.0)

    def test_srgb_to_lab_reference_colours(self):
        # D65 sRGB references (Lindbloom / CIE): white, black, primaries, mid grey
        cases = [((1, 1, 1), (100.0, 0.0, 0.0)), ((0, 0, 0), (0.0, 0.0, 0.0)),
                 ((1, 0, 0), (53.2408, 80.0925, 67.2032)), ((0, 1, 0), (87.7347, -86.1827, 83.1793)),
                 ((0, 0, 1), (32.2970, 79.1875, -107.8602)), ((128 / 255,) * 3, (53.5850, 0.0, 0.0))]
        for rgb, lab in cases:
            with self.subTest(rgb=rgb):
                np.testing.assert_allclose(em.srgb_to_lab(rgb), lab, atol=0.02)

    def test_hue(self):
        self.assertAlmostEqual(em.hue_deg((50, 0, 10)), 90.0)
        self.assertAlmostEqual(em.hue_deg((50, 0, -10)), 270.0)
        self.assertAlmostEqual(em.hue_diff(350.0, 10.0), 20.0)
        self.assertAlmostEqual(em.hue_diff(10.0, 190.0), 180.0)


def _texture(size: int, island_cols: int, fill=GREEN) -> np.ndarray:
    """size x size, fur in columns [0, island_cols), `fill` elsewhere (green = AnimalLift background)."""
    t = np.empty((size, size, 3))
    t[:] = fill
    t[:, :island_cols] = FUR
    return t


class TextureTest(unittest.TestCase):
    def test_green_test_is_convert_assets(self):
        """eval_metrics.green_mask must be convert_asset.pad_texture's test (read as text: no trimesh needed)."""
        self.assertIn(em.GREEN_TEST, (HERE / "convert_asset.py").read_text(encoding="utf-8"))
        self.assertIn(em.GREEN_TEST, inspect.getsource(em.green_mask))

    def test_island_mask(self):
        t = _texture(16, 9)
        t[0, 15] = (0.1, 0.7, 0.2)        # noisy decoded green still counts as background
        t[0, 0] = (0.3, 0.6, 0.3)         # a greenish fur texel does not
        mask = em.green_mask(t)
        self.assertTrue(mask[:, 9:].all())
        self.assertFalse(mask[:, :9].any())

    def test_box_downscale(self):
        a = np.arange(16, dtype=float).reshape(4, 4)
        np.testing.assert_allclose(em.box_downscale(a, 2), [[2.5, 4.5], [10.5, 12.5]])
        self.assertEqual(em.box_downscale(np.zeros((9, 9, 3)), 4).shape, (2, 2, 3))   # ragged edge dropped

    def test_erode_dilate(self):
        m = np.zeros((7, 7), bool)
        m[1:6, 1:6] = True
        self.assertEqual(int(em.erode(m, 1).sum()), 9)
        self.assertEqual(int(em.erode(m, 3).sum()), 0)
        self.assertEqual(int(em.dilate(np.eye(1, dtype=bool), 1).sum()), 1)
        one = np.zeros((9, 9), bool)
        one[4, 4] = True
        self.assertEqual(int(em.dilate(one, 2).sum()), 25)   # 8-neighbour: Chebyshev radius 2

    def test_seam_green_ratio_by_hand(self):
        # 16 px wide, island = 9 columns: at mip1 column 4 is half fur, half green -> (0.3, 0.7, 0.1), green.
        # Island at mip1 = columns 0-4 (coverage >= 0.5), border band (<= 4 from background col 5) = columns 1-4,
        # 4 x 8 = 32 texels of which column 4 (8 texels) is green -> 0.25.
        tex = _texture(16, 9)
        island = ~em.green_mask(tex)
        self.assertAlmostEqual(em.seam_green_ratio(tex, island, 2), 0.25)
        padded = _texture(16, 9, fill=FUR)                 # what pad_texture does: background = nearest island colour
        self.assertEqual(em.seam_green_ratio(padded, island, 2), 0.0)
        for factor in (2, 4, 8):
            self.assertLessEqual(em.seam_green_ratio(padded, island, factor), em.seam_green_ratio(tex, island, factor))

    def test_aligned_island_has_no_bleed(self):
        tex = _texture(64, 32)
        island = ~em.green_mask(tex)
        for factor in (2, 4, 8):
            self.assertEqual(em.seam_green_ratio(tex, island, factor), 0.0)

    def test_no_border_is_nan(self):
        tex = _texture(16, 16)
        self.assertTrue(math.isnan(em.seam_green_ratio(tex, ~em.green_mask(tex), 2)))


def _write_obj(path: Path, verts) -> None:
    lines = [f"v {x} {y} {z}" for x, y, z in verts]
    lines += ["vt 0 0", "vt 1 0", "vt 0 1", "vn 0 0 1", "f 1/1 2/2 3/3"]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


CUBE = [(x, y, z) for x in (0, 1) for y in (0, 1) for z in (0, 1)]


class ShapeTest(unittest.TestCase):
    def test_shape_delta_on_tiny_objs(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, moved = Path(tmp, "base.obj"), Path(tmp, "moved.obj")
            _write_obj(base, CUBE)
            _write_obj(moved, [(x + 0.1, y, z) for x, y, z in CUBE])
            vb, vm = em.read_obj_vertices(base), em.read_obj_vertices(moved)
            self.assertEqual(vb.shape, (8, 3))                       # vt / vn / f lines ignored
            self.assertAlmostEqual(em.shape_delta_pct(vm, vb), 100 * 0.1 / math.sqrt(3), places=6)
            self.assertEqual(em.shape_delta_pct(vb, vb), 0.0)
            self.assertTrue(math.isnan(em.shape_delta_pct(vb[:7], vb)))   # other topology: no answer


class StatsTest(unittest.TestCase):
    def test_sign_test(self):
        self.assertAlmostEqual(em.sign_test(10, 0), 2 / 1024)
        self.assertAlmostEqual(em.sign_test(8, 2), 2 * 56 / 1024)
        self.assertEqual(em.sign_test(3, 3), 1.0)
        self.assertEqual(em.sign_test(0, 0), 1.0)

    def test_paired_counts_with_tolerance(self):
        base = {"a": 5.0, "b": 5.0, "c": 5.0, "d": 5.0, "e": None}
        arm = {"a": 3.0, "b": 4.5, "c": 7.0, "d": 1.0, "e": 1.0}
        p = em.paired(arm, base, lower_better=True, tol=1.0)
        self.assertEqual((p["n"], p["improve"], p["same"], p["worse"]), (4, 2, 1, 1))
        self.assertAlmostEqual(p["meanDiff"], (-2 - 0.5 + 2 - 4) / 4)

    def test_same_dog_id(self):
        labs = {"coco_1": np.array([50, 20, 30]), "coco_2": np.array([51, 21, 29]),
                "bori_1": np.array([90, 0, 5]), "bori_2": np.array([60, 10, 20]), "dog": np.array([20, 0, 0])}
        dogs = {p: eval_arms.dog_of(p) for p in labs}
        ids, chance, chance_nn = em.same_dog_id(labs, dogs)
        self.assertEqual(ids, {"coco_1": 1, "coco_2": 1, "bori_1": 1, "bori_2": 0})   # bori_2 is nearer coco
        self.assertNotIn("dog", ids)                                # its only photo: cannot be asked
        self.assertAlmostEqual(chance, 1 / 3)
        self.assertAlmostEqual(chance_nn, 1 / 4)


class EvalArmsTest(unittest.TestCase):
    def test_imports_without_torch(self):
        mod = importlib.reload(eval_arms)
        self.assertNotIn("torch", sys.modules)
        self.assertNotIn("trimesh", sys.modules)
        self.assertTrue(callable(mod.load_runner))

    def test_names(self):
        self.assertEqual(eval_arms.photo_name("03_coco_2"), "coco_2")
        self.assertIsNone(eval_arms.photo_name("_warmup"))
        self.assertEqual(eval_arms.dog_of("coco_2"), "coco")
        self.assertEqual(eval_arms.dog_of("dog-single-black"), "dog-single-black")
        self.assertEqual(eval_arms.split_variant("remove+pad"), ("remove", True))
        self.assertEqual(eval_arms.variant_name("keep", False), "keep")

    def _photos_dir(self, root: Path) -> Path:
        d = root / "pre"
        names = ["bori_1", "coco_1", "dog-blurry"]
        for i, n in enumerate(names, start=1):
            f = d / f"{i:02d}_{n}"
            f.mkdir(parents=True)
            for arm, file in eval_arms.ARM_INPUTS.items():
                if not (n == "coco_1" and arm == "keep"):          # one missing input: crop fell back
                    (f / file).write_bytes(b"x")
        with open(d / "report.csv", "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            w.writerow(["file", "width", "decision"])
            w.writerows([["bori_1.jpg", 1, "PASS"], ["coco_1.jpg", 1, "PASS"], ["dog-blurry.jpg", 1, "IMAGE_TOO_BLURRY"]])
        return d

    def test_find_photos_skips_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = self._photos_dir(Path(tmp))
            logs = []
            photos = eval_arms.find_photos(d, log=logs.append)
            self.assertEqual([p.name for p in photos], ["bori_1", "coco_1"])
            self.assertIn("IMAGE_TOO_BLURRY", logs[0])
            self.assertEqual(len(eval_arms.find_photos(d, include_refused=True)), 3)

    def test_run_eval_and_csv_with_fake_runner(self):
        class FakeRunner:
            version, model_params = "fake@0:ckpt", 123

            def __init__(self):
                self.calls = []

            def predict(self, image_path, out_dir):
                self.calls.append(image_path)
                out = Path(out_dir)
                out.mkdir(parents=True, exist_ok=True)
                (out / "mesh.obj").write_text("v 0 0 0\n")
                (out / "uv.png").write_bytes(b"png")
                (out / "hair.npz").write_bytes(b"npz")
                return type("P", (), {"mesh_obj": out / "mesh.obj", "uv_png": out / "uv.png",
                                      "hair_npz": out / "hair.npz", "infer_ms": 4000, "gpu_peak_mb": 5120})()

        converted = []

        def fake_convert(obj, tex, out, metrics, hair_npz=None, forward_axis="+x", padded_texture=False):
            converted.append((out, padded_texture, hair_npz is not None, forward_axis))
            Path(out).write_bytes(b"glTF")
            m = {"vertices": 4310, "triangles": 7774, "textureSize": 1024,
                 "bytes": 1_800_000 if padded_texture else 2_000_000, "convertMs": 900}
            Path(metrics).write_text(json.dumps(m))
            return m

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            photos = eval_arms.find_photos(self._photos_dir(root), log=lambda *_: None)
            out = root / "eval"
            runner = FakeRunner()
            rows = eval_arms.run_eval(photos, ["off", "keep", "remove"], out, runner, fake_convert, log=lambda *_: None)
            self.assertEqual(len(rows), 2 * 3 * 2)                   # photos x arms x pad
            self.assertEqual(len(runner.calls), 1 + 5)               # warm-up + 5 inputs that exist
            self.assertEqual(len(converted), 10)
            self.assertTrue(all(c[2] and c[3] == "+x" for c in converted))   # fur maps in, head axis as production
            self.assertTrue((out / "remove+pad" / "bori_1" / "base.glb").is_file())
            self.assertTrue((out / "remove" / "bori_1" / "base.glb").is_file())
            missing = [r for r in rows if r.get("error")]
            self.assertEqual({(r["photo"], r["arm"]) for r in missing}, {("coco_1", "keep")})

            eval_arms.write_runs_csv(out / "runs.csv", rows)
            with open(out / "runs.csv", newline="", encoding="utf-8") as fh:
                table = list(csv.DictReader(fh))
            wanted = ["photo", "dog", "arm", "pad", "inferMs", "gpuPeakMB", "convertMs", "outputVertices",
                      "outputTriangles", "textureSize", "glbBytes"]
            self.assertEqual(list(table[0].keys())[:len(wanted)], wanted)
            pad_row = next(r for r in table if r["photo"] == "bori_1" and r["arm"] == "remove" and r["pad"] == "1")
            self.assertEqual((pad_row["inferMs"], pad_row["glbBytes"], pad_row["variant"]), ("4000", "1800000", "remove+pad"))

            # a rerun reuses every prediction and conversion (resume after a cut-off session)
            runner2 = FakeRunner()
            converted.clear()
            rows2 = eval_arms.run_eval(photos, ["off", "keep", "remove"], out, runner2, fake_convert, log=lambda *_: None)
            self.assertEqual(runner2.calls, [])
            self.assertEqual(converted, [])
            self.assertEqual([r.get("glbBytes") for r in rows2], [r.get("glbBytes") for r in rows])


# --------------------------------------------------------------------------- image fixtures (Pillow)


def _png(arr01: np.ndarray, mode="RGB") -> bytes:
    buf = io.BytesIO()
    Image.fromarray((np.clip(arr01, 0, 1) * 255 + 0.5).astype(np.uint8), mode).save(buf, "PNG")
    return buf.getvalue()


def _glb(png: bytes) -> bytes:
    """A minimal GLB whose first material's base colour is `png` (enough for embedded_base_color)."""
    gltf = {"asset": {"version": "2.0"}, "buffers": [{"byteLength": len(png)}],
            "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": len(png)}],
            "images": [{"mimeType": "image/png", "bufferView": 0}], "textures": [{"source": 0}],
            "materials": [{"pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}}]}
    js = json.dumps(gltf).encode()
    js += b" " * (-len(js) % 4)
    binary = png + b"\0" * (-len(png) % 4)
    return (struct.pack("<4sII", b"glTF", 2, 12 + 8 + len(js) + 8 + len(binary))
            + struct.pack("<I4s", len(js), b"JSON") + js + struct.pack("<I4s", len(binary), b"BIN\x00") + binary)


def _fake_eval(root: Path) -> tuple[Path, Path]:
    """photos (preprocessBatch layout) and eval output for 3 photos of 2 dogs x arms off, remove, remove+pad."""
    colours = {"coco_1": FUR, "coco_2": FUR, "bori_1": (0.9, 0.9, 0.85)}
    pre, ev = root / "pre", root / "eval"
    for i, (photo, colour) in enumerate(sorted(colours.items()), start=1):
        f = pre / f"{i:02d}_{photo}"
        f.mkdir(parents=True)
        cut = np.zeros((64, 64, 4))
        cut[16:48, 16:48, :3] = colour
        cut[16:48, 16:48, 3] = 1.0
        cut[16:48, 16, :3] = (0, 0, 1)                    # a blue rim, removed by the 3 px erosion
        (f / "cutout.png").write_bytes(_png(cut, "RGBA"))
        orig = np.ones((80, 120, 3)) * 0.5
        (f / "original.jpg").write_bytes(_png(orig))     # PNG bytes under .jpg: Pillow reads by content
        for arm, tint in (("off", 0.5), ("remove", 0.0)):
            tex = _texture(32, 18)                          # border at 18: half-green texels at mip2 (4 x 4 boxes)
            tex[:, :18] = np.array(colour) * (1 - tint) + 0.5 * tint   # off: background grey mixed into the fur
            d = ev / arm / photo
            d.mkdir(parents=True)
            (d / "uv.png").write_bytes(_png(tex))
            (d / "base.glb").write_bytes(_glb(_png(tex)))
            _write_obj(d / "mesh.obj", [(x + (0.05 if arm == "off" else 0), y, z) for x, y, z in CUBE])
            if arm == "remove":
                padded = tex.copy()
                padded[:, 18:] = padded[:, 17:18]           # background = nearest island colour
                p = ev / "remove+pad" / photo
                p.mkdir(parents=True)
                (p / "base.glb").write_bytes(_glb(_png(padded)))
    return pre, ev


@needs_pil
class MetricsFilesTest(unittest.TestCase):
    def test_embedded_texture_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            tex = _texture(8, 3)
            path = Path(tmp, "a.glb")
            path.write_bytes(_glb(_png(tex)))
            got = np.asarray(em.embedded_base_color(path), dtype=float) / 255
            np.testing.assert_allclose(got, tex, atol=1 / 255)

    def test_metrics_end_to_end(self):
        with tempfile.TemporaryDirectory() as tmp:
            pre, ev = _fake_eval(Path(tmp))
            self.assertEqual(em.find_variants(ev), ["off", "remove", "remove+pad"])
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(em.main(["--eval-dir", str(ev), "--photos-dir", str(pre)]), 0)
            with open(ev / "metrics" / "metrics.csv", newline="", encoding="utf-8") as fh:
                rows = {(r["photo"], r["arm"]): r for r in csv.DictReader(fh)}
            self.assertEqual(len(rows), 9)
            self.assertFalse(any(r["error"] for r in rows.values()))
            remove, padded, off = rows[("coco_1", "remove")], rows[("coco_1", "remove+pad")], rows[("coco_1", "off")]
            self.assertLess(float(remove["furColorDeltaE"]), 1.0)          # texture fur = photo fur (rim eroded)
            self.assertGreater(float(off["furColorDeltaE"]), 5.0)          # grey mixed in
            self.assertGreater(float(remove["seamGreenRatio@mip2"]), 0.0)
            self.assertEqual(float(padded["seamGreenRatio@mip2"]), 0.0)
            self.assertEqual(float(remove["shapeDeltaPct"]), 0.0)
            self.assertAlmostEqual(float(off["shapeDeltaPct"]), 100 * 0.05 / math.sqrt(3), places=4)
            self.assertEqual(remove["sameDogId"], "1")                      # coco_2 is the nearest
            self.assertEqual(rows[("bori_1", "remove")]["sameDogId"], "")  # bori has one photo
            md = (ev / "metrics" / "summary.md").read_text(encoding="utf-8")
            self.assertIn("remove (baseline)", md)
            with open(ev / "metrics" / "summary.csv", newline="", encoding="utf-8") as fh:
                summary = list(csv.DictReader(fh))
            seam = next(s for s in summary if s["section"] == "paired" and s["arm"] == "remove+pad"
                        and s["metric"] == "seamGreenRatio@mip2")
            self.assertEqual((seam["n"], seam["improve"], seam["worse"]), ("3", "3", "0"))
            self.assertAlmostEqual(float(seam["signP"]), 0.25)


@needs_pil
class BoardTest(unittest.TestCase):
    def test_build_is_blind_and_deterministic(self):
        import eval_board as eb
        with tempfile.TemporaryDirectory() as tmp:
            pre, ev = _fake_eval(Path(tmp))
            arms = ["off", "remove", "remove+pad"]
            a = eb.build(ev, pre, Path(tmp, "b1"), arms, seed=7, log=lambda *_: None)
            b = eb.build(ev, pre, Path(tmp, "b2"), arms, seed=7, log=lambda *_: None)
            self.assertEqual(a["codes"], b["codes"])
            self.assertEqual(len(a["codes"]), 9)
            other = eb.shuffled_codes(eb.board_items(ev, arms), seed=8)
            self.assertNotEqual([it for _, it in a["codes"]], [it for _, it in other])

            public, private = a["public"], a["private"]
            key = private / "key.csv"
            self.assertTrue(key.is_file())
            self.assertFalse(key.resolve().is_relative_to(public.resolve()))
            self.assertEqual(list(public.rglob("key.csv")), [])
            self.assertEqual(sorted(p.name for p in public.glob("*.glb")), [f"A{i:02d}.glb" for i in range(1, 10)])
            page = (public / "index.html").read_text(encoding="utf-8")
            for leak in ("remove", "+pad", "off", "coco", "bori"):
                self.assertNotIn(leak, page.replace("model-viewer", ""))
            self.assertIn("@google/model-viewer@3.5.0", page)
            with Image.open(public / "photos" / "P01.jpg") as im:
                self.assertLessEqual(max(im.size), 1024)
            with open(public / "ratings_template.csv", newline="", encoding="utf-8-sig") as fh:
                template = list(csv.DictReader(fh))
            self.assertEqual(len(template), 9)
            self.assertEqual(list(template[0].keys())[2:9], ["rater"] + eb.ITEMS)
            with self.assertRaises(SystemExit):                   # no silent overwrite of a board in use
                eb.build(ev, pre, Path(tmp, "b1"), arms, seed=7, log=lambda *_: None)

    def test_score(self):
        import eval_board as eb
        key = {"A01": {"photo": "p1", "arm": "remove"}, "A02": {"photo": "p1", "arm": "remove+pad"},
               "A03": {"photo": "p2", "arm": "remove"}, "A04": {"photo": "p2", "arm": "remove+pad"}}
        with tempfile.TemporaryDirectory() as tmp:
            files = []
            for rater, scores in (("kim", {"A01": 3, "A02": 4, "A03": 2, "A04": 4}),
                                  ("lee", {"A01": 3, "A02": 5, "A03": 3, "A04": 3})):
                path = Path(tmp, f"{rater}.csv")
                with open(path, "w", newline="", encoding="utf-8-sig") as fh:
                    w = csv.DictWriter(fh, fieldnames=eb.TEMPLATE_COLUMNS)
                    w.writeheader()
                    for code, s in scores.items():
                        w.writerow({"code": code, "rater": rater, **{i: s for i in eb.ITEMS}})
                files.append(path)
            result = eb.score(key, eb.read_ratings(files, key), "remove")
            mean = {(m["arm"], m["item"]): m["mean"] for m in result["means"]}
            self.assertAlmostEqual(mean[("remove", "faceShape")], (3 + 2.5) / 2)
            self.assertAlmostEqual(mean[("remove+pad", "mean6")], (4.5 + 3.5) / 2)
            pair = next(p for p in result["paired"] if p["item"] == "textureArtifacts")
            self.assertEqual((pair["n"], pair["improve"], pair["same"], pair["worse"]), (2, 2, 0, 0))
            agree = next(g for g in result["agreement"] if g["item"] == "faceShape")
            self.assertEqual((agree["n"], agree["exact"], agree["within1"]), (4, 0.25, 1.0))
            self.assertIn("weighted kappa", eb.score_markdown(result, "remove"))

            bad = Path(tmp, "bad.csv")
            with open(bad, "w", newline="", encoding="utf-8") as fh:
                w = csv.DictWriter(fh, fieldnames=eb.TEMPLATE_COLUMNS)
                w.writeheader()
                w.writerow({"code": "A01", "rater": "x", "faceShape": "6"})
            with self.assertRaises(SystemExit):
                eb.read_ratings([bad], key)

    def test_weighted_kappa(self):
        import eval_board as eb
        x, y = [1, 2, 3, 4, 5, 3, 2], [1, 3, 3, 5, 4, 2, 2]
        # cross-check: 1 - mean pairwise weighted disagreement / mean weighted disagreement over all cross pairs
        w = lambda a, b: (a - b) ** 2 / 16  # noqa: E731
        expected = 1 - (sum(w(a, b) for a, b in zip(x, y)) / len(x)) / (
            sum(w(a, b) for a in x for b in y) / len(x) ** 2)
        self.assertAlmostEqual(eb.weighted_kappa(x, y), expected)
        self.assertAlmostEqual(eb.weighted_kappa(x, x), 1.0)
        self.assertTrue(math.isnan(eb.weighted_kappa([3, 3], [3, 3])))


if __name__ == "__main__":
    unittest.main()
