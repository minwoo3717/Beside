// The app's user flow over the public API v1, as Unity's JobManager does it:
//   healthz -> POST /api/v1/jobs (2 photos, Idempotency-Key) -> GET /api/v1/jobs/{id} every 2 s until COMPLETED or
//   FAILED (give up after 600 s) -> on COMPLETED GET the asset once -> on FAILED wait 3 s, POST retry once, wait again.
// Every request carries endpoint=healthz|create|poll|asset|retry and a fixed `name` (no job ids in metric tags),
// plus the stage/phase tags the caller's ctx.tags() returns at the moment of the request.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { classify } from './classify.js';
import { uuid4 } from './config.js';
import * as m from './metrics.js';

const CALLBACKS = {};

function callbackFor(expected) {
  const k = expected.join(',');
  if (!CALLBACKS[k]) CALLBACKS[k] = http.expectedStatuses.apply(http, expected);
  return CALLBACKS[k];
}

// ctx: { cfg, tags(): {stage, phase}, body: {body, contentType} or a function returning it, makeFailBody(), failRatio,
//        requestTimeout }
export function makeContext(opts) {
  return {
    cfg: opts.cfg,
    tags: opts.tags || (() => ({})),
    body: opts.body,
    makeFailBody: opts.makeFailBody || null,
    failRatio: opts.failRatio || 0,
    requestTimeout: opts.requestTimeout || '60s',
    failBodyCache: null,
  };
}

// One request: sends it, records load_errorRatio / unexpected / rejected and returns { res, kind, tags }.
export function call(ctx, endpoint, name, method, url, body, opts) {
  const o = opts || {};
  const expected = o.expected || [200];
  const tags = Object.assign({ endpoint }, ctx.tags(), o.tags || {});
  const params = {
    tags: Object.assign({ name }, tags),
    responseType: o.responseType || 'none',
    responseCallback: callbackFor(expected),
    timeout: o.timeout || ctx.requestTimeout,
  };
  if (o.headers) params.headers = o.headers;
  const res = http.request(method, url, body === undefined ? null : body, params);
  const kind = classify(res.status, expected, typeof res.body === 'string' ? res.body : null);
  m.errorRatio.add(kind === 'error' ? 1 : 0, tags);
  if (kind === 'unexpected') m.unexpectedCount.add(1, Object.assign({ status: String(res.status) }, tags));
  if (kind === 'rejected') m.rejectedCount.add(1, tags);
  const checks = {};
  checks[`${endpoint} ${expected.join('|')}`] = () => kind === 'ok';
  check(res, checks, { endpoint });
  return { res, kind, tags };
}

export function healthz(ctx) {
  const r = call(ctx, 'healthz', '/api/v1/healthz', 'GET', `${ctx.cfg.baseUrl}/api/v1/healthz`, null, { expected: [200] });
  m.healthzMs.add(r.res.timings.duration, r.tags);
  return r.kind === 'ok';
}

export function createJob(ctx, multipart) {
  const headers = { 'Content-Type': multipart.contentType, 'Idempotency-Key': `lt-${ctx.cfg.runId}-${uuid4()}` };
  const r = call(ctx, 'create', '/api/v1/jobs', 'POST', `${ctx.cfg.baseUrl}/api/v1/jobs`, multipart.body,
    { expected: [202], responseType: 'text', headers });
  m.postMs.add(r.res.timings.duration, r.tags);
  if (r.kind !== 'ok') return { kind: r.kind, jobId: null };
  // load_bytesSent counts only bodies the server read in full (202). A refused, reset, timed-out or early-closed upload
  // (status 0, 5xx, a proxy 413) sent part of its body or none, and k6 has no per-request bytes-sent, so it is left out:
  // the per-stage upload Mbps stays a lower bound and a failing stage cannot fake a saturated uplink.
  const body = multipart.body;
  m.bytesSent.add(typeof multipart.bytes === 'number' ? multipart.bytes : (body.byteLength === undefined ? body.length : body.byteLength), r.tags);
  let jobId = null;
  try { jobId = r.res.json('jobId'); } catch (e) { jobId = null; }
  if (typeof jobId !== 'string' || jobId.length === 0) {
    m.unexpectedCount.add(1, Object.assign({ reason: 'no_job_id' }, r.tags));
    return { kind: 'unexpected', jobId: null };
  }
  return { kind: 'ok', jobId };
}

function recordServerTimings(job, tags) {
  const t = job && job.timings;
  if (!t) return;
  if (typeof t.queuedMs === 'number') m.serverQueuedMs.add(t.queuedMs, tags);
  if (typeof t.processingMs === 'number') m.serverProcessingMs.add(t.processingMs, tags);
  if (typeof t.totalMs === 'number') m.serverTotalMs.add(t.totalMs, tags);
}

