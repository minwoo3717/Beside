"""make_fixtures.py: the files have the sizes the load scripts and the server limits rely on."""
import json
import tempfile
import unittest
from pathlib import Path

import make_fixtures

MIB5 = 5 * 1024 * 1024    # spring.servlet.multipart.max-file-size=5MB
MIB20 = 20 * 1024 * 1024  # max-request-size=20MB
PART_OVERHEAD = 200       # boundary line and part headers of one multipart part, generously


class MakeFixturesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.manifest = make_fixtures.make(cls.tmp.name)
        cls.dir = Path(cls.tmp.name)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def size(self, name):
        return (self.dir / name).stat().st_size

    def test_photo_is_about_0_4_mb(self):
        self.assertTrue(380_000 <= self.size("photo-400k.png") <= 420_000, self.size("photo-400k.png"))
        self.assertEqual((self.dir / "photo-400k.png").read_bytes()[:8], b"\x89PNG\r\n\x1a\n")

    def test_large_photo_fits_the_limits(self):
        one = self.size("photo-5mb.png")
        self.assertLess(one, MIB5)
        self.assertGreater(one, 4_900_000)
        self.assertLess(4 * (one + PART_OVERHEAD), MIB20)  # upload.js SIZE=large sends 4

    def test_glb_is_1_5_mb_glTF(self):
        data = (self.dir / "glb-1.5mb.glb").read_bytes()
        self.assertEqual(len(data), 1_500_000)
        self.assertEqual(data[:4], b"glTF")

    def test_manifest_and_rerun(self):
        manifest = json.loads((self.dir / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(set(manifest["files"]), {"photo-400k.png", "photo-5mb.png", "glb-1.5mb.glb"})
        again = make_fixtures.make(self.tmp.name)
        self.assertEqual(again["files"], self.manifest["files"])  # deterministic


if __name__ == "__main__":
    unittest.main()
