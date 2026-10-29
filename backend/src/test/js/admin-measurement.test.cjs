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

test("a refused upload explains itself", () => {
  const header = M.uploadErrorText(400, { error: { code: "INVALID_REQUEST", message: "The first line must be exactly ..." } });
  assert.match(header, /첫 줄이 METRICS/);
  assert.match(header, /The first line must be exactly/);
  assert.match(M.uploadErrorText(413, null), /5MB/);
  assert.match(M.uploadErrorText(401, null), /로그인/);
  assert.equal(M.uploadErrorText(0, null), "서버에 연결할 수 없어요.");
  assert.equal(M.uploadErrorText(500, { error: { code: "INTERNAL_ERROR" } }), "올리지 못했어요 (HTTP 500).");
});
