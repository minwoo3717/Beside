"""Inference service checks that need no model: python -m unittest discover -s generation/inference_service
(fastapi + httpx for TestClient; Pillow for the photo test and numpy + trimesh + Pillow for the real conversion
tests, each skipped without them)."""
import errno
import json
import logging
import os
import shutil
import sys
import tempfile
import threading
import time
import types
import unittest
import uuid
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


class _GateLock:
    """Stand-in for MODEL_LOCK or a passthrough slot: the test holds .lock (another job has the GPU) and .waiting
    says when a run starts waiting for it."""

    def __init__(self):
        self.lock = threading.Lock()
        self.waiting = threading.Event()

    def __enter__(self):
        self.waiting.set()
        self.lock.acquire()
        return self

    def __exit__(self, *exc):
        self.lock.release()


def _in_thread(call):
    """Runs call() in a thread (a concurrent request); its result lands in box["answer"]."""
    box = {}
    thread = threading.Thread(target=lambda: box.setdefault("answer", call()))
    thread.start()
    return thread, box


def _wait_until(predicate, timeout=10.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.01)
    return predicate()


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
            answer = self.upload("lt-f1b-gq-slow-1.png")   # the k6 F1b name (FAIL_TOKEN=slow)
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

    def test_an_upload_cancelled_while_it_waits_for_a_slot_does_not_sleep(self):
        """Like a model run cancelled while waiting for MODEL_LOCK: an abandoned job does not take the slot's time."""
        gate = _GateLock()
        gate.lock.acquire()   # the one slot is busy
        with _knobs(delay_ms=10000), mock.patch.object(service, "_passthrough_slots", return_value=gate):
            thread, box = _in_thread(lambda: self.upload("lt-f1b-gq-1.png"))
            try:
                self.assertTrue(gate.waiting.wait(10))
                self.assertEqual(self.client.delete("/artifacts/job-2").status_code, 204)   # Spring gave up
            finally:
                gate.lock.release()
                thread.join(10)
        answer = box["answer"]
        self.assertEqual(answer.status_code, 500, answer.text)
        self.assertEqual(answer.json()["detail"]["code"], "INFERENCE_FAILED")
        self.sleep.assert_not_called()
        self.assertEqual(self.simulated_lines(), [])
        self.assertFalse((service.WORK_DIR / "job-2").exists())
        self.assertNotIn("job-2", service._CURRENT)

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


GLB_STUB = b"glTF\x02\x00\x00\x00\x0c\x00\x00\x00"
TETRA_OBJ = """v 0 0 0
v 1 0 0
v 0 1 0
v 0 0 1
vt 0 0
vt 1 0
vt 0 1
vt 1 1
f 1/1 2/2 3/3
f 1/1 4/4 2/2
f 1/1 3/3 4/4
f 2/2 4/4 3/3
"""


def _fake_convert_module(calls):
    """convert_asset stand-in (no numpy): writes a GLB stub and, like the real one, creates the folder only when
    make_dirs (otherwise the write fails on a deleted folder). Each call is appended to calls."""
    module = types.ModuleType("convert_asset")
    module.ConversionError = type("ConversionError", (Exception,), {})

    def convert(obj, texture, out, metrics=None, hair_npz=None, forward_axis="+x", make_dirs=True):
        calls.append({"out": out, "make_dirs": make_dirs})
        if make_dirs:
            os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "wb") as glb:
            glb.write(GLB_STUB)
        return {"vertices": 4, "triangles": 4, "textureSize": 8, "bytes": len(GLB_STUB), "convertMs": 1}

    module.convert = convert
    return module


def _write_stub_raw(out):
    for name in ("mesh.obj", "uv.png", "hair.npz"):
        (out / name).write_bytes(b"stub")


def _write_real_raw(out):
    """What AnimalLift writes, tiny: a tetrahedron with UVs, an 8x8 texture and quantized hair offsets."""
    import numpy as np
    from PIL import Image

    (out / "mesh.obj").write_text(TETRA_OBJ)
    Image.new("RGB", (8, 8), (200, 150, 100)).save(out / "uv.png")
    np.savez(out / "hair.npz", hair_offsets_local_q=np.full((8, 8, 2, 3), 20, np.int8), offset_scale=np.float32(0.01))


