"use strict";
// Pet library page (/library): pets, their generation history, unassigned jobs, a new model per pet and the 3D view.
// Calls the API of docs/api/openapi.yaml tag pets (plus createJob, getJob, cancelJob, listJobs). Pure helpers live in
// library-core.js (window.BesideLibrary, node:test). Ratings and share links of one job are on /job/?id= (linked only).
// Every value from the server goes in with textContent / attributes, never as HTML: pet names are free text.
(function (doc, win) {
  const L = win.BesideLibrary;
  const byId = (id) => doc.getElementById(id);
  const UNASSIGNED_PAGES = 5;      // GET /api/v1/jobs pages of 100 scanned for unassigned jobs
  const DETAIL_REFRESH_MS = 5000;  // pet view refresh while one of its jobs is still running
  const VIEWER_READY_MS = 15000;

  const state = {
    pets: [],
    pet: null,          // PetDetailResponse on screen
    active: null,       // { jobId, petId, petName, startedAt, timer } of the model being made here
    refreshTimer: null,
    flash: null,        // message for the view after the next hash change: text (success) or { text, kind }
    viewerJobId: null,
    idleClip: null,
    currentClip: null
  };

  // ---------------------------------------------------------------- API

  class ApiError extends Error {
    constructor(message, code, status) {
      super(message);
      this.code = code;
      this.status = status;
    }
  }

  /** fetch + the v1 error envelope: resolves the JSON body (null for 204), rejects with ApiError and Korean copy. */
  async function api(method, url, body) {
    const options = { method, headers: { Accept: "application/json" }, cache: "no-store" };
    if (body instanceof FormData) {
      options.body = body;
    } else if (body !== undefined) {
      options.headers["Content-Type"] = "application/json";
      options.body = JSON.stringify(body);
    }
    let response;
    try {
      response = await fetch(url, options);
    } catch (networkError) {
      throw new ApiError("서버에 연결할 수 없어요. 네트워크를 확인해 주세요.", null, 0);
    }
    if (response.status === 204) return null;
    const text = await response.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = null; }
    if (!response.ok) {
      const code = data && data.error ? data.error.code : null;
      console.error("Library request failed", method, url, response.status, text);
      throw new ApiError(L.errorMessage(code, response.status), code || null, response.status);
    }
    return data;
  }

  const enc = encodeURIComponent;

  // ---------------------------------------------------------------- small DOM helpers

  function el(tag, props, ...children) {
    const node = doc.createElement(tag);
    Object.entries(props || {}).forEach(([key, value]) => {
      if (value === null || value === undefined || value === false) return;
      if (key === "className") node.className = value;
      else if (key === "text") node.textContent = value;
      else if (key === "dataset") Object.assign(node.dataset, value);
      else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
      else node.setAttribute(key, value === true ? "" : String(value));
    });
    children.flat().forEach((child) => {
      if (child !== null && child !== undefined && child !== false) node.append(child);
    });
    return node;
  }

  function setMessage(text, kind) {
    const banner = byId("global-message");
    banner.textContent = text || "";
    banner.dataset.kind = kind || "error";
    banner.hidden = !text;
  }

  function showFormError(form, text) {
    const target = form.querySelector(".form-error");
    target.textContent = text || "";
    target.hidden = !text;
  }

  /** Runs fn with the button disabled; errors become the page message. */
  async function busy(button, fn) {
    if (button) button.disabled = true;
    try {
      await fn();
    } catch (error) {
      setMessage(error.message || "요청을 처리하지 못했어요.");
    } finally {
      if (button && button.isConnected) button.disabled = false;
    }
  }

  /** The picture slot of a row or a pet: always the placeholder, API v1.2 has no thumbnails (REVIEW_CHECKLIST #11). */
  function thumb(className) {
    return el("span", { className: `thumb placeholder ${className || ""}`.trim() });
  }

  function badge(status) {
    return el("span", { className: "badge", dataset: { status: L.statusKey(status) }, text: L.statusLabel(status) });
  }

  // ---------------------------------------------------------------- confirm dialog

  function confirmDialog(title, text, withOption) {
    const dialog = byId("confirm-dialog");
    const option = byId("confirm-with-jobs");
    if (typeof dialog.showModal !== "function") {
      return Promise.resolve({ ok: win.confirm(`${title}\n\n${text}`), withJobs: false });
    }
    byId("confirm-title").textContent = title;
    byId("confirm-text").textContent = text;
    byId("confirm-option").hidden = !withOption;
    option.checked = false;
    dialog.returnValue = "";
    return new Promise((resolve) => {
      dialog.addEventListener("close", () => resolve({ ok: dialog.returnValue === "ok", withJobs: option.checked }), { once: true });
      dialog.showModal();
    });
  }

  // ---------------------------------------------------------------- home: pets + unassigned jobs

  async function loadHome() {
    const list = await api("GET", "/api/v1/pets");
    state.pets = (list && list.items) || [];
    renderPets();
    await loadUnassigned();
  }

  function renderPets() {
    const grid = byId("pet-grid");
    grid.replaceChildren(...state.pets.map((pet) => el("li", null,
      el("a", { className: "pet-card", href: L.petRoute(pet.id) },
        thumb("cover-thumb"),
        el("span", { className: "pet-card-body" },
          el("strong", { className: "pet-card-name", text: pet.name }),
          el("span", { className: "hint", text: `${L.speciesLabel(pet.species)} · 모델 ${pet.jobCount}개` }),
          el("span", { className: "hint", text: pet.latestJobAt ? `마지막 ${L.formatDateTime(pet.latestJobAt)}` : "아직 기록 없음" }))))));
    byId("pets-empty").hidden = state.pets.length > 0;
  }

  async function loadUnassigned() {
    const found = [];
    let cursor = null;
    let pages = 0;
    do {
      const page = await api("GET", `/api/v1/jobs?limit=100${cursor ? `&cursor=${enc(cursor)}` : ""}`);
      found.push(...L.unassigned(page && page.items));
      cursor = page && page.nextCursor;
      pages += 1;
    } while (cursor && pages < UNASSIGNED_PAGES);
    renderUnassigned(found, Boolean(cursor));
  }

  function renderUnassigned(jobs, more) {
    byId("unassigned-list").replaceChildren(...jobs.map(unassignedRow));
    byId("unassigned-empty").hidden = jobs.length > 0;
    const hint = byId("unassigned-more");
    hint.hidden = !more;
    hint.textContent = more ? `최근 ${UNASSIGNED_PAGES * 100}건까지만 살펴봤어요. 오래된 미분류 작업은 더 있을 수 있어요.` : "";
  }

  function unassignedRow(job) {
    const select = el("select", { "aria-label": "넣을 반려동물" },
      el("option", { value: "", text: state.pets.length ? "펫 고르기" : "먼저 펫을 추가해 주세요" }),
      state.pets.map((pet) => el("option", { value: pet.id, text: pet.name })));
    const assign = el("button", { className: "primary-button small", type: "button", text: "넣기", disabled: true });
    select.addEventListener("change", () => { assign.disabled = !select.value; });
    assign.addEventListener("click", () => busy(assign, async () => {
      await api("PUT", `/api/v1/jobs/${enc(job.id)}/pet`, { petId: select.value });
      setMessage("펫에 넣었어요.", "ok");
      await loadHome();
    }));
    return el("li", { className: "job-row" },
      thumb(),
      jobInfo(job, null),
      el("div", { className: "job-actions" }, select, assign, detailLink(job)));
  }

  // ---------------------------------------------------------------- pet view

  async function loadPet(petId, keepViewer) {
    clearTimeout(state.refreshTimer);
    let pet;
    try {
      pet = await api("GET", `/api/v1/pets/${enc(petId)}`);
    } catch (error) {
      if (error.code === "PET_NOT_FOUND") {
        state.flash = { text: error.message, kind: "error" };
        win.location.hash = "#/";
        return;
      }
      throw error;
    }
    if (L.parseRoute(win.location.hash).petId !== petId) return; // navigated away meanwhile
    state.pet = pet;
    renderPet(pet);
    const shown = keepViewer && pet.jobs.find((job) => job.id === state.viewerJobId && job.status === "COMPLETED");
    showInViewer(shown || L.viewerJob(pet));
    renderProgressArea();
    if (pet.jobs.some((job) => !L.isFinished(job.status))) {
      state.refreshTimer = setTimeout(() => loadPet(petId, true).catch(() => {}), DETAIL_REFRESH_MS);
    }
  }

  function renderPet(pet) {
    doc.title = `${pet.name} · Beside 보관함`;
    byId("pet-cover").replaceChildren(thumb("cover-thumb large"));
    byId("pet-species").textContent = L.speciesLabel(pet.species);
    byId("pet-name").textContent = pet.name;
    byId("pet-note").textContent = pet.note || "";
    byId("pet-note").hidden = !pet.note;
    byId("pet-meta").textContent = `모델 ${pet.jobCount}개` + (pet.latestJobAt ? ` · 마지막 ${L.formatDateTime(pet.latestJobAt)}` : "")
      + ` · 추가 ${L.formatDateTime(pet.createdAt)}`;
    byId("history-list").replaceChildren(...pet.jobs.map((job) => historyRow(pet, job)));
    byId("history-empty").hidden = pet.jobs.length > 0;
    byId("history-count").textContent = pet.jobs.length ? `${pet.jobs.length}건 · 최신순` : "";
    updateCreateButton();
  }

  function jobInfo(job, pet) {
    const version = pet && pet.modelVersions ? pet.modelVersions[job.id] : null;
    const line = [L.formatDateTime(job.createdAt), L.photoCount(job)];
    if (version) line.push(version);
    const extra = L.isFinished(job.status) && job.status !== "FAILED" ? null : L.waitText(job);
    return el("div", { className: "job-main" },
      el("div", { className: "job-title" },
        badge(job.status),
        pet && job.id === pet.coverJobId ? el("span", { className: "cover-badge", text: "대표" }) : null,
        el("span", { text: line.join(" · ") })),
      extra ? el("span", { className: job.status === "FAILED" ? "hint error-text" : "hint", text: extra }) : null);
  }

  function detailLink(job) {
    return el("a", { className: "ghost-button small", href: L.jobDetailUrl(job.id), text: "상세" });
  }

  function historyRow(pet, job) {
    const actions = [];
    if (job.status === "COMPLETED") {
      const view = el("button", { className: "ghost-button small", type: "button", text: "3D 보기", dataset: { viewJob: job.id },
        "aria-pressed": String(job.id === state.viewerJobId) });
      view.addEventListener("click", () => showInViewer(job));
      actions.push(view);
      if (job.id !== pet.coverJobId) {
        const cover = el("button", { className: "ghost-button small", type: "button", text: "대표로" });
        cover.addEventListener("click", () => busy(cover, async () => {
          await api("PATCH", `/api/v1/pets/${enc(pet.id)}`, { coverJobId: job.id });
          await loadPet(pet.id, true);
        }));
        actions.push(cover);
      }
    }
    if (L.canCancel(job) && !(state.active && state.active.jobId === job.id)) {
      const cancel = el("button", { className: "ghost-button small", type: "button", text: "취소" });
      cancel.addEventListener("click", () => busy(cancel, async () => {
        await api("POST", `/api/v1/jobs/${enc(job.id)}/cancel`);
        await loadPet(pet.id, true);
      }));
      actions.push(cancel);
    }
    actions.push(detailLink(job));
    const out = el("button", { className: "ghost-button small", type: "button", text: "미분류로" });
    out.addEventListener("click", () => busy(out, async () => {
      await api("PUT", `/api/v1/jobs/${enc(job.id)}/pet`, { petId: null });
      setMessage("미분류로 옮겼어요. 보관함 첫 화면에서 다른 펫에 넣을 수 있어요.", "ok");
      await loadPet(pet.id, true);
    }));
    actions.push(out);
    return el("li", { className: "job-row", dataset: { jobId: job.id } },
      thumb(), jobInfo(job, pet), el("div", { className: "job-actions" }, actions));
  }

  // ---------------------------------------------------------------- editing and deleting the pet

  function fillForm(form, pet) {
    form.elements.name.value = pet ? pet.name : "";
    form.elements.species.value = pet ? (L.SPECIES.some((s) => s.value === pet.species) ? pet.species : "OTHER") : "DOG";
    form.elements.note.value = pet && pet.note ? pet.note : "";
    showFormError(form, "");
  }

  function readForm(form) {
    return { name: form.elements.name.value, species: form.elements.species.value, note: form.elements.note.value };
  }

  function toggleForm(form, toggle, open) {
    form.hidden = !open;
    toggle.setAttribute("aria-expanded", String(open));
    if (open) form.elements.name.focus();
  }

  function wireForms() {
    const addForm = byId("add-pet-form");
    const addToggle = byId("add-pet-toggle");
    addToggle.addEventListener("click", () => { fillForm(addForm, null); toggleForm(addForm, addToggle, addForm.hidden); });
    addForm.querySelector("[data-action=close]").addEventListener("click", () => toggleForm(addForm, addToggle, false));
    addForm.addEventListener("submit", (event) => {
      event.preventDefault();
      const problem = L.checkPetForm(readForm(addForm));
      if (problem) { showFormError(addForm, problem); return; }
      busy(addForm.querySelector("[type=submit]"), async () => {
        try {
          const pet = await api("POST", "/api/v1/pets", L.petBody(readForm(addForm)));
          toggleForm(addForm, addToggle, false);
          state.flash = `${pet.name}을(를) 추가했어요. 사진을 골라 첫 모델을 만들어 보세요.`;
          win.location.hash = L.petRoute(pet.id);
        } catch (error) {
          showFormError(addForm, error.message);
        }
      });
    });

    const editForm = byId("edit-pet-form");
    const editToggle = byId("edit-pet-toggle");
    editToggle.addEventListener("click", () => { fillForm(editForm, state.pet); toggleForm(editForm, editToggle, editForm.hidden); });
    editForm.querySelector("[data-action=close]").addEventListener("click", () => toggleForm(editForm, editToggle, false));
    editForm.addEventListener("submit", (event) => {
      event.preventDefault();
      const problem = L.checkPetForm(readForm(editForm));
      if (problem) { showFormError(editForm, problem); return; }
      const petId = state.pet.id;
      busy(editForm.querySelector("[type=submit]"), async () => {
        try {
          await api("PATCH", `/api/v1/pets/${enc(petId)}`, L.petBody(readForm(editForm)));
          toggleForm(editForm, editToggle, false);
          setMessage("저장했어요.", "ok");
          await loadPet(petId, true);
        } catch (error) {
          showFormError(editForm, error.message);
        }
      });
    });

    const deleteButtonEl = byId("delete-pet-button");
    deleteButtonEl.addEventListener("click", async () => {
      const pet = state.pet;
      if (!pet) return;
      const answer = await confirmDialog(`${pet.name}을(를) 보관함에서 지울까요?`,
        "기본으로는 펫만 지우고 생성 기록은 미분류로 옮겨요. 함께 지우면 사진·모델 파일도 지워지고 되돌릴 수 없어요.", true);
      if (!answer.ok) return;
      await busy(deleteButtonEl, async () => {
        await api("DELETE", `/api/v1/pets/${enc(pet.id)}?withJobs=${answer.withJobs}`);
        state.flash = answer.withJobs ? `${pet.name}과(와) 생성 기록을 지웠어요.` : `${pet.name}을(를) 지웠어요. 생성 기록은 미분류로 옮겼어요.`;
        win.location.hash = "#/";
      });
    });
  }

  // ---------------------------------------------------------------- 3D viewer

  function viewerReady() {
    if (!win.customElements) return Promise.resolve(false);
    if (win.customElements.get("model-viewer")) return Promise.resolve(true);
    return Promise.race([
      win.customElements.whenDefined("model-viewer").then(() => true),
      new Promise((resolve) => setTimeout(resolve, VIEWER_READY_MS, false))
    ]);
  }

  function viewerSay(text) {
    const message = byId("viewer-message");
    message.textContent = text || "";
    message.hidden = !text;
  }

  async function showInViewer(job) {
    const viewer = byId("viewer");
    const pet = state.pet;
    if (!job) {
      state.viewerJobId = null;
      viewer.hidden = true;
      viewer.removeAttribute("src");
      byId("animation-chips").hidden = true;
      byId("viewer-empty").hidden = false;
      byId("viewer-caption").textContent = "";
      viewerSay("");
      markViewButtons();
      return;
    }
    state.viewerJobId = job.id;
    byId("viewer-empty").hidden = true;
    byId("viewer-caption").textContent = `${L.formatDateTime(job.createdAt)} 모델` + (pet && job.id === pet.coverJobId ? " · 대표" : "");
    markViewButtons();
    if (!(await viewerReady())) {
      viewerSay("3D 보기 도구를 불러오지 못했어요. 네트워크를 확인한 뒤 새로고침해 주세요.");
      return;
    }
    const src = L.assetUrl(job.id);
    viewer.hidden = false;
    if (viewer.getAttribute("src") !== src) {
      viewerSay("모델을 불러오는 중이에요…");
      byId("animation-chips").hidden = true;
      viewer.setAttribute("src", src);
    }
  }

  function markViewButtons() {
    doc.querySelectorAll("[data-view-job]").forEach((button) =>
      button.setAttribute("aria-pressed", String(button.dataset.viewJob === state.viewerJobId)));
  }

  async function play(name, once) {
    const viewer = byId("viewer");
    state.currentClip = name;
    byId("animation-chips").querySelectorAll("button").forEach((chip) =>
      chip.setAttribute("aria-pressed", String(chip.dataset.clip === name)));
    viewer.animationName = name;
    await viewer.updateComplete; // as share.js: options given before model-viewer's next update would be overwritten
    if (state.currentClip !== name) return;
    viewer.play(once ? { repetitions: 1 } : undefined);
  }

  function wireViewer() {
    const viewer = byId("viewer");
    viewer.addEventListener("load", () => {
      viewerSay("");
      const available = viewer.availableAnimations || [];
      const chips = L.animationChips(available).map((clip) => {
        const chip = el("button", { className: "chip", type: "button", text: clip.label, dataset: { clip: clip.name }, "aria-pressed": "false" });
        chip.addEventListener("click", () => play(clip.name, clip.once));
        return chip;
      });
      byId("animation-chips").replaceChildren(...chips);
      byId("animation-chips").hidden = chips.length === 0;
      state.idleClip = L.defaultAnimation(available);
      if (state.idleClip) play(state.idleClip, false);
    });
    viewer.addEventListener("finished", () => {
      if (state.idleClip && state.currentClip !== state.idleClip) play(state.idleClip, false);
    });
    viewer.addEventListener("error", () => viewerSay("3D 모델 파일을 열지 못했어요. (Mock 의 0바이트 샘플이면 정상이에요)"));
  }

  // ---------------------------------------------------------------- new model for this pet

  function updateCreateButton() {
    const files = byId("create-photos").files;
    const problem = files && files.length ? L.checkPhotos(files) : "none";
    byId("create-button").disabled = Boolean(problem) || Boolean(state.active) || !state.pet;
  }

  function askNotificationPermission() {
    if (!("Notification" in win) || win.Notification.permission !== "default") return;
    try {
      const asked = win.Notification.requestPermission();
      if (asked && typeof asked.catch === "function") asked.catch(() => {});
    } catch { /* older browsers: no promise form; the page works without notifications */ }
  }

  function notify(job, petName) {
    if (!job || !("Notification" in win) || win.Notification.permission !== "granted") return;
    const notice = L.finishNotice(job, petName);
    try {
      const shown = new win.Notification(notice.title, { body: notice.body, tag: `beside-${job.id}` });
      shown.onclick = () => { win.focus(); shown.close(); };
    } catch { /* e.g. mobile Chrome allows notifications only from a service worker */ }
  }

  /** The progress box belongs to the pet whose model is being made; other pets show the empty form. */
  function renderProgressArea() {
    const active = state.active;
    const mine = Boolean(active && state.pet && active.petId === state.pet.id);
    const box = byId("create-progress");
    if (active) {
      box.hidden = !mine;
    } else if (box.dataset.petId && (!state.pet || box.dataset.petId !== state.pet.id)) {
      box.hidden = true; // a finished result of another pet
    }
    updateCreateButton();
  }

  function renderProgress(job, text) {
    const box = byId("create-progress");
    const status = job ? job.status : "PENDING";
    const badgeEl = byId("create-badge");
    badgeEl.dataset.status = L.statusKey(status);
    badgeEl.textContent = L.statusLabel(status);
    byId("create-wait").textContent = text || L.waitText(job);
    box.dataset.state = L.isFinished(status) || !state.active ? "done" : "running";
    byId("create-cancel").hidden = !(state.active && L.canCancel(job || { status: "PENDING" }));
  }

  async function poll() {
    const active = state.active;
    if (!active) return;
    let job;
    try {
      job = await api("GET", `/api/v1/jobs/${enc(active.jobId)}`);
    } catch (error) {
      if (error.code === "JOB_NOT_FOUND") {
        finish(active, null, "작업이 지워졌어요.");
        return;
      }
      renderProgress(null, `상태를 읽지 못했어요. 다시 확인하는 중… (${error.message})`);
      active.timer = setTimeout(poll, L.POLL_MS);
      return;
    }
    if (state.active !== active) return;
    if (L.isFinished(job.status)) {
      finish(active, job);
      return;
    }
    if (Date.now() - active.startedAt > L.MAX_WAIT_MS) {
      finish(active, job, `${Math.round(L.MAX_WAIT_MS / 60000)}분이 지나도 끝나지 않았어요. 생성 기록에서 상태를 확인해 주세요.`);
      return;
    }
    renderProgress(job);
    active.timer = setTimeout(poll, L.POLL_MS);
  }

  function finish(active, job, text, quiet) {
    clearTimeout(active.timer);
    if (state.active === active) state.active = null;
    renderProgress(job, text);
    byId("create-cancel").hidden = true;
    if (job && L.isFinished(job.status) && !quiet) notify(job, active.petName);
    if (state.pet && state.pet.id === active.petId) {
      loadPet(active.petId, true).then(() => {
        if (job && job.status === "COMPLETED" && state.pet) {
          const done = state.pet.jobs.find((item) => item.id === job.id);
          if (done) showInViewer(done);
        }
      }).catch((error) => setMessage(error.message));
    }
    updateCreateButton();
  }

  function wireCreate() {
    const input = byId("create-photos");
    const form = byId("create-form");
    const selection = byId("create-selection");
    const errorEl = byId("create-error");
    const defaultHint = selection.textContent;
    input.addEventListener("change", () => {
      const problem = input.files.length ? L.checkPhotos(input.files) : null;
      errorEl.textContent = problem || "";
      errorEl.hidden = !problem;
      selection.textContent = input.files.length && !problem
        ? `사진 ${input.files.length}장 선택 · 첫 사진: ${input.files[0].name}` : defaultHint;
      updateCreateButton();
    });
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      const pet = state.pet;
      const files = Array.from(input.files || []);
      if (!pet || state.active || L.checkPhotos(files)) return;
      askNotificationPermission();
      const button = byId("create-button");
      button.disabled = true;
      errorEl.hidden = true;
      const data = new FormData();
      files.forEach((file) => data.append("photos", file, file.name));
      data.append("petId", pet.id);
      button.textContent = "올리는 중…";
      api("POST", "/api/v1/jobs", data).then((created) => {
        state.active = { jobId: created.jobId, petId: pet.id, petName: pet.name, startedAt: Date.now(), timer: null };
        const box = byId("create-progress");
        box.dataset.petId = pet.id;
        box.hidden = false;
        renderProgress(null, "접수했어요. 순서를 확인하는 중…");
        input.value = "";
        selection.textContent = defaultHint;
        loadPet(pet.id, true).catch(() => {});
        poll();
      }).catch((error) => {
        errorEl.textContent = error.message;
        errorEl.hidden = false;
      }).finally(() => {
        button.textContent = "이 펫으로 만들기";
        updateCreateButton();
      });
    });
    byId("create-cancel").addEventListener("click", () => {
      const active = state.active;
      if (!active) return;
      busy(byId("create-cancel"), async () => {
        try {
          const job = await api("POST", `/api/v1/jobs/${enc(active.jobId)}/cancel`);
          finish(active, job, "취소했어요.", true);
        } catch (error) {
          if (error.code !== "JOB_NOT_PENDING") throw error;
          setMessage("이미 생성이 시작돼 취소할 수 없어요. 끝날 때까지 기다려 주세요.");
        }
      });
    });
  }

  // ---------------------------------------------------------------- routing

  function route() {
    clearTimeout(state.refreshTimer);
    const current = L.parseRoute(win.location.hash);
    const home = current.view === "home";
    byId("home-view").hidden = !home;
    byId("pet-view").hidden = home;
    if (home) {
      doc.title = "Beside 보관함";
      state.pet = null;
      showInViewer(null);
      loadHome().catch((error) => setMessage(error.message));
      return;
    }
    if (!state.pet || state.pet.id !== current.petId) {
      // Another pet: start from a clean view instead of showing the previous pet's data while loading.
      state.pet = null;
      showInViewer(null);
      byId("edit-pet-form").hidden = true;
      byId("edit-pet-toggle").setAttribute("aria-expanded", "false");
      byId("pet-name").textContent = "불러오는 중…";
      byId("history-list").replaceChildren();
    }
    loadPet(current.petId, false).catch((error) => setMessage(error.message));
    win.scrollTo(0, 0);
  }

  function start() {
    if (!L) {
      setMessage("페이지 스크립트를 불러오지 못했어요. 새로고침해 주세요.");
      return;
    }
    wireForms();
    wireViewer();
    wireCreate();
    // A new view starts without the old message, except the one left for it (state.flash: "deleted", "added", "gone").
    win.addEventListener("hashchange", () => {
      const flash = typeof state.flash === "string" ? { text: state.flash, kind: "ok" } : state.flash;
      setMessage(flash ? flash.text : "", flash ? flash.kind : "ok");
      state.flash = null;
      route();
    });
    win.addEventListener("pagehide", () => { if (state.active) clearTimeout(state.active.timer); });
    route();
  }

  start();
})(document, window);
