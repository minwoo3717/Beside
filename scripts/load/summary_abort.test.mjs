// How a run ended (setup failure, guard / threshold abort, Ctrl+C), empty stages, the breaking point of non-search
// scenarios and the Prometheus guard (drain, unreadable Prometheus). The k6 data shapes copy what k6 v2.3.0 passed to
// handleSummary in real runs: no abort flag in data.state, no setup_data after setup() threw, and every thresholded
// submetric that got no sample kept with all values 0 and count 0.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { BASE_ENV, lib, load, samples, state } from './testing/harness.mjs';

const CFG = { runId: 'r', baseUrl: 'b', rttBaseMs: 20, uplinkMbps: null, tier: '', promUrl: '', promEnv: 'loadtest',
  guard: { everyS: 15, breaches: 2, maxErrorRatio: 0.05, maxDiskRatio: 0.8, blindAbort: 3 } };
const ENDED = '2026-11-01T12:00:00.000Z';
const ENDED_MS = Date.parse(ENDED);

const PASS = (n) => ({ values: { count: n, med: 5, 'p(95)': 9, 'p(99)': 12 }, thresholds: { 'p(95)<120': { ok: true } } });
const EMPTY = { values: { count: 0, med: 0, 'p(95)': 0, 'p(99)': 0, min: 0, max: 0, avg: 0 }, thresholds: { 'p(95)<120': { ok: true } } };
const FAIL = { values: { count: 10, med: 50, 'p(95)': 900, 'p(99)': 1200 }, thresholds: { 'p(95)<120': { ok: false } } };

// Two levels: stage 0 ramp 0-10 s, hold 10-40 s; stage 1 ramp 40-50 s, hold 50-80 s.
const STAGES = [
  { index: 0, label: '', users: 200, target: 0, holdS: 30, holdEndS: 40 },
  { index: 1, label: '', users: 1000, target: 1, holdS: 30, holdEndS: 80 },
];

function twoStages(stage1) {
  return {
    'http_req_duration{endpoint:poll,stage:0,phase:hold}': PASS(10),
    'http_req_duration{endpoint:poll,stage:1,phase:hold}': stage1,
    http_reqs: { values: { count: 20, rate: 1 } },
  };
}

function ranFor(seconds) {
  return { readyAtMs: ENDED_MS - seconds * 1000, startedAt: 'T0' };
}

test('summary: setup() that threw gives aborted "setup", thresholdsOk false and no "all passed"', async () => {
  const s = await lib('summary.js');
  // k6 v2.3.0 after setup() threw: no setup_data, empty submetrics with ok thresholds
  const metrics = { 'http_req_duration{endpoint:poll,stage:0,phase:hold}': EMPTY };
  const report = s.buildReport({ metrics, state: { testRunDurationMs: 0.5 } },
    { script: 'poll_saturation.js', scenario: 'A2', stages: [{ index: 0, label: '', users: null, target: 5, holdS: 5, holdEndS: 5 }] }, CFG, ENDED);
  const v = report.verdict;
  assert.equal(v.aborted.by, 'setup');
  assert.match(v.aborted.reason, /exit code 107/);
  assert.equal(v.thresholdsOk, false);
  assert.deepEqual(v.failedThresholds, []);
  assert.equal(v.stages[0].status, 'not_run');
  assert.deepEqual(v.stages[0].latency, {}); // count 0 is "no data", not 0 ms
  assert.equal(v.breakingPoint, null);
  const text = s.textReport(report);
  assert.match(text, /^SETUP FAILED: /m);
  assert.match(text, /thresholds: not judged/);
  assert.doesNotMatch(text, /all passed/);
  assert.match(text, /^0 +- +5 +not_run +-$/m);
});

