// Report panels of the admin dashboard (static/admin/admin-measurement.js). Run: node --test backend/src/test/js/admin-measurement.test.cjs
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const M = require(path.resolve(__dirname, "../../main/resources/static/admin/admin-measurement.js"));

test("job ids link to the job detail page", () => {
  assert.equal(M.jobPageUrl("3fa85f64-5717-4562-b3fc-2c963f66afa6"), "/job/?id=3fa85f64-5717-4562-b3fc-2c963f66afa6");
  assert.equal(M.jobPageUrl("a&b=c"), "/job/?id=a%26b%3Dc");
});

test("report URLs follow the passthrough box", () => {
  assert.equal(M.ratingSummaryUrl(false), "/api/v1/admin/ratings/summary");
  assert.equal(M.ratingSummaryUrl(true), "/api/v1/admin/ratings/summary?includePassthrough=true");
  assert.equal(M.ratingsCsvUrl(false), "/api/v1/admin/ratings?format=csv");
  assert.equal(M.ratingsCsvUrl(true), "/api/v1/admin/ratings?format=csv&includePassthrough=true");
  assert.equal(M.e2eCsvUrl(true), "/api/v1/admin/e2e?format=csv&includePassthrough=true");
  assert.equal(M.CLIENT_METRICS_CSV_URL, "/api/v1/admin/client-metrics?format=csv");
});

test("score columns are the six METRICS §4 names in order", () => {
  assert.deepEqual(M.SCORE_COLUMNS.map((column) => column.name),
    ["faceShape", "ears", "bodyProportion", "furColor", "pattern", "textureArtifacts"]);
  assert.equal(M.formatScore(10 / 3), "3.33");
  assert.equal(M.formatScore(undefined), "—");
});

test("an import result reads as one Korean line plus the rejected lines", () => {
  assert.deepEqual(M.importSummary({ imported: 3, skipped: 0, errors: [] }), { text: "새로 3줄을 저장했어요.", errors: [] });
  const mixed = M.importSummary({
    imported: 2, skipped: 1,
    errors: [{ line: 3, reason: "avgFps is not a number" }, { line: 7, reason: "jobId is empty" }]
  });
  assert.equal(mixed.text, "새로 2줄을 저장했어요. 이미 있는 1줄은 건너뛰었어요. 2줄은 형식이 맞지 않아 저장하지 않았어요.");
  assert.deepEqual(mixed.errors, ["3번째 줄: avgFps is not a number", "7번째 줄: jobId is empty"]);

  const many = M.importSummary({ imported: 0, skipped: 0, errors: Array.from({ length: 25 }, (_, i) => ({ line: i + 2, reason: "x" })) });
  assert.equal(many.errors.length, 21);
  assert.equal(many.errors[20], "… 외 5줄");
  assert.deepEqual(M.importSummary(null), { text: "새로 0줄을 저장했어요.", errors: [] });
});

test("the session funnel panel asks for the chosen range by receivedAt and links the same range as CSV", () => {
  const now = Date.parse("2026-11-26T12:00:00Z");
  assert.equal(M.funnelUrl(7, now), "/api/v1/admin/funnel?from=2026-11-19T12%3A00%3A00.000Z");
  assert.equal(M.funnelUrl(1, now), "/api/v1/admin/funnel?from=2026-11-25T12%3A00%3A00.000Z");
  assert.equal(M.funnelUrl(undefined, now), M.funnelUrl(7, now), "a missing choice is 7 days");
  assert.equal(M.eventsCsvUrl(30, now), "/api/v1/admin/events?format=csv&from=2026-10-27T12%3A00%3A00.000Z");
  assert.deepEqual(M.FUNNEL_RANGES.map((range) => range.days), [1, 7, 30]);
});

