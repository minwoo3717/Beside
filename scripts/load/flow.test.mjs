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