test('summary: a guard abort cuts the running hold: "incomplete", never the last passing stage', async () => {
  const s = await lib('summary.js');
  const metrics = Object.assign(twoStages(PASS(4)), {
    load_guardAbort: { values: { value: 1, min: 1, max: 1 } },
    load_guardErrorRatio: { values: { value: 0.2, min: 0.001, max: 0.2 } },
    load_guardQueries: { values: { count: 8, rate: 0.1 } },
  });
  const report = s.buildReport({ metrics, setup_data: ranFor(60), state: {} },
    { script: 'api_staged.js', scenario: 'A1', stages: STAGES, plannedS: 80 }, Object.assign({}, CFG, { promUrl: 'http://p:9090' }), ENDED);
  const v = report.verdict;
  assert.equal(v.aborted.by, 'guard');
  assert.match(v.aborted.reason, /5xx ratio 0\.2 > GUARD_MAX_ERROR_RATIO 0\.05/);
  assert.equal(v.aborted.atS, 60);
  assert.equal(v.aborted.plannedS, 80);
  assert.deepEqual(v.aborted.cutStages, [1]);
  assert.deepEqual(v.stages.map((x) => x.status), ['pass', 'incomplete']);
  assert.deepEqual(v.stages.map((x) => x.heldS), [30, 10]);
  assert.equal(v.thresholdsOk, false);
  assert.equal(v.breakingPoint, null);
  assert.match(v.breakingPointNote, /aborted \(guard\)/);
  assert.equal(report.meta.loadRanS, 60);
  assert.equal(report.meta.loadPlannedS, 80);
  const text = s.textReport(report);
  assert.match(text, /ABORTED by the Prometheus guard after 60 s of the 80 s planned: .*hold cut short in stage 1/);
  assert.match(text, /breaking point: none — the run was aborted/);
  assert.match(text, /thresholds: none failed before the stop, but the run was cut short: NOT a pass/);
  assert.doesNotMatch(text, /all passed|last passing stage/);
});

test('summary: an abort during the next ramp leaves the finished hold "pass"', async () => {
  const s = await lib('summary.js');
  const metrics = Object.assign(twoStages(EMPTY), { load_guardAbort: { values: { value: 2, min: 2, max: 2 } },
    load_guardDiskRatio: { values: { value: 0.9, min: 0.5, max: 0.9 } } });
  const v = s.buildReport({ metrics, setup_data: ranFor(45), state: {} },
    { script: 'upload.js', scenario: 'A3-small', stages: STAGES, plannedS: 80 }, Object.assign({}, CFG, { promUrl: 'http://p:9090' }), ENDED).verdict;
  assert.match(v.aborted.reason, /disk 90 % used > GUARD_MAX_DISK_RATIO 0\.8/);
  assert.deepEqual(v.aborted.cutStages, []);
  assert.deepEqual(v.stages.map((x) => x.status), ['pass', 'not_run']);
  assert.equal(v.stages[1].heldS, null);
  assert.deepEqual(v.stages[1].latency, {});
});

test('summary: an abortOnFail threshold that stopped the load early is "threshold"; at the planned end it is only a failure', async () => {
  const s = await lib('summary.js');
  const th = await lib('thresholds.js');
  const thresholds = th.errorThresholds(null, true);
  assert.deepEqual(th.abortRules(thresholds), [{ metric: 'load_errorRatio', threshold: 'rate<0.05' }]);
  assert.deepEqual(th.abortRules(th.errorThresholds(null, false)), []);
  const metrics = Object.assign(twoStages(FAIL), {
    load_errorRatio: { values: { rate: 0.08, passes: 8, fails: 92 }, thresholds: { 'rate<0.005': { ok: false }, 'rate<0.05': { ok: false } } },
  });
  const info = { script: 'api_staged.js', scenario: 'A1', stages: STAGES, plannedS: 80, thresholds };
  const early = s.buildReport({ metrics, setup_data: ranFor(70), state: {} }, info, CFG, ENDED).verdict;
  assert.equal(early.aborted.by, 'threshold');
  assert.match(early.aborted.reason, /load_errorRatio rate<0\.05/);
  assert.deepEqual(early.stages.map((x) => x.status), ['pass', 'fail']); // a hold that already failed stays "fail"
  assert.deepEqual(early.aborted.cutStages, [1]);
  assert.equal(early.breakingPoint, null);
  const full = s.buildReport({ metrics, setup_data: ranFor(95), state: {} }, info, CFG, ENDED).verdict;
  assert.equal(full.aborted, null);
  assert.equal(full.thresholdsOk, false);
  assert.deepEqual(full.breakingPoint, { firstFailingStage: 1, lastPassingStage: 0, lastPassingUsers: 200, operatingLimitUsers: 140 });
});