// Polls until a terminal status, an error, or MAX_WAIT_S. Returns { outcome, job, polls }.
export function pollUntilDone(ctx, jobId, flowTags) {
  const url = `${ctx.cfg.baseUrl}/api/v1/jobs/${jobId}`;
  const start = Date.now();
  let polls = 0;
  let result = null;
  while (result === null) {
    const r = call(ctx, 'poll', '/api/v1/jobs/{id}', 'GET', url, null, { expected: [200], responseType: 'text' });
    polls++;
    m.pollMs.add(r.res.timings.duration, r.tags);
    if (r.kind !== 'ok') {
      result = { outcome: r.kind === 'rejected' ? 'rejected' : 'error', job: null };
      break;
    }
    let job = null;
    try { job = r.res.json(); } catch (e) { job = null; }
    if (!job || typeof job.status !== 'string') {
      m.unexpectedCount.add(1, Object.assign({ reason: 'bad_job_body' }, r.tags));
      result = { outcome: 'error', job: null };
      break;
    }
    if (job.status === 'COMPLETED' || job.status === 'FAILED') {
      recordServerTimings(job, flowTags);
      result = { outcome: job.status === 'COMPLETED' ? 'completed' : 'failed', job };
      break;
    }
    // Unity checks the clock after each response and before sleeping; an unknown status keeps polling (UNKNOWN).
    if (Date.now() - start > ctx.cfg.maxWaitS * 1000) {
      result = { outcome: 'timeout', job };
      break;
    }
    sleep(ctx.cfg.pollIntervalS);
  }
  m.waitMs.add(Date.now() - start, Object.assign({ outcome: result.outcome }, flowTags));
  m.pollsPerWait.add(polls, flowTags);
  result.polls = polls;
  return result;
}

export function downloadAsset(ctx, jobId, job) {
  const rel = job && job.asset && typeof job.asset.url === 'string' ? job.asset.url : `/api/v1/jobs/${jobId}/asset`;
  const r = call(ctx, 'asset', '/api/v1/jobs/{id}/asset', 'GET', `${ctx.cfg.baseUrl}${rel}`, null, { expected: [200] });
  m.assetMs.add(r.res.timings.duration, r.tags);
  m.assetTtfbMs.add(r.res.timings.waiting, r.tags);
  if (r.kind !== 'ok') return false;
  const got = Number(r.res.headers['Content-Length']);
  const want = job && job.asset && typeof job.asset.bytes === 'number' ? job.asset.bytes : null;
  const sizeOk = isFinite(got) && (want === null || got === want) && got >= ctx.cfg.minAssetBytes;
  check(r.res, { 'asset size matches job.asset.bytes': () => sizeOk }, { endpoint: 'asset' });
  if (!sizeOk) {
    m.unexpectedCount.add(1, Object.assign({ reason: 'asset_size' }, r.tags));
    return false;
  }
  m.assetBytes.add(got, r.tags);
  return true;
}

export function retryJob(ctx, jobId) {
  const r = call(ctx, 'retry', '/api/v1/jobs/{id}/retry', 'POST', `${ctx.cfg.baseUrl}/api/v1/jobs/${jobId}/retry`, null,
    { expected: [202], responseType: 'text' });
  m.retryMs.add(r.res.timings.duration, r.tags);
  return r.kind;
}

function failBody(ctx) {
  if (!ctx.failBodyCache) ctx.failBodyCache = ctx.makeFailBody();
  return ctx.failBodyCache;
}

// ctx.body may be a function so that only VUs that run flows build the (0.8 MB) body.
function normalBody(ctx) {
  return typeof ctx.body === 'function' ? ctx.body() : ctx.body;
}

// The whole flow; returns completed | failed | timeout | rejected | error.
export function userFlow(ctx) {
  const flowTags = ctx.tags();
  m.flowStarted.add(1, flowTags);
  const t0 = Date.now();
  const injected = ctx.failRatio > 0 && ctx.makeFailBody !== null && Math.random() < ctx.failRatio;
  const outcome = runFlow(ctx, injected, flowTags);
  m.flowMs.add(Date.now() - t0, Object.assign({ outcome }, flowTags));
  m.flowTimeoutRatio.add(outcome === 'timeout' ? 1 : 0, flowTags);
  m.flowOkRatio.add(outcome === 'completed' || (injected && outcome === 'failed') ? 1 : 0, flowTags);
  m.flowEnded.add(1, flowTags);
  return outcome;
}

// One upload (upload.js) or one download (asset.js) counted as a flow: load_flowStarted before, load_flowEnded after,
// both with the stage/phase tags of its start, so lib/summary.js can tell a request k6 cut off from one that ended.
export function countedFlow(ctx, fn) {
  const flowTags = ctx.tags();
  m.flowStarted.add(1, flowTags);
  const out = fn();
  m.flowEnded.add(1, flowTags);
  return out;
}

function runFlow(ctx, injected, flowTags) {
  if (ctx.cfg.healthzPerFlow && !healthz(ctx)) return 'error';
  const created = createJob(ctx, injected ? failBody(ctx) : normalBody(ctx));
  if (created.kind !== 'ok') return created.kind === 'rejected' ? 'rejected' : 'error';
  let res = pollUntilDone(ctx, created.jobId, flowTags);
  if (res.outcome === 'failed') {
    sleep(ctx.cfg.retryWaitS);
    const kind = retryJob(ctx, created.jobId);
    if (kind !== 'ok') return kind === 'rejected' ? 'rejected' : 'error';
    res = pollUntilDone(ctx, created.jobId, flowTags);
  }
  if (res.outcome === 'completed') return downloadAsset(ctx, created.jobId, res.job) ? 'completed' : 'error';
  return res.outcome;
}

// A session that opens the app and leaves without making a job (one healthz), so the request mix matches model.py.
export function browseOnly(ctx) {
  healthz(ctx);
}
