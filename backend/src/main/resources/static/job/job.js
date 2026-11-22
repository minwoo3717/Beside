"use strict";
// Job detail page (/job/?id={jobId}): status and timings (GET /api/v1/jobs/{id} + /api/v1/admin/jobs/{id}), the input
// photos' names and sizes next to the model (asset), the quality rating panel (ratings) and a share link with a QR code.
// API v1.2 has no endpoint that serves the uploaded photos (REVIEW_CHECKLIST #11), so the page lists them only.
// Rules and texts live in job-format.js, number formats in /admin/admin-format.js, clip labels in /share.js and the QR
// code in qr.js (all node-tested). Server text is only ever set as textContent.
(function () {
  const F = window.BesideAdminFormat;
  const J = window.BesideJob;
  const Q = window.BesideQr;
  const S = typeof BesideShare !== "undefined" ? BesideShare : null; // eslint-disable-line no-undef
  const POLL_MS = 2000;
  const VIEWER_READY_MS = 15000;
  const $ = (selector) => document.querySelector(selector);

  const jobId = J.jobIdFromSearch(window.location.search);
  const viewer = $("#viewer");
  const ratingForm = $("#rating-form");
  const raterInput = $("#rater");
  const commentInput = $("#comment");
  let job = null;
  let ratings = null;
  let pollTimer = null;
  let modelShown = false;
  let photosShown = false;
  let idleClip = null;
  let currentClip = null;

  /** fetch → { ok, status, body, code }; status 0 when the server could not be reached. */
  async function request(url, options) {
    let response;
    try {
      response = await fetch(url, { cache: "no-store", ...options, headers: { Accept: "application/json", ...(options && options.headers) } });
    } catch (offline) {
      return { ok: false, status: 0, body: null, code: null };
    }
    let body = null;
    if ((response.headers.get("Content-Type") || "").includes("json")) {
      try {
        body = await response.json();
      } catch (badJson) {
        body = null;
      }
    }
    const code = !response.ok && body && body.error ? body.error.code : null;
    return { ok: response.ok, status: response.status, body, code };
  }

  function setStatus(text, error) {
    const status = $("#page-status");
    status.textContent = text;
    status.dataset.state = error ? "error" : "";
  }

  function say(element, text, state) {
    element.textContent = text || "";
    element.dataset.state = state || "";
  }

  // ------------------------------------------------------------------ job

  async function loadJob() {
    clearTimeout(pollTimer);
    const [jobResult, summaryResult] = await Promise.all([request(J.jobUrl(jobId)), request(J.adminJobUrl(jobId))]);
    if (jobResult.status === 404) {
      showMissing();
      return;
    }
    if (!jobResult.ok || !jobResult.body) {
      setStatus(`작업을 불러오지 못했어요. ${J.requestErrorText(jobResult.status, jobResult.code)} 잠시 후 다시 시도합니다.`, true);
      pollTimer = setTimeout(loadJob, POLL_MS * 3);
      return;
    }
    job = jobResult.body;
    renderSummary(job, summaryResult.ok ? summaryResult.body : null);
    renderPhotos(job);
    const completed = job.status === "COMPLETED";
    setRatingEnabled(completed);
    $("#share-button").disabled = !completed;
    if (completed) {
      showModel();
      loadRatings();
    }
    if (J.isInFlight(job.status)) {
      setStatus(`진행 중이에요. ${POLL_MS / 1000}초마다 갱신합니다.`);
      if (!document.hidden) pollTimer = setTimeout(loadJob, POLL_MS);
    } else {
      setStatus(`마지막 갱신 ${new Date().toLocaleTimeString("en-GB", { hour12: false })}`);
    }
  }

  function showMissing() {
    $("#job-view").hidden = true;
    $("#missing-view").hidden = false;
    setStatus("작업을 찾을 수 없어요.", true);
  }

  function renderSummary(current, summary) {
    $("#job-view").hidden = false;
    document.title = `작업 ${F.shortId(current.id)} · Beside`;
    const badge = $("#status-badge");
    badge.dataset.status = F.statusKey(current.status);
    badge.textContent = F.statusLabel(current.status);
    $("#job-id").textContent = current.id || "";
    const created = Date.parse(current.createdAt);
    $("#created-at").textContent = Number.isFinite(created) ? new Date(created).toLocaleString("ko-KR") : F.DASH;
    const timings = current.timings || {};
    $("#queued").textContent = F.formatDuration(timings.queuedMs);
    $("#processing").textContent = F.formatDuration(timings.processingMs);
    $("#total").textContent = F.formatDuration(timings.totalMs);
    $("#glb-bytes").textContent = current.asset ? F.formatBytes(current.asset.bytes) : F.DASH;
    $("#attempt").textContent = summary ? F.formatCount(summary.attempt) : F.DASH;
    $("#model-version").textContent = summary && summary.modelVersion ? summary.modelVersion : F.DASH;

    const failed = current.status === "FAILED" && current.error;
    $("#failure").hidden = !failed;
    if (failed) {
      $("#failure-code").textContent = current.error.code || "";
      $("#failure-text").textContent = J.jobErrorText(current.error.code);
      $("#failure-message").textContent = current.error.message || "";
    }
  }

  function renderPhotos(current) {
    if (photosShown) return;
    photosShown = true;
    const files = Array.isArray(current.uploadedFiles) ? current.uploadedFiles : [];
    $("#photo-count").textContent = `${files.length}장`;
    $("#photos").replaceChildren(...files.map((file, index) => {
      const item = document.createElement("li");
      item.textContent = `${index + 1}. ${file.name || "사진"} · ${F.formatBytes(file.bytes)}`;
      return item;
    }));
  }

  // ------------------------------------------------------------------ model

  function viewerReady() {
    if (window.customElements.get("model-viewer")) return Promise.resolve(true);
    return Promise.race([
      window.customElements.whenDefined("model-viewer").then(() => true),
      new Promise((resolve) => setTimeout(resolve, VIEWER_READY_MS, false))
    ]);
  }

  async function showModel() {
    if (modelShown) return;
    modelShown = true;
    $("#model-placeholder").hidden = true;
    viewer.hidden = false;
    viewer.setAttribute("src", J.assetUrl(jobId));
    if (!(await viewerReady())) {
      say($("#viewer-message"), "3D 보기 도구(model-viewer)를 불러오지 못했어요. 네트워크를 확인해 주세요.", "error");
      $("#viewer-message").hidden = false;
    }
  }

  function markChips() {
    $("#animation-chips").querySelectorAll("button").forEach((chip) => chip.setAttribute("aria-pressed", String(chip.dataset.clip === currentClip)));
  }

  async function play(name, once) {
    currentClip = name;
    markChips();
    viewer.animationName = name;
    // model-viewer applies a new animation-name in its next update; options given before it would be lost (share.js).
    await viewer.updateComplete;
    if (currentClip !== name) return;
    viewer.play(once ? { repetitions: 1 } : undefined);
  }

  viewer.addEventListener("load", () => {
    $("#viewer-message").hidden = true;
    const available = viewer.availableAnimations || [];
    const chips = S ? S.animationChips(available) : [];
    $("#animation-chips").replaceChildren(...chips.map((clip) => {
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "chip";
      chip.textContent = clip.label;
      chip.dataset.clip = clip.name;
      chip.setAttribute("aria-pressed", "false");
      chip.addEventListener("click", () => play(clip.name, clip.once));
      return chip;
    }));
    $("#animation-chips").hidden = chips.length === 0;
    idleClip = S ? S.defaultAnimation(available) : null;
    if (idleClip) play(idleClip, false);
  });
  viewer.addEventListener("finished", () => {
    if (idleClip && currentClip !== idleClip) play(idleClip, false);
  });
  viewer.addEventListener("error", () => {
    const message = $("#viewer-message");
    say(message, "3D 모델 파일을 열지 못했어요. (Mock 의 0바이트 샘플이면 정상이에요.)", "error");
    message.hidden = false;
  });

  // ------------------------------------------------------------------ ratings

  function buildScoreItems() {
    $("#score-items").replaceChildren(...J.RATING_ITEMS.map((item) => {
      const fieldset = document.createElement("fieldset");
      fieldset.className = "score";
      fieldset.dataset.item = item.name;
      const legend = document.createElement("legend");
      legend.textContent = item.label;
      const hint = document.createElement("span");
      hint.className = "item-hint";
      hint.textContent = item.hint;
      const options = document.createElement("div");
      options.className = "score-options";
      for (let score = 1; score <= 5; score++) {
        const label = document.createElement("label");
        const input = document.createElement("input");
        input.type = "radio";
        input.name = `score-${item.name}`;
        input.value = String(score);
        input.setAttribute("aria-label", `${item.label} ${score}점`);
        input.addEventListener("change", () => { fieldset.dataset.missing = "false"; });
        const face = document.createElement("span");
        face.textContent = String(score);
        label.append(input, face);
        options.append(label);
      }
      fieldset.append(legend, hint, options);
      return fieldset;
    }));
  }

  function readScores() {
    const scores = {};
    for (const item of J.RATING_ITEMS) {
      const checked = ratingForm.querySelector(`input[name="score-${item.name}"]:checked`);
      scores[item.name] = checked ? checked.value : undefined;
    }
    return scores;
  }

  /** Puts a stored rating into the form (or clears it), so a rater sees and edits their own scores. */
  function fillForm(rating) {
    for (const item of J.RATING_ITEMS) {
      ratingForm.querySelectorAll(`input[name="score-${item.name}"]`).forEach((input) => {
        input.checked = Boolean(rating) && Number(input.value) === rating[item.name];
      });
    }
    commentInput.value = rating && rating.comment ? rating.comment : "";
  }

  function setRatingEnabled(enabled) {
    $("#rating-fields").disabled = !enabled;
    $("#rating-disabled").hidden = enabled;
  }

  async function loadRatings() {
    const result = await request(J.ratingsUrl(jobId));
    if (!result.ok || !result.body) {
      say($("#rating-message"), `평가 목록을 불러오지 못했어요. ${J.requestErrorText(result.status, result.code)}`, "error");
      return;
    }
    const first = ratings === null;
    ratings = result.body;
    renderRatings(ratings);
    if (first) {
      const name = J.normalizeRater(raterInput.value);
      if (name.ok) fillForm(J.ratingOf(ratings, name.value));
    }
  }

  function cell(text, className) {
    const td = document.createElement("td");
    td.textContent = text;
    if (className) td.className = className;
    return td;
  }

  function renderRatings(list) {
    const head = document.createElement("tr");
    ["평가자", ...J.RATING_ITEMS.map((item) => item.label), "코멘트", "저장"].forEach((label, index) => {
      const th = document.createElement("th");
      th.scope = "col";
      th.textContent = label;
      if (index > 0 && index <= J.RATING_ITEMS.length) th.className = "num";
      head.append(th);
    });
    $("#ratings-head").replaceChildren(head);

    const items = Array.isArray(list.items) ? list.items : [];
    const now = Date.now();
    $("#ratings-body").replaceChildren(...items.map((rating) => {
      const row = document.createElement("tr");
      row.append(cell(rating.rater));
      J.RATING_ITEMS.forEach((item) => row.append(cell(String(rating[item.name]), "num")));
      row.append(cell(rating.comment || F.DASH, rating.comment ? "wrap" : "muted"));
      row.append(cell(F.formatRelative(rating.updatedAt, now)));
      return row;
    }));

    const average = list.average;
    if (average) {
      const row = document.createElement("tr");
      row.append(cell(`평균 (${average.raters}명)`));
      J.RATING_ITEMS.forEach((item) => row.append(cell(J.formatScore(average[item.name]), "num")));
      row.append(cell(""), cell(""));
      $("#ratings-foot").replaceChildren(row);
    } else {
      $("#ratings-foot").replaceChildren();
    }
    $("#ratings-empty").hidden = items.length > 0;
    $("#ratings-note").hidden = !average || average.raters >= 2;
  }

  async function saveRating(event) {
    event.preventDefault();
    const message = $("#rating-message");
    const name = J.normalizeRater(raterInput.value);
    if (!name.ok) {
      say(message, name.message, "error");
      raterInput.focus();
      return;
    }
    const body = J.ratingBody(readScores(), commentInput.value);
    if (!body.ok) {
      ratingForm.querySelectorAll(".score").forEach((fieldset) => {
        fieldset.dataset.missing = String(body.missing.includes(fieldset.dataset.item));
      });
      say(message, body.message, "error");
      return;
    }
    rememberRater(name.value);
    const submit = $("#rating-submit");
    submit.disabled = true;
    say(message, "저장하는 중…");
    const result = await request(J.ratingUrl(jobId, name.value), {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body.body)
    });
    submit.disabled = false;
    if (!result.ok) {
      say(message, `저장하지 못했어요. ${J.requestErrorText(result.status, result.code)}`, "error");
      return;
    }
    say(message, `${result.body.rater} 님의 평가를 저장했어요 (${new Date().toLocaleTimeString("en-GB", { hour12: false })}).`, "ok");
    await loadRatings();
  }

  function storedRater() {
    try {
      return window.localStorage.getItem(J.RATER_STORAGE_KEY) || "";
    } catch (blocked) {
      return "";
    }
  }

  function rememberRater(name) {
    try {
      window.localStorage.setItem(J.RATER_STORAGE_KEY, name);
    } catch (blocked) {
      // Private mode or blocked storage: the name is simply not remembered.
    }
  }

  // ------------------------------------------------------------------ share

  async function createShare() {
    const button = $("#share-button");
    const message = $("#share-message");
    button.disabled = true;
    say(message, "링크를 만드는 중…");
    const result = await request(J.sharesUrl(jobId), { method: "POST" });
    button.disabled = !(job && job.status === "COMPLETED");
    const link = result.ok && result.body ? J.shareLink(window.location.origin, result.body.url) : null;
    if (!link) {
      // The only 400 a ttl-less request can get is the cap on active links per job (openapi createShare).
      const why = result.code === "INVALID_REQUEST"
        ? "이 작업의 살아 있는 공유 링크가 이미 20개예요. '모두 끊기' 뒤 다시 만들어 주세요."
        : J.requestErrorText(result.status, result.code);
      say(message, `링크를 만들지 못했어요. ${why}`, "error");
      return;
    }
    $("#share-url").value = link;
    const until = S ? S.formatExpiry(result.body.expiresAt) : null;
    $("#share-expiry").textContent = until ? `${until}까지 열 수 있어요.` : "";
    drawQr(link);
    $("#share-result").hidden = false;
    say(message, "새 공유 링크를 만들었어요. 폰 카메라로 QR 을 찍으면 열려요.", "ok");
  }

  /** DELETE /api/v1/jobs/{id}/shares: ends every active link of this job, also ones made elsewhere or earlier. */
  async function revokeShares() {
    const button = $("#share-revoke-button");
    const message = $("#share-message");
    if (!window.confirm("이 작업의 공유 링크를 모두 끊을까요? 이미 보낸 링크와 QR 도 열리지 않게 돼요.")) return;
    button.disabled = true;
    say(message, "링크를 끊는 중…");
    const result = await request(J.sharesUrl(jobId), { method: "DELETE" });
    button.disabled = false;
    if (!result.ok || !result.body) {
      say(message, `링크를 끊지 못했어요. ${J.requestErrorText(result.status, result.code)}`, "error");
      return;
    }
    $("#share-result").hidden = true;
    $("#share-url").value = "";
    const count = result.body.revoked;
    say(message, count > 0 ? `공유 링크 ${count}개를 끊었어요.` : "끊을 공유 링크가 없었어요.", "ok");
  }

  function drawQr(text) {
    const svg = $("#share-qr");
    const ns = "http://www.w3.org/2000/svg";
    let symbol;
    try {
      symbol = Q.encode(text);
    } catch (tooLong) {
      svg.replaceChildren();
      return;
    }
    const border = 4;
    const extent = symbol.size + border * 2;
    svg.setAttribute("viewBox", `0 0 ${extent} ${extent}`);
    svg.setAttribute("shape-rendering", "crispEdges");
    const background = document.createElementNS(ns, "rect");
    background.setAttribute("width", String(extent));
    background.setAttribute("height", String(extent));
    background.setAttribute("fill", "#ffffff");
    const modules = document.createElementNS(ns, "path");
    modules.setAttribute("d", Q.svgPath(symbol, border));
    modules.setAttribute("fill", "#000000");
    svg.replaceChildren(background, modules);
  }

  async function copyLink() {
    const input = $("#share-url");
    const message = $("#share-message");
    try {
      await navigator.clipboard.writeText(input.value);
      say(message, "링크를 복사했어요.", "ok");
    } catch (denied) {
      input.focus();
      input.select();
      say(message, "링크를 선택했어요. Ctrl+C 로 복사해 주세요.");
    }
  }

  // ------------------------------------------------------------------ start

  if (!jobId) {
    showMissing();
    return;
  }
  buildScoreItems();
  raterInput.value = storedRater();
  raterInput.addEventListener("change", () => {
    const name = J.normalizeRater(raterInput.value);
    if (name.ok && ratings) fillForm(J.ratingOf(ratings, name.value));
  });
  ratingForm.addEventListener("submit", saveRating);
  $("#share-button").addEventListener("click", createShare);
  $("#share-revoke-button").addEventListener("click", revokeShares);
  $("#copy-button").addEventListener("click", copyLink);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && job && J.isInFlight(job.status)) loadJob();
  });
  loadJob();
})();
