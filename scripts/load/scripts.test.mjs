// Entry scripts: options (stages, scenarios, thresholds) and their setup/exec functions.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { join } from 'node:path';
import { test } from 'node:test';
import { BASE_ENV, HERE, PHOTO, TMP, fakeServer, lib, load, samples, state } from './testing/harness.mjs';

test('api_staged.js A1: arrival stages, VUs and per-stage thresholds', async () => {
  const mod = await load('api_staged.js', { RTT_BASE_MS: '20' });
  const sc = mod.options.scenarios;
  assert.deepEqual(sc.flows.stages.filter((s, i) => i % 2 === 1).map((s) => s.target), [50, 400, 2000, 3600, 7200, 10800, 14400, 18000]);
  assert.deepEqual(sc.browse.stages.filter((s, i) => i % 2 === 1).map((s) => s.target), [200, 1600, 8000, 14400, 28800, 43200, 57600, 72000]);
  assert.equal(sc.flows.timeUnit, '1h');
  assert.equal(sc.flows.stages[0].duration, '60s');
  assert.equal(sc.flows.stages[1].duration, '480s');
  assert.equal(sc.flows.maxVUs, 3300); // 5 flows/s x 660 s
  assert.equal(sc.guard, undefined);
  const th = mod.options.thresholds;
  assert.deepEqual(th['http_req_duration{endpoint:poll,stage:3,phase:hold}'], ['p(95)<120', 'p(99)<320']);
  assert.deepEqual(th['load_flowOkRatio{stage:7,phase:hold}'], ['rate>=0.95']);
});

test('api_staged.js A5 spike and A6 soak follow tiers.json', async () => {
  const spike = await load('api_staged.js', { SCENARIO: 'spike', RTT_BASE_MS: '20' });
  assert.deepEqual(spike.options.scenarios.flows.stages, [
    { duration: '60s', target: 3600 }, { duration: '300s', target: 3600 }, { duration: '30s', target: 10800 },
    { duration: '600s', target: 10800 }, { duration: '30s', target: 3600 }, { duration: '600s', target: 3600 }]);
  const soak = await load('api_staged.js', { SCENARIO: 'soak', RTT_BASE_MS: '20', SOAK_HOLD_S: '600', PROM_URL: 'http://localhost:9090' });
  assert.deepEqual(soak.options.scenarios.flows.stages, [{ duration: '60s', target: 3600 }, { duration: '600s', target: 3600 }]);
  assert.equal(soak.options.scenarios.guard.executor, 'constant-vus');
});

test('api_staged.js refuses a remote target without RTT_BASE_MS', async () => {
  await assert.rejects(load('api_staged.js', { BASE_URL: 'https://beside.example.org' }), /RTT_BASE_MS/);
});

test('poll_saturation.js A2: one constant-arrival-rate scenario per step', async () => {
  const mod = await load('poll_saturation.js', { RTT_BASE_MS: '20' });
  const names = Object.keys(mod.options.scenarios);
  assert.deepEqual(names, ['rps0', 'rps1', 'rps2', 'rps3', 'rps4', 'rps5']);
  assert.deepEqual(names.map((n) => mod.options.scenarios[n].rate), [100, 300, 600, 900, 1200, 1800]);
  assert.equal(mod.options.scenarios.rps2.startTime, '660s');
  assert.deepEqual(mod.options.thresholds['dropped_iterations{scenario:rps5}'], ['count==0']);
});

test('upload.js A3: rates per minute and the disk budget guard', async () => {
  const small = await load('upload.js', { RTT_BASE_MS: '20' });
  assert.deepEqual(small.options.scenarios.uploads.stages.filter((s, i) => i % 2 === 1).map((s) => s.target), [60, 120, 300, 600, 900, 1200]);
  const large = await load('upload.js', { RTT_BASE_MS: '20', SIZE: 'large', LARGE_PHOTO: PHOTO });
  assert.deepEqual(large.options.scenarios.uploads.stages.filter((s, i) => i % 2 === 1).map((s) => s.target), [30, 60, 90, 120]);
  assert.equal(large.options.thresholds['http_req_duration{endpoint:create}'], undefined);
  await assert.rejects(load('upload.js', { RTT_BASE_MS: '20', DISK_BUDGET_GB: '0.0001' }), /DISK_BUDGET_GB/);
});

test('gpu_queue.js: lambda = rho x c / T and no flow criteria in overload', async () => {
  const g1 = await load('gpu_queue.js', { RTT_BASE_MS: '20', RHO: '0.7', SLOTS: '1', T_SEC: '10' });
  assert.equal(g1.options.scenarios.queue.rate, 252);
  assert.deepEqual(g1.options.thresholds.load_flowTimeoutRatio, ['rate==0']);
  const g3 = await load('gpu_queue.js', { RTT_BASE_MS: '20', RHO: '1.5', SLOTS: '1', T_SEC: '10' });
  assert.equal(g3.options.scenarios.queue.rate, 540);
  assert.equal(g3.options.thresholds.load_flowTimeoutRatio, undefined);
});

test('api_staged.js: stage tags follow the clock since the scenario start', async () => {
  const mod = await load('api_staged.js', { RTT_BASE_MS: '20' }, { clock: 61000, scenarioStartTime: 0 });
  state.handler = fakeServer({ completeAfterPolls: 0 });
  mod.flow();
  assert.deepEqual([state.requests[0].tags.stage, state.requests[0].tags.phase], ['0', 'hold']);
  mod.browse();
  assert.equal(state.requests[state.requests.length - 1].tags.endpoint, 'healthz');
});

test('poll_saturation.js: seeds completed jobs in setup and polls them', async () => {
  const mod = await load('poll_saturation.js', { RTT_BASE_MS: '20', SEED_COMPLETED: '3', IDS_OUT: join(TMP, 'ids.json') }, { clock: 0 });
  state.handler = fakeServer({ completeAfterPolls: 1 });
  const data = mod.setup();
  assert.equal(data.completed.length, 3);
  mod.poll(data);
  const last = state.requests[state.requests.length - 1];
  assert.equal(last.tags.endpoint, 'poll');
  assert.equal(last.tags.jobState, 'completed');
  const out = mod.handleSummary({ metrics: {}, setup_data: data, state: {} });
  assert.deepEqual(JSON.parse(out[join(TMP, 'ids.json')]).completed, data.completed);
});

test('asset.js: setup refuses a 0 B sample asset', async () => {
  const mod = await load('asset.js', { RTT_BASE_MS: '20', ASSET_JOBS: '2' }, { clock: 0 });
  state.handler = fakeServer({ completeAfterPolls: 0, assetBytes: 0 });
  assert.throws(() => mod.setup(), /MIN_ASSET_BYTES/);
});
