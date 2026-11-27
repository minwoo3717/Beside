"""Inference service checks that need no model: python -m unittest discover -s generation/inference_service
(fastapi + httpx for TestClient; Pillow for the photo test, numpy + trimesh + Pillow for the real conversion
tests and numpy for the rig transfer test, each skipped without them)."""
import contextlib
import errno
import io
import json
import logging
import os
import re
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
os.environ.pop("BESIDE_REQUEST_TIMING", None)   # RequestTimingTest wraps the app itself
for _name in ("BESIDE_OTLP_URL", "BESIDE_TELEMETRY_PROXY", "BESIDE_TELEMETRY_ENV"):   # span export: SpanExportTest
    os.environ.pop(_name, None)
for _switch in ("EMBED_HAIR", "HAIR_META", "PAD_TEXTURE"):   # converter switches: the tests patch them on the module
    os.environ.pop("BESIDE_" + _switch, None)
KNOBS = ("DELAY_MS", "JITTER_MS", "SLOTS", "FAIL_RATE", "FAIL_TOKEN", "SLOW_TOKEN", "SLOW_MS")
for _knob in KNOBS:   # each test sets the passthrough knobs it needs
    os.environ.pop("BESIDE_PASSTHROUGH_" + _knob, None)

try:
    from fastapi.testclient import TestClient
    import app as service
    # The service's stderr handler (INFO and up since 2026-11-26) would print every request and job line of these
    # tests; ServiceLogTest checks the lines through the records and the handler's formatter instead.
    service.LOG_HANDLER.setLevel(logging.CRITICAL)
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

    def test_healthz_busy_for_ms_counts_a_simulated_generation_in_flight(self):
        """FD13 drill: the stub sleeps BESIDE_PASSTHROUGH_DELAY_MS in its slot while /healthz still answers."""
        entered, release = threading.Event(), threading.Event()

        def generation(seconds):
            entered.set()
            release.wait(10)

        self.sleep.side_effect = generation
        idle = self.client.get("/healthz")
        with _knobs(delay_ms=600000):
            thread, box = _in_thread(lambda: self.upload("lt-fd13-dog.jpg"))
            try:
                self.assertTrue(entered.wait(10))
                time.sleep(0.1)
                busy = self.client.get("/healthz")
            finally:
                release.set()
                thread.join(10)
        self.assertEqual((idle.status_code, idle.json()["busyForMs"]), (200, 0))
        self.assertEqual(busy.status_code, 200)
        self.assertGreaterEqual(busy.json()["busyForMs"], 50)
        self.assertLess(busy.json()["busyForMs"], 10000)
        self.assertEqual(box["answer"].status_code, 200, box["answer"].text)
        self.assertEqual(self.client.get("/healthz").json()["busyForMs"], 0)   # the run ended
        self.assertEqual(service._BUSY, {})

    def test_a_run_waiting_for_a_slot_is_not_busy_yet(self):
        """Only the time inside the slot counts: a queue behind a stuck run shows the stuck run's age, not its own."""
        gate = _GateLock()
        gate.lock.acquire()   # the one slot is taken
        with _knobs(delay_ms=10000), mock.patch.object(service, "_passthrough_slots", return_value=gate):
            thread, box = _in_thread(self.infer)
            try:
                self.assertTrue(gate.waiting.wait(10))
                time.sleep(0.05)
                waiting = self.client.get("/healthz").json()["busyForMs"]
            finally:
                gate.lock.release()
                thread.join(10)
        self.assertEqual(waiting, 0)
        self.assertEqual(box["answer"].status_code, 200, box["answer"].text)
        self.assertEqual(service._BUSY, {})

    def test_a_simulated_failure_ends_the_busy_time_too(self):
        with _knobs(delay_ms=100, fail_rate=1):
            answer = self.infer()
        self.assertEqual(answer.status_code, 500)
        self.assertEqual(self.client.get("/healthz").json()["busyForMs"], 0)
        self.assertEqual(service._BUSY, {})


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class BusyClockTest(unittest.TestCase):
    """_busy_work registers one piece of work (a predict call or a simulated generation); _busy_for_ms is the age of
    the oldest one still running."""

    def test_the_oldest_work_still_running_sets_the_age(self):
        self.assertEqual(service._busy_for_ms(), 0)
        first, second = service._busy_work(), service._busy_work()
        first.__enter__()
        (first_start,) = service._BUSY.values()
        second.__enter__()
        second_start = list(service._BUSY.values())[-1]
        try:
            self.assertEqual(service._busy_for_ms(now=first_start + 2.5), 2500)
            first.__exit__(None, None, None)   # the older one ends first: the younger one sets the age now
            self.assertEqual(service._busy_for_ms(now=second_start + 1.0), 1000)
        finally:
            second.__exit__(None, None, None)
        self.assertEqual(service._busy_for_ms(), 0)
        self.assertEqual(service._BUSY, {})

    def test_never_negative_and_released_on_an_exception(self):
        with self.assertRaises(RuntimeError):
            with service._busy_work():
                (start,) = service._BUSY.values()
                self.assertEqual(service._busy_for_ms(now=start - 1.0), 0)
                raise RuntimeError("CUDA out of memory")
        self.assertEqual(service._BUSY, {})


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class HealthzTokenTest(unittest.TestCase):
    """/healthz stays open and 200. With an X-Inference-Token header it also says whether /infer would take that
    token (tokenOk), so Spring's monitor sees a token mismatch before every job fails 401 (FD17)."""

    def setUp(self):
        self.client = TestClient(service.app)

    def healthz(self, token=None):
        return self.client.get("/healthz", headers=None if token is None else {"X-Inference-Token": token})

    def test_without_the_header_token_ok_is_null_and_the_old_fields_stay(self):
        for configured in (None, "secret"):
            with mock.patch.object(service, "INFERENCE_TOKEN", configured):
                answer = self.healthz()
            self.assertEqual(answer.status_code, 200)
            body = answer.json()
            self.assertIsNone(body["tokenOk"], configured)
            self.assertEqual(body["status"], "ok")
            for field in ("device", "modelLoaded", "passthrough", "busyForMs"):
                self.assertIn(field, body)

    def test_instance_id_names_this_process_and_stays_the_same(self):
        """FD16b: Spring compares instanceId with the one it saw when it sent a request; a new value means the service
        restarted and that request will never be answered."""
        first, second = self.healthz().json(), self.healthz("anything").json()
        self.assertRegex(first["instanceId"], r"^[0-9a-f]{12}$")
        self.assertEqual(first["instanceId"], second["instanceId"])
        self.assertEqual(first["instanceId"], service.INSTANCE_ID)
        with mock.patch.object(service, "INSTANCE_ID", "0123456789ab"):   # what a restarted process would answer
            self.assertEqual(self.healthz().json()["instanceId"], "0123456789ab")

    def test_the_header_is_checked_as_infer_checks_it(self):
        with mock.patch.object(service, "INFERENCE_TOKEN", "secret"):
            right, wrong, empty = self.healthz("secret"), self.healthz("other"), self.healthz("")
            refused = self.client.post("/infer", headers={"X-Inference-Token": "other"},
                                       json={"jobId": "job-1", "imagePaths": ["/no/such/photo.jpg"]})
        self.assertEqual([a.status_code for a in (right, wrong, empty)], [200, 200, 200])   # healthz never 401
        self.assertEqual([a.json()["tokenOk"] for a in (right, wrong, empty)], [True, False, False])
        self.assertEqual(refused.status_code, 401)   # what tokenOk=false predicts for every job

    def test_a_service_without_a_token_takes_any_header(self):
        with mock.patch.object(service, "INFERENCE_TOKEN", None):
            answer = self.healthz("anything")
        self.assertEqual((answer.status_code, answer.json()["tokenOk"]), (200, True))

    def test_the_comparison_is_constant_time(self):
        with mock.patch.object(service, "INFERENCE_TOKEN", "secret"), \
                mock.patch.object(service.hmac, "compare_digest", wraps=service.hmac.compare_digest) as compare:
            self.healthz("secrex")
        compare.assert_called_once_with(b"secrex", b"secret")


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class HealthzLoadErrorTest(unittest.TestCase):
    """loadError (failure drill FD18, 2026-11-20): with a wrong BESIDE_ANIMALLIFT_CKPT the load raised
    FileNotFoundError and /healthz said only modelLoaded=false, the same as a model still loading, so Spring showed
    LOADING and jobs waited out inference.offline-wait-ms. /healthz now also names the failure: the exception type and
    a short one-line message without file paths or the token (it is open). status, modelLoaded and the 503 of /infer
    stay as they were. The drill's own message (the real runner's 'missing checkpoint') is
    test_fd18_the_real_runner_with_a_wrong_checkpoint; the errno form is what a file missing deeper in the load gives."""

    CKPT = "/workspace/AnimalLift/ckpt/wrong.pt"

    def setUp(self):
        self.raise_on_load = None
        test = self

        class Runner:   # the runner interface's constructor; predict is never reached here
            device, version, model_params = "cpu", "fake@load-test", 1

            def __init__(self, model_dir, **kwargs):
                if test.raise_on_load is not None:
                    raise test.raise_on_load

        module = types.ModuleType("beside_fake_runner")
        module.Runner = Runner
        patches = [mock.patch.dict(sys.modules, {"beside_fake_runner": module}),
                   mock.patch.object(service, "RUNNER_SPEC", "beside_fake_runner:Runner"),
                   mock.patch.object(service, "SAMPLE_GLB", None), mock.patch.object(service, "ANIMALLIFT_DIR", "model"),
                   mock.patch.object(service, "MODEL", None), mock.patch.object(service, "MODEL_ERROR", None),
                   mock.patch.object(service, "MODEL_LOAD_ERROR", None)]
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)
        self.client = TestClient(service.app)

    def load(self, error=None):
        """What the startup thread runs, here in the test's thread; error is what the runner raises."""
        self.raise_on_load = error
        if error is None:
            service._load_model()
        else:
            with self.assertLogs(service.log, "ERROR") as logged:   # the full text stays in the service log
                service._load_model()
            self.assertIn("model failed to load", logged.output[0])

    def healthz(self):
        answer = self.client.get("/healthz")
        self.assertEqual(answer.status_code, 200)
        self.assertEqual(answer.json()["status"], "ok")
        return answer.json()

    def test_while_loading_there_is_no_load_error(self):
        body = self.healthz()
        self.assertEqual((body["modelLoaded"], body["loadError"]), (False, None))

    def test_a_failed_load_names_the_error_without_its_path(self):
        self.load(FileNotFoundError(errno.ENOENT, "No such file or directory", self.CKPT))
        body = self.healthz()
        self.assertFalse(body["modelLoaded"])
        self.assertEqual(body["loadError"], "FileNotFoundError: [Errno 2] No such file or directory: '<path>'")
        self.assertNotIn("workspace", json.dumps(body))
        self.assertNotIn("wrong.pt", json.dumps(body))
        # Jobs are refused as before (Spring maps it to INFERENCE_UNAVAILABLE); its message is not part of this change.
        job = "t-" + uuid.uuid4().hex[:12]
        self.addCleanup(shutil.rmtree, service.WORK_DIR / job, True)
        refused = self.client.post("/infer/upload", data={"request": json.dumps({"jobId": job})},
                                   files=[("photos", ("dog.jpg", b"\xff\xd8\xff\xe0 jpeg", "image/jpeg"))])
        self.assertEqual(refused.status_code, 503, refused.text)
        self.assertEqual(refused.json()["detail"]["code"], "INFERENCE_UNAVAILABLE")
        self.assertIn("failed to load", refused.json()["detail"]["message"])

    def test_fd18_the_real_runner_with_a_wrong_checkpoint(self):
        """FD18 as it ran: the real AnimalLiftRunner (not the stand-in above) with BESIDE_ANIMALLIFT_CKPT naming no file.
        The runner's own message is not the errno form: 'missing checkpoint (<how to fetch it>): <path>'. Its fetch hint
        holds the Hugging Face repo id Chunyi99/AnimalLift, which has a slash and is masked like every path, so loadError
        says 'hf download <path>'; the full text stays in MODEL_ERROR (the 503 of /infer, behind the token) and the log."""
        repo = Path(tempfile.mkdtemp(prefix="beside-fd18-"))
        self.addCleanup(shutil.rmtree, repo, True)
        (repo / "animallift").mkdir()
        (repo / "animallift" / "inference.py").write_text("")   # a checkout, so the checkpoint check is the one that fails
        wrong = repo / "ckpt" / "wrong.pt"
        with mock.patch.object(service, "RUNNER_SPEC", "animallift_runner:AnimalLiftRunner"), \
                mock.patch.object(service, "ANIMALLIFT_DIR", str(repo)), \
                mock.patch.dict(os.environ, {"BESIDE_ANIMALLIFT_CKPT": str(wrong)}):
            with self.assertLogs(service.log, "ERROR") as logged:
                service._load_model()
        self.assertIn("model failed to load (animallift_runner:AnimalLiftRunner)", logged.output[0])
        self.assertEqual(service.MODEL_ERROR, "FileNotFoundError: missing checkpoint "
                         f"(hf download Chunyi99/AnimalLift --local-dir ckpt): {wrong.resolve()}")
        body = self.healthz()
        self.assertFalse(body["modelLoaded"])
        self.assertEqual(body["loadError"],
                         "FileNotFoundError: missing checkpoint (hf download <path> --local-dir ckpt): <path>")
        self.assertNotIn(repo.name, json.dumps(body))
        self.assertNotIn("wrong.pt", json.dumps(body))

    def test_a_loaded_model_has_no_load_error(self):
        self.load()
        body = self.healthz()
        self.assertEqual((body["modelLoaded"], body["loadError"]), (True, None))

    def test_windows_and_relative_paths_and_the_token_are_left_out(self):
        with mock.patch.object(service, "INFERENCE_TOKEN", "s3cret-token-value"):
            self.load(RuntimeError("cannot open C:\\beside\\AnimalLift\\ckpt\\x.pt or ckpt/latest.pt "
                                   "(token s3cret-token-value)"))
            body = self.healthz()
        self.assertEqual(body["loadError"], "RuntimeError: cannot open <path> or <path> (token <token>)")

    def test_the_message_is_one_short_line_and_an_empty_one_leaves_the_type(self):
        self.load(ValueError("line one\n\tline two " + "x" * 1000))
        error = self.healthz()["loadError"]
        self.assertTrue(error.startswith("ValueError: line one line two x"), error)
        self.assertLessEqual(len(error), service.LOAD_ERROR_MAX)
        self.assertNotIn("\n", error)
        self.load(MemoryError())
        self.assertEqual(self.healthz()["loadError"], "MemoryError")


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
    """convert_asset stand-in (no numpy): writes a GLB stub and its metrics file and, like the real one, creates the
    folder only when make_dirs (otherwise the write fails on a deleted folder). Each call is appended to calls.
    hair_meta / padded_texture: the converter's opt-in extras (app.py passes them only when switched on); with
    padded_texture it writes texture_padded.png next to the GLB like the real one."""
    module = types.ModuleType("convert_asset")
    module.ConversionError = type("ConversionError", (Exception,), {})
    module.MAX_GLB_BYTES = 10 * 1024 * 1024

    def convert(obj, texture, out, metrics=None, hair_npz=None, forward_axis="+x", make_dirs=True,
                hair_meta=None, padded_texture=False):
        calls.append({"out": out, "make_dirs": make_dirs, "hair_npz": hair_npz, "hair_meta": hair_meta,
                      "padded_texture": padded_texture})
        if make_dirs:
            os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "wb") as glb:
            glb.write(GLB_STUB)
        if padded_texture:
            with open(os.path.join(os.path.dirname(out), "texture_padded.png"), "wb") as png:
                png.write(b"padded")
        result = {"vertices": 4, "triangles": 4, "textureSize": 8, "bytes": len(GLB_STUB), "convertMs": 1}
        if metrics:
            with open(metrics, "w") as f:
                json.dump(result, f)
        return result

    module.convert = convert
    return module


