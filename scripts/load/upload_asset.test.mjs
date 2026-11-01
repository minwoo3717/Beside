// A3 (upload.js) and A4 (asset.js): refused uploads fail the run and the hold, upload bytes count only accepted bodies,
// A3-large reports its create latency per hold, and PRE_VUS sizes the pre-allocated VUs.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { BASE_ENV, PHOTO, fakeServer, lib, load, samples, state } from './testing/harness.mjs';

const SHORT = { RTT_BASE_MS: '20', RATES: '1,2', RAMP_S: '5', HOLD_S: '10' };

test('upload.js: a refused upload (unexpected 4xx or a 429/503 rejection) fails the run and the hold, small and large', async () => {
  const small = await load('upload.js', { RTT_BASE_MS: '20' });
  const large = await load('upload.js', { RTT_BASE_MS: '20', SIZE: 'large', LARGE_PHOTO: PHOTO });
  [small, large].forEach((mod) => {
    const th = mod.options.thresholds;
    assert.deepEqual(th.load_unexpectedCount, ['count==0']);
    assert.deepEqual(th.load_rejectedCount, ['count==0']);
    assert.deepEqual(th['load_unexpectedCount{stage:0,phase:hold}'], ['count==0']);
    assert.deepEqual(th['load_rejectedCount{stage:0,phase:hold}'], ['count==0']);
    // 5xx and status 0 keep the §8 budget in load_errorRatio, not a second zero rule
    assert.deepEqual(th['load_errorRatio{stage:0,phase:hold}'], ['rate<0.005']);
    assert.equal(th.load_errorCount, undefined);
  });
  assert.deepEqual(small.options.thresholds['load_unexpectedCount{stage:5,phase:hold}'], ['count==0']);
  assert.deepEqual(large.options.thresholds['load_rejectedCount{stage:3,phase:hold}'], ['count==0']);
});

test('upload.js: each refusal is counted once, in the hold it happened in', async () => {
  const mod = await load('upload.js', SHORT, { clock: 7000, scenarioStartTime: 0 }); // 7 s: hold 0
  const cases = [
    ['413 from Spring', () => ({ status: 413, body: '{"error":{"code":"PAYLOAD_TOO_LARGE","message":"x"}}' }), 'load_unexpectedCount'],
    ['413 from the proxy', fakeServer({ proxyLimit: 10 }), 'load_unexpectedCount'],
    ['400 bad type', () => ({ status: 400, body: '{"error":{"code":"UNSUPPORTED_IMAGE_TYPE","message":"x"}}' }), 'load_unexpectedCount'],
    ['404 from the proxy', () => ({ status: 404, body: '' }), 'load_unexpectedCount'],
    ['429 rate limited', () => ({ status: 429, body: '{"error":{"code":"RATE_LIMITED","message":"x"}}' }), 'load_rejectedCount'],
    ['503 queue full', () => ({ status: 503, body: '{"error":{"code":"QUEUE_FULL","message":"x"}}' }), 'load_rejectedCount'],
    ['502 from the proxy', () => ({ status: 502, body: '' }), 'load_errorRatio'],
    ['status 0', () => ({ status: 0, body: '' }), 'load_errorRatio'],
  ];
  cases.forEach(([name, handler, metric]) => {
    state.samples = [];
    state.handler = handler;
    mod.upload();
    const unexpected = samples('load_unexpectedCount');
    const rejected = samples('load_rejectedCount');
    const errors = samples('load_errorRatio').filter((s) => s.value === 1);
    assert.equal(unexpected.length + rejected.length + errors.length, 1, `${name}: counted once`);
    const hit = metric === 'load_unexpectedCount' ? unexpected : metric === 'load_rejectedCount' ? rejected : errors;
    assert.equal(hit.length, 1, `${name} -> ${metric}`);
    assert.deepEqual([hit[0].tags.stage, hit[0].tags.phase], ['0', 'hold'], name);
  });
});

test('upload.js: a hold with refused uploads reads "fail" and is the first failing stage', async () => {
  const mod = await load('upload.js', SHORT);
  const ok = { values: { count: 10, med: 5, 'p(95)': 9, 'p(99)': 12, max: 13 }, thresholds: { 'p(95)<1500': { ok: true } } };
  const metrics = {
    'http_req_duration{endpoint:create,stage:0,phase:hold}': ok,
    'http_req_duration{endpoint:create,stage:1,phase:hold}': ok,
    load_unexpectedCount: { values: { count: 20, rate: 1 }, thresholds: { 'count==0': { ok: false } } },
    'load_unexpectedCount{stage:0,phase:hold}': { values: { count: 0, rate: 0 }, thresholds: { 'count==0': { ok: true } } },
    'load_unexpectedCount{stage:1,phase:hold}': { values: { count: 20, rate: 1 }, thresholds: { 'count==0': { ok: false } } },
  };
  const doc = JSON.parse(mod.handleSummary({ metrics, setup_data: {}, state: {} })[BASE_ENV.SUMMARY_PATH]);
  assert.deepEqual(doc.verdict.stages.map((s) => s.status), ['pass', 'fail']);
  assert.equal(doc.verdict.breakingPoint.lastPassingStage, 0);
  assert.equal(doc.verdict.breakingPoint.firstFailingStage, 1);
  assert.equal(doc.verdict.thresholdsOk, false);
  assert.equal(doc.verdict.unexpectedCount, 20);
});

