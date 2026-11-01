// Entry scripts: options (stages, scenarios, thresholds) and their setup/exec functions.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { writeFileSync } from 'node:fs';
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
  // no-op thresholds keep the per-stage upload bytes in the summary (uplink ratio of the busiest stage)
  assert.deepEqual(th['load_bytesSent{stage:0,phase:hold}'], ['count>=0']);
  assert.deepEqual(th['load_bytesSent{stage:7,phase:hold}'], ['count>=0']);
});

test('api_staged.js A5 spike and A6 soak follow tiers.json', async () => {
  const spike = await load('api_staged.js', { SCENARIO: 'spike', RTT_BASE_MS: '20' });
  assert.deepEqual(spike.options.scenarios.flows.stages, [
    { duration: '60s', target: 3600 }, { duration: '300s', target: 3600 }, { duration: '30s', target: 10800 },
    { duration: '600s', target: 10800 }, { duration: '30s', target: 3600 }, { duration: '600s', target: 3600 }]);
  const soak = await load('api_staged.js', { SCENARIO: 'soak', RTT_BASE_MS: '20', SOAK_HOLD_S: '600', PROM_URL: 'http://localhost:9090' });
  assert.deepEqual(soak.options.scenarios.flows.stages, [{ duration: '60s', target: 3600 }, { duration: '600s', target: 3600 }]);
  // one iteration that returns when the load is over (lib/guard.js guardLoop), not a fixed-length constant-vus guard
  assert.equal(soak.options.scenarios.guard.executor, 'per-vu-iterations');
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
  // a 404 poll is "unexpected", not an error: without these a run where every id is unknown would pass
  assert.deepEqual(mod.options.thresholds.load_unexpectedCount, ['count==0']);
  assert.deepEqual(mod.options.thresholds['load_unexpectedCount{stage:2,phase:hold}'], ['count==0']);
});

test('poll_saturation.js: IDS_FILE ids are checked in setup', async () => {
  const known = ['00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002'];
  const good = join(TMP, 'ids-good.json');
  writeFileSync(good, JSON.stringify({ completed: [known[0]], pending: [known[1]] }));
  const mod = await load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: good }, { clock: 0 });
  const server = fakeServer();
  known.forEach((id) => { server.jobs[id] = { polls: 0, fail: false, status: 'COMPLETED', filenames: [] }; });
  state.handler = server;
  const data = mod.setup();
  assert.equal(data.idsChecked, 2);
  assert.ok(state.requests.every((r) => r.tags.endpoint === 'setup'));

  const other = join(TMP, 'ids-other.json'); // ids of another server or an emptied volume: every GET is 404
  const many = Array.from({ length: 50 }, (_, i) => `00000000-0000-4000-9000-${String(i).padStart(12, '0')}`);
  writeFileSync(other, JSON.stringify({ completed: many, pending: [] }));
  const bad = await load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: other }, { clock: 0 });
  state.handler = fakeServer();
  assert.throws(() => bad.setup(), /20 of 20 sampled IDS_FILE ids did not return 200/);
  assert.equal(state.requests.filter((r) => r.tags.endpoint === 'setup' && r.url.indexOf('/jobs/') > 0).length, 20);

  const empty = join(TMP, 'ids-empty.json');
  writeFileSync(empty, JSON.stringify({ completed: [], pending: [] }));
  const none = await load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: empty }, { clock: 0 });
  state.handler = fakeServer();
  assert.throws(() => none.setup(), /no completed or pending ids/);
});

test('upload.js A3: rates per minute and the disk budget guard', async () => {
  const small = await load('upload.js', { RTT_BASE_MS: '20' });
  assert.deepEqual(small.options.scenarios.uploads.stages.filter((s, i) => i % 2 === 1).map((s) => s.target), [60, 120, 300, 600, 900, 1200]);
  const large = await load('upload.js', { RTT_BASE_MS: '20', SIZE: 'large', LARGE_PHOTO: PHOTO });
  assert.deepEqual(large.options.scenarios.uploads.stages.filter((s, i) => i % 2 === 1).map((s) => s.target), [30, 60, 90, 120]);
  assert.equal(large.options.thresholds['http_req_duration{endpoint:create}'], undefined);
  assert.deepEqual(small.options.thresholds['load_bytesSent{stage:5,phase:hold}'], ['count>=0']);
  assert.deepEqual(large.options.thresholds['load_bytesSent{stage:3,phase:hold}'], ['count>=0']);
  await assert.rejects(load('upload.js', { RTT_BASE_MS: '20', DISK_BUDGET_GB: '0.0001' }), /DISK_BUDGET_GB/);
});