def _fake_rig_module(size, textures=None):
    """rig_transfer stand-in: writes a 'rigged' GLB of size bytes (GLB_STUB, zero padded) and reports it. The texture
    path of each call is appended to textures when given."""
    module = types.ModuleType("rig_transfer")
    module.RigError = type("RigError", (Exception,), {})

    def transfer(base, texture, template, out):
        if textures is not None:
            textures.append(texture)
        data = GLB_STUB + b"\0" * (size - len(GLB_STUB))
        with open(out, "wb") as glb:
            glb.write(data)
        return {"joints": 2, "fitErrorM": 0.0, "heightRatio": 1.0, "clips": ["Idle"], "bytes": len(data)}

    module.transfer = transfer
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
        self.assertNotIn(self.job, service._CANCELLED)


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
        self.assertNotIn(self.job, service._CANCELLED)

    def test_a_cancelled_attempt_ending_after_the_retry_finished_keeps_the_retrys_glb(self):
        # Pre-merge review 2026-11-19: with max-concurrency >= 2 (or SLOTS >= 2) the retry can finish first. Its
        # registry entry is then gone too, and the old attempt used to read that as "cancelled" and rmtree the
        # retry's folder before Spring's GET of base.glb (404 -> CONVERSION_FAILED for a retry that worked).
        with service._job_attempt(self.job) as old:
            (self.job_dir / "input").mkdir(parents=True)
            self.assertEqual(self.delete(), 204)   # Spring gave up on the old attempt
            self.assertEqual(old.state(), "cancelled")
            with service._job_attempt(self.job) as retry:
                self.job_dir.mkdir()   # the retry's upload makes the folder again
                (self.job_dir / "base.glb").write_bytes(GLB_STUB)   # the retry's answer, not fetched yet
                self.assertEqual(old.state(), "superseded")
            self.assertEqual(retry.state(), "superseded")   # ended: the folder is no attempt's to remove
            self.assertEqual(old.state(), "superseded")
        self.assertEqual((self.job_dir / "base.glb").read_bytes(), GLB_STUB)
        self.assertEqual(self.warnings("removed what the cancelled run wrote"), [])
        self.assertNotIn(self.job, service._CURRENT)
        self.assertNotIn(self.job, service._CANCELLED)

    def test_an_older_attempt_ending_after_a_newer_one_removes_nothing_without_a_delete(self):
        # Path mode never sends DELETE and keeps the outputs for experiments: nothing may remove them.
        with service._job_attempt(self.job) as old:
            with service._job_attempt(self.job):
                (self.job_dir / "raw").mkdir(parents=True)
                (self.job_dir / "raw" / "uv.png").write_bytes(b"x")
            self.assertEqual(old.state(), "superseded")
        self.assertTrue((self.job_dir / "raw" / "uv.png").is_file())
        self.assertNotIn(self.job, service._CURRENT)
        self.assertNotIn(self.job, service._CANCELLED)

    def test_a_cancelled_retry_is_cleaned_by_the_retry_not_by_the_older_attempt(self):
        with service._job_attempt(self.job) as old:
            self.assertEqual(self.delete(), 204)
            with service._job_attempt(self.job) as retry:
                self.assertEqual(self.delete(), 204)   # Spring gave up on the retry too
                (self.job_dir / "raw").mkdir(parents=True)   # predict wrote after that DELETE
                self.assertEqual((old.state(), retry.state()), ("superseded", "cancelled"))
            self.assertFalse(self.job_dir.exists())   # the retry removed what it wrote
        self.assertEqual(len(self.warnings("removed what the cancelled run wrote")), 1)
        self.assertNotIn(self.job, service._CURRENT)
        self.assertNotIn(self.job, service._CANCELLED)

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


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class HealthzBusyModelTest(_ModelModeCase):
    """busyForMs in model mode: the age of the MODEL.predict call still running (FD13: healthz UP, inference stuck)."""

    def test_busy_for_ms_counts_the_model_run_and_is_0_after_it(self):
        entered, go = threading.Event(), threading.Event()

        def stuck_predict(call):
            entered.set()
            go.wait(10)

        self.model.hook = stuck_predict
        thread, box = _in_thread(self.upload)
        try:
            self.assertTrue(entered.wait(10))
            time.sleep(0.1)
            busy = self.client.get("/healthz")
        finally:
            go.set()
            thread.join(10)
        self.assertEqual(busy.status_code, 200)
        self.assertTrue(busy.json()["modelLoaded"])
        self.assertGreaterEqual(busy.json()["busyForMs"], 50)
        self.assertEqual(box["answer"].status_code, 200, box["answer"].text)
        self.assertEqual(self.client.get("/healthz").json()["busyForMs"], 0)
        self.assertEqual(service._BUSY, {})

    def test_waiting_for_the_model_lock_is_not_busy(self):
        gate = _GateLock()
        gate.lock.acquire()   # an earlier job holds the GPU (its own predict is what busyForMs would show)
        with mock.patch.object(service, "MODEL_LOCK", gate):
            thread, box = _in_thread(self.upload)
            try:
                self.assertTrue(gate.waiting.wait(10))
                time.sleep(0.05)
                waiting = self.client.get("/healthz").json()["busyForMs"]
            finally:
                gate.lock.release()
                thread.join(10)
        self.assertEqual(waiting, 0)
        self.assertEqual(box["answer"].status_code, 200, box["answer"].text)

    def test_a_model_error_ends_the_busy_time_too(self):
        def broken(call):
            raise RuntimeError("CUDA out of memory")

        self.model.hook = broken
        with self.assertLogs(service.log, "ERROR"):
            answer = self.upload()
        self.assertEqual(answer.status_code, 500)
        self.assertEqual(answer.json()["detail"]["code"], "INFERENCE_FAILED")
        self.assertEqual(self.client.get("/healthz").json()["busyForMs"], 0)
        self.assertEqual(service._BUSY, {})


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
        served = self.client.get(answer.json()["glbUrl"]).content
        self.assertEqual(served[:4], b"glTF")
        fur = _app_reads_fur(served)   # the only file Spring and the app get carries the fur maps
        self.assertIsNotNone(fur)
        self.assertEqual(fur[0], (self.job_dir / "hair_length.png").read_bytes())
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


