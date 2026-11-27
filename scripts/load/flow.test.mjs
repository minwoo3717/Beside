// The user flow (lib/flow.js through smoke.js) against the fake API v1 server.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { join } from 'node:path';
import { test } from 'node:test';
import { BASE_ENV, HERE, PHOTO, TMP, fakeServer, lib, load, samples, state } from './testing/harness.mjs';

test('flow: healthz -> create -> poll every 2 s -> asset, tagged, prefixed', async () => {
  const mod = await load('smoke.js', {}, { clock: 1000000, scenarioStartTime: 1000000 });
  const server = fakeServer({ completeAfterPolls: 2 });
  state.handler = server;
  mod.flow();
  const eps = state.requests.map((r) => r.tags.endpoint);
  assert.deepEqual(eps, ['healthz', 'create', 'poll', 'poll', 'poll', 'asset']);
  state.requests.forEach((r) => assert.ok(r.tags.name && r.tags.name.indexOf('0000') < 0, `name tag without ids: ${r.tags.name}`));
  const create = state.requests[1];
  assert.match(create.headers['Idempotency-Key'], /^lt-unit1-[0-9a-f-]{36}$/);
  // every measured request names the run in X-Request-Id (Caddy/backend logs, Grafana 'Beside 로그' 부하 시험 행)
  state.requests.forEach((r) => assert.match(r.headers['X-Request-Id'], /^lt-unit1-[0-9a-f]{8}$/, r.tags.endpoint));
  assert.equal(new Set(state.requests.map((r) => r.headers['X-Request-Id'])).size, state.requests.length);
  const job = Object.values(server.jobs)[0];
  assert.deepEqual(job.filenames, ['lt-unit1-1.png', 'lt-unit1-2.png']);
  assert.equal(samples('load_flowOkRatio')[0].value, 1);
  assert.equal(samples('load_flowTimeoutRatio')[0].value, 0);
  assert.ok(samples('load_errorRatio').every((s) => s.value === 0));
  assert.equal(samples('load_assetBytes')[0].value, 1500000);
  // upload body bytes per request, tagged like the request (stage/phase in staged scripts) for per-stage uplink
  const sent = samples('load_bytesSent');
  assert.equal(sent.length, 1);
  assert.equal(sent[0].value, Buffer.from(create.body).length);
  assert.equal(sent[0].tags.endpoint, 'create');
  assert.equal(samples('load_serverQueuedMs')[0].value, 100);
  // two sleeps of 2 s between three polls, plus 5 ms per request
  assert.equal(samples('load_flowMs')[0].value, 4000 + 6 * 5);
  assert.equal(state.checks.filter((c) => !c.ok).length, 0);
});

test('flow: an injected failure is retried once after 3 s and counts as intended', async () => {
  const mod = await load('smoke.js', { FAIL_RATIO: '1' }, { clock: 0 });
  const server = fakeServer({ completeAfterPolls: 1 });
  state.handler = server;
  mod.flow();
  const eps = state.requests.map((r) => r.tags.endpoint);
  assert.deepEqual(eps, ['healthz', 'create', 'poll', 'poll', 'retry', 'poll', 'poll']);
  const job = Object.values(server.jobs)[0];
  assert.deepEqual(job.filenames, ['lt-unit1-fail-1.png', 'lt-unit1-fail-2.png']);
  assert.equal(job.retried, 1);
  assert.equal(samples('load_flowOkRatio')[0].value, 1);
  assert.equal(samples('load_flowMs')[0].tags.outcome, 'failed');
  assert.equal(samples('load_flowMs')[0].value, 2000 + 3000 + 2000 + 7 * 5);
});

test('flow: gives up after MAX_WAIT_S like the app', async () => {
  const mod = await load('smoke.js', { MAX_WAIT_S: '10' }, { clock: 0 });
  state.handler = fakeServer({ completeAfterPolls: null });
  mod.flow();
  assert.equal(samples('load_flowTimeoutRatio')[0].value, 1);
  assert.equal(samples('load_flowOkRatio')[0].value, 0);
  assert.equal(state.requests.filter((r) => r.tags.endpoint === 'poll').length, 6);
});

