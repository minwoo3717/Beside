// Real app.js with a small DOM test double: this is not a rendering engine.
// BESIDE_MVP_URL optionally connects this JavaScript to the running backend.
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const source = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/app.js"), "utf8");
const html = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/index.html"), "utf8");
const id = "01234567-89ab-cdef-0123-456789abcdef";

class Element {
  constructor() {
    this.listeners = {}; this.children = []; this.attributes = {}; this.dataset = {};
    this.hidden = false; this.disabled = false; this.history = []; this.files = [];
    this.classList = { toggle() {} };
  }
  set textContent(value) { this.text = value; this.history.push(value); }
  get textContent() { return this.text || ""; }
  addEventListener(name, handler) { this.listeners[name] = handler; }
  async emit(name) { if (this.listeners[name]) await this.listeners[name]({ preventDefault() {} }); }
  setAttribute(name, value) { this.attributes[name] = value; }
  removeAttribute(name) { delete this.attributes[name]; if (name === "src") delete this.src; }
  querySelector() { return this.child || (this.child = new Element()); }
  replaceChildren() { this.children = []; }
  append(...children) { this.children.push(...children); }
  focus() {}
}

function setup(fetch, { realTime = false, requestTimeout = false } = {}) {
  const elements = new Map();
  const element = (selector) => {
    if (!elements.has(selector)) elements.set(selector, new Element());
    return elements.get(selector);
  };
  const steps = ["WAITING", "PROCESSING", "COMPLETED"].map((status) => {
    const step = new Element(); step.dataset.step = status; return step;
  });
  let now = 0;
  vm.runInNewContext(source, {
    document: {
      querySelector(selector) {
        if (selector.startsWith("#")) assert.ok(html.includes(`id="${selector.slice(1)}"`), selector);
        return element(selector);
      },
      querySelectorAll: () => steps,
      createElement: () => new Element()
    },
    window: { location: { origin: "http://localhost:8080" }, addEventListener() {} },
    URL, FormData, AbortController, TypeError, fetch,
    console: { error() {} },
    performance: realTime ? performance : { now: () => now },
    setTimeout: realTime ? setTimeout : (callback, ms) => {
      const timer = { cancelled: false };
      if (ms <= 1000 || requestTimeout) queueMicrotask(() => {
        if (!timer.cancelled) { now += ms; callback(); }
      });
      return timer;
    },
    clearTimeout: realTime ? clearTimeout : (timer) => { timer.cancelled = true; }
  }, { filename: "app.js" });
  return {
    element,
    async select(files = [new File(["photo"], "dog.jpg", { type: "image/jpeg" })]) {
      element("#photos").files = files;
      await element("#photos").emit("change");
    },
    submit: () => element("#upload-form").emit("submit"),
    clear: () => element("#clear-photos").emit("click")
  };
}
// API v1 (docs/api/openapi.yaml): 202 + Location + { jobId }, JobResponse, and the { error: { code, message } } envelope.
function accepted(location = `/api/v1/jobs/${id}`) {
  return Response.json({ jobId: id }, { status: 202, headers: { Location: location } });
}
function job(status, jobId = id, extra = {}) { return Response.json({ id: jobId, status, ...extra }); }
function apiError(status, code) {
  return Response.json({ error: { code, message: "developer text", jobId: null } }, { status });
}

test("multiple previews, multipart contract, polling and representative result", async () => {
  const states = ["PENDING", "PROCESSING", "COMPLETED"];
  let uploads = 0;
  const app = setup(async (url, options) => {
    if (options.method === "POST") {
      uploads++;
      assert.equal(url, "/api/v1/jobs");
      const photos = options.body.getAll("photos");
      assert.equal(photos.length, 2);
      assert.notEqual(photos[0].name, photos[1].name);
      assert.equal(options.headers, undefined);
      return accepted();
    }
    assert.equal(url, `/api/v1/jobs/${id}`);
    return job(states.shift());
  });
  try {
    await app.select([new File(["one"], "dog.jpg", { type: "image/jpeg" }), new File(["two"], "dog.jpg", { type: "image/jpeg" })]);
    assert.equal(app.element("#photo-previews").children.length, 2);
    const representative = app.element("#photo-previews").children[0].children[0].src;
    await app.submit();
    assert.equal(uploads, 1);
    assert.ok(app.element("#status-badge").history.includes("WAITING"));
    assert.ok(app.element("#status-badge").history.includes("PROCESSING"));
    assert.equal(app.element("#status-badge").textContent, "COMPLETED");
    assert.equal(app.element("#result-photo").src, representative);
    assert.equal(app.element("#result").hidden, false);
    assert.equal(app.element("#job-progress").hidden, true);
    assert.equal(app.element("#generate-button").disabled, false);
  } finally { await app.clear(); }
});

for (const [label, reply, afterUpload] of [
  ["HTTP 500", () => new Response("server error", { status: 500 })],
  ["oversized upload", () => new Response("too large", { status: 413 })],
  ["missing Location", () => new Response(null, { status: 202 })],
  ["foreign Location", () => accepted(`http://elsewhere.invalid/api/v1/jobs/${id}`)],
  ["invalid JSON", () => new Response("not json"), true],
  ["unknown status", () => job("UNKNOWN"), true],
  ["wrong job ID", () => job("COMPLETED", "wrong-id"), true],
  ["failed worker", () => job("FAILED"), true],
  ["network failure", () => { throw new TypeError("Failed to fetch"); }]
]) {
  test(`${label} shows FAILED and permits retry`, async () => {
    const app = setup(async (_url, options) => options.method === "POST" && afterUpload ? accepted() : reply());
    try {
      await app.select(); await app.submit();
      assert.equal(app.element("#status-badge").textContent, "FAILED");
      assert.equal(app.element("#error-message").hidden, false);
      assert.equal(app.element("#result").hidden, true);
      assert.equal(app.element("#generate-button").disabled, false);
    } finally { await app.clear(); }
  });
}