def _app_reads_fur(glb):
    """RuntimeModelLoader.TryReadEmbeddedHair (Unity) in Python, step for step: JSON chunk length at byte 12, root
    extras.beside.{hairLengthImage, hairFlowImage, furLength}, image -> bufferView -> bytes at 20 + jsonLen + 8 +
    byteOffset. Returns (length PNG, flow PNG, furLength) or None when the app would find no fur."""
    import struct

    json_len = struct.unpack_from("<I", glb, 12)[0]
    root = json.loads(glb[20:20 + json_len])
    beside = (root.get("extras") or {}).get("beside")
    if beside is None:
        return None
    bin_start = 20 + json_len + 8

    def image(index):
        if index is None:
            return None
        view = root["bufferViews"][root["images"][index]["bufferView"]]
        start = bin_start + view.get("byteOffset", 0)
        return glb[start:start + view["byteLength"]]

    length = image(beside.get("hairLengthImage"))
    return None if length is None else (length, image(beside.get("hairFlowImage")), beside.get("furLength", -1.0))


@unittest.skipIf(not _has_converter_deps(), "numpy/trimesh/pillow not installed")
class FurMapsInGlbTest(unittest.TestCase):
    """tools/convert_asset.py puts the fur maps inside base.glb where the app looks for them (GLB_SPEC §4.1). Before
    2026-11-21 they were only PNG files next to the GLB, which nobody served, so the app drew no fur on any model."""

    def setUp(self):
        import convert_asset

        self.convert_asset = convert_asset
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.raw = Path(folder.name) / "raw"
        self.raw.mkdir()
        _write_real_raw(self.raw)
        self.out = Path(folder.name) / "out"

    def convert(self, hair=True):
        return self.convert_asset.convert(str(self.raw / "mesh.obj"), str(self.raw / "uv.png"), str(self.out / "base.glb"),
                                          str(self.out / "base.metrics.json"),
                                          hair_npz=str(self.raw / "hair.npz") if hair else None)

    def test_the_app_reads_the_fur_maps_from_the_glb(self):
        import struct

        import trimesh

        metrics = self.convert()
        glb = (self.out / "base.glb").read_bytes()
        self.assertEqual(struct.unpack_from("<I", glb, 8)[0], len(glb))   # header length still matches the file
        self.assertEqual(metrics["bytes"], len(glb))
        fur = _app_reads_fur(glb)
        self.assertIsNotNone(fur)
        self.assertEqual(fur[0], (self.out / "hair_length.png").read_bytes())
        self.assertEqual(fur[1], (self.out / "hair_flow.png").read_bytes())
        self.assertEqual(fur[2], json.loads((self.out / "hair_maps.json").read_text())["furLength"])
        self.assertGreater(fur[2], 0)
        self.assertEqual(fur[0][:8], b"\x89PNG\r\n\x1a\n")
        # texture 0 (the app's fallback) is still the body texture, no material uses the fur maps
        root = json.loads(glb[20:20 + struct.unpack_from("<I", glb, 12)[0]])
        self.assertEqual(len(root["textures"]), 1)
        self.assertEqual([i.get("name") for i in root["images"]][1:], ["hair_length", "hair_flow"])
        loaded = trimesh.load(str(self.out / "base.glb"), force="scene")   # still an ordinary GLB for other readers
        self.assertEqual(len(loaded.geometry), 1)

    def test_without_hair_the_glb_has_no_fur_entry(self):
        self.convert(hair=False)
        self.assertIsNone(_app_reads_fur((self.out / "base.glb").read_bytes()))
        self.assertFalse((self.out / "hair_length.png").exists())

    def test_an_unreadable_hair_file_is_a_conversion_error(self):
        (self.raw / "hair.npz").write_bytes(b"not an npz")
        with self.assertRaisesRegex(self.convert_asset.ConversionError, "cannot read hair"):
            self.convert()
        self.assertFalse((self.out / "base.glb").exists())

    def test_fur_maps_over_the_texture_limit_are_refused(self):
        import numpy as np

        np.savez(self.raw / "hair.npz", hair_offsets_local_q=np.zeros((4, 9, 2, 3), np.int8), offset_scale=np.float32(0.01))
        with self.assertRaisesRegex(self.convert_asset.ConversionError, r"hair maps 9x4 > limit 8"):
            self.convert_asset.convert(str(self.raw / "mesh.obj"), str(self.raw / "uv.png"), str(self.out / "base.glb"),
                                       hair_npz=str(self.raw / "hair.npz"), max_texture=8)


class _RiggedCase(_ModelModeCase):
    """BESIDE_RIG_TEMPLATE set, rig_transfer faked to write a rigged GLB of rigged_size bytes."""

    rigged_size = 64

    def converter_patches(self):
        return super().converter_patches() + [
            mock.patch.object(service, "RIG_TEMPLATE", "template-rigged.glb"),
            mock.patch.dict(sys.modules, {"rig_transfer": _fake_rig_module(self.rigged_size)}),
        ]

    def metrics_file(self):
        return json.loads((self.job_dir / "base.metrics.json").read_text())


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class RiggedResultTest(_RiggedCase):
    """The rigged GLB replaces base.glb, and base.metrics.json then describes the rigged file (review 2026-11-20)."""

    def test_a_kept_rigged_glb_rewrites_base_metrics_json(self):
        answer = self.upload()
        self.assertEqual(answer.status_code, 200, answer.text)
        self.assertEqual(answer.json()["modelVersion"], "fake@test:ckpt+rig")
        served = self.client.get(answer.json()["glbUrl"]).content
        self.assertEqual(len(served), self.rigged_size)
        metrics = self.metrics_file()
        self.assertEqual(metrics["bytes"], self.rigged_size)   # was the static GLB's size before the fix
        self.assertEqual(metrics["convertMs"], answer.json()["metrics"]["convertMs"])
        self.assertFalse((self.job_dir / "base.rigged.glb").exists())

    def test_healthz_model_version_never_carries_rig(self):
        """REVIEW_CHECKLIST #18: +rig means that job's rigging succeeded. /healthz names the loaded model for runs that
        failed (Spring records it on them), so a configured rig template must not add +rig there."""
        self.assertEqual(self.upload().json()["modelVersion"], "fake@test:ckpt+rig")
        self.assertEqual(self.client.get("/healthz").json()["modelVersion"], "fake@test:ckpt")


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class OversizeRiggedResultTest(_RiggedCase):
    """A rigged GLB over the GLB_SPEC §1 size limit is dropped like a failed transfer (review 2026-11-20): convert
    checked only the static GLB, and the rigged one adds the template's skin, clips and the raw uv.png."""

    rigged_size = 10 * 1024 * 1024 + 1   # one byte over convert_asset.MAX_GLB_BYTES

    def test_a_rigged_glb_over_the_size_limit_keeps_the_static_glb(self):
        answer = self.upload()
        self.assertEqual(answer.status_code, 200, answer.text)
        self.assertEqual(answer.json()["modelVersion"], "fake@test:ckpt")   # no +rig: the static result is served
        self.assertEqual(self.client.get(answer.json()["glbUrl"]).content, GLB_STUB)
        self.assertEqual(self.metrics_file()["bytes"], len(GLB_STUB))
        self.assertFalse((self.job_dir / "base.rigged.glb").exists())
        skipped = self.warnings("rig transfer skipped")
        self.assertEqual(len(skipped), 1)
        self.assertIn("> limit 10485760", skipped[0])


def _has_scipy():
    try:
        import scipy.ndimage  # noqa: F401
    except ImportError:
        return False
    return True


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class ConverterSwitchTest(_ModelModeCase):
    """BESIDE_EMBED_HAIR / BESIDE_HAIR_META / BESIDE_PAD_TEXTURE reach convert (port of the branch Unity 2d2e235, 2026-11-21).
    Unset, the call is the one from before the port: hair.npz in, no extras."""

    def run_job(self, **switches):
        with contextlib.ExitStack() as stack:
            for name, value in switches.items():
                stack.enter_context(mock.patch.object(service, name, value))
            answer = self.upload()
        self.assertEqual(answer.status_code, 200, answer.text)
        self.assertEqual(len(self.converted), 1)
        return self.converted[0]

    def test_by_default_the_fur_maps_go_in_and_no_extra_is_passed(self):
        call = self.run_job()
        self.assertTrue(call["hair_npz"].endswith("hair.npz"), call)
        self.assertIsNone(call["hair_meta"])
        self.assertFalse(call["padded_texture"])

    def test_embed_hair_0_converts_without_the_hair_file(self):
        self.assertIsNone(self.run_job(EMBED_HAIR=False)["hair_npz"])

    def test_the_opt_in_extras_reach_the_converter(self):
        call = self.run_job(HAIR_META="assets/hair_map_meta_uvanchor.npz", PAD_TEXTURE=True)
        self.assertEqual(call["hair_meta"], "assets/hair_map_meta_uvanchor.npz")
        self.assertTrue(call["padded_texture"])
        self.assertTrue(call["hair_npz"].endswith("hair.npz"))


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class PaddedTextureRigTest(_RiggedCase):
    """With BESIDE_PAD_TEXTURE=1 the rigged GLB gets the filled texture_padded.png instead of the raw uv.png."""

    def converter_patches(self):
        self.rig_textures = []
        return [mock.patch.dict(sys.modules, {"convert_asset": _fake_convert_module(self.converted)}),
                mock.patch.object(service, "RIG_TEMPLATE", "template-rigged.glb"),
                mock.patch.dict(sys.modules, {"rig_transfer": _fake_rig_module(self.rigged_size, self.rig_textures)})]

    def test_the_rig_gets_the_padded_texture(self):
        with mock.patch.object(service, "PAD_TEXTURE", True):
            answer = self.upload()
        self.assertEqual(answer.status_code, 200, answer.text)
        self.assertEqual(answer.json()["modelVersion"], "fake@test:ckpt+rig")
        self.assertEqual([Path(t).name for t in self.rig_textures], ["texture_padded.png"])

    def test_without_the_switch_the_rig_gets_uv_png(self):
        answer = self.upload()
        self.assertEqual(answer.status_code, 200, answer.text)
        self.assertEqual([Path(t).name for t in self.rig_textures], ["uv.png"])