test('gpu_queue.js: lambda = rho x c / T and no flow criteria in overload', async () => {
  // The scenario runs lambda x ARRIVAL_THIN iterations (default 20); one in 20 becomes a flow on average.
  const g1 = await load('gpu_queue.js', { RTT_BASE_MS: '20', RHO: '0.7', SLOTS: '1', T_SEC: '10' });
  assert.equal(g1.options.scenarios.queue.rate, 252 * 20);
  assert.deepEqual(g1.options.thresholds.load_flowTimeoutRatio, ['rate==0']);
  const g3 = await load('gpu_queue.js', { RTT_BASE_MS: '20', RHO: '1.5', SLOTS: '1', T_SEC: '10' });
  assert.equal(g3.options.scenarios.queue.rate, 540 * 20);
  assert.equal(g3.options.thresholds.load_flowTimeoutRatio, undefined);
});

test('gpu_queue.js: Poisson arrivals by thinning, VUs for bursts, prediction recompute in the summary', async () => {
  const g1 = await load('gpu_queue.js', { RTT_BASE_MS: '20', RHO: '0.7', SLOTS: '1', T_SEC: '10' });
  const q = g1.options.scenarios.queue;
  // flows in progress ~ Poisson(m), m = lambda x FLOW_S = 0.07 x (10 + 23.33 + 15): ceil(1.5m + 3 sqrt(1.5m)) + 2
  assert.equal(q.preAllocatedVUs, 14);
  assert.equal(q.maxVUs, 58); // ceil(0.07 x 660 x 1.2) + 2
  const plain = await load('gpu_queue.js', { RTT_BASE_MS: '20', RHO: '0.7', SLOTS: '1', T_SEC: '10', ARRIVAL_THIN: '1' });
  assert.equal(plain.options.scenarios.queue.rate, 252);
  await assert.rejects(load('gpu_queue.js', { RTT_BASE_MS: '20', ARRIVAL_THIN: '2.5' }), /ARRIVAL_THIN/);
  await assert.rejects(load('gpu_queue.js', { RTT_BASE_MS: '20', ARRIVAL_THIN: '0' }), /ARRIVAL_THIN/);

  const again = await load('gpu_queue.js', { RTT_BASE_MS: '20', RHO: '0.7', SLOTS: '1', T_SEC: '10' });
  state.handler = fakeServer({ completeAfterPolls: 0 });
  const realRandom = Math.random;
  try {
    Math.random = () => 0.5; // >= 1/20: this iteration is thinned away
    again.flow();
    assert.equal(state.requests.length, 0);
    Math.random = () => 0.01; // < 1/20: a flow
    again.flow();
  } finally {
    Math.random = realRandom;
  }
  assert.deepEqual(state.requests.map((r) => r.tags.endpoint), ['healthz', 'create', 'poll', 'asset']);

  const out = again.handleSummary({ metrics: { load_serverProcessingMs: { values: { avg: 11800, med: 11700, 'p(95)': 12500, count: 20 } } },
    setup_data: {}, state: {} });
  const doc = JSON.parse(out[BASE_ENV.SUMMARY_PATH]);
  assert.equal(doc.meta.arrivalProcess, 'poisson (thinned x20)');
  assert.equal(doc.meta.arrivalThin, 20);
  assert.equal(doc.meta.iterationsPerHour, 5040);
  assert.deepEqual(doc.meta.measuredProcessingMs, { mean: 11800, med: 11700, p95: 12500, count: 20 });
  assert.equal(doc.meta.recompute, 'python scripts/load/model.py --queue-rate-per-h 252 --queue-service-s 11.8 --queue-slots 1');
  const plainOut = JSON.parse(plain.handleSummary({ metrics: {}, setup_data: {}, state: {} })[BASE_ENV.SUMMARY_PATH]);
  assert.match(plainOut.meta.arrivalProcess, /^deterministic/);
  assert.equal(plainOut.meta.recompute, null);
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
  // each step names its scenario so dropped_iterations{scenario:rpsN} can fail that step
  const summary = JSON.parse(out[BASE_ENV.SUMMARY_PATH]);
  assert.deepEqual(summary.meta.stages.map((s) => s.scenario), ['rps0', 'rps1', 'rps2', 'rps3', 'rps4', 'rps5']);
});

test('asset.js: setup refuses a 0 B sample asset', async () => {
  const mod = await load('asset.js', { RTT_BASE_MS: '20', ASSET_JOBS: '2' }, { clock: 0 });
  state.handler = fakeServer({ completeAfterPolls: 0, assetBytes: 0 });
  assert.throws(() => mod.setup(), /MIN_ASSET_BYTES/);
});