test("v0 Location is not followed", async () => {
  let polls = 0;
  const app = setup(async (_url, options) => {
    if (options.method === "POST") return accepted(`/api/jobs/${id}`);
    polls++; return job("COMPLETED");
  });
  try {
    await app.select(); await app.submit();
    assert.equal(polls, 0);
    assert.equal(app.element("#status-badge").textContent, "FAILED");
  } finally { await app.clear(); }
});

test("v1 error envelope shows the ERROR_CODES copy, not the developer message", async () => {
  const app = setup(async () => apiError(400, "UNSUPPORTED_IMAGE_TYPE"));
  try {
    await app.select(); await app.submit();
    assert.equal(app.element("#status-badge").textContent, "FAILED");
    assert.equal(app.element("#error-message").textContent, "JPG, PNG, WEBP 사진만 사용할 수 있어요.");
  } finally { await app.clear(); }
});

test("failed job shows the copy for its error.code", async () => {
  const app = setup(async (_url, options) => options.method === "POST" ? accepted()
    : job("FAILED", id, { error: { code: "INFERENCE_FAILED", message: "developer text" } }));
  try {
    await app.select(); await app.submit();
    assert.equal(app.element("#status-badge").textContent, "FAILED");
    assert.match(app.element("#error-message").textContent, /3D 모델을 만들지 못했어요/);
    assert.doesNotMatch(app.element("#error-message").textContent, /developer text/);
  } finally { await app.clear(); }
});

test("polling stops at 60 seconds", async () => {
  let queries = 0;
  const app = setup(async (_url, options) => {
    if (options.method === "POST") return accepted();
    queries++; return job("PROCESSING");
  });
  try {
    await app.select(); await app.submit();
    assert.equal(queries, 60);
    assert.match(app.element("#error-message").textContent, /60초/);
    assert.equal(app.element("#status-badge").textContent, "FAILED");
  } finally { await app.clear(); }
});

test("stalled request aborts and restores controls", async () => {
  const app = setup((_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener("abort", () => reject(new DOMException("timeout", "AbortError")));
  }), { requestTimeout: true });
  try {
    await app.select(); await app.submit();
    assert.match(app.element("#error-message").textContent, /대기 시간/);
    assert.equal(app.element("#photos").disabled, false);
  } finally { await app.clear(); }
});

test("empty, non-image, GIF and oversized selections do not upload", async () => {
  let calls = 0;
  const app = setup(async () => { calls++; return accepted(); });
  for (const files of [[], [new File(["text"], "note.txt", { type: "text/plain" })],
    [new File(["gif"], "dog.gif", { type: "image/gif" })],
    [new File([new Uint8Array(5 * 1024 * 1024 + 1)], "large.jpg", { type: "image/jpeg" })]]) {
    await app.select(files); await app.submit();
    assert.equal(app.element("#generate-button").disabled, true);
  }
  assert.equal(calls, 0);
});

test("double submission creates only one job", async () => {
  let finishUpload, uploads = 0;
  const app = setup((_url, options) => {
    if (options.method === "POST") { uploads++; return new Promise((resolve) => { finishUpload = resolve; }); }
    return Promise.resolve(job("COMPLETED"));
  });
  try {
    await app.select();
    const first = app.submit();
    assert.equal(app.element("#photos").disabled, true);
    await app.submit();
    finishUpload(accepted()); await first;
    assert.equal(uploads, 1);
  } finally { await app.clear(); }
});

test("live app.js + Spring Boot workflow (without browser rendering)",
  { skip: !process.env.BESIDE_MVP_URL, timeout: 20000 }, async () => {
    const base = process.env.BESIDE_MVP_URL;
    assert.match(base, /^http:\/\/(localhost|127\.0\.0\.1):\d+$/);
    assert.equal(await (await fetch(`${base}/app.js`)).text(), source);
    let postMs, jobLocation;
    const app = setup(async (url, options) => {
      const start = performance.now();
      const response = await fetch(new URL(url, base), options);
      if (options.method === "POST") { postMs = performance.now() - start; jobLocation = response.headers.get("Location"); }
      return response;
    }, { realTime: true });
    try {
      const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=", "base64");
      await app.select([new File([png], "mvp-probe.png", { type: "image/png" }), new File([png], "mvp-probe.png", { type: "image/png" })]);
      await app.submit();
      assert.equal(app.element("#status-badge").textContent, "COMPLETED", app.element("#error-message").textContent);
      assert.ok(app.element("#status-badge").history.includes("PROCESSING"));
      assert.equal(app.element("#result").hidden, false);
      assert.ok(postMs < 2000, `POST took ${postMs} ms`);
      console.log(JSON.stringify({ jobLocation, postMs: Math.round(postMs), states: app.element("#status-badge").history, mockResultVisible: !app.element("#result").hidden }));
    } finally { await app.clear(); }
  });