class _FakeModel:
    """Runner interface stand-in. predict calls hook(call number) first (a DELETE or a pause arriving mid-run), then
    writes the raw files with mkdir(parents=True) as AnimalLiftRunner.predict does, so a deleted job folder comes back."""

    version = "fake@test:ckpt"
    model_params = 1
    device = "cpu"

    def __init__(self, write_raw):
        self.write_raw = write_raw
        self.calls = 0
        self.hook = None

    def predict(self, image, out_dir):
        self.calls += 1
        if self.hook:
            self.hook(self.calls)
        out = Path(out_dir)
        out.mkdir(parents=True, exist_ok=True)
        self.write_raw(out)
        return types.SimpleNamespace(mesh_obj=out / "mesh.obj", uv_png=out / "uv.png", hair_npz=out / "hair.npz",
                                     infer_ms=5, gpu_peak_mb=None)


def _has_converter_deps():
    try:
        import numpy  # noqa: F401
        import trimesh  # noqa: F401
        from PIL import Image  # noqa: F401
    except ImportError:
        return False
    return True


class _ModelModeCase(unittest.TestCase):
    """Model mode with a fake runner, driven through the upload endpoints like Spring on the VM does."""

    write_raw = staticmethod(_write_stub_raw)

    def converter_patches(self):
        return [mock.patch.dict(sys.modules, {"convert_asset": _fake_convert_module(self.converted)})]

    def setUp(self):
        self.job = "t-" + uuid.uuid4().hex[:12]
        self.model = _FakeModel(self.write_raw)
        self.converted = []
        patches = [mock.patch.object(service, "SAMPLE_GLB", None), mock.patch.object(service, "ANIMALLIFT_DIR", "model"),
                   mock.patch.object(service, "MODEL", self.model), mock.patch.object(service, "RIG_TEMPLATE", None)]
        for patcher in patches + self.converter_patches():
            patcher.start()
            self.addCleanup(patcher.stop)
        self.addCleanup(shutil.rmtree, self.job_dir, True)
        self.records = _Records()
        service.log.addHandler(self.records)
        self.addCleanup(service.log.removeHandler, self.records)
        self.client = TestClient(service.app)

    @property
    def job_dir(self):
        return service.WORK_DIR / self.job

    def upload(self, name="dog.jpg"):
        return self.client.post("/infer/upload", data={"request": json.dumps({"jobId": self.job})},
                                files=[("photos", (name, b"\xff\xd8\xff\xe0 jpeg", "image/jpeg"))])

    def delete(self):
        return self.client.delete(f"/artifacts/{self.job}").status_code

    def warnings(self, text):
        return [r.getMessage() for r in self.records.records if r.levelno == logging.WARNING and text in r.getMessage()]

    def assert_failed_and_gone(self, answer):
        """The contract's model failure (nobody reads it), no work folder, no attempt left registered."""
        self.assertEqual(answer.status_code, 500, answer.text)
        self.assertEqual(set(answer.json()["detail"]), {"code", "message"})
        self.assertEqual(answer.json()["detail"]["code"], "INFERENCE_FAILED")
        self.assertFalse(self.job_dir.exists(), sorted(str(p) for p in self.job_dir.rglob("*")))
        self.assertNotIn(self.job, service._CURRENT)


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class CancelOnDeleteTest(_ModelModeCase):
    """DELETE /artifacts/{jobId} cancels a run of the job (2026-11-19 F1b-real: Spring's 5 s timeout gave up on every
    job, the abandoned runs converted anyway and left 3 folders on the Pod's volume, one DELETE answered 500)."""

    def test_a_run_cancelled_while_it_waits_for_the_model_never_runs_it(self):
        gate = _GateLock()
        gate.lock.acquire()   # an earlier job holds the GPU
        with mock.patch.object(service, "MODEL_LOCK", gate):
            thread, box = _in_thread(self.upload)
            try:
                self.assertTrue(gate.waiting.wait(10))
                self.assertTrue((self.job_dir / "input" / "00_dog.jpg").is_file())
                self.assertEqual(self.delete(), 204)   # Spring timed out: its cleanup
                self.assertFalse(self.job_dir.exists())
            finally:
                gate.lock.release()
                thread.join(10)
        self.assert_failed_and_gone(box["answer"])
        self.assertEqual(self.model.calls, 0)   # no GPU time for a job nobody waits for
        self.assertEqual(self.converted, [])
        self.assertEqual(len(self.warnings("stopping before the model")), 1)

    def test_a_delete_during_the_model_run_skips_conversion_and_removes_what_the_run_wrote(self):
        deletes = []
        self.model.hook = lambda call: deletes.append(self.delete())   # then predict recreates raw/ (mkdir parents)
        answer = self.upload()
        self.assertEqual(deletes, [204])
        self.assertEqual(self.model.calls, 1)
        self.assertEqual(self.converted, [])   # stopped before conversion
        self.assert_failed_and_gone(answer)    # the raw files written after the DELETE went too
        self.assertEqual(len(self.warnings("stopping before conversion")), 1)
        self.assertEqual(len(self.warnings("removed what the cancelled run wrote")), 1)

    def test_a_retry_after_the_delete_completes_while_the_abandoned_run_stops(self):
        entered, go = threading.Event(), threading.Event()

        def first_run_is_slow(call):
            if call == 1:
                entered.set()
                go.wait(10)

        self.model.hook = first_run_is_slow
        first, first_box = _in_thread(lambda: self.upload("first.jpg"))
        second, second_box = None, {}
        try:
            self.assertTrue(entered.wait(10))
            self.assertEqual(self.delete(), 204)   # Spring timed out on the first attempt
            second, second_box = _in_thread(lambda: self.upload("retry.jpg"))   # the retry: same jobId
            self.assertTrue(_wait_until((self.job_dir / "input" / "00_retry.jpg").is_file))
        finally:
            go.set()
            first.join(10)
            if second:
                second.join(10)
        old, new = first_box["answer"], second_box["answer"]
        self.assertEqual(old.status_code, 500, old.text)
        self.assertEqual(old.json()["detail"]["code"], "INFERENCE_FAILED")
        self.assertEqual(len(self.warnings("a newer attempt of the same job started")), 1)
        self.assertEqual(new.status_code, 200, new.text)
        self.assertEqual(new.json()["glbUrl"], f"/artifacts/{self.job}/base.glb")
        self.assertEqual(self.model.calls, 2)
        self.assertEqual(len(self.converted), 1)   # only the retry converted, without creating folders
        self.assertFalse(self.converted[0]["make_dirs"])
        glb = self.client.get(new.json()["glbUrl"])
        self.assertEqual((glb.status_code, glb.content), (200, GLB_STUB))
        self.assertEqual(self.delete(), 204)   # Spring stored the GLB: its cleanup
        self.assertFalse(self.job_dir.exists())
        self.assertNotIn(self.job, service._CURRENT)

    def test_a_run_nobody_cancelled_answers_and_leaves_its_folder_for_the_get(self):
        answer = self.upload()
        self.assertEqual(answer.status_code, 200, answer.text)
        self.assertTrue((self.job_dir / "base.glb").is_file())
        self.assertNotIn(self.job, service._CURRENT)
        self.assertEqual(self.warnings("stopping"), [])

    def test_delete_tries_again_when_a_writer_refills_the_folder(self):
        (self.job_dir / "raw").mkdir(parents=True)
        (self.job_dir / "raw" / "uv.png").write_bytes(b"x")
        real_rmtree, tries = shutil.rmtree, []

        def racing_writer(path, *args, **kwargs):
            tries.append(path)
            if len(tries) == 1:   # a run wrote a file while rmtree walked (F1b-real: OSError 39, answered 500)
                raise OSError(errno.ENOTEMPTY, "Directory not empty", str(path))
            return real_rmtree(path, *args, **kwargs)

        with mock.patch.object(service.shutil, "rmtree", side_effect=racing_writer):
            self.assertEqual(self.delete(), 204)
        self.assertEqual(len(tries), 2)
        self.assertFalse(self.job_dir.exists())

    def test_delete_still_fails_when_the_folder_cannot_be_removed(self):
        self.job_dir.mkdir(parents=True)
        client = TestClient(service.app, raise_server_exceptions=False)
        with mock.patch.object(service.shutil, "rmtree", side_effect=PermissionError(errno.EACCES, "denied")) as rmtree, \
                mock.patch.object(service, "_REMOVE_PAUSE_S", 0):
            answer = client.delete(f"/artifacts/{self.job}")
        self.assertEqual(answer.status_code, 500)   # Spring logs it (README DELETE), the sweep is the backstop
        self.assertEqual(rmtree.call_count, service._REMOVE_TRIES)
        self.assertTrue(self.job_dir.exists())


