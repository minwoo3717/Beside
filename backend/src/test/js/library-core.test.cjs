// Pure helpers of the pet library page (static/library/library-core.js). Run: node --test backend/src/test/js/library-core.test.cjs
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const L = require(path.resolve(__dirname, "../../main/resources/static/library/library-core.js"));
const share = require(path.resolve(__dirname, "../../main/resources/static/share.js"));

const petId = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
const MB = 1024 * 1024;

test("routes: #/pets/{uuid} opens a pet, anything else is the home list", () => {
  assert.deepEqual(L.parseRoute(`#/pets/${petId}`), { view: "pet", petId });
  assert.deepEqual(L.parseRoute(`#/pets/${petId.toUpperCase()}/`), { view: "pet", petId });
  for (const hash of ["", "#", "#/", "#/pets/", "#/pets/abc", `#/pets/${petId}/x`, `#/jobs/${petId}`, undefined, null]) {
    assert.deepEqual(L.parseRoute(hash), { view: "home" }, String(hash));
  }
  assert.equal(L.petRoute(petId), `#/pets/${petId}`);
  assert.equal(L.jobDetailUrl(petId), `/job/?id=${petId}`);
  assert.equal(L.jobDetailUrl("a b&c"), "/job/?id=a%20b%26c");
  assert.equal(L.assetUrl(petId), `/api/v1/jobs/${petId}/asset?variant=base`);
});

test("species and status labels, unknown values never crash", () => {
  assert.deepEqual(L.SPECIES.map((s) => s.value), ["DOG", "CAT", "OTHER"]);
  assert.equal(L.speciesLabel("DOG"), "강아지");
  assert.equal(L.speciesLabel("CAT"), "고양이");
  assert.equal(L.speciesLabel("OTHER"), "기타");
  assert.equal(L.speciesLabel("FISH"), "알 수 없음");
  assert.equal(L.statusLabel("PENDING"), "대기");
  assert.equal(L.statusLabel("PROCESSING"), "생성 중");
  assert.equal(L.statusLabel("COMPLETED"), "완료");
  assert.equal(L.statusLabel("FAILED"), "실패");
  assert.equal(L.statusLabel("ARCHIVED"), "알 수 없음");
  assert.equal(L.statusKey("ARCHIVED"), "UNKNOWN");
  assert.equal(L.statusKey("toString"), "UNKNOWN", "not fooled by Object.prototype names");
  assert.equal(L.statusKey("FAILED"), "FAILED");
});

test("delete and cancel follow the API rules", () => {
  assert.equal(L.canDelete({ status: "COMPLETED" }), true);
  assert.equal(L.canDelete({ status: "FAILED" }), true);
  assert.equal(L.canDelete({ status: "PENDING" }), false);
  assert.equal(L.canDelete({ status: "PROCESSING" }), false);
  assert.equal(L.canDelete(null), false);
  assert.equal(L.canCancel({ status: "PENDING" }), true);
  assert.equal(L.canCancel({ status: "PROCESSING" }), false);
  assert.equal(L.isFinished("COMPLETED"), true);
  assert.equal(L.isFinished("UNKNOWN"), false);
});

test("error codes map to ERROR_CODES copy, unknown codes to a generic line", () => {
  assert.match(L.errorMessage("JOB_IN_PROGRESS"), /지울 수 없어요/);
  assert.match(L.errorMessage("PET_NOT_FOUND"), /반려동물을 찾을 수 없어요/);
  assert.match(L.errorMessage("CANCELED"), /취소한 작업/);
  assert.match(L.errorMessage("INFERENCE_FAILED"), /사진으로 다시 시도/);
  assert.equal(L.errorMessage("NEW_CODE", 409), "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요. (HTTP 409)");
  assert.equal(L.errorMessage(undefined), "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.");
  assert.match(L.errorMessage(null, 413), /5MB/);
  assert.equal(L.errorMessage("constructor"), "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.");
});

test("waiting line: queue position, estimate, GPU offline, processing", () => {
  assert.equal(L.waitText({ status: "PENDING", queue: { ahead: 2, reason: null }, estimate: { remainingMs: 150000, basedOnRuns: 3 } }),
    "앞에 2명 · 약 3분");
  assert.equal(L.waitText({ status: "PENDING", queue: { ahead: 0, reason: null }, estimate: { remainingMs: 30000, basedOnRuns: 1 } }),
    "곧 시작해요 · 1분 이내");
  assert.equal(L.waitText({ status: "PENDING", queue: { ahead: 1, reason: null }, estimate: null }), "앞에 1명");
  assert.equal(L.waitText({ status: "PENDING", queue: { ahead: 1, reason: "INFERENCE_OFFLINE" }, estimate: null }),
    "GPU 서버가 준비되기를 기다리는 중이에요");
  assert.equal(L.waitText({ status: "PROCESSING", queue: null, estimate: { remainingMs: 61000, basedOnRuns: 5 } }),
    "3D 모델을 만드는 중 · 약 2분 남음");
  assert.equal(L.waitText({ status: "PROCESSING", estimate: { remainingMs: 0, basedOnRuns: 5 } }),
    "3D 모델을 만드는 중 · 평소보다 오래 걸리고 있어요");
  assert.equal(L.waitText({ status: "PROCESSING", estimate: null }), "3D 모델을 만드는 중이에요");
  assert.equal(L.waitText({ status: "COMPLETED" }), "완성됐어요");
  assert.match(L.waitText({ status: "FAILED", error: { code: "CANCELED" } }), /취소한 작업/);
  assert.equal(L.waitText({ status: "LATER" }), "상태를 알 수 없어요");
  assert.equal(L.waitText(null), "");
  assert.equal(L.formatRemaining(null), null);
  assert.equal(L.formatRemaining(-1), null);
  assert.equal(L.formatRemaining(60000), "약 1분");
  assert.equal(L.formatRemaining(60001), "약 2분");
});