test('flow: a 5xx while polling is an error and ends the flow', async () => {
  const mod = await load('smoke.js', {}, { clock: 0 });
  state.handler = fakeServer({ pollStatus: 502 });
  mod.flow();
  assert.deepEqual(samples('load_errorRatio').map((s) => s.value), [0, 0, 1]);
  assert.equal(samples('load_flowMs')[0].tags.outcome, 'error');
});

test('flow: every response is counted by its raw status class with its code and the request tags (2xx not)', async () => {
  const isPoll = (req) => req.method === 'GET' && /^\/api\/v1\/jobs\/[^/]+$/.test(new URL(req.url).pathname);
  const counted = () => ['load_status0Count', 'load_status4xxCount', 'load_status5xxCount']
    .map((metric) => samples(metric).map((s) => [metric, s.value, s.tags.status, s.tags.endpoint]))
    .reduce((all, list) => all.concat(list), []);
  // a 502 while polling (A2's collapse: a proxy answer) -> 5xx; healthz 200 and create 202 are not counted
  const proxy = await load('smoke.js', {}, { clock: 0 });
  state.handler = fakeServer({ pollStatus: 502 });
  proxy.flow();
  assert.deepEqual(counted(), [['load_status5xxCount', 1, '502', 'poll']]);
  // no HTTP response (connection refused, reset or timed out: status 0) -> '0', also a load_errorRatio error
  const dropped = await load('smoke.js', {}, { clock: 0 });
  const server = fakeServer();
  state.handler = (req) => (isPoll(req) ? null : server(req));
  dropped.flow();
  assert.deepEqual(counted(), [['load_status0Count', 1, '0', 'poll']]);
  assert.deepEqual(samples('load_errorRatio').map((s) => s.value), [0, 0, 1]);
  // a 404 is a 4xx here and "unexpected" for the verdict (classify decides that, not the class)
  const missing = await load('smoke.js', {}, { clock: 0 });
  const other = fakeServer();
  state.handler = (req) => (isPoll(req) ? { status: 404, body: '{"error":{"code":"JOB_NOT_FOUND","message":"x"}}' } : other(req));
  missing.flow();
  assert.deepEqual(counted(), [['load_status4xxCount', 1, '404', 'poll']]);
  assert.equal(samples('load_unexpectedCount')[0].tags.status, '404');
  // a whole flow that went well counts nothing
  const fine = await load('smoke.js', {}, { clock: 0 });
  state.handler = fakeServer({ completeAfterPolls: 1 });
  fine.flow();
  assert.deepEqual(counted(), []);
  // staged scripts: the stage/phase tags of the moment come along, so each hold keeps its own counts
  const staged = await load('api_staged.js', { RTT_BASE_MS: '20' }, { clock: 61000, scenarioStartTime: 0 });
  state.handler = fakeServer({ pollStatus: 504 });
  staged.flow();
  assert.deepEqual(samples('load_status5xxCount').map((s) => [s.tags.status, s.tags.stage, s.tags.phase]), [['504', '0', 'hold']]);
});

test('flow: an asset whose size differs from job.asset.bytes fails the flow', async () => {
  const mod = await load('smoke.js', { MIN_ASSET_BYTES: '2000000' }, { clock: 0 });
  state.handler = fakeServer({ completeAfterPolls: 0 });
  mod.flow();
  assert.equal(samples('load_flowMs')[0].tags.outcome, 'error');
  assert.equal(samples('load_unexpectedCount')[0].tags.reason, 'asset_size');
});

// 503 QUEUE_FULL + Retry-After is the queue cap (agenda #21, decided 2026-11-21): the fake server answers it the way the server does.
const QUEUE_FULL = '{"error":{"code":"QUEUE_FULL","message":"x"}}';