@unittest.skipIf(TestClient is None or not _has_converter_deps(), "fastapi/httpx or numpy/trimesh/pillow not installed")
class RealConversionCancelTest(_ModelModeCase):
    """The same with tools/convert_asset.py itself: its makedirs brought a deleted job folder back with base.glb."""

    write_raw = staticmethod(_write_real_raw)

    def converter_patches(self):
        return []

    def test_a_delete_during_conversion_leaves_no_folder(self):
        import convert_asset

        deletes, normals = [], convert_asset._smooth_normals

        def delete_then_normals(*args):   # the DELETE lands after the OBJ and texture were read, before any write
            deletes.append(self.delete())
            return normals(*args)

        with mock.patch.object(convert_asset, "_smooth_normals", side_effect=delete_then_normals):
            answer = self.upload()
        self.assertEqual(deletes, [204])
        self.assert_failed_and_gone(answer)
        self.assertEqual(len(self.warnings("stopping after a conversion error")), 1)
        self.assertEqual([r.getMessage() for r in self.records.records if r.levelno >= logging.ERROR], [])   # no traceback

    def test_the_conversion_writes_into_the_existing_job_folder(self):
        answer = self.upload()
        self.assertEqual(answer.status_code, 200, answer.text)
        for name in ("base.glb", "base.metrics.json", "hair_length.png", "hair_flow.png", "hair_maps.json"):
            self.assertTrue((self.job_dir / name).is_file(), name)
        self.assertEqual(self.client.get(answer.json()["glbUrl"]).content[:4], b"glTF")
        self.assertEqual(self.delete(), 204)
        self.assertFalse(self.job_dir.exists())

    def test_convert_creates_the_output_folder_only_with_make_dirs(self):
        import convert_asset

        with tempfile.TemporaryDirectory() as folder:
            raw = Path(folder) / "raw"
            raw.mkdir()
            _write_real_raw(raw)
            gone = Path(folder) / "gone"
            with self.assertRaisesRegex(convert_asset.ConversionError, "output folder is gone"):
                convert_asset.convert(str(raw / "mesh.obj"), str(raw / "uv.png"), str(gone / "base.glb"),
                                      str(gone / "base.metrics.json"), hair_npz=str(raw / "hair.npz"), make_dirs=False)
            self.assertFalse(gone.exists())
            made = Path(folder) / "made"   # the CLI default still creates it
            convert_asset.convert(str(raw / "mesh.obj"), str(raw / "uv.png"), str(made / "base.glb"))
            self.assertTrue((made / "base.glb").is_file())


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class WorkSweepTest(unittest.TestCase):
    """BESIDE_WORK_SWEEP_MINUTES: the backstop for job folders nothing else removes."""

    def setUp(self):
        self.records = _Records()
        service.log.addHandler(self.records)
        self.addCleanup(service.log.removeHandler, self.records)

    def test_only_old_job_folders_no_run_uses_are_removed(self):
        with tempfile.TemporaryDirectory() as folder:
            work = Path(folder).resolve()
            now = time.time()
            old = now - 2 * 3600

            def job(name, mtime):
                path = work / name
                (path / "raw").mkdir(parents=True)
                (path / "raw" / "mesh.obj").write_bytes(b"x")
                for entry in (path / "raw" / "mesh.obj", path / "raw", path):
                    os.utime(entry, (mtime, mtime))
                return path

            stale, fresh, busy, odd = job("job-stale", old), job("job-fresh", now - 60), job("job-busy", old), job("not_a_job", old)
            touched = job("job-touched", old)
            os.utime(touched / "raw" / "mesh.obj", (now, now))   # an old folder with a new file inside
            stray = work / "notes.txt"
            stray.write_text("x")
            os.utime(stray, (old, old))
            with mock.patch.object(service, "WORK_DIR", work), mock.patch.dict(service._CURRENT, {"job-busy": 1}):
                removed = service._sweep_work_dir(3600, now=now)
            self.assertEqual(removed, ["job-stale"])
            self.assertFalse(stale.exists())
            for kept in (fresh, busy, touched, odd, stray):
                self.assertTrue(kept.exists(), kept)

    def test_the_setting_is_off_unless_a_positive_number(self):
        for raw, minutes in ((None, 0), ("", 0), ("60", 60), (" 30 ", 30), ("abc", 0), ("-5", 0)):
            with mock.patch.dict(os.environ, {}):
                os.environ.pop("BESIDE_WORK_SWEEP_MINUTES", None)
                if raw is not None:
                    os.environ["BESIDE_WORK_SWEEP_MINUTES"] = raw
                self.assertEqual(service._sweep_minutes(), minutes, raw)


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