test("funnel rows read as Korean steps with percentages and median times", () => {
  const view = M.funnelView({
    sessions: 6,
    steps: [
      { step: "home", event: "screen_view(screen=home)", sessions: 5, conversionPct: 100, fromPrevPct: null, medianSecondsFromPrev: null, droppedSessions: 0 },
      { step: "photos_selected", event: "photos_selected(count!=0)", sessions: 3, conversionPct: 60, fromPrevPct: 60, medianSecondsFromPrev: 20, droppedSessions: 2 },
      { step: "model_ready", event: "model_ready", sessions: 2, conversionPct: 40, fromPrevPct: 200 / 3, medianSecondsFromPrev: 95.4 },
      { step: "new_step", event: "x", sessions: 0, conversionPct: 0, fromPrevPct: 0, medianSecondsFromPrev: 12.5 }
    ]
  });
  assert.equal(view.summary, "이 기간 세션 6개 · 홈을 본 세션 5개");
  assert.equal(view.empty, false);
  // dropped (이탈, 2026-11-27): "—" on the first step and when an older server leaves it out
  assert.deepEqual(view.rows[0], { label: "홈 화면", event: "screen_view(screen=home)", sessions: "5", conversion: "100.0%", fromPrev: "—", median: "—", dropped: "—" });
  assert.deepEqual(view.rows[1], { label: "사진 고름", event: "photos_selected(count!=0)", sessions: "3", conversion: "60.0%", fromPrev: "60.0%", median: "20초", dropped: "2" });
  assert.equal(view.rows[2].fromPrev, "66.7%");
  assert.equal(view.rows[2].median, "1분 35초");
  assert.equal(view.rows[2].dropped, "—");
  assert.equal(view.rows[3].label, "new_step", "a step this page does not know keeps its name");
  assert.equal(view.rows[3].median, "12.5초");
  assert.equal(M.funnelView(null).empty, true);
  assert.deepEqual(M.funnelView({ sessions: 0, steps: [] }).rows, []);
});

test("the funnel panel shows where dropped sessions stopped, why, the library funnel and the job completion", () => {
  const view = M.funnelView({
    sessions: 8,
    steps: [],
    lastScreens: [{ screen: "ar", sessions: 2 }, { screen: "library", sessions: 1 }, { screen: "new_screen", sessions: 1 }],
    dropReasons: [
      { event: "app_error", key: "kind", value: "exception", sessions: 2 },
      { event: "job_cancel_tap", key: "stage", value: "queued", sessions: 1 },
      { event: "job_failed_shown", key: "code", value: "INFERENCE_TIMEOUT", sessions: 1 },
      { event: "photos_selected", key: "count", value: "0", sessions: 1 }
    ],
    librarySteps: [
      { step: "home", event: "screen_view(screen=home)", sessions: 8, conversionPct: 100, fromPrevPct: null, medianSecondsFromPrev: null, droppedSessions: 0 },
      { step: "library", event: "screen_view(screen=library)", sessions: 2, conversionPct: 25, fromPrevPct: 25, medianSecondsFromPrev: 5, droppedSessions: 6 },
      { step: "saved_dog_open", event: "saved_dog_open(ok=true)", sessions: 1, conversionPct: 12.5, fromPrevPct: 50, medianSecondsFromPrev: 5, droppedSessions: 1 }
    ],
    jobs: { created: 7, completed: 3, failed: 1, canceled: 1, inProgress: 2, completionPct: 50 }
  });
  assert.deepEqual(view.lastScreens, [
    { label: "AR", sessions: "2" }, { label: "저장한 강아지", sessions: "1" }, { label: "new_screen", sessions: "1" }]);
  assert.deepEqual(view.dropReasons.map((reason) => reason.label), [
    "앱 오류 (kind=exception)", "만드는 중 취소 (stage=queued)", "실패 화면 (code=INFERENCE_TIMEOUT)", "사진을 고르지 않고 닫음"]);
  assert.equal(view.dropReasons[0].sessions, "2");
  assert.deepEqual(view.libraryRows.map((row) => [row.label, row.sessions, row.dropped]), [
    ["홈 화면", "8", "—"], ["저장한 강아지 화면", "2", "6"], ["강아지 엶", "1", "1"]]);
  assert.equal(view.jobs, "작업 완료율(서버) 50.0% · 만든 작업 7 · 완료 3 · 실패 1 · 취소 1 · 진행 중 2");
  // an older server without these fields: empty tables and no job line
  const old = M.funnelView({ sessions: 1, steps: [] });
  assert.deepEqual([old.lastScreens, old.dropReasons, old.libraryRows], [[], [], []]);
  assert.equal(old.jobs, "작업 완료율(서버) —");
});

test("a refused upload explains itself", () => {
  const header = M.uploadErrorText(400, { error: { code: "INVALID_REQUEST", message: "The first line must be exactly ..." } });
  assert.match(header, /첫 줄이 METRICS/);
  assert.match(header, /The first line must be exactly/);
  assert.match(M.uploadErrorText(413, null), /5MB/);
  assert.match(M.uploadErrorText(401, null), /로그인/);
  assert.equal(M.uploadErrorText(0, null), "서버에 연결할 수 없어요.");
  assert.equal(M.uploadErrorText(500, { error: { code: "INTERNAL_ERROR" } }), "올리지 못했어요 (HTTP 500).");
});
