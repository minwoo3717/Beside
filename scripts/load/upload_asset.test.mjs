// A3 (upload.js): refused uploads fail the run and the hold, upload bytes count only accepted bodies, and A3-large
// reports its create latency per hold.
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

test('thresholds: report-only latency and "not one" counters', async () => {
  const th = await lib('thresholds.js');
  assert.deepEqual(th.latencyReportThresholds(['create'], 'stage:2,phase:hold'), { 'http_req_duration{endpoint:create,stage:2,phase:hold}': ['max>=0'] });
  assert.deepEqual(th.latencyReportThresholds([], 'stage:2,phase:hold'), {});
  assert.deepEqual(th.zeroCountThresholds(['load_unexpectedCount', 'load_rejectedCount'], null),
    { load_unexpectedCount: ['count==0'], load_rejectedCount: ['count==0'] });
  assert.deepEqual(th.zeroCountThresholds(['load_unexpectedCount'], 'stage:0,phase:hold'), { 'load_unexpectedCount{stage:0,phase:hold}': ['count==0'] });
});
