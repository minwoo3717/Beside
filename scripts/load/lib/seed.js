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

// GET /api/v1/jobs/{id} once per id (tagged endpoint=setup, not measured). Returns { checked, bad: [{ id, status }] }.
export function checkIds(cfg, ids) {
  const res = ids.length === 0 ? [] : http.batch(ids.map((id) => ({
    method: 'GET',
    url: `${cfg.baseUrl}/api/v1/jobs/${id}`,
    params: { tags: { endpoint: 'setup', name: '/api/v1/jobs/{id}' }, responseType: 'none', timeout: '30s' },
  })));
  const bad = [];
  res.forEach((r, i) => { if (r.status !== 200) bad.push({ id: ids[i], status: r.status }); });
  return { checked: ids.length, bad };
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
