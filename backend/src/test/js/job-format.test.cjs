// Pure helpers of the job detail page (static/job/job-format.js). Run: node --test backend/src/test/js/job-format.test.cjs
// The rater and body rules must match the server (RatingService.normalizeRater, JobRatingRequest.from).
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const J = require(path.resolve(__dirname, "../../main/resources/static/job/job-format.js"));

const jobId = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

test("the job id comes from ?id= and must look like a job id", () => {
  assert.equal(J.jobIdFromSearch(`?id=${jobId}`), jobId);
  assert.equal(J.jobIdFromSearch(`?x=1&id=${jobId}`), jobId);
  assert.equal(J.jobIdFromSearch(`?id=%20${jobId}%20`), jobId);
  for (const search of ["", "?", "?id=", "?id=../admin", "?id=a/b", "?id=<script>", `?id=${"a".repeat(65)}`, undefined, null]) {
    assert.equal(J.jobIdFromSearch(search), null, String(search));
  }
});

test("rater names follow the server rules", () => {
  assert.deepEqual(J.normalizeRater("  민우 "), { ok: true, value: "민우" });
  assert.deepEqual(J.normalizeRater("가".repeat(40)), { ok: true, value: "가".repeat(40) });
  assert.equal(J.normalizeRater("😀".repeat(40)).ok, true, "40 characters, not 80 UTF-16 units");
  for (const bad of ["", "   ", "가".repeat(41), "a/b", "a\\b", "a;b", "..", ".", "bell\u0007", "tab\tname", undefined]) {
    const result = J.normalizeRater(bad);
    assert.equal(result.ok, false, JSON.stringify(bad));
    assert.ok(result.message.length > 0);
  }
  assert.equal(J.normalizeRater("a.b").ok, true);
});

test("the rating body needs six scores 1~5 and trims the comment", () => {
  const scores = { faceShape: 5, ears: "4", bodyProportion: 3, furColor: 2, pattern: 1, textureArtifacts: "5" };
  assert.deepEqual(J.ratingBody(scores, "  귀가 작다  "), {
    ok: true,
    body: { faceShape: 5, ears: 4, bodyProportion: 3, furColor: 2, pattern: 1, textureArtifacts: 5, comment: "귀가 작다" }
  });
  assert.deepEqual(Object.keys(J.ratingBody(scores, "").body), J.RATING_ITEMS.map((item) => item.name), "empty comment left out");

  const missing = J.ratingBody({ faceShape: 5, ears: 0, bodyProportion: 6, furColor: "3.5", pattern: null }, "");
  assert.equal(missing.ok, false);
  assert.deepEqual(missing.missing, ["ears", "bodyProportion", "furColor", "pattern", "textureArtifacts"]);
  assert.match(missing.message, /귀 모양\/위치/);
  assert.equal(J.ratingBody(scores, "가".repeat(501)).ok, false);
  assert.equal(J.ratingBody(scores, "가".repeat(500)).ok, true);
});

test("rating items are the six METRICS §4 names in order", () => {
  assert.deepEqual(J.RATING_ITEMS.map((item) => item.name),
    ["faceShape", "ears", "bodyProportion", "furColor", "pattern", "textureArtifacts"]);
});

test("URLs encode the job id and the rater", () => {
  assert.equal(J.assetUrl(jobId), `/api/v1/jobs/${jobId}/asset`);
  assert.equal(J.photoUrl(jobId, 2), `/api/v1/jobs/${jobId}/photos/2`);
  assert.equal(J.ratingsUrl(jobId), `/api/v1/jobs/${jobId}/ratings`);
  assert.equal(J.ratingUrl(jobId, "민우 #1?"), `/api/v1/jobs/${jobId}/ratings/%EB%AF%BC%EC%9A%B0%20%231%3F`);
  assert.equal(J.sharesUrl(jobId), `/api/v1/jobs/${jobId}/shares`);
  assert.equal(J.adminJobUrl(jobId), `/api/v1/admin/jobs/${jobId}`);
});

test("share links become absolute on this server, anything else is refused", () => {
  assert.equal(J.shareLink("https://beside.example.org", "/s/q3Zr8yT0bWm1xK4vL9pN2A"), "https://beside.example.org/s/q3Zr8yT0bWm1xK4vL9pN2A");
  for (const url of ["https://evil.example/s/abc", "//evil.example/s/abc", "/admin", "/s/", "/s/a b", null]) {
    assert.equal(J.shareLink("https://beside.example.org", url), null, String(url));
  }
});

test("scores, errors and states read as Korean", () => {
  assert.equal(J.formatScore(11 / 3), "3.67");
  assert.equal(J.formatScore(4), "4.00");
  assert.equal(J.formatScore(null), "—");
  assert.match(J.jobErrorText("INFERENCE_TIMEOUT"), /오래 걸려/);
  assert.equal(J.jobErrorText("SOMETHING_NEW"), "알 수 없는 이유로 실패했어요.");
  assert.match(J.requestErrorText(409, "JOB_NOT_COMPLETED"), /완료된 작업만/);
  assert.match(J.requestErrorText(401, undefined), /로그인/);
  assert.equal(J.requestErrorText(502, "UNKNOWN_CODE"), "요청이 실패했어요 (HTTP 502).");
  assert.equal(J.requestErrorText(0, undefined), "서버에 연결할 수 없어요.");
  assert.equal(J.isInFlight("PENDING"), true);
  assert.equal(J.isInFlight("PROCESSING"), true);
  assert.equal(J.isInFlight("COMPLETED"), false);
  assert.equal(J.isInFlight("SOMETHING_NEW"), false);
});

test("the form is prefilled with the rater's own rating", () => {
  const list = { items: [{ rater: "alice", faceShape: 5 }, { rater: "bob", faceShape: 2 }], average: null };
  assert.equal(J.ratingOf(list, "bob").faceShape, 2);
  assert.equal(J.ratingOf(list, "carol"), null);
  assert.equal(J.ratingOf(null, "bob"), null);
});
