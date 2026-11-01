"""Inference service checks that need no model: python -m unittest discover -s generation/inference_service
(fastapi + httpx for TestClient; Pillow for the photo test, skipped without it)."""
import json
import logging
import os
import sys
import tempfile
import threading
import time
import types
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
WORK = tempfile.mkdtemp(prefix="beside-infer-test-")
os.environ.setdefault("BESIDE_WORK_DIR", WORK)
os.environ.pop("BESIDE_INFERENCE_TOKEN", None)
KNOBS = ("DELAY_MS", "JITTER_MS", "SLOTS", "FAIL_RATE", "FAIL_TOKEN", "SLOW_TOKEN", "SLOW_MS")
for _knob in KNOBS:   # each test sets the passthrough knobs it needs
    os.environ.pop("BESIDE_PASSTHROUGH_" + _knob, None)

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


def _knobs(**values):
    """os.environ patch: BESIDE_PASSTHROUGH_<NAME>=<value> for each keyword (read per request by the service)."""
    return mock.patch.dict(os.environ, {"BESIDE_PASSTHROUGH_" + name.upper(): str(value) for name, value in values.items()})


class _Records(logging.Handler):
    """Keeps the service's log records (and keeps them off the test output)."""

    def __init__(self):
        super().__init__()
        self.records = []

    def emit(self, record):
        self.records.append(record)


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class PassthroughKnobTest(unittest.TestCase):
    """BESIDE_PASSTHROUGH_* load-test knobs (plan B5): simulated generation time, GPU slots, failures, slow jobs."""

    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.folder = Path(folder.name)
        self.sample = self.folder / "sample.glb"
        self.sample.write_bytes(b"glTF\x02\x00\x00\x00\x0c\x00\x00\x00")
        self.photo = self.photo_named("lt-run1-dog.jpg")
        for patcher in (mock.patch.object(service, "SAMPLE_GLB", str(self.sample)),   # passthrough mode
                        mock.patch.dict(os.environ, {}),                               # undo the knobs a test sets
                        mock.patch.object(service, "_sleep")):
            patcher.start()
            self.addCleanup(patcher.stop)
        self.sleep = service._sleep
        self.records = _Records()
        service.log.addHandler(self.records)
        self.addCleanup(service.log.removeHandler, self.records)
        self.client = TestClient(service.app)

    def photo_named(self, name):
        path = self.folder / name
        path.write_bytes(b"\xff\xd8\xff\xe0 not a real jpeg")
        return path

    def infer(self, photo=None):
        return self.client.post("/infer", json={"jobId": "job-1", "imagePaths": [str(photo or self.photo)]})

    def upload(self, name):
        return self.client.post("/infer/upload", data={"request": json.dumps({"jobId": "job-2"})},
                                files=[("photos", (name, b"\xff\xd8\xff\xe0 jpeg", "image/jpeg"))])

    def slept(self):
        return [call.args[0] for call in self.sleep.call_args_list]

    def simulated_lines(self):
        return [r.getMessage() for r in self.records.records if "passthrough sim" in r.getMessage()]

    def model_failure(self):
        """The real model failure path: a model whose predict raises (convert_asset faked: no numpy needed)."""

        class Broken:
            def predict(self, image, out_dir):
                raise RuntimeError("CUDA out of memory")

        fake_convert = types.ModuleType("convert_asset")
        fake_convert.ConversionError = type("ConversionError", (Exception,), {})
        fake_convert.convert = mock.Mock()
        with mock.patch.object(service, "SAMPLE_GLB", None), mock.patch.object(service, "ANIMALLIFT_DIR", "model"), \
                mock.patch.object(service, "MODEL", Broken()), mock.patch.dict(sys.modules, {"convert_asset": fake_convert}):
            return self.infer(self.photo_named("lt-run1-fail-slow-dog.jpg"))

    def held_check(self):
        """A fake sleep that records whether the passthrough slot (SLOTS=1) is taken while it runs."""
        held = []

        def fake_sleep(seconds):
            slots = service._passthrough_slots(1)
            free = slots.acquire(blocking=False)
            if free:
                slots.release()
            held.append(not free)

        self.sleep.side_effect = fake_sleep
        return held

    def test_without_knobs_there_is_no_sleep_and_no_slot_wait(self):
        with mock.patch.object(service, "_passthrough_slots", wraps=service._passthrough_slots) as slots:
            path = self.infer()
            upload = self.upload("lt-run1-dog.jpg")
            with _knobs(slots=4):   # a slot count alone simulates nothing
                sized = self.infer()
        for answer in (path, upload, sized):
            self.assertEqual(answer.status_code, 200, answer.text)
            self.assertTrue(answer.json()["passthrough"])
            self.assertEqual(answer.json()["metrics"]["convertMs"], 0)
        self.sleep.assert_not_called()
        slots.assert_not_called()
        self.assertEqual(self.simulated_lines(), [])

    def test_delay_and_jitter_sleep_in_range_while_a_slot_is_held(self):
        held = self.held_check()
        with _knobs(delay_ms=200, jitter_ms=100):
            answers = [self.infer() for _ in range(15)] + [self.upload("lt-run1-dog.jpg")]
            with mock.patch.object(service.random, "uniform", return_value=37.5) as uniform:
                exact = self.infer()
        self.assertEqual([a.status_code for a in answers + [exact]], [200] * 17)
        seconds = self.slept()
        self.assertEqual(len(seconds), 17)
        for value in seconds[:-1]:
            self.assertGreaterEqual(value, 0.2)
            self.assertLessEqual(value, 0.3)
        uniform.assert_called_once_with(0, 100)
        self.assertAlmostEqual(seconds[-1], 0.2375)
        self.assertEqual(held, [True] * 17)
        lines = self.simulated_lines()
        self.assertEqual(len(lines), 17)   # one line per simulated run
        self.assertIn("slot wait", lines[0])
        self.assertIn("outcome ok", lines[0])

    def test_two_slots_let_two_generations_overlap_but_not_three(self):
        cond = threading.Condition()
        state = {"inside": 0, "entered": 0, "most": 0}
        release = threading.Event()

        def fake_sleep(seconds):
            with cond:
                state["inside"] += 1
                state["entered"] += 1
                state["most"] = max(state["most"], state["inside"])
                cond.notify_all()
            release.wait(10)
            with cond:
                state["inside"] -= 1

        self.sleep.side_effect = fake_sleep
        errors = []

        def generate(index):
            try:
                service._run(f"job-{index}", [str(self.photo)], service.InferOptions())
            except Exception as error:  # noqa: BLE001  reported by the assertion below
                errors.append(error)

        with _knobs(delay_ms=1000, slots=2):
            threads = [threading.Thread(target=generate, args=(i,)) for i in range(3)]
            try:
                for thread in threads:
                    thread.start()
                with cond:
                    self.assertTrue(cond.wait_for(lambda: state["entered"] >= 2, timeout=5))
                    self.assertFalse(cond.wait_for(lambda: state["entered"] >= 3, timeout=0.3))   # third waits
            finally:
                release.set()
                for thread in threads:
                    thread.join(10)
        self.assertEqual(errors, [])
        self.assertEqual(state["entered"], 3)
        self.assertEqual(state["most"], 2)

    def test_fail_token_and_fail_rate_answer_like_a_model_failure(self):
        with self.assertLogs(service.log, "ERROR"):   # the real path logs the model's traceback
            model = self.model_failure()
        self.assertEqual(model.status_code, 500)
        self.assertEqual(model.json()["detail"]["code"], "INFERENCE_FAILED")
        with _knobs(fail_token="-fail-"):
            token = self.infer(self.photo_named("lt-run1-fail-dog.jpg"))
            upload = self.upload("lt-run1-fail-dog.jpg")
            other = self.infer()   # no token in this name
        with _knobs(fail_rate=1):
            rate = self.infer()
        with _knobs(fail_rate=0.5), mock.patch.object(service.random, "random", side_effect=[0.6, 0.4]):
            lucky, unlucky = self.infer(), self.infer()
        for answer in (token, upload, rate, unlucky):
            self.assertEqual(answer.status_code, model.status_code)
            self.assertEqual(set(answer.json()), set(model.json()))
            self.assertEqual(set(answer.json()["detail"]), set(model.json()["detail"]))
            self.assertEqual(answer.json()["detail"]["code"], "INFERENCE_FAILED")
        self.assertEqual(other.status_code, 200)
        self.assertEqual(lucky.status_code, 200)
        self.sleep.assert_not_called()   # no delay knob: a failure answers at once
        outcomes = self.simulated_lines()
        self.assertEqual(len(outcomes), 6)
        self.assertIn("outcome failed (fail token)", outcomes[0])
        self.assertIn("outcome failed (fail rate)", outcomes[3])

    def test_slow_token_sleeps_slow_ms_inside_the_slot(self):
        held = self.held_check()
        with _knobs(delay_ms=100, slow_token="-slow-", slow_ms=30000):
            slow = self.infer(self.photo_named("lt-run1-slow-dog.jpg"))
            upload = self.upload("lt-run1-slow-dog.jpg")
            normal = self.infer()
        self.assertEqual([slow.status_code, upload.status_code, normal.status_code], [200, 200, 200])
        self.assertEqual(self.slept(), [30.0, 30.0, 0.1])
        self.assertEqual(held, [True, True, True])
        self.assertIn("outcome ok (slow token)", self.simulated_lines()[0])

    def test_an_upload_whose_folder_is_deleted_during_the_run_answers_the_model_failure(self):
        """F1b: Spring times out, then its cleanup DELETEs the job folder while the stub still sleeps in the slot."""
        job_dir = service.WORK_DIR / "job-2"
        deletes = []

        def spring_gives_up(seconds):
            deletes.append(self.client.delete("/artifacts/job-2").status_code)

        self.sleep.side_effect = spring_gives_up
        with _knobs(delay_ms=100, slow_token="slow", slow_ms=30000):
            answer = self.upload("lt-f1b-gq-slow1.png")
        self.assertEqual(deletes, [204])
        self.assertEqual(self.slept(), [30.0])
        self.assertEqual(answer.status_code, 500, answer.text)
        self.assertEqual(set(answer.json()["detail"]), {"code", "message"})   # the model failure shape
        self.assertEqual(answer.json()["detail"]["code"], "INFERENCE_FAILED")
        self.assertFalse(job_dir.exists())   # nothing left behind for a job Spring already cleaned up
        warnings = [r.getMessage() for r in self.records.records
                    if r.levelno == logging.WARNING and "work folder deleted during the run" in r.getMessage()]
        self.assertEqual(len(warnings), 1)
        self.assertIn("job-2", warnings[0])

    def test_infer_ms_is_the_simulated_generation_time(self):
        self.sleep.side_effect = time.sleep   # a real 60 ms sleep
        with _knobs(delay_ms=60):
            answer = self.infer()
        self.assertEqual(answer.status_code, 200)
        self.assertGreaterEqual(answer.json()["metrics"]["inferMs"], 50)

    def test_knobs_are_ignored_outside_passthrough_mode(self):
        every_knob = dict(delay_ms=500, jitter_ms=100, slots=1, fail_rate=1, fail_token="fail", slow_token="slow",
                          slow_ms=9000)
        with _knobs(**every_knob):
            with mock.patch.object(service, "SAMPLE_GLB", None), mock.patch.object(service, "ANIMALLIFT_DIR", None):
                unconfigured = self.infer(self.photo_named("lt-run1-fail-slow-dog.jpg"))
            with self.assertLogs(service.log, "ERROR"):
                model = self.model_failure()
        self.assertEqual(unconfigured.status_code, 501)
        self.assertEqual(unconfigured.json()["detail"]["code"], "INFERENCE_UNAVAILABLE")
        self.assertEqual(model.json()["detail"]["message"], "RuntimeError: CUDA out of memory")   # the model's own
        self.sleep.assert_not_called()
        self.assertEqual(self.simulated_lines(), [])

    def test_knob_values_are_read_per_call_and_bounded(self):
        with _knobs(delay_ms=-5, jitter_ms="abc", slots=0, fail_rate=7, slow_ms=250):
            knobs = service._passthrough_knobs()
        self.assertEqual((knobs.delay_ms, knobs.jitter_ms, knobs.slots, knobs.fail_rate, knobs.slow_ms),
                         (0, 0, 1, 1.0, 250))
        self.assertEqual(service._passthrough_knobs().slots, 1)   # back to the default once the env is gone
        self.assertIs(service._passthrough_slots(2), service._passthrough_slots(2))
        self.assertIsNot(service._passthrough_slots(3), service._passthrough_slots(2))   # a new size, a new semaphore


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