function isCreate(req) {
  return req.method === 'POST' && new URL(req.url).pathname === '/api/v1/jobs';
}

function refuseCreates(retryAfter) {
  const server = fakeServer({ completeAfterPolls: 1 });
  return (req) => (isCreate(req) ? { status: 503, body: QUEUE_FULL, headers: retryAfter === undefined ? {} : { 'Retry-After': retryAfter } } : server(req));
}

function queueFullChecks() {
  return state.checks.filter((c) => /QUEUE_FULL Retry-After$/.test(c.name)).map((c) => [c.name, c.ok]);
}

test('flow: a create refused with 503 QUEUE_FULL ends the flow as rejected and is not a created job', async () => {
  // a 202 is a created job: load_createdCount +1, tagged like the request
  const ok = await load('smoke.js', {}, { clock: 0 });
  state.handler = fakeServer({ completeAfterPolls: 0 });
  ok.flow();
  assert.deepEqual(samples('load_createdCount').map((s) => [s.value, s.tags.endpoint]), [[1, 'create']]);
  assert.deepEqual(samples('load_queueFullCount'), []);
  assert.deepEqual(queueFullChecks(), []);

  const mod = await load('smoke.js', {}, { clock: 0 });
  state.handler = refuseCreates('165');
  mod.flow();
  assert.deepEqual(state.requests.map((r) => r.tags.endpoint), ['healthz', 'create']);
  // the POST was sent (its latency stays in load_postMs) but no job was created and no body counts as accepted
  assert.equal(samples('load_postMs').length, 1);
  assert.deepEqual(samples('load_createdCount'), []);
  assert.deepEqual(samples('load_bytesSent'), []);
  // an intended refusal: rejected (not an error, not unexpected), one QUEUE_FULL on create, still a raw 5xx
  assert.equal(samples('load_rejectedCount').length, 1);
  assert.deepEqual(samples('load_queueFullCount').map((s) => [s.value, s.tags.endpoint]), [[1, 'create']]);
  assert.ok(samples('load_errorRatio').every((s) => s.value === 0));
  assert.deepEqual(samples('load_unexpectedCount'), []);
  assert.deepEqual(samples('load_status5xxCount').map((s) => s.tags.status), ['503']);
  // the flow: rejected, not ok, not a timeout
  assert.equal(samples('load_flowMs')[0].tags.outcome, 'rejected');
  assert.deepEqual(samples('load_flowOkRatio').map((s) => s.value), [0]);
  assert.deepEqual(samples('load_flowTimeoutRatio').map((s) => s.value), [0]);
  // the Retry-After check passed; the create 202 check failed as for any refusal
  assert.deepEqual(queueFullChecks(), [['create QUEUE_FULL Retry-After', true]]);
  assert.deepEqual(samples('load_retryAfterOkRatio').map((s) => [s.value, s.tags.endpoint]), [[1, 'create']]);
  assert.deepEqual(state.checks.filter((c) => c.name === 'create 202').map((c) => c.ok), [false]);
});

