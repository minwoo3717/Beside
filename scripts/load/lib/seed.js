// setup() helpers: a fail-fast healthz and job seeding. Seed requests are tagged endpoint=seed|setup so they never
// count toward the measured endpoints or load_errorRatio.
import http from 'k6/http';
import { sleep } from 'k6';
import { uuid4 } from './config.js';

export function preflight(cfg) {
  const res = http.get(`${cfg.baseUrl}/api/v1/healthz`, { tags: { endpoint: 'setup', name: '/api/v1/healthz' }, responseType: 'text' });
  if (res.status !== 200) throw new Error(`healthz returned ${res.status} at ${cfg.baseUrl}: is the server up?`);
  let server = {};
  try {
    const doc = JSON.parse(res.body);
    server = { profile: doc.profile, workerType: doc.workerType };
  } catch (e) {
    server = {};
  }
  return { startedAt: new Date().toISOString(), server };
}

// Up to n ids spread evenly over `list` (index access only: a k6 SharedArray has no slice/map).
export function sampleIds(list, n) {
  const len = list.length;
  const k = Math.min(len, n);
  const out = [];
  for (let i = 0; i < k; i++) out.push(list[Math.floor((i * len) / k)]);
  return out;
}

// GET /api/v1/jobs/{id} once per id (tagged endpoint=setup, not measured). Without `states` only the HTTP status is
// checked. With `states` (e.g. ['PENDING']) the body is read and a 200 whose job status is not one of them is bad too:
// a PENDING job that a backend restart closed (InterruptedJobRecovery: FAILED) still answers 200.
// Returns { checked, bad: [{ id, status, state }], maxQueuedMs } (maxQueuedMs: the largest timings.queuedMs among the
// ids in one of `states`, i.e. how long the oldest PENDING one has waited; null without `states` or values).
export function checkIds(cfg, ids, states) {
  const res = ids.length === 0 ? [] : http.batch(ids.map((id) => ({
    method: 'GET',
    url: `${cfg.baseUrl}/api/v1/jobs/${id}`,
    params: { tags: { endpoint: 'setup', name: '/api/v1/jobs/{id}' }, responseType: states ? 'text' : 'none', timeout: '30s' },
  })));
  const bad = [];
  let maxQueuedMs = null;
  res.forEach((r, i) => {
    if (r.status !== 200) {
      bad.push({ id: ids[i], status: r.status, state: null });
      return;
    }
    if (!states) return;
    const state = jsonField(r, 'status');
    if (states.indexOf(state) < 0) {
      bad.push({ id: ids[i], status: 200, state });
      return;
    }
    const timings = jsonField(r, 'timings');
    const q = timings && typeof timings.queuedMs === 'number' ? timings.queuedMs : null;
    if (q !== null && (maxQueuedMs === null || q > maxQueuedMs)) maxQueuedMs = q;
  });
  return { checked: ids.length, bad, maxQueuedMs };
}

// The id lists of one or more id files ({"completed": [...], "pending": [...]}, e.g. the IDS_OUT of two SEED_ONLY
// runs of poll_saturation.js) joined in file order, each id once. `names` label the files in errors. An id in both
// lists cannot have both states, so it is refused.
export function joinIdLists(docs, names) {
  const out = { completed: [], pending: [] };
  const seen = { completed: {}, pending: {} };
  docs.forEach((doc, i) => {
    const name = names && names[i] ? names[i] : `#${i + 1}`;
    if (!doc || typeof doc !== 'object' || Array.isArray(doc)) {
      throw new Error(`id file ${name}: not an object with "completed" and "pending" lists`);
    }
    ['completed', 'pending'].forEach((key) => {
      const list = doc[key] === undefined || doc[key] === null ? [] : doc[key];
      if (!Array.isArray(list)) throw new Error(`id file ${name}: "${key}" is not a list`);
      list.forEach((id) => {
        if (typeof id !== 'string' || id === '') throw new Error(`id file ${name}: "${key}" holds ${JSON.stringify(id)}, not a job id`);
        if (!seen[key][id]) {
          seen[key][id] = true;
          out[key].push(id);
        }
      });
    });
  });
  const both = out.completed.filter((id) => seen.pending[id]);
  if (both.length > 0) {
    throw new Error(`id files: ${both.length} id(s) are in both "completed" and "pending" (first ${both[0]})`);
  }
  return out;
}

function jsonField(res, field) {
  try { return res.json(field); } catch (e) { return null; }
}

// Creates n jobs (in batches) and, with opts.wait, polls them until COMPLETED/FAILED or opts.waitS.
// Returns { created: [ids], completed: [{id, asset}], failed: [ids], unfinished: [ids] }.
export function seedJobs(cfg, multipart, n, opts) {
  const o = Object.assign({ wait: true, waitS: 900, batch: 20, pollEveryS: 5 }, opts || {});
  const created = [];
  for (let i = 0; i < n; i += o.batch) {
    const reqs = [];
    for (let j = i; j < Math.min(n, i + o.batch); j++) {
      reqs.push({
        method: 'POST',
        url: `${cfg.baseUrl}/api/v1/jobs`,
        body: multipart.body,
        params: {
          headers: { 'Content-Type': multipart.contentType, 'Idempotency-Key': `lt-${cfg.runId}-${uuid4()}` },
          tags: { endpoint: 'seed', name: '/api/v1/jobs' },
          responseType: 'text',
          timeout: '120s',
        },
      });
    }
    http.batch(reqs).forEach((r) => {
      const id = r.status === 202 ? jsonField(r, 'jobId') : null;
      if (id) created.push(id);
    });
  }
  const out = { created, completed: [], failed: [], unfinished: [] };
  if (!o.wait) return out;
  const deadline = Date.now() + o.waitS * 1000;
  let left = created.slice();
  while (left.length > 0 && Date.now() < deadline) {
    const next = [];
    for (let i = 0; i < left.length; i += o.batch) {
      const chunk = left.slice(i, i + o.batch);
      const res = http.batch(chunk.map((id) => ({
        method: 'GET',
        url: `${cfg.baseUrl}/api/v1/jobs/${id}`,
        params: { tags: { endpoint: 'seed', name: '/api/v1/jobs/{id}' }, responseType: 'text' },
      })));
      res.forEach((r, k) => {
        const status = r.status === 200 ? jsonField(r, 'status') : null;
        if (status === 'COMPLETED') out.completed.push({ id: chunk[k], asset: jsonField(r, 'asset') });
        else if (status === 'FAILED') out.failed.push(chunk[k]);
        else next.push(chunk[k]);
      });
    }
    left = next;
    if (left.length > 0) sleep(o.pollEveryS);
  }
  out.unfinished = left;
  return out;
}
