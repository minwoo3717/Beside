"use strict";
// Web viewer of a share link: /s/{token} → GET /api/v1/shares/{token} → <model-viewer src=asset.url>.
// Pure helpers first (node:test in src/test/js/share-viewer.test.cjs requires this file), then the page itself.

const BesideShare = (() => {
  const TOKEN_PATH = /^\/s\/([A-Za-z0-9_-]{1,128})\/?$/;
  const IDLE = "Idle";
  // Same clips, labels and order as Unity PetAnimationPlayer.Actions (rigged GLB clip names, GLB_SPEC).
  // once: played one time, then back to Idle (PetAnimationPlayer.oneShotClips).
  const ANIMATIONS = Object.freeze([
    Object.freeze({ name: "Walk", label: "걷기", once: false }),
    Object.freeze({ name: "Sit", label: "앉기", once: true }),
    Object.freeze({ name: "TailWag", label: "꼬리", once: false }),
    Object.freeze({ name: "HeadTilt", label: "갸웃", once: true }),
    Object.freeze({ name: IDLE, label: "기본", once: false })
  ]);

  /** The token of a /s/{token} path, or null. The server checks the exact format (404 SHARE_NOT_FOUND otherwise). */
  function tokenFromPath(pathname) {
    const match = TOKEN_PATH.exec(typeof pathname === "string" ? pathname : "");
    return match ? match[1] : null;
  }

  /** Korean chip label of a clip; clips the app does not know keep the name from the GLB. */
  function animationLabel(name) {
    const known = ANIMATIONS.find((clip) => clip.name === name);
    return known ? known.label : String(name);
  }

  /** One chip per clip in the GLB: known clips in the Unity order, then the others in file order. */
  function animationChips(available) {
    const names = Array.from(available || [], String).filter((name, index, all) => name && all.indexOf(name) === index);
    const known = ANIMATIONS.filter((clip) => names.includes(clip.name)).map((clip) => ({ ...clip }));
    const others = names.filter((name) => !ANIMATIONS.some((clip) => clip.name === name))
      .map((name) => ({ name, label: name, once: false }));
    return known.concat(others);
  }

  /** Idle when the GLB has it, else its first clip, else null (a model without animations). */
  function defaultAnimation(available) {
    const names = Array.from(available || [], String);
    return names.includes(IDLE) ? IDLE : (names[0] || null);
  }

  /** "2026년 11월 6일 14:05" in the viewer's time zone, or null for a missing or broken value. */
  function formatExpiry(iso) {
    const date = new Date(typeof iso === "string" ? iso : NaN);
    if (Number.isNaN(date.getTime())) return null;
    const pad = (value) => String(value).padStart(2, "0");
    return `${date.getFullYear()}년 ${date.getMonth() + 1}월 ${date.getDate()}일 ${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }

  /**
   * GET /api/v1/shares/{token} (docs/api/openapi.yaml getShare).
   * { kind: "ok", info } | { kind: "missing" } for no token or 404 SHARE_NOT_FOUND | { kind: "error" } for anything else.
   */
  async function loadShare(fetchImpl, token) {
    if (!token) return { kind: "missing" };
    let response;
    try {
      response = await fetchImpl(`/api/v1/shares/${encodeURIComponent(token)}`,
        { headers: { Accept: "application/json" }, cache: "no-store" });
    } catch (networkError) {
      return { kind: "error" };
    }
    if (response.status === 404) return { kind: "missing" };
    if (!response.ok) return { kind: "error" };
    let info;
    try {
      info = await response.json();
    } catch (badJson) {
      return { kind: "error" };
    }
    const url = info && info.asset && info.asset.url;
    if (typeof url !== "string" || !url.startsWith("/api/v1/shares/")) return { kind: "error" };
    return { kind: "ok", info };
  }

  // ------------------------------------------------------------------ product events (docs/METRICS.md §6)
  // The viewer tells the server that a shared model was opened (screen_view, screen=share) and which clip chips were
  // pressed (animation_play). Same origin, one small POST per event. A batch holds only a random install id, a random
  // session id and the event: never the token, the page address or anything typed. Failures are ignored.

  const EVENTS_URL = "/api/v1/events";
  const INSTALL_KEY = "beside.installId";
  const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

  /** A random v4 UUID: crypto.randomUUID where there is one (https, localhost), else getRandomValues (plain http). */
  function randomUuid(cryptoImpl) {
    if (cryptoImpl && typeof cryptoImpl.randomUUID === "function") return cryptoImpl.randomUUID();
    const bytes = new Uint8Array(16);
    if (cryptoImpl && typeof cryptoImpl.getRandomValues === "function") cryptoImpl.getRandomValues(bytes);
    else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  /** The install id kept in localStorage, made once. Storage may be missing or throw (private mode): a new id then. */
  function installId(storage, cryptoImpl) {
    try {
      const saved = storage ? storage.getItem(INSTALL_KEY) : null;
      if (typeof saved === "string" && UUID_PATTERN.test(saved)) return saved;
    } catch (unavailable) { /* blocked storage: fall through */ }
    const made = randomUuid(cryptoImpl);
    try {
      if (storage) storage.setItem(INSTALL_KEY, made);
    } catch (unavailable) { /* not kept: the next visit counts as a new install */ }
    return made;
  }

  /** animation_play props: the clip when it is one of the app's five, else none (the server keeps only those). */
  function animationProps(name) {
    return ANIMATIONS.some((clip) => clip.name === name) ? { clip: name } : undefined;
  }

  /**
   * Sends the viewer's events. options: fetchImpl, storage (localStorage or null), cryptoImpl, now (() => Date).
   * track(name, props) resolves true when the server answered 2xx; it never rejects.
   */
  function createTracker(options) {
    const opts = options || {};
    const now = typeof opts.now === "function" ? opts.now : () => new Date();
    const install = installId(opts.storage, opts.cryptoImpl);
    const session = randomUuid(opts.cryptoImpl);
    let seq = 0;

    function batch(name, props) {
      const event = { name, at: now().toISOString(), seq: seq++, screen: "share" };
      if (props) event.props = props;
      return { installId: install, sessionId: session, appVersion: "web", platform: "web", events: [event] };
    }

    async function track(name, props) {
      try {
        const response = await opts.fetchImpl(EVENTS_URL, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(batch(name, props)),
          keepalive: true,                // still sent when the viewer closes the tab right after
          credentials: "same-origin",
          referrerPolicy: "no-referrer"   // the page address holds the token
        });
        return Boolean(response && response.ok);
      } catch (offline) {
        return false;
      }
    }

    return Object.freeze({ track, installId: install, sessionId: session });
  }

  return Object.freeze({
    ANIMATIONS, tokenFromPath, animationLabel, animationChips, defaultAnimation, formatExpiry, loadShare,
    EVENTS_URL, randomUuid, installId, animationProps, createTracker
  });
})();

if (typeof module === "object" && module.exports) {
  module.exports = BesideShare;
} else if (typeof document !== "undefined" && document.getElementById("viewer-view")) {
  // share.html. Other pages (the job detail page /job/) load this file only for BesideShare's clip helpers.
  startShareViewer(document, window);
}

function startShareViewer(doc, win) {
  const VIEWER_READY_MS = 15000;
  const byId = (id) => doc.getElementById(id);
  const views = { loading: byId("loading-view"), viewer: byId("viewer-view"), missing: byId("missing-view"), error: byId("error-view") };
  const viewer = byId("viewer");
  const chipBar = byId("animation-chips");
  const expiry = byId("expiry");
  const arNote = byId("ar-note");
  const message = byId("viewer-message");
  const errorDetail = byId("error-detail");
  let idleClip = null;
  let current = null;
  // localStorage itself can throw (blocked site data): the tracker then makes a new install id per visit.
  let storage = null;
  try { storage = win.localStorage; } catch (blocked) { storage = null; }
  const events = BesideShare.createTracker({ fetchImpl: win.fetch.bind(win), storage, cryptoImpl: win.crypto });
  events.track("screen_view");

  // model-viewer comes from the CDN; give up with a message instead of an empty box when it never arrives.
  function viewerReady() {
    if (win.customElements.get("model-viewer")) return Promise.resolve(true);
    return Promise.race([
      win.customElements.whenDefined("model-viewer").then(() => true),
      new Promise((resolve) => win.setTimeout(resolve, VIEWER_READY_MS, false))
    ]);
  }

  function show(name) {
    Object.entries(views).forEach(([key, view]) => { view.hidden = key !== name; });
  }

  function say(text) {
    message.textContent = text || "";
    message.hidden = !text;
  }

  function markChips() {
    chipBar.querySelectorAll("button").forEach((chip) => chip.setAttribute("aria-pressed", String(chip.dataset.clip === current)));
  }

  async function play(name, once) {
    current = name;
    markChips();
    viewer.animationName = name;
    // model-viewer applies a new animation-name in its next update, with endless repetitions: options given to
    // play() before that update would be overwritten, so wait for it (checked with model-viewer 3.5.0).
    await viewer.updateComplete;
    if (current !== name) return; // another chip was pressed meanwhile
    viewer.play(once ? { repetitions: 1 } : undefined);
  }

  function renderChips(available) {
    const chips = BesideShare.animationChips(available).map((clip) => {
      const chip = doc.createElement("button");
      chip.type = "button";
      chip.className = "chip";
      chip.textContent = clip.label;
      chip.dataset.clip = clip.name;
      chip.setAttribute("aria-pressed", "false");
      chip.addEventListener("click", () => {
        events.track("animation_play", BesideShare.animationProps(clip.name));
        play(clip.name, clip.once);
      });
      return chip;
    });
    chipBar.replaceChildren(...chips);
    chipBar.hidden = chips.length === 0;
  }

  viewer.addEventListener("load", () => {
    say("");
    const available = viewer.availableAnimations || [];
    renderChips(available);
    idleClip = BesideShare.defaultAnimation(available);
    if (idleClip) play(idleClip, false);
    arNote.hidden = Boolean(viewer.canActivateAR);
  });
  // A one-shot clip (Sit, HeadTilt) ran its single repetition: back to Idle, like the app.
  viewer.addEventListener("finished", () => {
    if (idleClip && current !== idleClip) play(idleClip, false);
  });
  viewer.addEventListener("error", () => say("3D 모델 파일을 열지 못했어요. 잠시 후 다시 시도해 주세요."));
  viewer.addEventListener("ar-status", (event) => {
    if (event.detail && event.detail.status === "failed") {
      say("AR을 시작하지 못했어요. ARCore를 지원하는 안드로이드 폰의 Chrome에서 열어 주세요.");
    }
  });
  byId("retry-button").addEventListener("click", () => { load(); });

  async function load() {
    show("loading");
    const token = BesideShare.tokenFromPath(win.location.pathname);
    const result = await BesideShare.loadShare(win.fetch.bind(win), token);
    if (result.kind === "missing") {
      doc.title = "링크가 없거나 만료됐어요 · Beside";
      show("missing");
      return;
    }
    if (result.kind === "error") {
      errorDetail.textContent = "잠시 후 다시 시도해 주세요.";
      show("error");
      return;
    }
    if (!(await viewerReady())) {
      errorDetail.textContent = "3D 보기 도구를 불러오지 못했어요. 네트워크를 확인한 뒤 다시 시도해 주세요.";
      show("error");
      return;
    }
    const until = BesideShare.formatExpiry(result.info.expiresAt);
    expiry.textContent = until ? `${until}까지 볼 수 있어요` : "";
    expiry.hidden = !until;
    show("viewer");
    viewer.setAttribute("src", result.info.asset.url);
  }

  load();
}