test('flow: only a 202 is a created job; every other create answer is sent (load_postMs) but not created', async () => {
  // docs/perf/load/TRAFFIC_MODEL.md §9: meta.measuredCreates (load_createdCount) leaves out every answer that is not a 202.
  // The flow ends 'rejected' for 429 / 503 QUEUE_FULL and 'error' for the rest (an unexpected 4xx ends it as an error too).
  const answers = [
    ['503 QUEUE_FULL', { status: 503, body: QUEUE_FULL, headers: { 'Retry-After': '165' } }, 'rejected'],
    ['429 RATE_LIMITED', { status: 429, body: '{"error":{"code":"RATE_LIMITED","message":"x"}}' }, 'rejected'],
    ['400 photo check', { status: 400, body: '{"error":{"code":"NO_ANIMAL_DETECTED","message":"x"}}' }, 'error'],
    ['413 from the proxy', { status: 413, body: '<html>413</html>' }, 'error'],
    ['500', { status: 500, body: '{"error":{"code":"INTERNAL_ERROR","message":"x"}}' }, 'error'],
    ['503 from the proxy', { status: 503, body: '' }, 'error'],
    ['status 0', { status: 0, body: '' }, 'error'],
  ];
  for (const [name, answer, outcome] of answers) {
    const mod = await load('smoke.js', {}, { clock: 0 });
    const server = fakeServer({ completeAfterPolls: 0 });
    state.handler = (req) => (isCreate(req) ? answer : server(req));
    mod.flow();
    assert.equal(samples('load_postMs').length, 1, `${name}: the POST is still timed`);
    assert.deepEqual(samples('load_createdCount'), [], `${name}: no job was created`);
    assert.equal(samples('load_flowMs')[0].tags.outcome, outcome, name);
  }
});

test('flow: every 503 QUEUE_FULL has its Retry-After checked (whole seconds >= 1, = QUEUE_RETRY_AFTER when set)', async () => {
  const f = await lib('flow.js');
  assert.equal(f.retryAfterOk('165', null), true);
  assert.equal(f.retryAfterOk('1', null), true);
  assert.equal(f.retryAfterOk('165', 165), true);
  assert.equal(f.retryAfterOk('60', 165), false);
  // missing, empty, zero, negative, a fraction, padded, or an HTTP-date (RFC 9110 allows it, the contract does not)
  [undefined, '', '0', '-5', '1.5', ' 165', '165s', 'Wed, 21 Oct 2026 07:28:00 GMT'].forEach((v) => {
    assert.equal(f.retryAfterOk(v, null), false, String(v));
  });

  const cases = [['165', {}, true], [undefined, {}, false], ['0', {}, false], ['165', { QUEUE_RETRY_AFTER: '165' }, true],
    ['60', { QUEUE_RETRY_AFTER: '165' }, false]];
  for (const [header, env, want] of cases) {
    const mod = await load('smoke.js', env, { clock: 0 });
    state.handler = refuseCreates(header);
    mod.flow();
    assert.deepEqual(queueFullChecks(), [['create QUEUE_FULL Retry-After', want]], `${header} ${JSON.stringify(env)}`);
    assert.deepEqual(samples('load_retryAfterOkRatio').map((s) => s.value), [want ? 1 : 0], `${header} ${JSON.stringify(env)}`);
    assert.equal(samples('load_queueFullCount').length, 1);
  }

  // a refused retry is checked and counted on the retry endpoint (the job stays FAILED, the flow ends rejected)
  const retried = await load('smoke.js', { FAIL_RATIO: '1' }, { clock: 0 });
  const server = fakeServer({ completeAfterPolls: 0 });
  state.handler = (req) => (/\/retry$/.test(new URL(req.url).pathname)
    ? { status: 503, body: QUEUE_FULL, headers: { 'retry-after': '30' } } : server(req));
  retried.flow();
  assert.deepEqual(state.requests.map((r) => r.tags.endpoint), ['healthz', 'create', 'poll', 'retry']);
  assert.deepEqual(samples('load_createdCount').map((s) => s.value), [1]);
  assert.deepEqual(samples('load_queueFullCount').map((s) => s.tags.endpoint), ['retry']);
  assert.deepEqual(queueFullChecks(), [['retry QUEUE_FULL Retry-After', true]]);
  assert.equal(samples('load_flowMs')[0].tags.outcome, 'rejected');

  // not QUEUE_FULL: a proxy 503 without the envelope is an error, 429 RATE_LIMITED a rejection; neither is checked
  for (const answer of [{ status: 503, body: '' }, { status: 429, body: '{"error":{"code":"RATE_LIMITED","message":"x"}}' }]) {
    const mod = await load('smoke.js', {}, { clock: 0 });
    const other = fakeServer();
    state.handler = (req) => (isCreate(req) ? answer : other(req));
    mod.flow();
    assert.deepEqual(samples('load_queueFullCount'), [], String(answer.status));
    assert.deepEqual(samples('load_retryAfterOkRatio'), [], String(answer.status));
    assert.deepEqual(queueFullChecks(), [], String(answer.status));
  }
});

