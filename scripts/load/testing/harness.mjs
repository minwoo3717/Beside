// Shared setup for the Node tests of the k6 scripts (k6 itself is not installed on this PC):
//   node --test "scripts/load/*.test.mjs"
// An in-thread resolve hook replaces the k6 modules by the stand-ins in testing/k6/, so the pure libs, every entry
// script's init code (options, stages, thresholds) and the user flow run under Node against a fake server.
// Each load() gets a fresh module graph (?gen=N), so its lib/config.js reads the __ENV set for that test.
import { mkdtempSync, writeFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const HERE = fileURLToPath(new URL('..', import.meta.url));
const LOAD_URL = pathToFileURL(HERE).href;
const STUBS = {
  k6: new URL('./k6/k6.mjs', import.meta.url).href,
  'k6/http': new URL('./k6/http.mjs', import.meta.url).href,
  'k6/metrics': new URL('./k6/metrics.mjs', import.meta.url).href,
  'k6/execution': new URL('./k6/execution.mjs', import.meta.url).href,
  'k6/data': new URL('./k6/execution.mjs', import.meta.url).href,
};
let GEN = 0;
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (STUBS[specifier]) return { url: STUBS[specifier], shortCircuit: true };
    const r = nextResolve(specifier, context);
    if (!r.url.startsWith(LOAD_URL) || r.url.indexOf('/testing/') >= 0) return r;
    if (r.url.endsWith('.js')) return Object.assign({}, r, { url: `${r.url}?gen=${GEN}`, format: 'module' });
    if (/\.js\?gen=\d+$/.test(r.url)) return Object.assign({}, r, { url: r.url.replace(/\?gen=\d+$/, `?gen=${GEN}`), format: 'module' });
    return r;
  },
});

const stub = await import('./k6/state.mjs');
export const state = stub.state;

export const TMP = mkdtempSync(join(tmpdir(), 'beside-load-test-'));
export const PHOTO = join(TMP, 'photo.png');
writeFileSync(PHOTO, Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(4000, 7)]));
export const BASE_ENV = { BASE_URL: 'http://localhost:18080', RUN_ID: 'unit1', PHOTOS: `${PHOTO},${PHOTO}`, SUMMARY_PATH: join(TMP, 's.json') };

// Imports scripts/load/<rel> with a fresh module graph after setting __ENV (BASE_ENV + env) and the stub state.
export async function load(rel, env, extra) {
  GEN++;
  stub.reset(Object.assign({ env: Object.assign({}, BASE_ENV, env || {}), openBase: HERE }, extra || {}));
  return import(new URL(`../${rel}?gen=${GEN}`, import.meta.url).href);
}

export function lib(rel) {
  return load(`lib/${rel}`);
}

export function samples(metric, filter) {
  return state.samples.filter((s) => s.metric === metric && (!filter || filter(s)));
}

// ---------------------------------------------------------------- a fake API v1 server (Spring's upload rules)

const MIB = 1024 * 1024;

function parseMultipart(req) {
  const ct = req.headers['Content-Type'] || '';
  const m = /boundary=(.+)$/.exec(ct);
  if (!m) return null;
  const text = Buffer.from(req.body).toString('latin1');
  const parts = [];
  text.split(`--${m[1]}`).slice(1).forEach((chunk) => {
    if (chunk.startsWith('--')) return;
    const end = chunk.indexOf('\r\n\r\n');
    const head = chunk.slice(2, end);
    const data = chunk.slice(end + 4, chunk.length - 2);
    const name = /name="([^"]*)"/.exec(head);
    const filename = /filename="([^"]*)"/.exec(head);
    const type = /Content-Type: (.+)/i.exec(head);
    parts.push({ name: name && name[1], filename: filename ? filename[1] : null, type: type ? type[1].trim() : null, data });
  });
  return { parts, bytes: Buffer.from(req.body).length };
}

function err(status, code) {
  return { status, body: JSON.stringify({ error: { code, message: 'x' } }) };
}

