// Pure helpers of the share link web viewer (static/share.js). The page part needs a browser and model-viewer;
// these cover the token in the address, the animation chips (labels as in Unity PetAnimationPlayer) and the API answers.
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const share = require(path.resolve(__dirname, "../../main/resources/static/share.js"));

const token = "q3Zr8yT0bWm1xK4vL9pN2A";

test("token comes from /s/{token}, anything else has none", () => {
  assert.equal(share.tokenFromPath(`/s/${token}`), token);
  assert.equal(share.tokenFromPath(`/s/${token}/`), token);
  for (const pathname of ["/", "/share.html", "/s/", "/s", `/s/${token}/asset`, `/x/${token}`, "/s/a.b", "/s/%2e%2e", undefined, null]) {
    assert.equal(share.tokenFromPath(pathname), null, String(pathname));
  }
});

test("clip labels match the app: 기본/걷기/앉기/꼬리/갸웃, other clips keep their own name", () => {
  assert.equal(share.animationLabel("Idle"), "기본");
  assert.equal(share.animationLabel("Walk"), "걷기");
  assert.equal(share.animationLabel("Sit"), "앉기");
  assert.equal(share.animationLabel("TailWag"), "꼬리");
  assert.equal(share.animationLabel("HeadTilt"), "갸웃");
  assert.equal(share.animationLabel("Jump"), "Jump");
  assert.equal(share.animationLabel("idle"), "idle", "names are case-sensitive like the GLB clip names");
});

test("chips follow the Unity order for known clips, then the GLB order, without duplicates", () => {
  const chips = share.animationChips(["Jump", "Idle", "HeadTilt", "Walk", "Sit", "TailWag", "Roll", "Jump"]);
  assert.deepEqual(chips.map((chip) => chip.name), ["Walk", "Sit", "TailWag", "HeadTilt", "Idle", "Jump", "Roll"]);
  assert.deepEqual(chips.map((chip) => chip.label), ["걷기", "앉기", "꼬리", "갸웃", "기본", "Jump", "Roll"]);
  assert.deepEqual(chips.filter((chip) => chip.once).map((chip) => chip.name), ["Sit", "HeadTilt"]);
  assert.deepEqual(share.animationChips([]), []);
  assert.deepEqual(share.animationChips(undefined), []);
  chips[0].label = "changed";
  assert.equal(share.animationLabel("Walk"), "걷기", "chips are copies");
});

test("the default clip is Idle when present, else the first one", () => {
  assert.equal(share.defaultAnimation(["Walk", "Idle"]), "Idle");
  assert.equal(share.defaultAnimation(["Run", "Walk"]), "Run");
  assert.equal(share.defaultAnimation([]), null);
});

test("expiry is shown in the viewer's local time", () => {
  const local = new Date(2026, 10, 6, 9, 5);
  assert.equal(share.formatExpiry(local.toISOString()), "2026년 11월 6일 09:05");
  assert.equal(share.formatExpiry("not a date"), null);
  assert.equal(share.formatExpiry(undefined), null);
});

test("share info: 200 is ok, 404 SHARE_NOT_FOUND or no token is missing, the rest is an error", async () => {
  const info = {
    createdAt: "2026-10-30T12:00:00Z", expiresAt: "2026-11-06T12:00:00Z",
    asset: { url: `/api/v1/shares/${token}/asset`, bytes: 10, contentType: "model/gltf-binary" }
  };
  const calls = [];
  const ok = await share.loadShare(async (url, options) => { calls.push({ url, options }); return Response.json(info); }, token);
  assert.deepEqual(ok, { kind: "ok", info });
  assert.equal(calls[0].url, `/api/v1/shares/${token}`);
  assert.equal(calls[0].options.cache, "no-store", "a revoked link must not come back from the HTTP cache");

  const notFound = Response.json({ error: { code: "SHARE_NOT_FOUND", message: "Share link not found or expired", jobId: null } }, { status: 404 });
  assert.deepEqual(await share.loadShare(async () => notFound, token), { kind: "missing" });
  assert.deepEqual(await share.loadShare(async () => { throw new Error("must not be called"); }, null), { kind: "missing" });

  assert.deepEqual(await share.loadShare(async () => Response.json({ error: { code: "INTERNAL_ERROR" } }, { status: 500 }), token), { kind: "error" });
  assert.deepEqual(await share.loadShare(async () => { throw new TypeError("offline"); }, token), { kind: "error" });
  assert.deepEqual(await share.loadShare(async () => new Response("<html>", { status: 200 }), token), { kind: "error" });
  assert.deepEqual(await share.loadShare(async () => Response.json({ ...info, asset: { url: "https://elsewhere.example/x.glb" } }), token),
    { kind: "error" }, "only the share asset route of this server is loaded");
});

