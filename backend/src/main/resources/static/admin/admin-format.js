// Pure helpers of the admin dashboard (no DOM, no fetch): numbers → Korean labels, query URLs.
// Loaded by /admin/index.html as window.BesideAdminFormat and by node:test (src/test/js/admin-format.test.cjs) via require.
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.BesideAdminFormat = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const DASH = "—";
  // JobStatus values (docs/api/openapi.yaml). Anything else is UNKNOWN, never a crash.
  const STATUS_LABELS = Object.freeze({ PENDING: "대기", PROCESSING: "생성 중", COMPLETED: "완료", FAILED: "실패" });
  // GPU monitor states (PLAN S3); unknown states are shown as they come.
  const GPU_STATE_LABELS = Object.freeze({ UP: "정상", LOADING: "모델 불러오는 중", DOWN: "꺼짐" });

  function isNumber(value) {
    return typeof value === "number" && Number.isFinite(value);
  }

  /** ms → "850ms", "3.2초", "42초", "4분 5초", "1시간 2분"; null → "—". */
  function formatDuration(ms) {
    if (!isNumber(ms)) return DASH;
    const value = Math.max(0, ms);
    if (value < 1000) return `${Math.round(value)}ms`;
    if (value < 10000) return `${Math.floor(value / 100) / 10}초`;
    const seconds = Math.floor(value / 1000);
    if (seconds < 60) return `${seconds}초`;
    if (seconds < 3600) {
      const rest = seconds % 60;
      return rest === 0 ? `${Math.floor(seconds / 60)}분` : `${Math.floor(seconds / 60)}분 ${rest}초`;
    }
    const minutes = Math.floor(seconds / 60) % 60;
    return minutes === 0 ? `${Math.floor(seconds / 3600)}시간` : `${Math.floor(seconds / 3600)}시간 ${minutes}분`;
  }

  /** bytes → "512 B", "1.5 KB", "5.0 MB", "93.1 GB" (1024 base); null → "—". */
  function formatBytes(bytes) {
    if (!isNumber(bytes)) return DASH;
    const units = ["B", "KB", "MB", "GB", "TB"];
    let value = Math.max(0, bytes);
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    return unit === 0 ? `${Math.round(value)} B` : `${value.toFixed(1)} ${units[unit]}`;
  }

  /** gpuPeakMB → "9,120 MB"; null → "—". */
  function formatMegabytes(mb) {
    return isNumber(mb) ? `${Math.round(mb).toLocaleString("en-US")} MB` : DASH;
  }

  /** 0~1 → "83.3%", 1 → "100%"; null → "—". */
  function formatPercent(ratio) {
    if (!isNumber(ratio)) return DASH;
    return `${(ratio * 100).toFixed(1).replace(/\.0$/, "")}%`;
  }

  /** Whole number with thousands separators; null → "—". */
  function formatCount(value) {
    return isNumber(value) ? Math.round(value).toLocaleString("en-US") : DASH;
  }

  /** ISO instant → "방금", "12초 전", "3분 전", "2시간 전", "4일 전" relative to nowMs; bad input → "—". */
  function formatRelative(iso, nowMs) {
    const at = Date.parse(iso);
    if (!Number.isFinite(at) || !isNumber(nowMs)) return DASH;
    const seconds = Math.floor((nowMs - at) / 1000);
    if (seconds < 10) return "방금";
    if (seconds < 60) return `${seconds}초 전`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}분 전`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)}시간 전`;
    return `${Math.floor(seconds / 86400)}일 전`;
  }

  /** Known JobStatus → itself, anything else → "UNKNOWN" (data-status attribute, badge colour). */
  function statusKey(status) {
    return Object.prototype.hasOwnProperty.call(STATUS_LABELS, status) ? status : "UNKNOWN";
  }

  function statusLabel(status) {
    return STATUS_LABELS[statusKey(status)] || "알 수 없음";
  }

  /** AdminPercentiles → "3.4초 / 5.5초"; n = 0 or missing → "—". */
  function formatPercentiles(percentiles) {
    if (!percentiles || !isNumber(percentiles.n) || percentiles.n === 0) return DASH;
    return `${formatDuration(percentiles.p50)} / ${formatDuration(percentiles.p95)}`;
  }

  /**
   * AdminOverview.inference → { state, label, details }. Null means the server has no GPU information yet
   * (no monitor connected): "GPU 상태: 정보 없음". Otherwise state/status is the headline and the other simple
   * fields become detail lines, so the card keeps working whatever shape the monitor ends up with.
   */
  function inferenceSummary(inference, nowMs) {
    if (inference === null || inference === undefined || typeof inference !== "object") {
      return { state: "UNKNOWN", label: "GPU 상태: 정보 없음", details: [] };
    }
    const raw = inference.state !== undefined ? inference.state : inference.status;
    const state = typeof raw === "string" && raw ? raw.toUpperCase() : "UNKNOWN";
    const label = `GPU 상태: ${GPU_STATE_LABELS[state] || (state === "UNKNOWN" ? "알 수 없음" : state)}`;
    const details = [];
    for (const [key, value] of Object.entries(inference)) {
      if (key === "state" || key === "status" || value === null || typeof value === "object") continue;
      if (key === "device") details.push(`장치 ${value}`);
      else if (key === "modelLoaded") details.push(value ? "모델 불러옴" : "모델 안 불러옴");
      else if (key === "checkedAt") details.push(`확인 ${formatRelative(value, nowMs)}`);
      else details.push(`${key}: ${value}`);
    }
    return { state, label, details };
  }

  /** AdminDisk → { usedRatio (0~1 or null), label }. */
  function diskSummary(disk) {
    if (!disk || !isNumber(disk.totalBytes) || disk.totalBytes <= 0 || !isNumber(disk.usableBytes)) {
      return { usedRatio: null, label: DASH };
    }
    const usedRatio = Math.min(1, Math.max(0, (disk.totalBytes - disk.usableBytes) / disk.totalBytes));
    return { usedRatio, label: `남은 ${formatBytes(disk.usableBytes)} / 전체 ${formatBytes(disk.totalBytes)}` };
  }

  /** GET /api/v1/admin/runs as CSV (METRICS §1.1 header). Passthrough rows only when asked for. */
  function runsCsvUrl(includePassthrough) {
    return `/api/v1/admin/runs?format=csv${includePassthrough ? "&includePassthrough=true" : ""}`;
  }

  function statsUrl(includePassthrough) {
    return `/api/v1/admin/stats${includePassthrough ? "?includePassthrough=true" : ""}`;
  }

  /** First 8 characters of a job id for the table; the full id goes into the title. */
  function shortId(id) {
    return typeof id === "string" && id.length > 8 ? id.slice(0, 8) : String(id ?? DASH);
  }

  return Object.freeze({
    DASH, formatDuration, formatBytes, formatMegabytes, formatPercent, formatCount, formatRelative,
    statusKey, statusLabel, formatPercentiles, inferenceSummary, diskSummary, runsCsvUrl, statsUrl, shortId
  });
});