// The order of JobServiceImpl.submitJobV1 and the multipart limits (5 MB per photo, 20 MB per request).
function validateUpload(mp, verifyMagic) {
  if (mp.bytes > 20 * MIB) return err(413, 'PAYLOAD_TOO_LARGE');
  const photos = mp.parts.filter((p) => p.name === 'photos');
  if (photos.some((p) => p.data.length > 5 * MIB)) return err(413, 'PAYLOAD_TOO_LARGE');
  if (photos.length === 0) return err(400, 'NO_PHOTOS');
  if (photos.length > 10) return err(400, 'TOO_MANY_PHOTOS');
  for (const p of photos) {
    if (p.data.length === 0) return err(400, 'INVALID_REQUEST');
    if (['image/jpeg', 'image/png', 'image/webp'].indexOf(p.type) < 0) return err(400, 'UNSUPPORTED_IMAGE_TYPE');
    const sig = p.data.slice(0, 8);
    const ok = sig.startsWith('\xff\xd8\xff') || sig === '\x89PNG\r\n\x1a\n';
    if (verifyMagic && !ok) return err(400, 'UNSUPPORTED_IMAGE_TYPE');
  }
  return null;
}

export function fakeServer(opts) {
  const o = Object.assign({ completeAfterPolls: 2, assetBytes: 1500000, verifyMagic: true, pollStatus: null, proxyLimit: 0 }, opts || {});
  const jobs = {};
  let next = 1;
  const server = (req) => {
    const path = new URL(req.url).pathname;
    if (req.method === 'GET' && path === '/api/v1/healthz') return { status: 200, body: '{"status":"ok","profile":"mock","workerType":"mock"}' };
    if (req.method === 'POST' && path === '/api/v1/jobs') {
      if (o.proxyLimit && Buffer.from(req.body).length > o.proxyLimit) return { status: 413, body: '' };
      const mp = parseMultipart(req);
      const bad = validateUpload(mp, o.verifyMagic);
      if (bad) return bad;
      const id = `00000000-0000-4000-8000-${String(next++).padStart(12, '0')}`;
      jobs[id] = { polls: 0, fail: mp.parts.some((p) => p.filename && p.filename.toLowerCase().indexOf('fail') >= 0), status: 'PENDING',
        filenames: mp.parts.map((p) => p.filename), key: req.headers['Idempotency-Key'] };
      return { status: 202, body: JSON.stringify({ jobId: id }), headers: { Location: `/api/v1/jobs/${id}` } };
    }
    let mm = /^\/api\/v1\/jobs\/([^/]+)$/.exec(path);
    if (mm && req.method === 'GET') {
      const j = jobs[mm[1]];
      if (!j) return err(404, 'JOB_NOT_FOUND');
      if (o.pollStatus) return { status: o.pollStatus, body: '' };
      j.polls++;
      if (j.status === 'PENDING' && o.completeAfterPolls !== null && j.polls > o.completeAfterPolls) j.status = j.fail ? 'FAILED' : 'COMPLETED';
      const done = j.status === 'COMPLETED';
      return { status: 200, body: JSON.stringify({ id: mm[1], status: j.status, timings: { queuedMs: 100, processingMs: done ? 60000 : null, totalMs: done ? 60100 : null },
        asset: done ? { url: `/api/v1/jobs/${mm[1]}/asset?variant=base`, bytes: o.assetBytes, variant: 'base', contentType: 'model/gltf-binary' } : null,
        error: j.status === 'FAILED' ? { code: 'INFERENCE_FAILED', message: 'x' } : null, uploadedFiles: [] }) };
    }
    mm = /^\/api\/v1\/jobs\/([^/]+)\/retry$/.exec(path);
    if (mm && req.method === 'POST') {
      const j = jobs[mm[1]];
      if (!j) return err(404, 'JOB_NOT_FOUND');
      if (j.status !== 'FAILED') return err(409, 'JOB_NOT_FAILED');
      j.status = 'PENDING';
      j.polls = 0;
      j.retried = (j.retried || 0) + 1;
      return { status: 202, body: JSON.stringify({ jobId: mm[1] }) };
    }
    mm = /^\/api\/v1\/jobs\/([^/]+)\/asset$/.exec(path);
    if (mm && req.method === 'GET') {
      const j = jobs[mm[1]];
      if (!j) return err(404, 'JOB_NOT_FOUND');
      if (j.status !== 'COMPLETED') return err(409, 'JOB_NOT_COMPLETED');
      return { status: 200, body: '', headers: { 'content-length': String(o.assetBytes) } };
    }
    return err(404, 'NOT_FOUND');
  };
  server.jobs = jobs;
  return server;
}