@unittest.skipIf(TestClient is None or not _has_converter_deps(), "fastapi/httpx or numpy/trimesh/pillow not installed")
class RealEmbedHairOffTest(_ModelModeCase):
    """BESIDE_EMBED_HAIR=0 with the real converter: the served GLB has no extras.beside, so the app draws no server fur
    (the off switch GLB_SPEC §4.1 '알려진 점' asked for)."""

    write_raw = staticmethod(_write_real_raw)

    def converter_patches(self):
        return []

    def test_the_served_glb_has_no_fur_maps(self):
        with mock.patch.object(service, "EMBED_HAIR", False):
            answer = self.upload()
        self.assertEqual(answer.status_code, 200, answer.text)
        served = self.client.get(answer.json()["glbUrl"]).content
        self.assertEqual(served[:4], b"glTF")
        self.assertIsNone(_app_reads_fur(served))
        self.assertFalse((self.job_dir / "hair_length.png").exists())


def _write_green_texture(path, size=16):
    """uv.png like AnimalLift's: pure green background, one brown UV island in the middle."""
    from PIL import Image

    image = Image.new("RGB", (size, size), (0, 255, 0))
    image.paste((150, 90, 40), (4, 4, 12, 12))
    image.save(path)


def _glb_base_color(glb):
    """The base colour image (textures[0]) of a GLB, decoded."""
    import struct

    from PIL import Image

    json_len = struct.unpack_from("<I", glb, 12)[0]
    root = json.loads(glb[20:20 + json_len])
    view = root["bufferViews"][root["images"][root["textures"][0]["source"]]["bufferView"]]
    start = 20 + json_len + 8 + view.get("byteOffset", 0)
    return Image.open(io.BytesIO(glb[start:start + view["byteLength"]])).convert("RGB")


@unittest.skipIf(not _has_converter_deps() or not _has_scipy(), "numpy/trimesh/pillow/scipy not installed")
class PadTextureTest(unittest.TestCase):
    """convert(padded_texture=True) / --pad-texture (branch Unity 2d2e235): uv.png's green background takes the nearest
    island colour in the GLB and in texture_padded.png. Off by default, so GLB_SPEC §4 stays true as written."""

    def setUp(self):
        import convert_asset

        self.convert_asset = convert_asset
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.raw = Path(folder.name) / "raw"
        self.raw.mkdir()
        _write_real_raw(self.raw)
        _write_green_texture(self.raw / "uv.png")
        self.out = Path(folder.name) / "out"

    def convert(self, out=None, **options):
        out = out or self.out
        return self.convert_asset.convert(str(self.raw / "mesh.obj"), str(self.raw / "uv.png"), str(out / "base.glb"),
                                          hair_npz=str(self.raw / "hair.npz"), **options)

    @staticmethod
    def green_pixels(image):
        return sum(1 for pixel in image.getdata() if pixel == (0, 255, 0))

    def test_the_glb_and_the_copy_have_no_green_background(self):
        from PIL import Image

        self.convert(padded_texture=True)
        copy = Image.open(self.out / "texture_padded.png").convert("RGB")
        self.assertEqual(set(copy.getdata()), {(150, 90, 40)})   # every background pixel took the island colour
        glb = (self.out / "base.glb").read_bytes()
        self.assertEqual(list(_glb_base_color(glb).getdata()), list(copy.getdata()))
        self.assertIsNotNone(_app_reads_fur(glb))   # the fur maps are still there

    def test_off_by_default(self):
        self.convert()
        self.assertFalse((self.out / "texture_padded.png").exists())
        self.assertGreater(self.green_pixels(_glb_base_color((self.out / "base.glb").read_bytes())), 0)

    def test_a_missing_output_folder_is_still_not_created(self):
        gone = self.out.parent / "gone"
        with self.assertRaisesRegex(self.convert_asset.ConversionError, "output folder is gone"):
            self.convert(out=gone, padded_texture=True, make_dirs=False)
        self.assertFalse(gone.exists())

    def test_a_texture_without_background_is_left_alone(self):
        from PIL import Image

        image = Image.new("RGB", (8, 8), (200, 150, 100))
        self.assertEqual(list(self.convert_asset.pad_texture(image).getdata()), list(image.getdata()))


@unittest.skipIf(not _has_converter_deps() or not _has_scipy(), "numpy/trimesh/pillow/scipy not installed")
class HairMetaTest(unittest.TestCase):
    """convert(hair_meta=...) / --hair-meta / BESIDE_HAIR_META (branch Unity 2d2e235): fur maps rebuilt from AnimalLift's
    guide texels, zero where the guides do not reach, plus hair_region.png. Off by default; a meta file that cannot be
    used keeps the raw maps byte for byte."""

    def setUp(self):
        import numpy as np

        import convert_asset

        self.np = np
        self.convert_asset = convert_asset
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.dir = Path(folder.name)
        self.raw = self.dir / "raw"
        self.raw.mkdir()
        _write_real_raw(self.raw)
        np.savez(self.raw / "hair.npz", hair_offsets_local_q=np.full((16, 16, 2, 3), 20, np.int8),
                 offset_scale=np.float32(0.01))

    def convert(self, name, **options):
        out = self.dir / name
        self.convert_asset.convert(str(self.raw / "mesh.obj"), str(self.raw / "uv.png"), str(out / "base.glb"),
                                   hair_npz=str(self.raw / "hair.npz"), **options)
        return out, _app_reads_fur((out / "base.glb").read_bytes())

    def meta(self, shape=(16, 16), uv_columns=8):
        """Guides on every texel of the left uv_columns columns (one UV island), nothing to the right of it."""
        np = self.np
        group = np.full(shape, -1, np.int32)
        group[:, :uv_columns] = np.arange(shape[0] * uv_columns).reshape(shape[0], uv_columns) % 7
        faces = np.full(shape, -1, np.int32)
        faces[:, :uv_columns] = 1
        path = self.dir / "assets" / "hair_map_meta_uvanchor.npz"
        path.parent.mkdir(exist_ok=True)
        np.savez(path, guide_group_id_map=group, uv_face_index_map=faces)
        return path

    def pixels(self, png):
        from PIL import Image

        return self.np.asarray(Image.open(io.BytesIO(png)))

    def test_fur_only_where_the_guides_reach(self):
        out, fur = self.convert("meta", hair_meta=str(self.meta()))
        length = self.pixels(fur[0])
        self.assertTrue((length[:, :8] > 0).all())     # the island
        self.assertTrue((length[:, 11:] == 0).all())   # 4 texels past it and further: no fur (3 texels of seam padding)
        self.assertGreater(fur[2], 0)
        region = self.pixels((out / "hair_region.png").read_bytes())
        self.assertTrue((region[:, :8] < 7).all())
        self.assertTrue((region[:, 11:] == 255).all())

    def test_a_meta_file_that_cannot_be_used_keeps_the_raw_maps(self):
        _, raw = self.convert("raw")
        with self.assertLogs("convert_asset", "WARNING"):
            out, missing = self.convert("missing", hair_meta=str(self.dir / "nope.npz"))
        self.assertEqual(missing, raw)
        self.assertFalse((out / "hair_region.png").exists())
        with self.assertLogs("convert_asset", "WARNING"):
            _, other_grid = self.convert("grid", hair_meta=str(self.meta(shape=(8, 8), uv_columns=4)))
        self.assertEqual(other_grid, raw)

    def test_nose_and_paw_pads_are_bare_when_base_texture_is_there(self):
        from PIL import Image

        np = self.np
        assets = self.dir / "assets"
        assets.mkdir(exist_ok=True)
        texture = np.full((512, 512, 3), 255, np.uint8)
        texture[180:200, 250:270] = 0   # dark skin inside the nose box (x 232..290, y 166..218)
        texture[20:40, 20:40] = 0       # dark but outside every box: keeps its fur
        Image.fromarray(texture).save(assets / "base_texture.png")
        mask = self.convert_asset._hairless_mask(str(assets), (512, 512))[::-1]   # back to image rows
        self.assertTrue(mask[185:195, 255:265].all())
        self.assertFalse(mask[20:40, 20:40].any())
        self.assertFalse(self.convert_asset._hairless_mask(str(self.dir), (512, 512)).any())   # no base_texture.png


def _write_glb(path, gltf, arrays):
    """A GLB with one buffer: arrays[i] (numpy) becomes accessor i in its own bufferView, then gltf (the rest of the
    JSON: nodes, meshes, ...) refers to them by number."""
    import struct

    import numpy as np

    kinds = {np.dtype(np.float32): 5126, np.dtype(np.uint32): 5125, np.dtype(np.uint16): 5123, np.dtype(np.uint8): 5121}
    shapes = {1: "SCALAR", 2: "VEC2", 3: "VEC3", 4: "VEC4", 16: "MAT4"}
    blob, views, accessors = bytearray(), [], []
    for array in arrays:
        rows = np.ascontiguousarray(array).reshape(len(array), -1)
        views.append({"buffer": 0, "byteOffset": len(blob), "byteLength": rows.nbytes})
        accessors.append({"bufferView": len(views) - 1, "componentType": kinds[rows.dtype], "count": len(rows),
                          "type": shapes[rows.shape[1]]})
        blob += rows.tobytes()
        blob += b"\0" * ((4 - len(blob) % 4) % 4)
    gltf = dict(gltf, asset={"version": "2.0"}, buffers=[{"byteLength": len(blob)}], bufferViews=views, accessors=accessors)
    js = json.dumps(gltf).encode()
    js += b" " * ((4 - len(js) % 4) % 4)
    total = 12 + 8 + len(js) + 8 + len(blob)
    Path(path).write_bytes(struct.pack("<4sII", b"glTF", 2, total) + struct.pack("<I4s", len(js), b"JSON") + js
                           + struct.pack("<I4s", len(blob), b"BIN\x00") + bytes(blob))