test('config: QUEUE_RETRY_AFTER is a whole number of seconds >= 1, null when not set', async () => {
  const cfg = await lib('config.js');
  assert.equal(cfg.parseConfig({}).queueRetryAfterS, null);
  assert.equal(cfg.parseConfig({ QUEUE_RETRY_AFTER: '165' }).queueRetryAfterS, 165);
  ['0', '2.5', '-1', 'abc'].forEach((v) => {
    assert.throws(() => cfg.parseConfig({ QUEUE_RETRY_AFTER: v }), /QUEUE_RETRY_AFTER/, v);
  });
  await assert.rejects(load('smoke.js', { QUEUE_RETRY_AFTER: '0' }), /QUEUE_RETRY_AFTER must be a whole number of seconds >= 1/);
});

test('setup + handleSummary write the summary file entry and a text report', async () => {
  const mod = await load('smoke.js', {}, { clock: 0 });
  state.handler = fakeServer();
  const setupData = mod.setup();
  assert.equal(setupData.server.profile, 'mock');
  const out = mod.handleSummary({ metrics: { load_healthzMs: { values: { med: 1.25 } } }, setup_data: setupData, state: { testRunDurationMs: 5 } });
  const doc = JSON.parse(out[BASE_ENV.SUMMARY_PATH]);
  assert.equal(doc.meta.scenario, 'A0');
  assert.equal(doc.meta.rttBaseMsMeasured, 1.25);
  assert.equal(doc.meta.runId, 'unit1');
  assert.match(out.stdout, /RTT_base candidate\) 1\.25 ms/);
  assert.doesNotMatch(out.stdout, /not_run/);
});

test('marker: setup ends and teardown runs with one healthz carrying lt-<RUN_ID>-begin|end, outside every threshold', async () => {
  // every entry script marks the start and the end of the load in the Caddy log (Grafana annotation, 부하 시험 행)
  for (const script of ['smoke.js', 'api_staged.js', 'upload.js', 'gpu_queue.js', 'asset.js', 'poll_saturation.js', 'failure/f3_uploads.js']) {
    const src = (await import('node:fs')).readFileSync(join(HERE, script), 'utf8');
    assert.match(src, /mark\(CONFIG, 'begin'\)/, `${script} marks begin`);
    assert.match(src, /mark\(CONFIG, 'end'\)/, `${script} marks end`);
  }
  const mod = await load('smoke.js', {}, { clock: 0 });
  state.handler = fakeServer();
  mod.setup();
  const begin = state.requests[state.requests.length - 1];
  assert.equal(begin.url, `${BASE_ENV.BASE_URL}/api/v1/healthz`);
  assert.equal(begin.headers['X-Request-Id'], 'lt-unit1-begin');
  assert.deepEqual(begin.tags, { endpoint: 'marker', name: 'marker' });
  assert.equal(begin.params.timeout, '5s');
  const before = samples('load_errorRatio').length;
  state.handler = () => { throw new Error('connection refused'); };   // never fails the run
  mod.teardown();
  assert.equal(state.requests[state.requests.length - 1].headers['X-Request-Id'], 'lt-unit1-end');
  assert.equal(samples('load_errorRatio').length, before, 'a mark is not a measured request');

  const marker = await lib('marker.js');
  assert.equal(marker.markerId('a1-end', 'begin'), 'lt-a1-end-begin');
  assert.ok(marker.markerId('x'.repeat(40), 'begin').length <= 64, 'the server keeps X-Request-Id up to 64 characters');
});
