"use strict";
// Admin dashboard (/admin): GET /api/v1/admin/overview, /jobs, /stats and /ratings/summary every 5 s while the tab is
// visible, and the app metrics.csv upload. Formatting rules live in admin-format.js and admin-measurement.js (tested
// with node:test). Server text is only ever set as textContent.
(function () {
  const F = window.BesideAdminFormat;
  const M = window.BesideAdminMeasurement;
  const REFRESH_MS = 5000;
  const JOBS_LIMIT = 20;
  const $ = (selector) => document.querySelector(selector);

  const refreshStatus = $("#refresh-status");
  const liveDot = $("#live-dot");
  const passthroughBox = $("#include-passthrough");
  const csvLink = $("#csv-link");
  let timer = null;
  let loading = false;
  let rerun = false; // a refresh asked for while one is loading (e.g. the passthrough box changed)
  let lastSuccess = null;

  async function getJson(url) {
    const response = await fetch(url, { headers: { Accept: "application/json" }, cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status} ${url}`);
    return response.json();
  }

  async function refresh() {
    if (loading) {
      rerun = true;
      return;
    }
    loading = true;
    clearTimeout(timer);
    try {
      const [overview, jobs, stats, ratingSummary] = await Promise.all([
        getJson("/api/v1/admin/overview"),
        getJson(`/api/v1/admin/jobs?limit=${JOBS_LIMIT}`),
        getJson(F.statsUrl(passthroughBox.checked)),
        getJson(M.ratingSummaryUrl(passthroughBox.checked))
      ]);
      const now = Date.now();
      renderOverview(overview, now);
      renderJobs(Array.isArray(jobs.items) ? jobs.items : [], now);
      renderStats(Array.isArray(stats.groups) ? stats.groups : []);
      renderRatingSummary(Array.isArray(ratingSummary.groups) ? ratingSummary.groups : []);
      lastSuccess = new Date(now);
      showConnection(true);
    } catch (error) {
      console.error(error);
      showConnection(false);
    } finally {
      loading = false;
      if (rerun) {
        rerun = false;
        refresh();
      } else {
        schedule();
      }
    }
  }

  /** Next refresh only while the tab is visible; visibilitychange restarts it. */
  function schedule() {
    clearTimeout(timer);
    timer = document.hidden ? null : setTimeout(refresh, REFRESH_MS);
  }

  /** Local wall-clock time as 00:47:57 (ko-KR would spell it 0시 47분 57초, too long for the status line). */
  function clock(date) {
    return date.toLocaleTimeString("en-GB", { hour12: false });
  }

  function showConnection(ok) {
    liveDot.dataset.state = ok ? "live" : "error";
    refreshStatus.dataset.state = ok ? "live" : "error";
    if (ok) {
      refreshStatus.textContent = `마지막 갱신 ${clock(lastSuccess)} · 5초마다 갱신`;
    } else {
      refreshStatus.textContent = lastSuccess
        ? `서버에 연결할 수 없어요. 마지막 갱신 ${clock(lastSuccess)} 의 값을 보여 주고 있어요.`
        : "서버에 연결할 수 없어요. 잠시 후 다시 시도합니다.";
    }
  }

  // ------------------------------------------------------------------ cards

  function renderOverview(overview, now) {
    $("#server-profile").textContent = `${overview.profile || "—"} · ${overview.workerType || "—"}`.toUpperCase();

    const queue = overview.queue || {};
    $("#queue-pending").textContent = F.formatCount(queue.pending);
    $("#queue-processing").textContent = F.formatCount(queue.processing);
    $("#queue-oldest-pending").textContent = F.formatDuration(queue.oldestPendingMs);
    $("#queue-oldest-processing").textContent = F.formatDuration(queue.oldestProcessingMs);

    const gpu = F.inferenceSummary(overview.inference, now);
    const gpuLabel = $("#gpu-label");
    gpuLabel.textContent = gpu.label;
    gpuLabel.dataset.state = gpu.state;
    $("#gpu-details").replaceChildren(...gpu.details.map((text) => {
      const item = document.createElement("li");
      item.textContent = text;
      return item;
    }));
    $("#gpu-note").hidden = overview.inference !== null && overview.inference !== undefined;

    const disk = F.diskSummary(overview.disk);
    $("#disk-used").textContent = F.formatPercent(disk.usedRatio);
    $("#disk-label").textContent = disk.label;
    const meter = $("#disk-meter");
    meter.style.width = `${Math.round((disk.usedRatio || 0) * 100)}%`;
    meter.dataset.level = disk.usedRatio >= 0.9 ? "bad" : disk.usedRatio >= 0.8 ? "warn" : "ok";
    $("#disk-storage").textContent = F.formatBytes(overview.disk ? overview.disk.storageBytes : null);

    const counts = overview.statusCounts || {};
    for (const status of ["PENDING", "PROCESSING", "COMPLETED", "FAILED"]) {
      $(`#count-${status}`).textContent = F.formatCount(counts[status]);
    }
  }

  // ------------------------------------------------------------------ tables

  function cell(text, className, title) {
    const td = document.createElement("td");
    td.textContent = text;
    if (className) td.className = className;
    if (title) td.title = title;
    return td;
  }

  function renderJobs(items, now) {
    const rows = items.map((job) => {
      const row = document.createElement("tr");
      const idCell = document.createElement("td");
      const link = document.createElement("a");
      link.className = "job-link";
      link.href = M.jobPageUrl(job.id);
      link.title = `${job.id || ""} · 사진·모델·평가 보기`;
      const code = document.createElement("code");
      code.textContent = F.shortId(job.id);
      link.append(code);
      idCell.append(link);

      const statusCell = document.createElement("td");
      const badge = document.createElement("span");
      badge.className = "badge";
      badge.dataset.status = F.statusKey(job.status);
      badge.textContent = F.statusLabel(job.status);
      statusCell.append(badge);

      const timings = job.timings || {};
      const created = Date.parse(job.createdAt);
      row.append(
        idCell,
        statusCell,
        cell(F.formatRelative(job.createdAt, now), "", Number.isFinite(created) ? new Date(created).toLocaleString("ko-KR") : ""),
        cell(F.formatCount(job.attempt), "num"),
        cell(F.formatDuration(timings.queuedMs), "num"),
        cell(F.formatDuration(timings.processingMs), "num"),
        cell(F.formatDuration(timings.totalMs), "num"),
        cell(job.errorCode || F.DASH, job.errorCode ? "error-code" : "muted"),
        cell(job.modelVersion || F.DASH, job.modelVersion ? "wrap" : "muted"));
      return row;
    });
    $("#jobs-body").replaceChildren(...rows);
    $("#jobs-empty").hidden = rows.length > 0;
  }

  function renderStats(groups) {
    const rows = groups.map((group) => {
      const row = document.createElement("tr");
      row.append(
        cell(group.modelVersion || F.DASH, "wrap"),
        cell(group.workerType || F.DASH),
        cell(F.formatCount(group.runs), "num", `완료 ${F.formatCount(group.completed)} · 실패 ${F.formatCount(group.failed)}`),
        cell(F.formatPercent(group.successRate), "num"),
        cell(F.formatPercentiles(group.queuedMs), "num"),
        cell(F.formatPercentiles(group.processingMs), "num"),
        cell(F.formatPercentiles(group.totalMs), "num"),
        cell(F.formatPercentiles(group.inferMs), "num"),
        cell(F.formatBytes(group.avgGlbBytes), "num"),
        cell(F.formatMegabytes(group.avgGpuPeakMB), "num"));
      return row;
    });
    $("#stats-body").replaceChildren(...rows);
    $("#stats-empty").hidden = rows.length > 0;
  }

  function renderRatingSummary(groups) {
    const rows = groups.map((group) => {
      const row = document.createElement("tr");
      row.append(
        cell(group.modelVersion || F.DASH, "wrap"),
        cell(F.formatCount(group.jobs), "num"),
        cell(F.formatCount(group.ratings), "num"),
        cell(F.formatCount(group.raters), "num"),
        ...M.SCORE_COLUMNS.map((column) => cell(M.formatScore(group[column.name]), "num")));
      return row;
    });
    $("#ratings-body").replaceChildren(...rows);
    $("#ratings-empty").hidden = rows.length > 0;
  }

  // ------------------------------------------------------------------ app metrics.csv upload

  const clientForm = $("#client-form");
  const clientFile = $("#client-file");
  const clientSubmit = $("#client-submit");
  const clientResult = $("#client-result");

  function showUpload(text, state, errors) {
    clientResult.textContent = text;
    clientResult.dataset.state = state || "";
    $("#client-errors").replaceChildren(...(errors || []).map((line) => {
      const item = document.createElement("li");
      item.textContent = line;
      return item;
    }));
  }

  clientForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const file = clientFile.files && clientFile.files[0];
    if (!file) {
      showUpload("올릴 metrics.csv 파일을 골라 주세요.", "error");
      return;
    }
    const body = new FormData();
    body.append("file", file, file.name);
    clientSubmit.disabled = true;
    showUpload("올리는 중…");
    let response = null;
    let json = null;
    try {
      response = await fetch(M.CLIENT_METRICS_URL, { method: "POST", body, headers: { Accept: "application/json" } });
      json = await response.json().catch(() => null);
    } catch (offline) {
      response = null;
    } finally {
      clientSubmit.disabled = false;
    }
    if (!response || !response.ok) {
      showUpload(M.uploadErrorText(response ? response.status : 0, json), "error");
      return;
    }
    const summary = M.importSummary(json);
    showUpload(summary.text, summary.errors.length > 0 ? "error" : "ok", summary.errors);
    clientFile.value = "";
  });

  // ------------------------------------------------------------------ controls

  function updateCsvLink() {
    csvLink.href = F.runsCsvUrl(passthroughBox.checked);
    $("#ratings-csv-link").href = M.ratingsCsvUrl(passthroughBox.checked);
    $("#e2e-csv-link").href = M.e2eCsvUrl(passthroughBox.checked);
  }

  passthroughBox.addEventListener("change", () => {
    updateCsvLink();
    refresh();
  });

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      clearTimeout(timer);
      timer = null;
    } else {
      refresh();
    }
  });

  updateCsvLink();
  refresh();
})();
