// Interaction for the web stand-in of DesignedUIController. Texts and states mirror the Unity code;
// nothing here talks to a real server, camera or 3D model.
(() => {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const EXAMPLES = ["/res/Examples/example_1.jpg", "/res/Examples/example_2.jpg", "/res/Examples/example_3.jpg"];
  const STEPS = ["사진 올리기", "3D 모델 만들기", "모델 받기", "AR 준비"];
  const DEFAULT_URL = "http://192.168.0.10:8080";
  const COLORS = { success: "var(--success)", warning: "var(--warning)", danger: "var(--danger)", muted: "var(--ink-muted)", soft: "var(--ink-soft)" };

  // icons: the app's own white PNGs used as masks
  $$(".ic[data-i]").forEach(el => el.style.setProperty("--m", `url("/res/Icons/${el.dataset.i}.png")`));

  const state = { photos: EXAMPLES.slice(0, 2), step: 0, timer: null, started: 0, savedJob: true, fur: true, url: "" };

  // ---------- scaling the 390×844 phone into the window ----------
  const wrap = $("#wrap"), phone = $("#phone");
  function fit() {
    const small = innerWidth <= 520;
    const top = $(".top").offsetHeight;
    const availH = innerHeight - top - (small ? 0 : 24);
    const availW = innerWidth - (small ? 0 : 32);
    const scale = small ? Math.min(availW / 390, 1.15) : Math.min(availH / 844, availW / 390, 1);
    // on a phone the frame fills the window below the shortcut bar instead of keeping 844 px
    const h = small ? Math.max(availH / scale, 640) : 844;
    phone.style.transform = `scale(${scale})`;
    phone.style.transformOrigin = "top left";
    phone.style.height = h + "px";
    wrap.style.width = 390 * scale + "px";
    wrap.style.height = h * scale + "px";
  }
  addEventListener("resize", fit);

  // ---------- navigation ----------
  function show(id, arMode) {
    stopProgress();
    $$(".screen").forEach(s => s.classList.toggle("on", s.id === id));
    $("#statusbar").classList.toggle("light", id === "ar");
    const key = id === "ar" ? "ar-" + arMode : id;
    $$(".jump button").forEach(b => b.setAttribute("aria-current", String(b.dataset.go === key)));
    const scroller = $(`#${id} .scroll`);
    if (scroller) scroller.scrollTop = 0;
    if (id === "home") refreshHome();
    if (id === "confirm") renderConfirm();
    if (id === "progress") startProgress();
    if (id === "ar") enterAR(arMode || "scan");
    if (id === "settings") enterSettings();
    if (location.hash.slice(1) !== key) history.replaceState(null, "", "#" + key);
  }

  function go(key) {
    if (key.startsWith("ar-")) show("ar", key.slice(3));
    else show(key);
  }

  // ---------- home ----------
  function refreshHome() {
    $("#resume").hidden = !state.savedJob;
    const img = $("#resumeImg");
    img.src = state.photos[0]; img.hidden = false;
    $("#serverDot").style.background = COLORS.muted;
    $("#serverText").textContent = "확인 중";
    clearTimeout(refreshHome.t);
    refreshHome.t = setTimeout(() => {
      $("#serverDot").style.background = COLORS.success;
      $("#serverText").textContent = "연결됨";
    }, 900);
  }

  function pickPhotos() {
    // stand-in for the gallery picker: rotate through the bundled example photos
    pickPhotos.n = ((pickPhotos.n || 0) + 1) % 3;
    const sets = [[0, 1], [1, 0, 2], [0]];
    state.photos = sets[pickPhotos.n].map(i => EXAMPLES[i]);
    show("confirm");
  }

  // ---------- confirm ----------
  function renderConfirm() {
    $("#confirmCount").textContent = `선택한 사진 ${state.photos.length}장 · 최대 10장`;
    $("#thumbs").innerHTML = state.photos.map((src, i) =>
      `<div class="cell"><img src="${src}" alt="선택한 사진 ${i + 1}">${i === 0 ? '<span class="tag">대표</span>' : ""}</div>`).join("");
  }

  // ---------- progress ----------
  // quarter arc, round ends, 0.9 s a turn (DesignKit.Spinner); the delay keeps the phase when a new step takes over
  const spinner = () => `<svg class="spin" style="animation-delay:-${(performance.now() / 1000 % 0.9).toFixed(3)}s" viewBox="0 0 22 22" aria-hidden="true">` +
    '<circle class="track" cx="11" cy="11" r="9.3"/><path class="spin-arc" d="M11 1.7a9.3 9.3 0 0 1 9.3 9.3"/></svg>';

  // rebuilt only when a step changes, so the spinner keeps turning instead of restarting
  function renderSteps() {
    const key = state.step + ":" + !!state.queued;
    if (renderSteps.key !== key) {
      renderSteps.key = key;
      $("#steps").innerHTML = STEPS.map((name, i) => {
        const cls = i < state.step ? "done" : i === state.step ? "now" : "pending";
        const right = i < state.step ? "완료" : i === state.step ? (i === 1 && state.queued ? "순서 기다리는 중" : "진행 중") : "";
        const mark = cls === "done" ? '<i class="ic" style="--s:12px;--c:var(--accent);--m:url(&quot;/res/Icons/check.png&quot;)"></i>' : cls === "now" ? spinner() : "";
        return `<div class="step ${cls}"><span class="smark">${mark}</span><span class="lbl">${name}</span><span class="right">${right}</span></div>`;
      }).join("");
    }
    const sec = Math.floor((Date.now() - state.started) / 1000);
    const t = sec < 60 ? `${sec}초` : `${Math.floor(sec / 60)}분 ${String(sec % 60).padStart(2, "0")}초`;
    $("#progressPill").innerHTML = `<span style="color:var(--accent)">${Math.min(state.step + 1, 4)} / 4</span><span class="sep">|</span>${t}`;
  }

  function startProgress() {
    const img = $("#progressImg");
    img.src = state.photos[0]; img.hidden = false;
    state.step = 0; state.queued = false; state.started = Date.now();
    renderSteps.key = null;
    renderSteps();
    // a real job takes 2–3 minutes; the preview runs the four steps in about 12 seconds
    const plan = [[1500, () => { state.step = 1; state.queued = true; }], [3000, () => { state.queued = false; }],
      [8500, () => { state.step = 2; }], [10500, () => { state.step = 3; }], [12500, () => { state.savedJob = true; show("preview"); }]];
    const t0 = Date.now();
    state.timer = setInterval(() => {
      const el = Date.now() - t0;
      while (plan.length && plan[0][0] <= el) plan.shift()[1]();
      if ($("#progress").classList.contains("on")) renderSteps();
    }, 250);
  }
  function stopProgress() { clearInterval(state.timer); state.timer = null; }

  // ---------- preview: drag to turn the stand-in model ----------
  const view = $("#view"), cube = $("#cube");
  let ry = 35, dragX = null;
  view.addEventListener("pointerdown", e => { dragX = e.clientX; view.setPointerCapture(e.pointerId); view.style.cursor = "grabbing"; });
  view.addEventListener("pointermove", e => {
    if (dragX === null) return;
    ry += (e.clientX - dragX) * 0.6; dragX = e.clientX;
    cube.style.setProperty("--ry", ry + "deg");
  });
  const endDrag = () => { dragX = null; view.style.cursor = ""; };
  view.addEventListener("pointerup", endDrag);
  view.addEventListener("pointercancel", endDrag);

  // ---------- AR ----------
  const ar = $("#ar"), camera = $("#camera"), pet = $("#pet");
  let arTimer = null, toastTimer = null, hintDismissed = false;

  function setStatus(text, color) { $("#arStatus").textContent = text; $("#arDot").style.background = color; }
  function setHint(text) {
    $("#arHintText").textContent = text;
    $("#arHint").style.display = hintDismissed ? "none" : "flex";
  }
  function toast(text, sec) {
    const el = $("#toast");
    el.textContent = text; el.style.display = "block";
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.style.display = "none"; }, sec * 1000);
  }
  function setPlaced(value) {
    if (value !== ar.classList.contains("is-placed")) hintDismissed = false;
    ar.classList.toggle("is-placed", value);
    camera.classList.toggle("placed", value);
    setClip("idle");
    if (value) {
      setStatus("배치됨", COLORS.success);
      setHint("한 손가락으로 옮기고, 두 손가락으로 돌릴 수 있어요");
    }
  }
  function setFound(found) {
    camera.classList.toggle("found", found);
    setStatus(found ? "바닥 인식됨" : "바닥 찾는 중", found ? COLORS.success : COLORS.warning);
    setHint(found ? "흰 점이 보이는 바닥을 터치하면 그 자리에 놓여요" : "폰을 좌우로 천천히 움직여 바닥을 비춰 주세요");
  }
  function enterAR(mode) {
    clearTimeout(arTimer);
    hintDismissed = false;
    ar.classList.remove("sheet-hidden");
    $("#toast").style.display = "none";
    const img = $("#arImg"); img.src = state.photos[0]; img.hidden = false;
    setPlaced(false);
    pet.style.left = "50%"; pet.style.top = "47%";
    if (mode === "placed") { setFound(true); setPlaced(true); }
    else if (mode === "found") setFound(true);
    else {
      setFound(false);
      // like a real session: the floor shows up after the phone has moved a little
      arTimer = setTimeout(() => { if (!ar.classList.contains("is-placed")) { setFound(true); markJump("found"); } }, 2500);
    }
  }
  function markJump(mode) {
    $$(".jump button").forEach(b => b.setAttribute("aria-current", String(b.dataset.go === "ar-" + mode)));
    history.replaceState(null, "", "#ar-" + mode);
  }
  function setClip(clip) {
    pet.classList.remove("walk", "sit", "wag", "tilt");
    if (clip !== "idle") pet.classList.add(clip);
    $$("#acts button").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.clip === clip)));
  }

  camera.addEventListener("click", e => {
    if (!camera.classList.contains("found")) { toast("폰을 좌우로 천천히 움직여 바닥을 비춰 주세요", 3); return; }
    const r = camera.getBoundingClientRect();
    const y = (e.clientY - r.top) / r.height;
    if (y < 0.42) { toast("화면 가운데에 바닥이 없어요. 폰을 조금 움직여 보세요.", 3); return; }
    pet.style.left = ((e.clientX - r.left) / r.width * 100) + "%";
    pet.style.top = (y * 100 - 8) + "%";
    if (!ar.classList.contains("is-placed")) { setPlaced(true); markJump("placed"); }
  });
  $("#acts").addEventListener("click", e => { const b = e.target.closest("button[data-clip]"); if (b) setClip(b.dataset.clip); });

  // ---------- settings ----------
  function setSettingsStatus(text, color) { const el = $("#settingsStatus"); el.textContent = text; el.style.color = color; }
  function normalize(url) {
    url = (url || "").trim();
    if (!url) return "";
    if (!/^https?:\/\//.test(url)) url = "https://" + url;
    return url.replace(/\/+$/, "");
  }
  function enterSettings() {
    $("#url").value = state.url || DEFAULT_URL;
    setSettingsStatus("지금 주소로 연결되는지 보려면 \"연결 확인\"을 눌러 주세요.", COLORS.soft);
  }

  // ---------- one click handler for every data-act ----------
  const actions = {
    home: () => show("home"),
    pick: pickPhotos,
    shoot: pickPhotos,
    resume: () => show("preview"),
    settings: () => show("settings"),
    start: () => show("progress"),
    cancel: () => { state.savedJob = false; show("home"); },
    fur: () => {
      state.fur = !state.fur;
      $("#furBtn").textContent = state.fur ? "털 표현 켜짐" : "털 표현 꺼짐";
      view.classList.toggle("fur-on", state.fur);
    },
    ar: () => show("ar", "scan"),
    preview: () => show("preview"),
    new: () => { state.savedJob = false; show("home"); },
    retry: () => show("progress"),
    dismiss: () => { hintDismissed = true; $("#arHint").style.display = "none"; },
    toggle: () => ar.classList.toggle("sheet-hidden"),
    center: () => {
      if (!camera.classList.contains("found")) { toast("화면 가운데에 바닥이 없어요. 폰을 조금 움직여 보세요.", 3); return; }
      pet.style.left = "50%"; pet.style.top = "47%";
      setPlaced(true); markJump("placed");
    },
    replace: () => { setPlaced(false); setFound(true); markJump("found"); },
    snap: () => {
      const flash = document.createElement("div");
      flash.style.cssText = "position:absolute;inset:0;background:#fff;opacity:.85;transition:opacity .35s;pointer-events:none;z-index:40";
      ar.appendChild(flash);
      requestAnimationFrame(() => requestAnimationFrame(() => { flash.style.opacity = "0"; }));
      setTimeout(() => flash.remove(), 400);
      toast("갤러리에 저장했어요", 2.5);
    },
    paste: async () => {
      let clip = "";
      try { clip = await navigator.clipboard.readText(); } catch { /* the preview frame may not allow clipboard reads */ }
      if (!clip.trim()) { setSettingsStatus("클립보드가 비어 있어요. 주소를 먼저 복사해 주세요.", COLORS.danger); return; }
      $("#url").value = clip.trim();
      setSettingsStatus("붙여넣었어요. \"연결 확인\"을 눌러 보세요.", COLORS.soft);
    },
    clear: () => { $("#url").value = ""; $("#url").focus(); setSettingsStatus("", COLORS.soft); },
    test: () => {
      const url = normalize($("#url").value);
      if (!url) { setSettingsStatus("주소를 입력해 주세요.", COLORS.danger); return; }
      setSettingsStatus("확인하는 중…", COLORS.soft);
      setTimeout(() => setSettingsStatus("연결됐어요 · local/gpu (미리보기 예시)", COLORS.success), 900);
    },
    save: () => {
      const url = normalize($("#url").value);
      // like JobManager.SetBaseUrl: only another server drops the job; empty means the default address
      if ((url || DEFAULT_URL) !== (state.url || DEFAULT_URL)) state.savedJob = false;
      state.url = url || "";
      $("#url").value = url || DEFAULT_URL;
      setSettingsStatus("저장했어요 · " + (url || DEFAULT_URL), COLORS.success);
    },
    default: () => { $("#url").value = DEFAULT_URL; setSettingsStatus("기본 주소를 넣었어요. 저장을 누르면 적용돼요.", COLORS.soft); },
  };

  document.addEventListener("click", e => {
    const jump = e.target.closest("[data-go]");
    // the shortcut bar shows each screen in its sample state, so 홈 always has the resume card
    if (jump) { if (jump.dataset.go === "home") state.savedJob = true; go(jump.dataset.go); return; }
    const btn = e.target.closest("[data-act]");
    if (btn && actions[btn.dataset.act]) { e.stopPropagation(); actions[btn.dataset.act](); }
  }, true);

  // ---------- reload when a preview file changes (serve.py /live-version) ----------
  let version = null;
  setInterval(async () => {
    try {
      const res = await fetch("/live-version", { cache: "no-store" });
      const v = (await res.text()).trim();
      if (!res.ok || !/^\d+$/.test(v)) return;   // a proxy error page is not a new version
      if (version === null) version = v;
      else if (v !== version) location.reload();
    } catch { /* server restarting */ }
  }, 1000);

  // ---------- start ----------
  fit();
  const start = location.hash.slice(1);
  const known = ["home", "confirm", "progress", "preview", "ar-scan", "ar-found", "ar-placed", "error", "settings"];
  go(known.includes(start) ? start : "home");
  document.fonts && document.fonts.ready.then(fit);
})();