// ---------------------------------------------------------------- product events (docs/METRICS.md §6, POST /api/v1/events)

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function memoryStorage() {
  const values = new Map();
  return { getItem: (key) => (values.has(key) ? values.get(key) : null), setItem: (key, value) => values.set(key, String(value)), values };
}

function recordingFetch(status = 202) {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options, body: JSON.parse(options.body) });
    return new Response(JSON.stringify({ accepted: 1, dropped: 0 }), { status });
  };
  return { calls, fetchImpl };
}

test("viewer events go to the collector as web, with ids only and never the share token or the address", async () => {
  const storage = memoryStorage();
  const { calls, fetchImpl } = recordingFetch();
  const clock = new Date("2026-11-26T01:02:03.004Z");
  const tracker = share.createTracker({ fetchImpl, storage, cryptoImpl: globalThis.crypto, now: () => clock });

  assert.equal(await tracker.track("screen_view"), true);
  assert.equal(await tracker.track("animation_play", share.animationProps("Walk")), true);
  await tracker.track("animation_play", share.animationProps("Jump"));   // a clip the app does not have: no props

  assert.equal(calls.length, 3);
  for (const call of calls) {
    assert.equal(call.url, "/api/v1/events", "same origin, the public collector");
    assert.equal(call.options.method, "POST");
    assert.equal(call.options.keepalive, true);
    assert.equal(call.options.referrerPolicy, "no-referrer");
    assert.equal(call.options.headers["Content-Type"], "application/json");
    const text = call.options.body;
    for (const secret of [token, "/s/", "http", "localhost", "share.html"]) {
      assert.ok(!text.includes(secret), `the batch must not contain ${secret}: ${text}`);
    }
    assert.deepEqual(Object.keys(call.body), ["installId", "sessionId", "appVersion", "platform", "events"]);
    assert.equal(call.body.platform, "web");
    assert.equal(call.body.appVersion, "web");
    assert.match(call.body.installId, UUID);
    assert.match(call.body.sessionId, UUID);
    assert.equal(call.body.events.length, 1);
    for (const key of Object.keys(call.body.events[0])) {
      assert.ok(["name", "at", "seq", "screen", "props"].includes(key), `unexpected event field ${key}`);
    }
  }
  assert.deepEqual(calls.map((call) => call.body.events[0]), [
    { name: "screen_view", at: "2026-11-26T01:02:03.004Z", seq: 0, screen: "share" },
    { name: "animation_play", at: "2026-11-26T01:02:03.004Z", seq: 1, screen: "share", props: { clip: "Walk" } },
    { name: "animation_play", at: "2026-11-26T01:02:03.004Z", seq: 2, screen: "share" }
  ]);

  // The install id is kept for the next visit; every visit is a new session.
  assert.equal(storage.values.get("beside.installId"), tracker.installId);
  const again = share.createTracker({ fetchImpl, storage, cryptoImpl: globalThis.crypto });
  assert.equal(again.installId, tracker.installId);
  assert.notEqual(again.sessionId, tracker.sessionId);
});

test("blocked storage, no crypto.randomUUID and a failing network never break the viewer", async () => {
  const throwing = { getItem() { throw new Error("SecurityError"); }, setItem() { throw new Error("SecurityError"); } };
  const noRandomUuid = { getRandomValues: (bytes) => globalThis.crypto.getRandomValues(bytes) };   // plain http
  const offline = share.createTracker({
    fetchImpl: async () => { throw new TypeError("Failed to fetch"); }, storage: throwing, cryptoImpl: noRandomUuid
  });
  assert.match(offline.installId, UUID);
  assert.match(offline.sessionId, UUID);
  assert.equal(await offline.track("screen_view"), false, "resolves false instead of rejecting");

  const refused = share.createTracker({ fetchImpl: recordingFetch(400).fetchImpl, storage: null });
  assert.equal(await refused.track("screen_view"), false);
  assert.match(share.randomUuid(undefined), UUID, "Math.random fallback still has the v4 shape");

  const broken = memoryStorage();
  broken.setItem("beside.installId", "not-a-uuid");
  assert.match(share.installId(broken, globalThis.crypto), UUID, "a broken saved id is replaced");
});