# a square pyramid: base 0..3 on y = 0, apex 4 (unlike a tetrahedron, renumbering its vertices changes its triangles)
PYRAMID = [[-0.5, 0, -0.5], [0.5, 0, -0.5], [0.5, 0, 0.5], [-0.5, 0, 0.5], [0, 1, 0]]
PYRAMID_FACES = [[0, 1, 4], [1, 2, 4], [2, 3, 4], [3, 0, 4], [0, 2, 1], [0, 3, 2]]


def _has_numpy():
    try:
        import numpy  # noqa: F401
    except ImportError:
        return False
    return True


class _RigCase(unittest.TestCase):
    """A two-joint pyramid template and pyramid base GLBs for tools/rig_transfer.py."""

    TEMPLATE_UV = None   # per-vertex TEXCOORD_0 of the template, or None for a template without UVs

    def setUp(self):
        import numpy as np

        import rig_transfer

        self.np, self.rig = np, rig_transfer
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.folder = Path(folder.name)
        self.template = self.folder / "template.glb"
        pos = np.array(PYRAMID, np.float32)
        _write_glb(self.template, {
            "scene": 0, "scenes": [{"nodes": [0, 1]}],
            "nodes": [{"name": "base", "mesh": 0, "skin": 0}, {"name": "Root", "children": [2]},
                      {"name": "Pelvis", "translation": [0, 0.5, 0]}],
            "meshes": [{"primitives": [{"attributes": dict({"POSITION": 0, "NORMAL": 1, "JOINTS_0": 3, "WEIGHTS_0": 4},
                                                           **({} if self.TEMPLATE_UV is None else {"TEXCOORD_0": 8})),
                                        "indices": 2}]}],
            "skins": [{"joints": [1, 2], "inverseBindMatrices": 5}],
            "animations": [{"name": "Idle", "samplers": [{"input": 6, "output": 7}],
                            "channels": [{"sampler": 0, "target": {"node": 2, "path": "translation"}}]}],
        }, [pos, self.normals(pos), np.array(PYRAMID_FACES, np.uint16).reshape(-1),
            np.tile(np.array([0, 1, 0, 0], np.uint8), (5, 1)), np.tile(np.array([0.5, 0.5, 0, 0], np.float32), (5, 1)),
            np.tile(np.eye(4, dtype=np.float32).reshape(16), (2, 1)),
            np.array([0, 1], np.float32), np.array([[0, 0.5, 0], [0, 0.6, 0]], np.float32)]
            + ([] if self.TEMPLATE_UV is None else [np.array(self.TEMPLATE_UV, np.float32)]))

    def normals(self, pos):
        n = pos - pos.mean(0)
        return (n / self.np.linalg.norm(n, axis=1, keepdims=True)).astype(self.np.float32)

    def base(self, pos, faces, uv=None):
        path = self.folder / "base.glb"
        pos = self.np.asarray(pos, self.np.float32)
        attributes = {"POSITION": 0, "NORMAL": 1} if uv is None else {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 3}
        _write_glb(path, {"scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"name": "base", "mesh": 0}],
                          "meshes": [{"primitives": [{"attributes": attributes, "indices": 2}]}]},
                   [pos, self.normals(pos), self.np.array(faces, self.np.uint16).reshape(-1)]
                   + ([] if uv is None else [self.np.asarray(uv, self.np.float32)]))
        return path


@unittest.skipIf(TestClient is None or not _has_numpy(), "fastapi/httpx or numpy not installed")
class RigTransferVertexOrderTest(_RigCase):
    """tools/rig_transfer.py keeps the template's triangles, UVs and skin weights and takes only the new positions,
    so a base GLB with the template's counts but another vertex order must be refused, not rigged into a torn mesh."""

    def test_the_same_vertex_order_is_rigged_also_with_faces_listed_differently(self):
        np = self.np
        bigger = np.array(PYRAMID) * 1.2
        faces = [f[1:] + f[:1] for f in PYRAMID_FACES[::-1]]   # other face order, other first corner
        out = self.folder / "rigged.glb"
        info = self.rig.transfer(str(self.base(bigger, faces)), None, str(self.template), str(out))
        self.assertEqual((info["joints"], info["clips"]), (2, ["Idle"]))
        self.assertAlmostEqual(info["heightRatio"], 1.2, places=3)
        rigged = self.rig.Glb(str(out))
        positions = rigged.read(rigged.json["meshes"][0]["primitives"][0]["attributes"]["POSITION"])
        np.testing.assert_allclose(positions, bigger, atol=1e-6)

    def test_another_vertex_order_with_the_same_counts_is_refused(self):
        np = self.np
        order = [0, 4, 2, 3, 1]   # vertex 1 and the apex swap numbers: same shape, same counts
        renumber = {old: new for new, old in enumerate(order)}
        faces = [[renumber[v] for v in face] for face in PYRAMID_FACES]
        # other vertex-number triples (swapping 0 and the apex would not change them: a symmetry of this mesh)
        self.assertNotEqual(sorted(map(sorted, faces)), sorted(map(sorted, PYRAMID_FACES)))
        base = self.base(np.array(PYRAMID)[order], faces)
        out = self.folder / "rigged.glb"
        with self.assertRaisesRegex(self.rig.RigError, "vertex order differs"):
            self.rig.transfer(str(base), None, str(self.template), str(out))
        self.assertFalse(out.exists())
        with contextlib.redirect_stderr(io.StringIO()) as stderr:   # the CLI: exit code 2, keep the static GLB
            code = self.rig.main(["--base", str(base), "--template", str(self.template), "--out", str(out)])
        self.assertEqual(code, 2)
        self.assertIn("RIG_FAILED: vertex order differs", stderr.getvalue())


@unittest.skipIf(TestClient is None or not _has_numpy(), "fastapi/httpx or numpy not installed")
class ReorderRigTemplateTest(_RigCase):
    """tools/reorder_rig_template.py renumbers a template's vertices to a converter output's numbering once, so the
    real SMAL-derived template (np.unique numbering) passes rig_transfer's vertex-order check (2026-11-21)."""

    # reference vertex k is template vertex ORDER[k]; faces keep the template's order. A 5-cycle, not its own inverse:
    # the old [0, 4, 2, 3, 1] only swapped 1 and 4, so data moved the wrong way (perm for inverse) passed every check
    # here (review finding, 2026-11-25).
    ORDER = [1, 2, 3, 4, 0]
    TEMPLATE_UV = [[0, 0], [1, 0], [1, 1], [0, 1], [0.5, 0.5]]   # distinct per vertex, so the UV checks run

    def reference(self, scale=1.0, uv=None):
        renumber = {old: new for new, old in enumerate(self.ORDER)}
        faces = [[renumber[v] for v in face] for face in PYRAMID_FACES]
        uv = self.np.array(self.TEMPLATE_UV)[self.ORDER] if uv is None else uv
        return self.base(self.np.array(PYRAMID)[self.ORDER] * scale, faces, uv)

    def test_a_renumbered_template_rigs_a_base_the_original_template_refuses(self):
        import reorder_rig_template

        np = self.np
        base = self.reference(scale=1.2)
        out = self.folder / "rigged.glb"
        with self.assertRaisesRegex(self.rig.RigError, "vertex order differs"):
            self.rig.transfer(str(base), None, str(self.template), str(out))
        fixed = self.folder / "template-rigged.glb"
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            code = reorder_rig_template.main(["--template", str(self.template), "--reference", str(base),
                                              "--out", str(fixed)])
        self.assertEqual(code, 0)
        self.assertIn("5 of 5 vertices renumbered", stdout.getvalue())
        info = self.rig.transfer(str(base), None, str(fixed), str(out))
        self.assertEqual((info["joints"], info["clips"]), (2, ["Idle"]))
        rigged = self.rig.Glb(str(out))
        positions = rigged.read(rigged.json["meshes"][0]["primitives"][0]["attributes"]["POSITION"])
        np.testing.assert_allclose(positions, np.array(PYRAMID)[self.ORDER] * 1.2, atol=1e-6)
        # the skin follows its vertex: renumbered template vertex k carries original template vertex ORDER[k]
        tpl, new = self.rig.Glb(str(self.template)), self.rig.Glb(str(fixed))
        attrs = tpl.json["meshes"][0]["primitives"][0]["attributes"]
        for name in ("POSITION", "NORMAL", "TEXCOORD_0", "JOINTS_0", "WEIGHTS_0"):
            np.testing.assert_array_equal(new.read(attrs[name]), tpl.read(attrs[name])[self.ORDER])
        # a direction error (the inverse of ORDER) gives other per-vertex data for this 5-cycle
        inverse = np.argsort(self.ORDER)
        self.assertFalse(np.array_equal(tpl.read(attrs["POSITION"])[inverse], tpl.read(attrs["POSITION"])[self.ORDER]))

    def test_a_reference_with_another_uv_layout_is_refused(self):
        import reorder_rig_template

        uv = self.np.array(self.TEMPLATE_UV)[self.ORDER][::-1]   # same triangles, UVs of other vertices
        base = self.reference(uv=uv)
        fixed = self.folder / "template-rigged.glb"
        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            code = reorder_rig_template.main(["--template", str(self.template), "--reference", str(base),
                                              "--out", str(fixed)])
        self.assertEqual(code, 2)
        self.assertIn("UVs differ", stderr.getvalue())
        self.assertFalse(fixed.exists())

    def test_a_reference_with_another_face_order_is_refused_and_nothing_is_written(self):
        import reorder_rig_template

        renumber = {old: new for new, old in enumerate(self.ORDER)}
        faces = [[renumber[v] for v in face] for face in PYRAMID_FACES[::-1]]   # same mesh, faces listed reversed
        base = self.base(self.np.array(PYRAMID)[self.ORDER], faces)
        fixed = self.folder / "template-rigged.glb"
        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            code = reorder_rig_template.main(["--template", str(self.template), "--reference", str(base),
                                              "--out", str(fixed)])
        self.assertEqual(code, 2)
        self.assertIn("REORDER_FAILED", stderr.getvalue())
        self.assertFalse(fixed.exists())