test('flow: load_bytesSent counts only bodies the server took (202), not refused, failed or cut uploads', async () => {
  const mod = await load('upload.js', SHORT, { clock: 7000, scenarioStartTime: 0 });
  [['status 0', () => ({ status: 0, body: '' })], ['500', () => ({ status: 500, body: '' })],
    ['413 from the proxy', fakeServer({ proxyLimit: 10 })], ['429', () => ({ status: 429, body: '{"error":{"code":"RATE_LIMITED"}}' })]]
    .forEach(([name, handler]) => {
      state.samples = [];
      state.handler = handler;
      mod.upload();
      assert.equal(samples('load_bytesSent').length, 0, name);
      assert.equal(samples('load_postMs').length, 1, `${name}: the request is still timed`);
    });
  state.samples = [];
  state.requests = [];
  state.handler = fakeServer();
  mod.upload();
  const sent = samples('load_bytesSent');
  assert.equal(sent.length, 1);
  assert.equal(sent[0].value, Buffer.from(state.requests[0].body).length);
  assert.deepEqual([sent[0].tags.stage, sent[0].tags.phase], ['0', 'hold']);
});

test('upload.js SIZE=large: create latency is kept per hold (reported, not judged), never overall', async () => {
  const large = await load('upload.js', { RTT_BASE_MS: '20', SIZE: 'large', LARGE_PHOTO: PHOTO, RATES: '0.5,1', RAMP_S: '2', HOLD_S: '8' });
  const th = large.options.thresholds;
  assert.deepEqual(th['http_req_duration{endpoint:create,stage:0,phase:hold}'], ['max>=0']);
  assert.deepEqual(th['http_req_duration{endpoint:create,stage:1,phase:hold}'], ['max>=0']);
  assert.equal(th['http_req_duration{endpoint:create}'], undefined);
  const lat = (p95) => ({ values: { count: 4, med: p95 - 20, 'p(95)': p95, 'p(99)': p95 + 5, max: p95 + 6 }, thresholds: { 'max>=0': { ok: true } } });
  const metrics = { 'http_req_duration{endpoint:create,stage:0,phase:hold}': lat(216.74), 'http_req_duration{endpoint:create,stage:1,phase:hold}': lat(313.26) };
  const out = large.handleSummary({ metrics, setup_data: {}, state: {} });
  const doc = JSON.parse(out[BASE_ENV.SUMMARY_PATH]);
  assert.deepEqual(doc.verdict.stages.map((s) => [s.status, s.latency.create.p95]), [['pass', 216.74], ['pass', 313.26]]);
  assert.match(out.stdout, /create=216\.74/);
  // small keeps the judged limits and gets no report-only threshold
  const small = await load('upload.js', SHORT);
  assert.deepEqual(small.options.thresholds['http_req_duration{endpoint:create,stage:0,phase:hold}'], ['p(95)<1500', 'p(99)<3000']);
});

test('upload.js: PRE_VUS is read (default: top rate x 3 s small, x 10 s large) and lifts the default MAX_VUS', async () => {
  const vus = (mod) => [mod.options.scenarios.uploads.preAllocatedVUs, mod.options.scenarios.uploads.maxVUs];
  assert.deepEqual(vus(await load('upload.js', { RTT_BASE_MS: '20' })), [60, 600]); // 20/s x 3 s
  assert.deepEqual(vus(await load('upload.js', { RTT_BASE_MS: '20', SIZE: 'large', LARGE_PHOTO: PHOTO })), [20, 600]); // 2/s x 10 s
  assert.deepEqual(vus(await load('upload.js', { RTT_BASE_MS: '20', PRE_VUS: '50' })), [50, 600]);
  assert.deepEqual(vus(await load('upload.js', { RTT_BASE_MS: '20', SIZE: 'large', LARGE_PHOTO: PHOTO, PRE_VUS: '50', MAX_VUS: '2000' })), [50, 2000]);
  assert.deepEqual(vus(await load('upload.js', { RTT_BASE_MS: '20', PRE_VUS: '1000' })), [1000, 1000]);
  assert.deepEqual(vus(await load('upload.js', { RTT_BASE_MS: '20', MAX_VUS: '10' })), [10, 10]); // the default stays under MAX_VUS
  assert.deepEqual(vus(await load('upload.js', SHORT)), [6, 600]);
  await assert.rejects(load('upload.js', { RTT_BASE_MS: '20', PRE_VUS: '50', MAX_VUS: '10' }), /PRE_VUS 50 must be a whole number from 1 to MAX_VUS 10/);
  await assert.rejects(load('upload.js', { RTT_BASE_MS: '20', PRE_VUS: '2.5' }), /PRE_VUS 2\.5/);
  await assert.rejects(load('upload.js', { RTT_BASE_MS: '20', PRE_VUS: '0' }), /PRE_VUS 0/);
  const mod = await load('upload.js', Object.assign({ PRE_VUS: '7' }, SHORT));
  const doc = JSON.parse(mod.handleSummary({ metrics: {}, setup_data: {}, state: {} })[BASE_ENV.SUMMARY_PATH]);
  assert.deepEqual(doc.meta.vus, { preAllocated: 7, max: 600 });
});