test('summary: a load that stopped early with no guard or threshold behind it is "interrupted" (Ctrl+C)', async () => {
  const s = await lib('summary.js');
  const report = s.buildReport({ metrics: twoStages(PASS(3)), setup_data: ranFor(55.04), state: {} },
    { script: 'api_staged.js', scenario: 'A1', stages: STAGES, plannedS: 80, thresholds: {} }, CFG, ENDED);
  const v = report.verdict;
  assert.equal(v.aborted.by, 'interrupted');
  assert.equal(v.aborted.atS, 55);
  assert.deepEqual(v.stages.map((x) => x.status), ['pass', 'incomplete']);
  assert.equal(v.stages[1].heldS, 5);
  assert.equal(v.thresholdsOk, false);
  assert.match(s.textReport(report), /^STOPPED EARLY after 55 s of the 80 s planned: .*Ctrl\+C/m);
});

test('summary: a run that reached its planned end is not aborted; old data without readyAtMs is judged as before', async () => {
  const s = await lib('summary.js');
  const info = { script: 'api_staged.js', scenario: 'A1', stages: STAGES, plannedS: 80 };
  const ok = s.buildReport({ metrics: twoStages(PASS(10)), setup_data: ranFor(79.5), state: {} }, info, CFG, ENDED);
  assert.equal(ok.verdict.aborted, null); // within the 1 s slack of a normal end
  assert.equal(ok.verdict.thresholdsOk, true);
  assert.deepEqual(ok.verdict.stages.map((x) => x.status), ['pass', 'pass']);
  assert.equal(ok.verdict.breakingPoint.lastPassingStage, 1);
  assert.match(s.textReport(ok), /thresholds: all passed/);
  const old = s.buildReport({ metrics: twoStages(PASS(10)), setup_data: {}, state: {} }, info, CFG, 'T1');
  assert.equal(old.verdict.aborted, null);
  assert.equal(old.meta.loadRanS, null);
  // a guard abort without the times: the last stage that got samples may have been cut, so it is not a pass
  const metrics = Object.assign(twoStages(PASS(10)), { load_guardAbort: { values: { value: 1, min: 1, max: 1 } } });
  const blind = s.buildReport({ metrics, setup_data: {}, state: {} }, info, CFG, 'T1').verdict;
  assert.equal(blind.aborted.atS, null);
  assert.deepEqual(blind.stages.map((x) => x.status), ['pass', 'incomplete']);
});