@unittest.skipIf(TestClient is None or not _has_converter_deps(), "fastapi/httpx or numpy/trimesh/pillow not installed")
class RigTransferFurMapsTest(_RigCase):
    """The rigged GLB is rebuilt from the template's JSON, so the base GLB's fur maps (extras.beside) have to be
    carried over, and a template's own fur maps (another animal) must not reach the new model."""

    def with_fur(self, path, length, flow, fur_length):
        import convert_asset

        path.write_bytes(convert_asset.embed_hair(path.read_bytes(), length, flow, fur_length))
        return path

    def rigged(self, base):
        out = self.folder / "rigged.glb"
        info = self.rig.transfer(str(base), None, str(self.template), str(out))
        glb = out.read_bytes()
        self.assertEqual(info["bytes"], len(glb))
        return glb

    def test_the_base_glbs_fur_maps_are_kept(self):
        base = self.with_fur(self.base(self.np.array(PYRAMID) * 1.1, PYRAMID_FACES), b"LENGTH-PNG", b"FLOW-PNG", 0.012)
        self.assertEqual(_app_reads_fur(self.rigged(base)), (b"LENGTH-PNG", b"FLOW-PNG", 0.012))

    def test_the_templates_own_fur_is_never_kept(self):
        self.with_fur(self.template, b"TEMPLATE-LENGTH", b"TEMPLATE-FLOW", 0.5)
        plain = self.base(PYRAMID, PYRAMID_FACES)
        self.assertIsNone(_app_reads_fur(self.rigged(plain)))
        furry = self.with_fur(self.base(PYRAMID, PYRAMID_FACES), b"LENGTH-PNG", b"FLOW-PNG", 0.012)
        self.assertEqual(_app_reads_fur(self.rigged(furry)), (b"LENGTH-PNG", b"FLOW-PNG", 0.012))


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


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class RequestTimingTest(_ModelModeCase):
    """BESIDE_REQUEST_TIMING=1: one line per request with the Pod-side times (load test round 4)."""

    LINE = (r"^timing (?P<method>\S+) (?P<path>\S+) (?P<status>\S+) client=(?P<client>\S+) in=(?P<inb>\d+) "
            r"body_ms=(?P<body>\S+) start_ms=(?P<start>\S+) end_ms=(?P<end>\S+) gone_ms=(?P<gone>\S+) "
            r"out=(?P<out>\d+) err=(?P<err>\S+) at=(?P<at>\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z)$")

    def setUp(self):
        super().setUp()
        self.lines = _Records()
        service.timing_log.addHandler(self.lines)
        self.addCleanup(service.timing_log.removeHandler, self.lines)
        level = service.timing_log.level
        service.timing_log.setLevel(logging.INFO)
        self.addCleanup(service.timing_log.setLevel, level)
        self.client = TestClient(service._RequestTiming(service.app))

    def timings(self):
        import re as _re
        out = []
        for record in self.lines.records:
            match = _re.match(self.LINE, record.getMessage())
            self.assertIsNotNone(match, record.getMessage())
            out.append(match.groupdict())
        return out

    def test_off_unless_the_setting_is_1(self):
        # The tests import the service without BESIDE_REQUEST_TIMING: no middleware, no handler.
        self.assertFalse(service.REQUEST_TIMING)
        self.assertFalse(any(m.cls is service._RequestTiming for m in service.app.user_middleware))

    def test_a_path_with_a_newline_or_a_space_stays_one_line_with_the_same_fields(self):
        # 2026-11-26 pre-deploy review: scope["path"] is percent-decoded and the Pod writes these lines by default now,
        # with no token needed (this layer wraps the token check), so %0A must not start a forged line
        self.client.get("/artifacts/x%0Atiming%20GET%20/forged%20200/base.glb")
        self.assertEqual(len(self.lines.records), 1)
        self.assertNotIn(chr(10), self.lines.records[0].getMessage())
        (line,) = self.timings()
        self.assertEqual(line["path"], "/artifacts/x?timing?GET?/forged?200/base.glb")

    def test_an_upload_and_its_glb_download_are_timed(self):
        answer = self.upload()
        self.assertEqual(answer.status_code, 200, answer.text)
        glb = self.client.get(answer.json()["glbUrl"])
        self.assertEqual((glb.status_code, glb.content), (200, GLB_STUB))
        up, down = self.timings()
        self.assertEqual((up["method"], up["path"], up["status"]), ("POST", "/infer/upload", "200"))
        self.assertGreater(int(up["inb"]), len(b"\xff\xd8\xff\xe0 jpeg"))   # the whole multipart body
        self.assertLessEqual(float(up["body"]), float(up["start"]))          # read, then answered
        self.assertLessEqual(float(up["start"]), float(up["end"]))
        self.assertEqual(int(up["out"]), len(answer.content))
        self.assertEqual((down["method"], down["path"], down["status"]), ("GET", f"/artifacts/{self.job}/base.glb", "200"))
        self.assertEqual((int(down["inb"]), int(down["out"])), (0, len(GLB_STUB)))
        self.assertLessEqual(float(down["start"]), float(down["end"]))
        self.assertEqual(down["body"], "-")   # no request body bytes, although FileResponse polls receive
        self.assertEqual((up["gone"], up["err"], down["gone"], down["err"]), ("-", "-", "-", "-"))

    def test_a_request_refused_by_the_token_check_is_timed_without_reading_its_body(self):
        with mock.patch.object(service, "INFERENCE_TOKEN", "secret"):
            answer = self.upload()
        self.assertEqual(answer.status_code, 401)
        (line,) = self.timings()
        self.assertEqual((line["path"], line["status"], line["body"]), ("/infer/upload", "401", "-"))

    def test_an_exception_is_logged_with_its_name_and_raised_unchanged(self):
        self.job_dir.mkdir(parents=True)
        client = TestClient(service._RequestTiming(service.app), raise_server_exceptions=False)
        with mock.patch.object(service.shutil, "rmtree", side_effect=PermissionError(errno.EACCES, "denied")), \
                mock.patch.object(service, "_REMOVE_PAUSE_S", 0):
            answer = client.delete(f"/artifacts/{self.job}")
        self.assertEqual(answer.status_code, 500)
        (line,) = self.timings()
        self.assertEqual((line["method"], line["status"], line["err"]), ("DELETE", "500", "PermissionError"))

    def test_the_production_wiring_wraps_the_token_check_and_logs_no_secret(self):
        # Import the service as the Pod does (setting on), in a fresh interpreter: middleware order, stderr line,
        # neither the token sent nor the query string in it.
        script = (
            "import sys; sys.path.insert(0, sys.argv[1]); import app\n"
            "kinds =[m.cls.__name__ for m in app.app.user_middleware]\n"
            "assert kinds[:2] == ['_RequestTiming', 'BaseHTTPMiddleware'], kinds\n"
            "from fastapi.testclient import TestClient\n"
            "c = TestClient(app.app, raise_server_exceptions=False)\n"
            "r = c.post('/infer/upload?q=leakme', headers={'X-Inference-Token': 'wrong-secret'}, data={'request': '{}'})\n"
            "assert r.status_code == 401, r.status_code\n"
        )
        import subprocess
        env = dict(os.environ, BESIDE_REQUEST_TIMING="1", BESIDE_INFERENCE_TOKEN="right-secret", BESIDE_WORK_DIR=WORK)
        done = subprocess.run([sys.executable, "-c", script, str(HERE)], env=env, capture_output=True, text=True,
                              timeout=120)
        self.assertEqual(done.returncode, 0, done.stderr[-2000:])
        lines = [l for l in done.stderr.splitlines() if l.startswith("timing ")]
        self.assertEqual(len(lines), 1, done.stderr[-2000:])
        self.assertRegex(lines[0], r"^timing POST /infer/upload 401 ")
        for secret in ("wrong-secret", "right-secret", "leakme"):
            self.assertNotIn(secret, lines[0])


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class ServiceLogTest(_ModelModeCase):
    """Service log (plan B4, 2026-11-26, README '로그'): every line has its UTC time, level and logger; routine lines are
    INFO; every failure answer that nothing else logged is a WARNING naming the job; X-Request-Id and the traceparent's
    trace id go with every line of their request, and never the token."""

    TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
    TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01"
    LINE = r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z (INFO|WARNING|ERROR) (beside\.inference|convert_asset) "

    def failures(self):
        return [r.getMessage() for r in self.records.records if r.levelno == logging.WARNING]

    def photo(self):
        path = Path(tempfile.mkdtemp(prefix="beside-log-")) / "dog.jpg"
        self.addCleanup(shutil.rmtree, path.parent, True)
        path.write_bytes(b"\xff\xd8\xff\xe0 jpeg")
        return path

    def infer(self, image):
        return self.client.post("/infer", json={"jobId": self.job, "imagePaths": [str(image)]})

    def test_a_line_has_its_utc_time_level_logger_and_request(self):
        record = logging.LogRecord("beside.inference", logging.WARNING, __file__, 1, "job %s: %d %s", ("j-1", 503, "X"),
                                   None)
        record.created, record.msecs = 86400.25, 250.0
        formatter = service.LOG_HANDLER.formatter
        self.assertEqual(formatter.format(record), "1970-01-02T00:00:00.250Z WARNING beside.inference job j-1: 503 X")
        record.request_id, record.trace_id = "j-1-2", self.TRACE_ID
        self.assertEqual(formatter.format(record), "1970-01-02T00:00:00.250Z WARNING beside.inference job j-1: 503 X "
                         f"rid=j-1-2 trace={self.TRACE_ID}")
        self.assertTrue(service.log.isEnabledFor(logging.INFO))   # routine lines are INFO now, and shown

    def test_a_fresh_process_writes_info_lines_with_time_and_level_to_stderr(self):
        """As the Pod imports it: no logging set up by anyone else; INFO shows, DEBUG not, the converter's warnings too."""
        script = ("import sys, logging; sys.path.insert(0, sys.argv[1]); import app\n"
                  "app.log.info('hello %s', 'pod'); app.log.debug('hidden')\n"
                  "logging.getLogger('convert_asset').warning('converter says')\n")
        import subprocess
        done = subprocess.run([sys.executable, "-c", script, str(HERE)], capture_output=True, text=True, timeout=120,
                              env=dict(os.environ, BESIDE_WORK_DIR=WORK))
        self.assertEqual(done.returncode, 0, done.stderr[-2000:])
        lines = done.stderr.splitlines()
        self.assertTrue(any(re.match(self.LINE + r"hello pod$", l) and " INFO beside.inference " in l for l in lines),
                        done.stderr[-2000:])
        self.assertTrue(any(re.match(self.LINE + r"converter says$", l) and " WARNING convert_asset " in l for l in lines),
                        done.stderr[-2000:])
        self.assertNotIn("hidden", done.stderr)

    def test_every_failure_answer_is_a_warning_naming_the_job(self):
        photo = self.photo()
        module = sys.modules["convert_asset"]
        with mock.patch.object(service, "MODEL", None):
            loading = self.upload()
        with mock.patch.object(service, "ANIMALLIFT_DIR", None):
            unconfigured = self.infer(photo)
        missing_photo = self.infer(photo.parent / "gone.jpg")
        with mock.patch.object(service, "SAMPLE_GLB", str(photo.parent / "no-sample.glb")):
            no_sample = self.infer(photo)
        with mock.patch.object(module, "convert", side_effect=module.ConversionError("GLB_SPEC: 90000 triangles")):
            conversion = self.upload()
        no_glb = self.client.get(f"/artifacts/{self.job}/base.glb")
        eleven = self.client.post("/infer/upload", data={"request": json.dumps({"jobId": self.job})},
                                  files=[("photos", (f"{i}.jpg", b"\xff\xd8", "image/jpeg")) for i in range(11)])
        bad_part = self.client.post("/infer/upload", data={"request": "{}"},
                                    files=[("photos", ("dog.jpg", b"\xff\xd8", "image/jpeg"))])
        bad_body = self.client.post("/infer", json={"jobId": "../x", "imagePaths": [str(photo)]})

        self.assertEqual([a.status_code for a in (loading, unconfigured, missing_photo, no_sample, conversion, no_glb,
                                                  eleven, bad_part, bad_body)],
                         [503, 501, 400, 500, 500, 404, 422, 422, 422])
        warned = self.failures()
        for prefix in (f"job {self.job}: 503 INFERENCE_UNAVAILABLE AnimalLift is still loading",
                       f"job {self.job}: 501 INFERENCE_UNAVAILABLE No model configured",
                       f"job {self.job}: 400 INFERENCE_FAILED image not found: ",
                       f"job {self.job}: 500 CONVERSION_FAILED BESIDE_SAMPLE_GLB not found: ",
                       f"job {self.job}: 500 CONVERSION_FAILED GLB_SPEC: 90000 triangles",
                       f"job {self.job}: 404 base.glb not in the work folder",
                       f"job {self.job}: 422 1 to 10 photos, got 11",
                       "POST /infer/upload: 422 invalid request part ",
                       "POST /infer: 422 invalid request "):
            self.assertEqual(len([w for w in warned if w.startswith(prefix)]), 1, (prefix, warned))
        self.assertEqual(len(warned), 9, warned)   # one line per failure answer, no second line for any of them
        self.assertNotIn("../x", " ".join(warned))   # what was wrong, not the input itself

    def test_a_refused_token_is_logged_without_either_token(self):
        with mock.patch.object(service, "INFERENCE_TOKEN", "right-secret"):
            wrong = self.client.post("/infer/upload", headers={"X-Inference-Token": "wrong-secret",
                                                               "X-Request-Id": f"{self.job}-3"},
                                     data={"request": json.dumps({"jobId": self.job})},
                                     files=[("photos", ("dog.jpg", b"\xff\xd8", "image/jpeg"))])
            missing = self.client.delete(f"/artifacts/{self.job}")
        self.assertEqual((wrong.status_code, missing.status_code), (401, 401))
        warned = [r for r in self.records.records if r.levelno == logging.WARNING]
        self.assertEqual([r.getMessage() for r in warned],
                         ["POST /infer/upload: 401 INFERENCE_UNAVAILABLE wrong X-Inference-Token",
                          f"DELETE /artifacts/{self.job}: 401 INFERENCE_UNAVAILABLE missing X-Inference-Token"])
        self.assertEqual([r.request_id for r in warned], [f"{self.job}-3", None])
        for record in self.records.records:
            text = service.LOG_HANDLER.formatter.format(record)
            self.assertNotIn("secret", text)

    def test_the_request_id_and_trace_go_with_every_line_of_the_request(self):
        answer = self.client.post("/infer/upload", headers={"X-Request-Id": f"{self.job}-2", "traceparent": self.TRACEPARENT},
                                  data={"request": json.dumps({"jobId": self.job})},
                                  files=[("photos", ("dog.jpg", b"\xff\xd8\xff\xe0 jpeg", "image/jpeg"))])
        self.assertEqual(answer.status_code, 200, answer.text)
        service.log.info("after the request")
        glb = self.client.get(answer.json()["glbUrl"])
        self.assertEqual(glb.status_code, 200)
        health = self.client.get("/healthz", headers={"X-Request-Id": "health-1"})
        self.assertEqual(health.status_code, 200)

        by_message = {r.getMessage(): r for r in self.records.records}
        request = next(r for m, r in by_message.items() if m.startswith("request POST /infer/upload "))
        self.assertIn(f"traceparent={self.TRACEPARENT}", request.getMessage())
        job_line = next(r for m, r in by_message.items() if m.startswith(f"job {self.job}: infer "))
        for record in (request, job_line, by_message[f"job {self.job}: upload mode, 1 photo(s) in, "
                                                     f"{self.job_dir / 'base.glb'} out"]):
            self.assertEqual((record.levelno, record.request_id, record.trace_id),
                             (logging.INFO, f"{self.job}-2", self.TRACE_ID), record.getMessage())
        self.assertTrue(service.LOG_HANDLER.formatter.format(job_line).endswith(f" rid={self.job}-2 trace={self.TRACE_ID}"))
        after = by_message["after the request"]
        self.assertEqual((after.request_id, after.trace_id), (None, None))   # nothing left behind
        download = next(r for m, r in by_message.items() if m.startswith(f"request GET /artifacts/{self.job}/base.glb "))
        self.assertEqual((download.request_id, download.getMessage().endswith("traceparent=-")), (None, True))
        self.assertFalse(any("/healthz" in m for m in by_message))   # polled every 15 s: no line
        self.assertEqual([r.getMessage() for r in self.records.records if r.levelno >= logging.WARNING], [])

    def test_header_values_of_another_form_are_not_logged(self):
        for rid, traceparent in (("bad id", "00-xyz"), ("x" * 129, "00-" + "0" * 32 + "-00f067aa0ba902b7-01"),
                                 ("ok-id", "ff-" + self.TRACE_ID + "-00f067aa0ba902b7-01"),
                                 ("ok-id", self.TRACEPARENT.upper())):
            self.records.records.clear()
            self.client.delete(f"/artifacts/{self.job}", headers={"X-Request-Id": rid, "traceparent": traceparent})
            (request,) = [r for r in self.records.records if r.getMessage().startswith("request DELETE ")]
            self.assertEqual(request.trace_id, None, traceparent)
            self.assertTrue(request.getMessage().endswith("traceparent=-"), request.getMessage())
            self.assertEqual(request.request_id, rid if rid == "ok-id" else None, rid)
        weird = self.client.get("/artifacts/a%0Afake-line/base.glb")
        self.assertEqual(weird.status_code, 404)
        self.assertFalse(any("\n" in r.getMessage() for r in self.records.records))


