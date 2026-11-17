// A3 (upload.js) and A4 (asset.js): an unexpected answer to a valid upload fails the run and the hold (429/503
// rejections stay counted apart), upload bytes count only accepted bodies, A3-large reports its create latency per
// hold, each level is its own scenario so drops fail only their level, and PRE_VUS sizes the levels' VUs.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { BASE_ENV, PHOTO, fakeServer, lib, load, samples, state } from './testing/harness.mjs';
import exec from './testing/k6/execution.mjs';

const SHORT = { RTT_BASE_MS: '20', RATES: '1,2', RAMP_S: '5', HOLD_S: '10' };

function levels(mod) {
  return Object.keys(mod.options.scenarios).filter((n) => /^lvl\d+$/.test(n)).map((n) => mod.options.scenarios[n]);
}

test('upload.js: an unexpected answer (4xx) fails the run and the hold; 429/503 rejections and 5xx keep their own rules', async () => {
  const small = await load('upload.js', { RTT_BASE_MS: '20' });
  const large = await load('upload.js', { RTT_BASE_MS: '20', SIZE: 'large', LARGE_PHOTO: PHOTO });
  [small, large].forEach((mod) => {
    const th = mod.options.thresholds;
    assert.deepEqual(th.load_unexpectedCount, ['count==0']);
    assert.deepEqual(th['load_unexpectedCount{stage:0,phase:hold}'], ['count==0']);
    // 429 RATE_LIMITED / 503 QUEUE_FULL are counted apart (TRAFFIC_MODEL.md §8), not a failure: no threshold
    Object.keys(th).forEach((k) => assert.doesNotMatch(k, /load_rejectedCount/));
    // 5xx and status 0 keep the §8 budget in load_errorRatio, not a second zero rule
    assert.deepEqual(th['load_errorRatio{stage:0,phase:hold}'], ['rate<0.005']);
    assert.equal(th.load_errorCount, undefined);
  });
  assert.deepEqual(small.options.thresholds['load_unexpectedCount{stage:5,phase:hold}'], ['count==0']);
  assert.deepEqual(large.options.thresholds['load_unexpectedCount{stage:3,phase:hold}'], ['count==0']);
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

test('upload.js: a hold with unexpected answers reads "fail" and is the first failing stage', async () => {
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

test('stages: levelScenarios runs each level as its own scenario on the buildLevels schedule', async () => {
  const st = await lib('stages.js');
  const lv = st.buildLevels([{ target: 60, rampS: 5, holdS: 10 }, { target: 120, rampS: 5, holdS: 10 }, { target: 180, rampS: 5, holdS: 10 }]);
  const out = st.levelScenarios(lv, 'lvl', 20, (l) => ({ exec: 'x', timeUnit: '1m', preAllocatedVUs: l.target / 60, gracefulStop: '1s' }));
  assert.deepEqual(out.names, ['lvl0', 'lvl1', 'lvl2']);
  assert.deepEqual(out.scenarios.lvl1, { executor: 'ramping-arrival-rate', startTime: '15s', startRate: 60,
    stages: [{ duration: '5s', target: 120 }, { duration: '10s', target: 120 }], exec: 'x', timeUnit: '1m', preAllocatedVUs: 2,
    gracefulStop: '20s' });
  assert.equal(out.scenarios.lvl0.startRate, 0);
  assert.deepEqual(out.startS, { lvl0: 0, lvl1: 15, lvl2: 30 });
  // the same arrivals as one scenario over buildLevels().stages
  let sum = 0;
  out.names.forEach((n) => { sum += st.expectedIterations(out.scenarios[n].stages, 60, out.scenarios[n].startRate); });
  assert.ok(Math.abs(sum - st.expectedIterations(lv.stages, 60, 0)) < 1e-9);
  // windows [0,35) [15,50) [30,65): all three overlap at 30 s -> 1 + 2 + 3
  assert.equal(out.peakPreAllocated, 6);
  assert.equal(st.levelScenarios(lv, 'lvl', 0, () => ({ preAllocatedVUs: 4 })).peakPreAllocated, 4);
  // maxVUs is one cap on the levels that run at once: 12 x 1/6, 12 x 2/6, 12 x 3/6 (sum 12); below the peak, the pre-allocated VUs
  assert.deepEqual(st.shareMaxVUs(out, 12).names.map((n) => out.scenarios[n].maxVUs), [2, 4, 6]);
  assert.deepEqual(st.shareMaxVUs(out, 3).names.map((n) => out.scenarios[n].maxVUs), [1, 2, 3]);
  assert.deepEqual(st.shareMaxVUs(out, 600).names.map((n) => out.scenarios[n].maxVUs), [100, 200, 300]);
  // without a ramp a level starts at its own rate instead of ramping over its hold
  const flat = st.levelScenarios(st.buildLevels([{ target: 60, rampS: 0, holdS: 10 }, { target: 120, rampS: 0, holdS: 10 }]), 'lvl', 5, () => ({}));
  assert.deepEqual([flat.scenarios.lvl1.startRate, flat.scenarios.lvl1.stages], [120, [{ duration: '10s', target: 120 }]]);
  // tags on the whole-run timeline from a level's own start: lvl1 started 15 s into the run
  assert.deepEqual(st.levelTagsAt(lv.segments, out.startS, 'lvl1', 100000, 100000 + 7000), { stage: '1', phase: 'hold' });
  assert.deepEqual(st.levelTagsAt(lv.segments, out.startS, 'lvl1', 100000, 100000 + 2000), { stage: '1', phase: 'ramp' });
  assert.deepEqual(st.levelTagsAt(lv.segments, out.startS, 'lvl2', 100000, 100000 + 60000), { stage: '2', phase: 'drain' });
  assert.deepEqual(st.levelTagsAt(lv.segments, out.startS, 'other', 100000, 100000 + 7000), { stage: '0', phase: 'hold' });
  const th = await lib('thresholds.js');
  assert.deepEqual(th.droppedThresholds(['lvl0', 'lvl1']), { 'dropped_iterations{scenario:lvl0}': ['count==0'], 'dropped_iterations{scenario:lvl1}': ['count==0'] });
});

test('upload.js and asset.js: one scenario per level, drops judged per level, requests tagged with their level', async () => {
  for (const [rel, env, fn] of [['upload.js', SHORT, 'upload'], ['asset.js', SHORT, 'download']]) {
    const mod = await load(rel, env);
    const sc = mod.options.scenarios;
    assert.deepEqual(Object.keys(sc).sort(), ['lvl0', 'lvl1'], rel);
    assert.deepEqual([sc.lvl0.startTime, sc.lvl0.startRate, sc.lvl0.exec, sc.lvl0.timeUnit], ['0s', 0, fn, '1m'], rel);
    assert.deepEqual([sc.lvl1.startTime, sc.lvl1.startRate, sc.lvl1.stages], ['15s', 60, [{ duration: '5s', target: 120 }, { duration: '10s', target: 120 }]], rel);
    const th = mod.options.thresholds;
    assert.deepEqual(th.dropped_iterations, ['count==0'], rel);
    assert.deepEqual(th['dropped_iterations{scenario:lvl1}'], ['count==0'], rel);
    const doc = JSON.parse(mod.handleSummary({ metrics: {}, setup_data: {}, state: {} })[BASE_ENV.SUMMARY_PATH]);
    assert.deepEqual(doc.meta.stages.map((s) => s.scenario), ['lvl0', 'lvl1'], rel);
  }
  // an upload of lvl1 7 s after that scenario started is in hold 1 (15 + 7 = 22 s; hold 1 is 20-30 s)
  const mod = await load('upload.js', SHORT, { clock: 107000, scenarioStartTime: 100000 });
  const name = exec.scenario.name;
  exec.scenario.name = 'lvl1';
  try {
    state.handler = fakeServer();
    mod.upload();
  } finally {
    exec.scenario.name = name;
  }
  assert.deepEqual(samples('load_flowStarted').map((x) => [x.tags.stage, x.tags.phase]), [['1', 'hold']]);
  assert.deepEqual(samples('load_postMs').map((x) => [x.tags.stage, x.tags.phase]), [['1', 'hold']]);
});

test('upload.js and asset.js: a level that drops reads "fail" and only it; the earlier levels keep "pass"', async () => {
  for (const [rel, ep, limit] of [['upload.js', 'create', 'p(95)<1500'], ['asset.js', 'asset', 'p(95)<3000']]) {
    const mod = await load(rel, SHORT);
    const lat = (p95, ok) => ({ values: { count: 10, med: 5, 'p(95)': p95, 'p(99)': p95, max: p95 }, thresholds: { [limit]: { ok } } });
    const drop = (n) => ({ values: { count: n, rate: 0 }, thresholds: { 'count==0': { ok: n === 0 } } });
    const metrics = {
      [`http_req_duration{endpoint:${ep},stage:0,phase:hold}`]: lat(50, true),
      [`http_req_duration{endpoint:${ep},stage:1,phase:hold}`]: lat(8000, false),
      dropped_iterations: drop(4),
      'dropped_iterations{scenario:lvl0}': drop(0),
      'dropped_iterations{scenario:lvl1}': drop(4),
    };
    const doc = JSON.parse(mod.handleSummary({ metrics, setup_data: { jobs: [{ asset: { bytes: 1 } }] }, state: {} })[BASE_ENV.SUMMARY_PATH]);
    const v = doc.verdict;
    assert.deepEqual(v.stages.map((s) => [s.status, s.dropped]), [['pass', 0], ['fail', 4]], rel);
    assert.deepEqual([v.breakingPoint.lastPassingStage, v.breakingPoint.firstFailingStage], [0, 1], rel);
    assert.equal(v.breakingPointNote, null, rel);
    // drops alone (latency within the limit) still fail their level
    metrics[`http_req_duration{endpoint:${ep},stage:1,phase:hold}`] = lat(50, true);
    const again = JSON.parse(mod.handleSummary({ metrics, setup_data: { jobs: [{ asset: { bytes: 1 } }] }, state: {} })[BASE_ENV.SUMMARY_PATH]);
    assert.deepEqual(again.verdict.stages.map((s) => s.status), ['pass', 'fail'], rel);
  }
});

function vusMeta(mod) {
  return JSON.parse(mod.handleSummary({ metrics: {}, setup_data: {}, state: {} })[BASE_ENV.SUMMARY_PATH]).meta.vus;
}

test('upload.js: PRE_VUS is the top level\'s VUs (default: top rate x 3 s small, x 10 s large), lower levels in proportion', async () => {
  const vus = (mod) => levels(mod).map((s) => s.preAllocatedVUs);
  const caps = (mod) => levels(mod).map((s) => s.maxVUs);
  const small = await load('upload.js', { RTT_BASE_MS: '20' });
  assert.deepEqual(vus(small), [3, 6, 15, 30, 45, 60]); // 20/s x 3 s at the top, every level covers 3 s
  // tiers.json A3-small: 150 s levels, 180 s drain -> three levels at once at most (30 + 45 + 60 = 135); MAX_VUS 600
  // is shared so that those three stay within it (133 + 200 + 266 = 599), as the single scenario's 600 did
  assert.deepEqual(vusMeta(small), { preAllocated: 60, max: 600, byLevel: [3, 6, 15, 30, 45, 60], peakPreAllocated: 135,
    maxByLevel: [13, 26, 66, 133, 200, 266] });
  assert.deepEqual(caps(small), [13, 26, 66, 133, 200, 266]);
  const large = await load('upload.js', { RTT_BASE_MS: '20', SIZE: 'large', LARGE_PHOTO: PHOTO });
  assert.deepEqual([vus(large), caps(large)], [[5, 10, 15, 20], [60, 120, 180, 240]]); // 2/s x 10 s; all four levels overlap
  assert.deepEqual(vus(await load('upload.js', { RTT_BASE_MS: '20', PRE_VUS: '50' })), [3, 5, 13, 25, 38, 50]);
  const big = await load('upload.js', { RTT_BASE_MS: '20', SIZE: 'large', LARGE_PHOTO: PHOTO, PRE_VUS: '50', MAX_VUS: '2000' });
  assert.deepEqual([vus(big), caps(big)], [[13, 25, 38, 50], [206, 396, 603, 793]]);
  // an explicit PRE_VUS is used as given and the default MAX_VUS rises to the peak it needs (500 + 750 + 1000)
  const many = await load('upload.js', { RTT_BASE_MS: '20', PRE_VUS: '1000' });
  assert.deepEqual([vus(many), vusMeta(many).max], [[50, 100, 250, 500, 750, 1000], 2250]);
  assert.deepEqual(vus(await load('upload.js', SHORT)), [3, 6]);
  // an explicit MAX_VUS below the peak is refused, not clamped
  await assert.rejects(load('upload.js', { RTT_BASE_MS: '20', MAX_VUS: '10' }), /reach 135 \(PRE_VUS 60 at the top level\) > MAX_VUS 10; raise MAX_VUS or lower PRE_VUS/);
  await assert.rejects(load('upload.js', { RTT_BASE_MS: '20', PRE_VUS: '50', MAX_VUS: '100' }), /reach 113 \(PRE_VUS 50 at the top level\) > MAX_VUS 100/);
  await assert.rejects(load('upload.js', { RTT_BASE_MS: '20', PRE_VUS: '2.5' }), /PRE_VUS 2\.5 must be a whole number of at least 1/);
  await assert.rejects(load('upload.js', { RTT_BASE_MS: '20', PRE_VUS: '0' }), /PRE_VUS 0/);
  // levels of 15 s with a 180 s drain overlap: 4 + 7 VUs at once
  assert.deepEqual(vusMeta(await load('upload.js', Object.assign({ PRE_VUS: '7' }, SHORT))),
    { preAllocated: 7, max: 600, byLevel: [4, 7], peakPreAllocated: 11, maxByLevel: [218, 381] });
});

test('asset.js: PRE_VUS is the top level\'s VUs (default: top rate x the 6 s p99 limit), lower levels in proportion', async () => {
  const vus = (mod) => levels(mod).map((s) => s.preAllocatedVUs);
  const def = await load('asset.js', { RTT_BASE_MS: '20' });
  assert.deepEqual(vus(def), [6, 30, 60, 120, 180, 240]); // 40/s x 6 s at the top
  // tiers.json A4: 210 s levels, 120 s drain -> two neighbouring levels at once (180 + 240 = 420); MAX_VUS 800 shared (342 + 457)
  assert.deepEqual(vusMeta(def), { preAllocated: 240, max: 800, byLevel: [6, 30, 60, 120, 180, 240], peakPreAllocated: 420,
    maxByLevel: [11, 57, 114, 228, 342, 457] });
  assert.deepEqual(levels(def).map((s) => s.maxVUs), [11, 57, 114, 228, 342, 457]);
  assert.deepEqual(vus(await load('asset.js', { RTT_BASE_MS: '20', RATES: '1,5' })), [6, 30]);
  assert.deepEqual(vus(await load('asset.js', { RTT_BASE_MS: '20', RATES: '1,3' })), [6, 18]);
  assert.deepEqual(vus(await load('asset.js', { RTT_BASE_MS: '20', RATES: '1,3', PRE_VUS: '40' })), [14, 40]);
  const many = await load('asset.js', { RTT_BASE_MS: '20', PRE_VUS: '2000' });
  assert.deepEqual([vus(many).slice(-2), vusMeta(many).max], [[1500, 2000], 3500]);
  await assert.rejects(load('asset.js', { RTT_BASE_MS: '20', MAX_VUS: '100' }), /reach 420 \(PRE_VUS 240 at the top level\) > MAX_VUS 100/);
  await assert.rejects(load('asset.js', { RTT_BASE_MS: '20', PRE_VUS: '900', MAX_VUS: '800' }), /reach 1575 \(PRE_VUS 900 at the top level\) > MAX_VUS 800/);
  await assert.rejects(load('asset.js', { RTT_BASE_MS: '20', PRE_VUS: '-3' }), /PRE_VUS -3 must be a whole number of at least 1/);
  assert.deepEqual(vusMeta(await load('asset.js', { RTT_BASE_MS: '20', RATES: '1,3', PRE_VUS: '12' })),
    { preAllocated: 12, max: 800, byLevel: [4, 12], peakPreAllocated: 16, maxByLevel: [200, 600] });
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
  assert.deepEqual(th.zeroCountThresholds(['load_unexpectedCount'], null), { load_unexpectedCount: ['count==0'] });
  assert.deepEqual(th.zeroCountThresholds(['load_unexpectedCount'], 'stage:0,phase:hold'), { 'load_unexpectedCount{stage:0,phase:hold}': ['count==0'] });
});