test('asset.js: PRE_VUS is read (default: top rate x the 6 s p99 limit) and lifts the default MAX_VUS', async () => {
  const vus = (mod) => [mod.options.scenarios.downloads.preAllocatedVUs, mod.options.scenarios.downloads.maxVUs];
  assert.deepEqual(vus(await load('asset.js', { RTT_BASE_MS: '20' })), [240, 800]); // 40/s x 6 s
  assert.deepEqual(vus(await load('asset.js', { RTT_BASE_MS: '20', RATES: '1,5' })), [30, 800]);
  assert.deepEqual(vus(await load('asset.js', { RTT_BASE_MS: '20', RATES: '1,3' })), [18, 800]);
  assert.deepEqual(vus(await load('asset.js', { RTT_BASE_MS: '20', PRE_VUS: '40' })), [40, 800]);
  assert.deepEqual(vus(await load('asset.js', { RTT_BASE_MS: '20', PRE_VUS: '2000' })), [2000, 2000]);
  assert.deepEqual(vus(await load('asset.js', { RTT_BASE_MS: '20', MAX_VUS: '100' })), [100, 100]);
  await assert.rejects(load('asset.js', { RTT_BASE_MS: '20', PRE_VUS: '900', MAX_VUS: '800' }), /PRE_VUS 900 must be a whole number from 1 to MAX_VUS 800/);
  const mod = await load('asset.js', { RTT_BASE_MS: '20', RATES: '1,3', PRE_VUS: '12' });
  const doc = JSON.parse(mod.handleSummary({ metrics: {}, setup_data: {}, state: {} })[BASE_ENV.SUMMARY_PATH]);
  assert.deepEqual(doc.meta.vus, { preAllocated: 12, max: 800 });
});

test('summary: the drop note names PRE_VUS as the fix and says MAX_VUS alone does not help', async () => {
  const s = await lib('summary.js');
  const pass = { values: { count: 10, med: 5, 'p(95)': 9, 'p(99)': 12 }, thresholds: { 'p(95)<3000': { ok: true } } };
  const metrics = { 'http_req_duration{endpoint:asset,stage:0,phase:hold}': pass, dropped_iterations: { values: { count: 7 }, thresholds: { 'count==0': { ok: false } } } };
  const report = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'asset.js', scenario: 'A4', stages: [{ index: 0, holdS: 20 }] },
    { runId: 'r', baseUrl: 'b', rttBaseMs: 0, uplinkMbps: null }, 'T1');
  assert.equal(report.verdict.stages[0].status, 'invalid');
  assert.match(report.verdict.breakingPointNote, /raise PRE_VUS \(k6 drops an iteration for every VU it starts mid-test, so MAX_VUS alone does not help\)/);
  assert.doesNotMatch(report.verdict.breakingPointNote, /PRE_VUS\/MAX_VUS/);
});

test('thresholds: report-only latency and "not one" counters', async () => {
  const th = await lib('thresholds.js');
  assert.deepEqual(th.latencyReportThresholds(['create'], 'stage:2,phase:hold'), { 'http_req_duration{endpoint:create,stage:2,phase:hold}': ['max>=0'] });
  assert.deepEqual(th.latencyReportThresholds([], 'stage:2,phase:hold'), {});
  assert.deepEqual(th.zeroCountThresholds(['load_unexpectedCount', 'load_rejectedCount'], null),
    { load_unexpectedCount: ['count==0'], load_rejectedCount: ['count==0'] });
  assert.deepEqual(th.zeroCountThresholds(['load_unexpectedCount'], 'stage:0,phase:hold'), { 'load_unexpectedCount{stage:0,phase:hold}': ['count==0'] });
});
