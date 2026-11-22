// Pure helpers of the dashboard's report panels (quality rating summary, app metrics.csv upload, CSV links) and the
// link to the job detail page. No DOM, no fetch. Kept apart from admin-format.js so that file stays as it was.
// Loaded by /admin/index.html as window.BesideAdminMeasurement and by node:test (src/test/js/admin-measurement.test.cjs).
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.BesideAdminMeasurement = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const DASH = "—";
  const CLIENT_METRICS_URL = "/api/v1/admin/client-metrics";
  const CLIENT_METRICS_CSV_URL = `${CLIENT_METRICS_URL}?format=csv`;
  const SHOWN_ERRORS = 20;
  // docs/METRICS.md §4 names in order with short table headers.
  const SCORE_COLUMNS = Object.freeze([
    Object.freeze({ name: "faceShape", label: "얼굴" }),
    Object.freeze({ name: "ears", label: "귀" }),
    Object.freeze({ name: "bodyProportion", label: "체형" }),
    Object.freeze({ name: "furColor", label: "털색" }),
    Object.freeze({ name: "pattern", label: "무늬" }),
    Object.freeze({ name: "textureArtifacts", label: "텍스처" })
  ]);

  function withPassthrough(url, includePassthrough) {
    if (!includePassthrough) return url;
    return `${url}${url.includes("?") ? "&" : "?"}includePassthrough=true`;
  }

  /** The job detail page of a job id (the recent-jobs table links there). */
  function jobPageUrl(id) {
    return `/job/?id=${encodeURIComponent(String(id))}`;
  }

  function ratingSummaryUrl(includePassthrough) {
    return withPassthrough("/api/v1/admin/ratings/summary", includePassthrough);
  }

  function ratingsCsvUrl(includePassthrough) {
    return withPassthrough("/api/v1/admin/ratings?format=csv", includePassthrough);
  }

  function e2eCsvUrl(includePassthrough) {
    return withPassthrough("/api/v1/admin/e2e?format=csv", includePassthrough);
  }

  /** Mean score → "3.67"; anything else → "—". */
  function formatScore(value) {
    return typeof value === "number" && Number.isFinite(value) ? value.toFixed(2) : DASH;
  }

  /**
   * AdminClientMetricImport → { text, errors } in Korean. errors lists at most 20 lines ("3번째 줄: reason") and says
   * how many more there were.
   */
  function importSummary(result) {
    const imported = result && Number.isInteger(result.imported) ? result.imported : 0;
    const skipped = result && Number.isInteger(result.skipped) ? result.skipped : 0;
    const errors = result && Array.isArray(result.errors) ? result.errors : [];
    const parts = [`새로 ${imported}줄을 저장했어요.`];
    if (skipped > 0) parts.push(`이미 있는 ${skipped}줄은 건너뛰었어요.`);
    if (errors.length > 0) parts.push(`${errors.length}줄은 형식이 맞지 않아 저장하지 않았어요.`);
    const lines = errors.slice(0, SHOWN_ERRORS).map((error) => `${error.line}번째 줄: ${error.reason}`);
    if (errors.length > SHOWN_ERRORS) lines.push(`… 외 ${errors.length - SHOWN_ERRORS}줄`);
    return { text: parts.join(" "), errors: lines };
  }

  /** A refused upload (400 header mismatch, 413, 401, offline) → Korean text with the server's explanation when there is one. */
  function uploadErrorText(status, body) {
    const error = body && body.error ? body.error : null;
    if (status === 400 && error) return `파일을 받지 않았어요. 첫 줄이 METRICS 의 metrics.csv 헤더와 같아야 해요. (${error.message || error.code})`;
    if (status === 413) return "파일이 너무 커요 (5MB 까지).";
    if (status === 401 || status === 403) return "로그인이 필요해요. 페이지를 새로 고쳐 주세요.";
    if (!status) return "서버에 연결할 수 없어요.";
    return `올리지 못했어요 (HTTP ${status}).`;
  }

  return Object.freeze({
    DASH, CLIENT_METRICS_URL, CLIENT_METRICS_CSV_URL, SCORE_COLUMNS,
    jobPageUrl, ratingSummaryUrl, ratingsCsvUrl, e2eCsvUrl, formatScore, importSummary, uploadErrorText
  });
});
