"""Inference service checks that need no model: python -m unittest discover -s generation/inference_service
(fastapi + httpx for TestClient; Pillow for the photo test, skipped without it)."""
import os
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
WORK = tempfile.mkdtemp(prefix="beside-infer-test-")
os.environ.setdefault("BESIDE_WORK_DIR", WORK)
os.environ.pop("BESIDE_INFERENCE_TOKEN", None)

try:
    from fastapi.testclient import TestClient
    import app as service
except ImportError as missing:  # pragma: no cover - environment without fastapi/httpx
    TestClient = None
    MISSING = str(missing)


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class PathModeJobIdTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(service.app)

    def test_a_job_id_that_would_leave_the_work_dir_is_refused(self):
        outside = Path(WORK).parent / "beside-escape-probe"
        for job_id in ("../beside-escape-probe", str(outside), "a/b", "x" * 65, ""):
            answer = self.client.post("/infer", json={"jobId": job_id, "imagePaths": ["/no/such/photo.jpg"]})
            self.assertEqual(answer.status_code, 422, job_id)
        self.assertFalse(outside.exists())

    def test_a_spring_job_id_passes_validation(self):
        answer = self.client.post("/infer", json={"jobId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                                  "imagePaths": ["/no/such/photo.jpg"]})
        self.assertNotEqual(answer.status_code, 422)  # past validation (501/503/400: no model or no photo here)


class UprightPhotoTest(unittest.TestCase):
    def test_exif_orientation_is_applied(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("Pillow not installed")
        import animallift_runner

        with tempfile.TemporaryDirectory() as folder:
            stored = Image.new("RGB", (40, 20), (255, 0, 0))   # stored landscape: left red, right blue
            stored.paste((0, 0, 255), (20, 0, 40, 20))
            exif = Image.Exif()
            exif[0x0112] = 6  # rotate 90 degrees clockwise to view
            path = os.path.join(folder, "portrait.jpg")
            stored.save(path, exif=exif, quality=95)
            upright = animallift_runner.load_rgb(path)
            self.assertEqual(upright.size, (20, 40))              # shown as a portrait
            top, bottom = upright.getpixel((10, 5)), upright.getpixel((10, 35))
            self.assertGreater(top[0], 200)                       # red on top
            self.assertGreater(bottom[2], 200)                    # blue at the bottom


if __name__ == "__main__":
    unittest.main()