test('summary: empty endpoints and stages report no latency and no Mbps (not 0)', async () => {
  const s = await lib('summary.js');
  const metrics = {
    'http_req_duration{endpoint:create,stage:0,phase:hold}': PASS(10),
    'http_req_duration{endpoint:asset,stage:0,phase:hold}': EMPTY, // an endpoint with no sample in a stage that ran
    'http_req_duration{endpoint:create,stage:1,phase:hold}': EMPTY,
    'load_bytesSent{stage:0,phase:hold}': { values: { count: 3.75e6, rate: 1 }, thresholds: { 'count>=0': { ok: true } } },
    'load_bytesSent{stage:1,phase:hold}': { values: { count: 0, rate: 0 }, thresholds: { 'count>=0': { ok: true } } },
  };
  const report = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'upload.js', scenario: 'A3-small', stages: STAGES }, CFG, 'T1');
  const v = report.verdict;
  assert.deepEqual(Object.keys(v.stages[0].latency), ['create']);
  assert.deepEqual(v.stages[1].latency, {});
  assert.deepEqual(v.stages.map((x) => x.sentMbps), [1, null]); // 3.75 MB x 8 / 30 s; nothing for the stage that never ran
  assert.equal(v.peakStage, 0);
  const text = s.textReport(report);
  assert.match(text, /^0 .* pass +create=9$/m);
  assert.match(text, /^1 .* not_run +-$/m);
  assert.doesNotMatch(text, /=0\b/);
  // a cut hold's upload Mbps is over the seconds it ran, not the planned holdS
  const cut = s.buildReport({ metrics: Object.assign({}, metrics, { 'load_bytesSent{stage:1,phase:hold}': { values: { count: 1.25e6, rate: 1 } },
    'http_req_duration{endpoint:create,stage:1,phase:hold}': PASS(2) }), setup_data: ranFor(60), state: {} },
  { script: 'upload.js', scenario: 'A3-small', stages: STAGES, plannedS: 80, thresholds: {} }, CFG, ENDED).verdict;
  assert.equal(cut.stages[1].status, 'incomplete');
  assert.equal(cut.stages[1].sentMbps, 1); // 1.25 MB x 8 / 10 s
});

test('summary: A5/A6 have no breaking point; the 70 % operating limit is exact', async () => {
  const s = await lib('summary.js');
  const metrics = {
    'http_req_duration{endpoint:poll,stage:0,phase:hold}': PASS(10),
    'http_req_duration{endpoint:poll,stage:1,phase:hold}': PASS(10),
    'http_req_duration{endpoint:poll,stage:2,phase:hold}': FAIL,
  };
  const stages = [1800, 5400, 1800].map((u, i) => ({ index: i, users: u, target: i, holdS: 600, label: '' }));
  const r = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'api_staged.js', scenario: 'A5', stages, breakingPoint: false }, CFG, 'T1');
  assert.equal(r.verdict.breakingPoint, null);
  assert.match(r.verdict.breakingPointNote, /not an increasing search/);
  assert.match(s.textReport(r), /breaking point: none — the levels are not an increasing search/);
  [[5400, 3780], [360, 252], [180, 126], [200, 140], [25, 17]].forEach(([users, limit]) => {
    assert.equal(s.breakingPoint([{ stage: 0, users, status: 'pass' }]).operatingLimitUsers, limit, `${users} users`);
  });
});

test('api_staged.js: handleSummary of A5 and A6 has no breaking point, A1 has one; stages carry holdEndS', async () => {
  const data = (mod) => ({ metrics: { 'http_req_duration{endpoint:poll,stage:0,phase:hold}': PASS(5) },
    setup_data: { readyAtMs: Date.now() - 1e9 }, state: {} });
  for (const [scenario, expectNull] of [['spike', true], ['soak', true], ['staged', false]]) {
    const mod = await load('api_staged.js', { SCENARIO: scenario, RTT_BASE_MS: '20' });
    const doc = JSON.parse(mod.handleSummary(data(mod))[BASE_ENV.SUMMARY_PATH]);
    assert.equal(doc.verdict.breakingPoint === null, expectNull, scenario);
    assert.equal(doc.verdict.aborted, null, scenario);
    assert.equal(doc.meta.loadPlannedS, doc.meta.stages[doc.meta.stages.length - 1].holdEndS, scenario);
  }
  const a1 = await load('api_staged.js', { RTT_BASE_MS: '20', USERS: '25,200', RAMP_S: '10', HOLD_S: '30' });
  const doc = JSON.parse(a1.handleSummary(data(a1))[BASE_ENV.SUMMARY_PATH]);
  assert.deepEqual(doc.meta.stages.map((x) => x.holdEndS), [40, 80]);
  assert.equal(doc.meta.loadPlannedS, 80);
});