test("photo selection follows the upload rules of the Mock UI and the API", () => {
  const jpg = (size, type = "image/jpeg") => ({ name: "a.jpg", size, type });
  assert.equal(L.checkPhotos([jpg(MB)]), null);
  assert.equal(L.checkPhotos([jpg(MB, "image/png"), jpg(MB, "image/webp")]), null);
  assert.match(L.checkPhotos([]), /1장 이상/);
  assert.match(L.checkPhotos(undefined), /1장 이상/);
  assert.match(L.checkPhotos(Array.from({ length: 11 }, () => jpg(10))), /최대 10장/);
  assert.match(L.checkPhotos([jpg(5 * MB), jpg(5 * MB), jpg(5 * MB), jpg(4 * MB)]), /합계 18MB/);
  assert.match(L.checkPhotos([jpg(MB, "image/gif")]), /JPG, PNG, WEBP/);
  assert.match(L.checkPhotos([jpg(0)]), /빈 파일/);
  assert.match(L.checkPhotos([jpg(5 * MB + 1)]), /5MB/);
  assert.equal(L.checkPhotos([jpg(5 * MB)]), null, "exactly 5 MB is allowed");
});

test("pet form: name 1~40 after trimming, note up to 500, empty note becomes null", () => {
  assert.equal(L.checkPetForm({ name: " 초코 ", note: "" }), null);
  assert.match(L.checkPetForm({ name: "   " }), /이름을 입력/);
  assert.match(L.checkPetForm({ name: "가".repeat(41) }), /40자/);
  assert.equal(L.checkPetForm({ name: "가".repeat(40), note: "a".repeat(500) }), null);
  assert.match(L.checkPetForm({ name: "a", note: "a".repeat(501) }), /500자/);
  assert.deepEqual(L.petBody({ name: " 초코 ", species: "CAT", note: "  " }), { name: "초코", species: "CAT", note: null });
  assert.deepEqual(L.petBody({ name: "보리", note: " 산책 " }), { name: "보리", species: "DOG", note: "산책" });
});

test("unassigned jobs are the ones without a pet", () => {
  const jobs = [{ id: "a", petId: null }, { id: "b", petId }, { id: "c" }, null];
  assert.deepEqual(L.unassigned(jobs).map((job) => job.id), ["a", "c"]);
  assert.deepEqual(L.unassigned(undefined), []);
  assert.equal(L.photoCount({ uploadedFiles: [{}, {}, {}] }), "사진 3장");
  assert.equal(L.photoCount({}), "사진 0장");
});

test("3D view starts at the cover, else the newest completed job", () => {
  const jobs = [{ id: "new", status: "FAILED" }, { id: "mid", status: "COMPLETED" }, { id: "old", status: "COMPLETED" }];
  assert.equal(L.viewerJob({ coverJobId: "old", jobs }).id, "old");
  assert.equal(L.viewerJob({ coverJobId: null, jobs }).id, "mid");
  assert.equal(L.viewerJob({ coverJobId: "new", jobs }).id, "mid", "a cover that is not COMPLETED is skipped");
  assert.equal(L.viewerJob({ jobs: [{ id: "x", status: "PENDING" }] }), null);
  assert.equal(L.viewerJob(null), null);
});

test("finish notice names the pet", () => {
  assert.deepEqual(L.finishNotice({ status: "COMPLETED" }, "초코"), { title: "초코의 3D 모델이 완성됐어요", body: "보관함에서 확인해 보세요." });
  const failed = L.finishNotice({ status: "FAILED", error: { code: "INFERENCE_TIMEOUT" } }, "");
  assert.equal(failed.title, "3D 모델을 만들지 못했어요");
  assert.match(failed.body, /너무 오래/);
});

test("animation chips and labels are the share viewer's (Unity PetAnimationPlayer)", () => {
  assert.deepEqual(JSON.parse(JSON.stringify(L.ANIMATIONS)), JSON.parse(JSON.stringify(share.ANIMATIONS)));
  const available = ["Jump", "Idle", "HeadTilt", "Walk", "Sit", "TailWag", "Jump"];
  assert.deepEqual(L.animationChips(available), share.animationChips(available));
  assert.equal(L.defaultAnimation(available), share.defaultAnimation(available));
  assert.equal(L.defaultAnimation([]), null);
  for (const name of ["Idle", "Walk", "Sit", "TailWag", "HeadTilt", "Roll"]) {
    assert.equal(L.animationLabel(name), share.animationLabel(name));
  }
});

test("dates read as YYYY.MM.DD HH:mm in local time", () => {
  const iso = new Date(2026, 10, 6, 14, 5).toISOString();
  assert.equal(L.formatDateTime(iso), "2026.11.06 14:05");
  assert.equal(L.formatDateTime(null), "—");
  assert.equal(L.formatDateTime("not a date"), "—");
});

test("polling waits long enough for a real GPU run", () => {
  assert.equal(L.POLL_MS, 2000);
  assert.ok(L.MAX_WAIT_MS >= 15 * 60 * 1000);
});
