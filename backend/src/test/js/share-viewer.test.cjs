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