test('scripts: setup stamps readyAtMs and handleSummary passes the planned end and the abortOnFail rules', async () => {
  const s = await lib('summary.js');
  const stamped = s.markReady({ server: {} });
  assert.equal(typeof stamped.readyAtMs, 'number');
  // poll_saturation.js: the last step ends at (n-1) x (hold + gap) + hold; a summary 3 s in with no guard is "interrupted"
  const ps = await load('poll_saturation.js', { RTT_BASE_MS: '20', RPS: '5,5', HOLD_S: '6', GAP_S: '2' });
  const out = JSON.parse(ps.handleSummary({ metrics: { 'http_req_duration{endpoint:poll,stage:0,phase:hold}': PASS(10) },
    setup_data: { readyAtMs: Date.now() - 3000, completed: [], pending: [] }, state: {} })[BASE_ENV.SUMMARY_PATH]);
  assert.equal(out.meta.loadPlannedS, 14);
  assert.deepEqual(out.meta.stages.map((x) => x.holdEndS), [6, 14]);
  assert.equal(out.verdict.aborted.by, 'interrupted');
  assert.deepEqual(out.verdict.stages.map((x) => x.status), ['incomplete', 'not_run']);
  // the scripts without stages judge the run's length against their duration
  const smoke = await load('smoke.js', { SMOKE_DURATION_S: '20' });
  const sm = JSON.parse(smoke.handleSummary({ metrics: {}, setup_data: { readyAtMs: Date.now() - 25000 }, state: {} })[BASE_ENV.SUMMARY_PATH]);
  assert.equal(sm.meta.loadPlannedS, 20);
  assert.equal(sm.verdict.aborted, null);
  const gq = await load('gpu_queue.js', { RTT_BASE_MS: '20', DURATION_S: '60' });
  const g = JSON.parse(gq.handleSummary({ metrics: { load_errorRatio: { values: { rate: 0.5 }, thresholds: { 'rate<0.005': { ok: false }, 'rate<0.05': { ok: false } } } },
    setup_data: { readyAtMs: Date.now() - 30000 }, state: {} })[BASE_ENV.SUMMARY_PATH]);
  assert.equal(g.verdict.aborted.by, 'threshold');
  const f3 = await load('failure/f3_uploads.js', { RTT_BASE_MS: '20', F3_BG_S: '30' });
  assert.equal(JSON.parse(f3.handleSummary({ metrics: {}, setup_data: { readyAtMs: Date.now() - 50000 }, state: {} })[BASE_ENV.SUMMARY_PATH]).meta.loadPlannedS, 40);
});

test('asset.js: a hold that never ran has assetMbps null; one that ran and delivered nothing keeps 0', async () => {
  const mod = await load('asset.js', { RTT_BASE_MS: '20', RATES: '1,2', RAMP_S: '1', HOLD_S: '10' });
  const metrics = {
    'http_req_duration{endpoint:asset,stage:0,phase:hold}': PASS(8),
    'load_assetBytes{stage:0,phase:hold}': { values: { count: 0, rate: 0 }, thresholds: { 'count>=0': { ok: true } } },
    'http_req_duration{endpoint:asset,stage:1,phase:hold}': EMPTY,
    'load_assetBytes{stage:1,phase:hold}': { values: { count: 0, rate: 0 }, thresholds: { 'count>=0': { ok: true } } },
  };
  const doc = JSON.parse(mod.handleSummary({ metrics, setup_data: { jobs: [{ asset: { bytes: 1 } }] }, state: {} })[BASE_ENV.SUMMARY_PATH]);
  assert.deepEqual(doc.meta.stages.map((x) => x.assetMbps), [0, null]);
  assert.deepEqual(doc.meta.stages.map((x) => x.holdEndS), [11, 22]);
});