class _FakeCollector:
    """OTLP/HTTP stand-in: records each POST (its path as sent: absolute-form when it came as a proxy) and answers 200."""

    def __init__(self):
        import http.server
        self.posts = []
        collector = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                collector.posts.append({"path": self.path, "type": self.headers.get("Content-Type"),
                                        "body": json.loads(body.decode("utf-8"))})
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"{}")

            def log_message(self, *_):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.url = self.base + "/v1/traces"

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def spans(self):
        return [span for post in self.posts for resource in post["body"]["resourceSpans"]
                for scope in resource["scopeSpans"] for span in scope["spans"]]


def _attrs(span):
    return {a["key"]: a["value"] for a in span.get("attributes", [])}


def _closed_port():
    import socket
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class _SpanExport:
    """Span export on, to a fake collector, flushed by the test (no background thread)."""

    TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
    PARENT_ID = "00f067aa0ba902b7"
    STEPS = ("beside.inference.save-photos", "beside.inference.lock-wait", "beside.inference.predict",
             "beside.inference.convert")

    def setUp(self):
        super().setUp()
        self.collector = _FakeCollector()
        self.addCleanup(self.collector.close)
        self.exporter = service.otlp_spans.Exporter(self.collector.url, environment="test", background=False)
        patcher = mock.patch.object(service.otlp_spans, "EXPORTER", self.exporter)
        patcher.start()
        self.addCleanup(patcher.stop)

    def traceparent(self, flags="01"):
        return f"00-{self.TRACE_ID}-{self.PARENT_ID}-{flags}"

    def upload_traced(self, flags="01", attempt=1):
        return self.client.post("/infer/upload", headers={"traceparent": self.traceparent(flags),
                                                          "X-Request-Id": f"{self.job}-{attempt}"},
                                data={"request": json.dumps({"jobId": self.job})},
                                files=[("photos", ("dog.jpg", b"\xff\xd8\xff\xe0 jpeg", "image/jpeg"))])

    def spans(self):
        self.exporter.flush()
        return self.collector.spans()

    def named(self):
        spans = self.spans()
        named = {s["name"]: s for s in spans}
        self.assertEqual(len(named), len(spans), [s["name"] for s in spans])
        return named


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class SpanExportTest(_SpanExport, _ModelModeCase):
    """Python spans (plan C2, 2026-11-27, otlp_spans.py): a SERVER span per job request under Spring's traceparent,
    the run's steps as its children, OTLP/JSON to the VM's collector; off without BESIDE_OTLP_URL or with flags 00,
    and a collector that is down changes no answer."""

    def test_an_upload_is_a_server_span_under_springs_span_with_its_steps_as_children(self):
        predict = self.model.predict
        self.model.predict = lambda image, out: types.SimpleNamespace(**dict(vars(predict(image, out)), gpu_peak_mb=2048))
        answer = self.upload_traced(attempt=2)
        self.assertEqual(answer.status_code, 200, answer.text)
        named = self.named()
        self.assertEqual(sorted(named), sorted(("POST /infer/upload",) + self.STEPS))
        (post,) = self.collector.posts
        self.assertEqual((post["path"], post["type"]), ("/v1/traces", "application/json"))
        (resource,) = post["body"]["resourceSpans"]
        self.assertEqual({a["key"]: a["value"] for a in resource["resource"]["attributes"]},
                         {"service.name": {"stringValue": "beside-inference"},
                          "deployment.environment": {"stringValue": "test"}})

        server = named["POST /infer/upload"]
        self.assertEqual((server["traceId"], server["parentSpanId"], server["kind"]), (self.TRACE_ID, self.PARENT_ID, 2))
        self.assertNotIn("status", server)
        self.assertEqual(_attrs(server), {"http.request.method": {"stringValue": "POST"},
                                          "http.route": {"stringValue": "/infer/upload"},
                                          "beside.request_id": {"stringValue": f"{self.job}-2"},
                                          "jobId": {"stringValue": self.job}, "attempt": {"intValue": "2"},
                                          "http.response.status_code": {"intValue": "200"}})
        for name in self.STEPS:
            step = named[name]
            self.assertEqual((step["traceId"], step["parentSpanId"], step["kind"]), (self.TRACE_ID, server["spanId"], 1))
            self.assertEqual(_attrs(step)["jobId"], {"stringValue": self.job})
            self.assertNotIn("status", step)
            self.assertLessEqual(int(server["startTimeUnixNano"]), int(step["startTimeUnixNano"]))
            self.assertLessEqual(int(step["endTimeUnixNano"]), int(server["endTimeUnixNano"]))
        self.assertEqual({k: v for k, v in _attrs(named["beside.inference.convert"]).items() if k != "jobId"},
                         {"tris": {"intValue": "4"}, "bytes": {"intValue": str(len(GLB_STUB))}})
        self.assertEqual({k: v for k, v in _attrs(named["beside.inference.predict"]).items() if k != "jobId"},
                         {"inferMs": {"intValue": "5"}, "gpuPeakMB": {"intValue": "2048"}})
        self.assertEqual({k: v for k, v in _attrs(named["beside.inference.save-photos"]).items() if k != "jobId"},
                         {"photos": {"intValue": "1"}, "bytes": {"intValue": str(len(b"\xff\xd8\xff\xe0 jpeg"))}})
        for span in named.values():
            self.assertRegex(span["traceId"], r"^[0-9a-f]{32}$")
            self.assertRegex(span["spanId"], r"^[0-9a-f]{16}$")
            self.assertRegex(span["startTimeUnixNano"], r"^\d{19}$")
            self.assertRegex(span["endTimeUnixNano"], r"^\d{19}$")
            self.assertLessEqual(int(span["startTimeUnixNano"]), int(span["endTimeUnixNano"]))

    def test_the_glb_download_and_the_delete_are_spans_of_their_own(self):
        answer = self.upload()   # no traceparent: a trace of its own
        self.assertEqual(answer.status_code, 200, answer.text)
        headers = {"traceparent": self.traceparent(), "X-Request-Id": f"{self.job}-1"}
        glb = self.client.get(answer.json()["glbUrl"], headers=headers)
        self.assertEqual((glb.status_code, glb.content), (200, GLB_STUB))
        self.assertEqual(self.client.delete(f"/artifacts/{self.job}", headers=headers).status_code, 204)
        named = self.named()
        upload = named["POST /infer/upload"]
        self.assertNotIn("parentSpanId", upload)
        self.assertRegex(upload["traceId"], r"^[0-9a-f]{32}$")
        self.assertNotEqual(upload["traceId"], self.TRACE_ID)
        for name, status in (("GET /artifacts/{jobId}/{name}", "200"), ("DELETE /artifacts/{jobId}", "204")):
            span = named[name]
            self.assertEqual((span["traceId"], span["parentSpanId"], span["kind"]), (self.TRACE_ID, self.PARENT_ID, 2))
            attributes = _attrs(span)
            self.assertEqual((attributes["http.response.status_code"], attributes["jobId"], attributes["attempt"]),
                             ({"intValue": status}, {"stringValue": self.job}, {"intValue": "1"}))

    def test_a_traceparent_with_flags_00_records_nothing(self):
        answer = self.upload_traced(flags="00")
        self.assertEqual(answer.status_code, 200, answer.text)
        self.assertEqual(self.client.get(answer.json()["glbUrl"], headers={"traceparent": self.traceparent("00")})
                         .status_code, 200)
        self.assertEqual(self.spans(), [])
        self.assertEqual(self.collector.posts, [])

    def test_without_an_otlp_url_nothing_is_recorded(self):
        with mock.patch.object(service.otlp_spans, "EXPORTER", None):
            self.assertEqual(self.upload_traced().status_code, 200)
        self.assertEqual(self.spans(), [])
        self.assertIsNone(service.otlp_spans.from_env({"BESIDE_OTLP_URL": " ", "BESIDE_TELEMETRY_PROXY": "http://x:1"}))
        pod = service.otlp_spans.from_env({"BESIDE_OTLP_URL": "http://100.64.0.9:4320/v1/traces",
                                           "BESIDE_TELEMETRY_PROXY": "http://127.0.0.1:1055"})
        self.assertEqual((pod.url, pod.proxy, pod.environment),
                         ("http://100.64.0.9:4320/v1/traces", "http://127.0.0.1:1055", "prod"))

    def test_a_failed_model_run_marks_its_step_and_its_request_as_errors(self):
        def out_of_memory(_):
            raise RuntimeError("CUDA out of memory")

        self.model.hook = out_of_memory
        answer = self.upload_traced()
        self.assertEqual(answer.status_code, 500)
        named = self.named()
        self.assertEqual(sorted(named), sorted(("POST /infer/upload",) + self.STEPS[:3]))
        self.assertEqual(named["beside.inference.predict"]["status"], {"code": 2, "message": "RuntimeError: CUDA out of memory"})
        self.assertEqual(named["POST /infer/upload"]["status"], {"code": 2, "message": "500"})
        self.assertEqual(_attrs(named["POST /infer/upload"])["http.response.status_code"], {"intValue": "500"})

    def test_a_request_refused_by_the_token_check_has_no_span(self):
        with mock.patch.object(service, "INFERENCE_TOKEN", "right-secret"):
            self.assertEqual(self.upload_traced().status_code, 401)
        self.assertEqual(self.spans(), [])

    def test_a_passthrough_simulation_is_a_step(self):
        sample = Path(tempfile.mkdtemp(prefix="beside-span-")) / "sample.glb"
        self.addCleanup(shutil.rmtree, sample.parent, True)
        sample.write_bytes(GLB_STUB)
        with mock.patch.object(service, "SAMPLE_GLB", str(sample)), mock.patch.object(service, "_sleep"):
            self.assertEqual(self.upload_traced().status_code, 200)
            with _knobs(DELAY_MS=5):
                self.assertEqual(self.upload_traced(attempt=2).status_code, 200)
        names = [s["name"] for s in self.spans()]
        self.assertEqual(sorted(names), sorted(["POST /infer/upload", "beside.inference.save-photos"] * 2
                                               + ["beside.inference.simulate"]))

    def test_a_collector_that_is_down_changes_no_answer(self):
        down = service.otlp_spans.Exporter(f"http://127.0.0.1:{_closed_port()}/v1/traces", interval=0.05)
        with mock.patch.object(service.otlp_spans, "EXPORTER", down):
            answer = self.upload_traced()
            self.assertEqual(answer.status_code, 200, answer.text)
            glb = self.client.get(answer.json()["glbUrl"], headers={"traceparent": self.traceparent()})
            self.assertEqual((glb.status_code, glb.content), (200, GLB_STUB))
            self.assertTrue(_wait_until(lambda: down.failed == 1 + len(self.STEPS) + 1, timeout=30), down.failed)
        self.assertEqual(down.sent, 0)

    def test_a_full_queue_drops_spans_instead_of_waiting(self):
        small = service.otlp_spans.Exporter(self.collector.url, max_queue=2, background=False)
        for _ in range(3):
            small.submit({"name": "x"})
        self.assertEqual((small.dropped, small.queue.qsize()), (1, 2))

    def test_spans_go_through_the_proxy(self):
        # the fake collector stands in for tailscaled's HTTP proxy: the request arrives in absolute form
        via = service.otlp_spans.Exporter("http://collector.invalid:4320/v1/traces", proxy=self.collector.base,
                                          background=False)
        with mock.patch.object(service.otlp_spans, "EXPORTER", via):
            self.assertEqual(self.client.delete(f"/artifacts/{self.job}",
                                                headers={"traceparent": self.traceparent()}).status_code, 204)
        self.assertTrue(via.flush())
        self.assertEqual([p["path"] for p in self.collector.posts], ["http://collector.invalid:4320/v1/traces"])

    def test_a_fresh_process_exports_only_with_an_otlp_url(self):
        """As the Pod starts it: the setting read at import, a background thread posts within the 2 s interval."""
        import subprocess
        script = ("import sys, threading, time; sys.path.insert(0, sys.argv[1]); import app\n"
                  "from fastapi.testclient import TestClient\n"
                  "r = TestClient(app.app).delete('/artifacts/j-1', headers={'traceparent': sys.argv[2], "
                  "'X-Request-Id': 'j-1-3'})\n"
                  "assert r.status_code == 204, r.status_code\n"
                  "e = app.otlp_spans.EXPORTER\n"
                  "if e is None:\n"
                  "    assert not [t for t in threading.enumerate() if t.name == 'otlp-export']\n"
                  "    print('no exporter'); sys.exit(0)\n"
                  "deadline = time.time() + 20\n"
                  "while e.sent < 1 and time.time() < deadline: time.sleep(0.05)\n"
                  "assert e.sent == 1, (e.sent, e.failed)\n")
        base = {k: v for k, v in os.environ.items() if not k.startswith(("BESIDE_OTLP", "BESIDE_TELEMETRY"))}
        base["BESIDE_WORK_DIR"] = WORK
        off = subprocess.run([sys.executable, "-c", script, str(HERE), self.traceparent()], env=base,
                             capture_output=True, text=True, timeout=120)
        self.assertEqual((off.returncode, off.stdout.strip()), (0, "no exporter"), off.stderr[-2000:])
        on = subprocess.run([sys.executable, "-c", script, str(HERE), self.traceparent()],
                            env=dict(base, BESIDE_OTLP_URL=self.collector.url, BESIDE_TELEMETRY_ENV="loadtest"),
                            capture_output=True, text=True, timeout=120)
        self.assertEqual(on.returncode, 0, on.stderr[-2000:])
        (post,) = self.collector.posts
        resource = {a["key"]: a["value"] for a in post["body"]["resourceSpans"][0]["resource"]["attributes"]}
        self.assertEqual(resource["deployment.environment"], {"stringValue": "loadtest"})
        (span,) = self.collector.spans()
        self.assertEqual((span["name"], span["traceId"], span["parentSpanId"], _attrs(span)["attempt"]),
                         ("DELETE /artifacts/{jobId}", self.TRACE_ID, self.PARENT_ID, {"intValue": "3"}))


@unittest.skipIf(TestClient is None, "fastapi/httpx not installed")
class RigSpanTest(_SpanExport, _RiggedCase):
    def test_the_rig_step_is_a_span_with_the_rigged_size(self):
        self.assertEqual(self.upload_traced().status_code, 200)
        named = self.named()
        rig = named["beside.inference.rig"]
        self.assertEqual(rig["parentSpanId"], named["POST /infer/upload"]["spanId"])
        self.assertEqual({k: v for k, v in _attrs(rig).items() if k != "jobId"},
                         {"bytes": {"intValue": str(self.rigged_size)}, "joints": {"intValue": "2"}})
        self.assertNotIn("status", rig)


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
