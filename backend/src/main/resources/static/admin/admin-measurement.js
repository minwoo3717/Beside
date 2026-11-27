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

  // ------------------------------------------------------------------ session funnel (product events, docs/METRICS.md §6.2)

  const DAY_MS = 24 * 60 * 60 * 1000;
  /** The range choices of the funnel panel; the value is days back from now. */
  const FUNNEL_RANGES = Object.freeze([
    Object.freeze({ days: 1, label: "최근 24시간" }),
    Object.freeze({ days: 7, label: "최근 7일" }),
    Object.freeze({ days: 30, label: "최근 30일 (보존 기간)" })
  ]);
  /** Korean names of the server's six steps (AdminFunnelStep.step); unknown steps keep their own name. */
  const FUNNEL_LABELS = Object.freeze({
    home: "홈 화면", photos_selected: "사진 고름", generate_tap: "만들기 누름",
    model_ready: "모델 받음", ar: "AR 화면", model_placed: "AR 에 놓음"
  });
  /** The library funnel's own names where they differ (librarySteps, 2026-11-27). */
  const LIBRARY_LABELS = Object.freeze({ library: "저장한 강아지 화면", saved_dog_open: "강아지 엶" });
  /** ProductEvent.screen → Korean (the app's DesignedUIController pages and the share viewer). */
  const SCREEN_LABELS = Object.freeze({
    home: "홈", confirm: "사진 확인", progress: "만드는 중", preview: "미리보기", ar: "AR", error: "오류",
    settings: "설정", library: "저장한 강아지", camera: "촬영", share: "공유 뷰어"
  });
  /** Drop reason events (AdminFunnelReason.event) → Korean. */
  const REASON_LABELS = Object.freeze({
    job_cancel_tap: "만드는 중 취소", job_failed_shown: "실패 화면", app_error: "앱 오류"
  });

  function rangeStart(days, nowMs) {
    const span = Number.isFinite(days) && days > 0 ? days : 7;
    return new Date(nowMs - span * DAY_MS).toISOString();
  }

  /** GET /api/v1/admin/funnel from `days` before nowMs (receivedAt, open end). */
  function funnelUrl(days, nowMs) {
    return `/api/v1/admin/funnel?from=${encodeURIComponent(rangeStart(days, nowMs))}`;
  }

  /** CSV of the same range (GET /api/v1/admin/events?format=csv). */
  function eventsCsvUrl(days, nowMs) {
    return `/api/v1/admin/events?format=csv&from=${encodeURIComponent(rangeStart(days, nowMs))}`;
  }

  /** 60 → "60.0%", null → "—". */
  function formatPct(value) {
    return typeof value === "number" && Number.isFinite(value) ? `${value.toFixed(1)}%` : DASH;
  }

  /** Median seconds between steps: 8 → "8초", 12.5 → "12.5초", 95 → "1분 35초", null → "—". */
  function formatSeconds(value) {
    if (typeof value !== "number" || !Number.isFinite(value) || value < 0) return DASH;
    if (value < 60) return `${Number.isInteger(value) ? value : value.toFixed(1)}초`;
    const whole = Math.round(value);
    return `${Math.floor(whole / 60)}분 ${whole % 60}초`;
  }

  function count(value) {
    return Number.isFinite(value) ? String(value) : DASH;
  }

  function stepRows(steps, labels) {
    return steps.map((step, index) => ({
      label: labels[step.step] || FUNNEL_LABELS[step.step] || String(step.step),
      event: String(step.event || ""),
      sessions: count(step.sessions),
      conversion: formatPct(step.conversionPct),
      fromPrev: formatPct(step.fromPrevPct),
      median: formatSeconds(step.medianSecondsFromPrev),
      dropped: index === 0 ? DASH : count(step.droppedSessions)
    }));
  }

  function list(funnel, field) {
    return funnel && Array.isArray(funnel[field]) ? funnel[field] : [];
  }

  function reasonLabel(reason) {
    if (reason.event === "photos_selected") return "사진을 고르지 않고 닫음";
    const name = REASON_LABELS[reason.event] || String(reason.event);
    return `${name} (${reason.key}=${reason.value})`;
  }

  function jobsLine(jobs) {
    if (!jobs || !Number.isFinite(jobs.created)) return `작업 완료율(서버) ${DASH}`;
    return `작업 완료율(서버) ${formatPct(jobs.completionPct)} · 만든 작업 ${jobs.created} · 완료 ${count(jobs.completed)}`
      + ` · 실패 ${count(jobs.failed)} · 취소 ${count(jobs.canceled)} · 진행 중 ${count(jobs.inProgress)}`;
  }

  /**
   * AdminFunnel → table rows of text and the line above the table, plus (2026-11-27) the drop analysis (last screens,
   * reasons), the library funnel and the server's job completion line. Fields an older server leaves out read as empty.
   */
  function funnelView(funnel) {
    const steps = list(funnel, "steps");
    const sessions = funnel && Number.isFinite(funnel.sessions) ? funnel.sessions : 0;
    const first = steps.length > 0 && Number.isFinite(steps[0].sessions) ? steps[0].sessions : 0;
    return {
      rows: stepRows(steps, {}),
      summary: `이 기간 세션 ${sessions}개 · 홈을 본 세션 ${first}개`,
      empty: sessions === 0,
      lastScreens: list(funnel, "lastScreens").map((item) => ({
        label: SCREEN_LABELS[item.screen] || String(item.screen), sessions: count(item.sessions)
      })),
      dropReasons: list(funnel, "dropReasons").map((item) => ({ label: reasonLabel(item), sessions: count(item.sessions) })),
      libraryRows: stepRows(list(funnel, "librarySteps"), LIBRARY_LABELS),
      jobs: jobsLine(funnel && funnel.jobs)
    };
  }

  return Object.freeze({
    DASH, CLIENT_METRICS_URL, CLIENT_METRICS_CSV_URL, SCORE_COLUMNS, FUNNEL_RANGES, FUNNEL_LABELS, SCREEN_LABELS,
    jobPageUrl, ratingSummaryUrl, ratingsCsvUrl, e2eCsvUrl, formatScore, importSummary, uploadErrorText,
    funnelUrl, eventsCsvUrl, formatPct, formatSeconds, funnelView
  });
});