test('guard: one iteration whose length follows the load, not the drain upper bound', async () => {
  const st = await load('api_staged.js', { RTT_BASE_MS: '20', USERS: '25,200', RAMP_S: '10', HOLD_S: '30', GRACEFUL_STOP_S: '30',
    PROM_URL: 'http://localhost:9090' });
  assert.deepEqual(st.options.scenarios.guard, { executor: 'per-vu-iterations', exec: 'guard', vus: 1, iterations: 1, maxDuration: '110s', gracefulStop: '0s' });
  const gq = await load('gpu_queue.js', { RTT_BASE_MS: '20', DURATION_S: '60', PROM_URL: 'http://localhost:9090' });
  assert.equal(gq.options.scenarios.guard.maxDuration, '780s');
  const ps = await load('poll_saturation.js', { RTT_BASE_MS: '20', RPS: '5,5', HOLD_S: '6', GAP_S: '2', PROM_URL: 'http://localhost:9090' });
  assert.equal(ps.options.scenarios.guard.maxDuration, '24s');
  const none = await load('upload.js', { RTT_BASE_MS: '20' });
  assert.equal(none.options.scenarios.guard, undefined);
});

// A fake Prometheus for lib/guard.js: answer(kind) returns { status, body } for kind 'error' or 'disk'.
function prometheus(answer) {
  return (req) => {
    const q = decodeURIComponent(new URL(req.url).searchParams.get('query') || '');
    return answer(q.indexOf('disk_free_bytes') >= 0 ? 'disk' : 'error', req);
  };
}

function vector(v) {
  return { status: 200, body: JSON.stringify({ status: 'success', data: { resultType: 'vector', result: v === null ? [] : [{ metric: {}, value: [0, String(v)] }] } }) };
}

async function guardModule(env) {
  const mod = await load('lib/guard.js', Object.assign({ PROM_URL: 'http://127.0.0.1:9090' }, env || {}), { clock: 0, requestMs: 5 });
  const config = await import('./lib/config.js');
  return { mod, cfg: config.parseConfig(Object.assign({}, BASE_ENV, { PROM_URL: 'http://127.0.0.1:9090' }, env || {})) };
}

function captureWarnings(fn) {
  const real = console.warn;
  const out = [];
  console.warn = (msg) => out.push(String(msg));
  try {
    return { result: fn(), warnings: out };
  } finally {
    console.warn = real;
  }
}

test('guard: guardLoop returns once the load is over, and keeps guarding the drain while flows are in flight', async () => {
  const { mod, cfg } = await guardModule({ GUARD_EVERY_S: '10' });
  state.handler = prometheus((kind) => vector(kind === 'disk' ? 0.3 : null));
  mod.guardLoop(cfg, 30);
  assert.ok(Date.now() >= 30000 && Date.now() < 31000, `returned at ${Date.now()} ms`);
  assert.equal(samples('load_guardQueries').length, 8); // checks at 0, 10, 20, 30 s
  assert.deepEqual(samples('load_guardErrorRatio').map((x) => x.value), [0, 0, 0, 0]); // empty 5xx result = no 5xx
  // flows still in flight after the last stage: the guard goes on until only its own VU is left
  const again = await guardModule({ GUARD_EVERY_S: '10' });
  state.vusActive = 4;
  state.handler = prometheus((kind) => {
    if (state.clock > 45000) state.vusActive = 1;
    return vector(kind === 'disk' ? 0.3 : 0);
  });
  again.mod.guardLoop(again.cfg, 30);
  assert.ok(Date.now() >= 50000 && Date.now() < 51000, `returned at ${Date.now()} ms`);
});

test('guard: 5xx and disk aborts leave their reason in load_guardAbort', async () => {
  const errors = await guardModule();
  state.handler = prometheus((kind) => vector(kind === 'disk' ? 0.3 : 0.2));
  errors.mod.guardCheck(errors.cfg);
  assert.throws(() => errors.mod.guardCheck(errors.cfg), /test aborted: guard: server 5xx ratio 0\.200 > 0\.05 over 1m \(2 checks\)/);
  assert.deepEqual(samples('load_guardAbort').map((x) => x.value), [1]);
  const disk = await guardModule();
  state.handler = prometheus((kind) => vector(kind === 'disk' ? 0.9 : 0));
  assert.throws(() => disk.mod.guardCheck(disk.cfg), /test aborted: guard: disk 90\.0 % > 80 %/);
  assert.deepEqual(samples('load_guardAbort').map((x) => x.value), [2]);
});

test('guard: an unreadable Prometheus is counted and warned about; from the start it aborts, later it does not', async () => {
  const start = await guardModule();
  state.handler = () => ({ status: 500, body: '{"status":"error"}' });
  const first = captureWarnings(() => { start.mod.guardCheck(start.cfg); start.mod.guardCheck(start.cfg); });
  assert.equal(first.warnings.length, 1); // once per streak, not per query
  assert.match(first.warnings[0], /cannot read Prometheus: HTTP 500 from http:\/\/127\.0\.0\.1:9090.*NOT guarded/);
  assert.throws(() => start.mod.guardCheck(start.cfg), /could not be read in the first 3 checks .*unset PROM_URL/);
  assert.equal(samples('load_guardFailures').length, 6);
  assert.deepEqual(samples('load_guardLastFailure').map((x) => x.value).slice(-1), [500]);
  assert.deepEqual(samples('load_guardAbort').map((x) => x.value), [3]);

  const later = await guardModule();
  let broken = false;
  state.handler = prometheus((kind) => (broken ? { status: 200, body: '<html>grafana</html>' } : vector(kind === 'disk' ? 0.3 : 0)));
  later.mod.guardCheck(later.cfg);
  broken = true;
  const mid = captureWarnings(() => { for (let i = 0; i < 6; i++) later.mod.guardCheck(later.cfg); });
  assert.equal(mid.warnings.length, 1);
  assert.match(mid.warnings[0], /not a Prometheus query result/);
  assert.equal(samples('load_guardAbort').length, 0);
  broken = false;
  const back = captureWarnings(() => later.mod.guardCheck(later.cfg));
  assert.match(back.warnings[0], /answers again after 6 unreadable check/);

  // GUARD_BLIND_ABORT=0: never abort for an unreadable Prometheus; an empty disk result counts as unreadable
  const off = await guardModule({ GUARD_BLIND_ABORT: '0' });
  state.handler = prometheus(() => vector(null));
  captureWarnings(() => { for (let i = 0; i < 5; i++) off.mod.guardCheck(off.cfg); });
  assert.equal(samples('load_guardFailures').length, 5);
  assert.deepEqual(samples('load_guardLastFailure').map((x) => x.value).slice(-1), [-2]);
});

test('summary: verdict.guard reports queries, unreadable answers and the readings', async () => {
  const s = await lib('summary.js');
  const cfg = Object.assign({}, CFG, { promUrl: 'http://127.0.0.1:9090' });
  const metrics = {
    load_guardQueries: { values: { count: 12, rate: 0.2 } },
    load_guardFailures: { values: { count: 4, rate: 0.1 } },
    load_guardLastFailure: { values: { value: 500, min: 500, max: 500 } },
    load_guardErrorRatio: { values: { value: 0.001, min: 0, max: 0.0123 } },
    load_guardDiskRatio: { values: { value: 0.31, min: 0.3, max: 0.31 } },
  };
  const report = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'smoke.js', scenario: 'A0', stages: [] }, cfg, 'T1');
  assert.deepEqual(report.verdict.guard, { promUrl: 'http://127.0.0.1:9090', env: 'loadtest', queries: 12, failures: 4,
    lastError: 'HTTP 500', maxErrorRatio: 0.0123, maxDiskRatio: 0.31 });
  assert.equal(report.verdict.thresholdsOk, true); // a blind guard is reported, it does not fail the load's result
  assert.match(s.textReport(report), /guard http:\/\/127\.0\.0\.1:9090: 12 queries, 4 unreadable \(last: HTTP 500\): the run was NOT fully guarded/);
  const off = s.buildReport({ metrics: {}, setup_data: {}, state: {} }, { script: 'smoke.js', scenario: 'A0', stages: [] }, CFG, 'T1');
  assert.equal(off.verdict.guard, null);
  assert.doesNotMatch(s.textReport(off), /^guard /m);
});
