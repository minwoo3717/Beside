// Pure k6 libs: docs/perf/load/TRAFFIC_MODEL.md §8 thresholds, queue predictions, summary verdicts.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { join } from 'node:path';
import { test } from 'node:test';
import { BASE_ENV, HERE, PHOTO, TMP, fakeServer, lib, load, samples, state } from './testing/harness.mjs';

test('thresholds: TRAFFIC_MODEL §8 criteria relative to RTT_base', async () => {
  const th = await lib('thresholds.js');
  const t = th.stagedThresholds({ rttMs: 20, endpoints: ['healthz', 'create', 'poll', 'asset'], flows: true, scopes: [{ scope: 'stage:3,phase:hold' }] });
  assert.deepEqual(t['http_req_duration{endpoint:healthz}'], ['p(95)<70', 'p(99)<170']);
  assert.deepEqual(t['http_req_duration{endpoint:poll,stage:3,phase:hold}'], ['p(95)<120', 'p(99)<320']);
  assert.deepEqual(t['http_req_duration{endpoint:create}'], ['p(95)<1500', 'p(99)<3000']);
  assert.deepEqual(t['http_req_waiting{endpoint:asset}'], ['p(95)<170']);
  assert.deepEqual(t['http_req_duration{endpoint:asset}'], ['p(95)<3000', 'p(99)<6000']);
  assert.equal(t.load_errorRatio[0], 'rate<0.005');
  assert.deepEqual(t.load_errorRatio[1], { threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '1m' });
  assert.deepEqual(t['load_errorRatio{stage:3,phase:hold}'], ['rate<0.005']);
  assert.deepEqual(t.dropped_iterations, ['count==0']);
  assert.equal(t['load_bytesSent{stage:3,phase:hold}'], undefined); // only with uploads: true
  const up = th.stagedThresholds({ rttMs: 20, endpoints: ['create'], flows: false, uploads: true, scopes: [{ scope: 'stage:3,phase:hold' }] });
  assert.deepEqual(up['load_bytesSent{stage:3,phase:hold}'], ['count>=0']);
  assert.deepEqual(th.bytesSentThresholds([{ scope: 'stage:0,phase:hold' }]), { 'load_bytesSent{stage:0,phase:hold}': ['count>=0'] });
  assert.deepEqual(th.parseMetricName('a{endpoint:poll,stage:3}'), { metric: 'a', tags: { endpoint: 'poll', stage: '3' } });
});

test('queue: predictions agree with model.py', async () => {
  const q = await lib('queue.js');
  assert.ok(Math.abs(q.erlangC(2, 1) - 1 / 3) < 1e-12);
  const p = q.predict(0.7, 1, 10, 600);
  assert.equal(p.jobsPerHour, 252);
  assert.ok(Math.abs(p.wqMMcSec - 23.333333) < 1e-5);
  assert.ok(Math.abs(p.wqMDcSec - 11.666667) < 1e-5);
  assert.equal(q.predict(1.2, 1, 10, 600).wqMMcSec, null);
  assert.ok(Math.abs(q.predict(1.2, 1, 10, 600).queueGrowthPerMin - 1.2) < 1e-9);
  assert.equal(q.qMax(1, 60, 600), 9);
});

test('summary: stage verdicts and breaking point', async () => {
  const s = await lib('summary.js');
  const metrics = {
    'http_req_duration{endpoint:poll,stage:0,phase:hold}': { values: { count: 10, med: 5, 'p(95)': 9, 'p(99)': 12 }, thresholds: { 'p(95)<120': { ok: true } } },
    'http_req_duration{endpoint:poll,stage:1,phase:hold}': { values: { count: 10, med: 5, 'p(95)': 9, 'p(99)': 12 }, thresholds: { 'p(95)<120': { ok: true } } },
    'http_req_duration{endpoint:poll,stage:2,phase:hold}': { values: { count: 10, med: 50, 'p(95)': 900, 'p(99)': 1200 }, thresholds: { 'p(95)<120': { ok: false } } },
    'load_healthzMs': { values: { med: 21.5 } },
    'http_reqs': { values: { count: 30, rate: 3 } },
    'data_sent': { values: { rate: 125000 } },
  };
  const stages = [0, 1, 2, 3].map((i) => ({ index: i, users: [25, 200, 1000, 1800][i], target: i, holdS: 480, label: '' }));
  const v = s.stageVerdicts(metrics, stages);
  assert.deepEqual(v.map((x) => x.status), ['pass', 'pass', 'fail', 'not_run']);
  assert.deepEqual(s.breakingPoint(v), { firstFailingStage: 2, lastPassingStage: 1, lastPassingUsers: 200, operatingLimitUsers: 140 });
  const report = s.buildReport({ metrics, setup_data: { startedAt: 'T0' }, state: { testRunDurationMs: 1 } }, { script: 'x.js', scenario: 'A1', stages },
    { runId: 'r', baseUrl: 'b', rttBaseMs: 20, uplinkMbps: 1, tier: '' }, 'T1');
  assert.equal(report.meta.rttBaseMsMeasured, 21.5);
  assert.equal(report.verdict.dataSentMbps, 1);
  assert.equal(report.verdict.uplinkSaturated, true);
  assert.equal(report.verdict.thresholdsOk, false);
  assert.match(s.textReport(report), /first failing stage 2/);
});

test('summary: dropped iterations fail the stage of their scenario (poll_saturation.js)', async () => {
  const s = await lib('summary.js');
  const pass = { values: { count: 10, med: 5, 'p(95)': 9, 'p(99)': 12 }, thresholds: { 'p(95)<120': { ok: true } } };
  const metrics = {
    'http_req_duration{endpoint:poll,stage:0,phase:hold}': pass,
    'http_req_duration{endpoint:poll,stage:1,phase:hold}': pass,
    'dropped_iterations{scenario:rps0}': { values: { count: 0, rate: 0 }, thresholds: { 'count==0': { ok: true } } },
    'dropped_iterations{scenario:rps1}': { values: { count: 12, rate: 0.04 }, thresholds: { 'count==0': { ok: false } } },
    dropped_iterations: { values: { count: 12, rate: 0.02 }, thresholds: { 'count==0': { ok: false } } },
  };
  const stages = [0, 1].map((i) => ({ index: i, users: null, target: [100, 300][i], holdS: 300, label: '', scenario: `rps${i}` }));
  const v = s.stageVerdicts(metrics, stages);
  assert.deepEqual(v.map((x) => x.status), ['pass', 'fail']);
  assert.deepEqual(v.map((x) => x.dropped), [0, 12]);
  const report = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'poll_saturation.js', scenario: 'A2', stages },
    { runId: 'r', baseUrl: 'b', rttBaseMs: 20, uplinkMbps: null, tier: '' }, 'T1');
  assert.deepEqual(report.verdict.breakingPoint, { firstFailingStage: 1, lastPassingStage: 0, lastPassingUsers: null, operatingLimitUsers: null });
  assert.equal(report.verdict.breakingPointNote, null);
});

test('summary: dropped iterations that no stage can own make the staged result invalid (ramping-arrival-rate)', async () => {
  const s = await lib('summary.js');
  const pass = { values: { count: 10, med: 5, 'p(95)': 9, 'p(99)': 12 }, thresholds: { 'p(95)<120': { ok: true } } };
  const metrics = {
    'http_req_duration{endpoint:poll,stage:0,phase:hold}': pass,
    'http_req_duration{endpoint:poll,stage:1,phase:hold}': pass,
    dropped_iterations: { values: { count: 3, rate: 0.01 }, thresholds: { 'count==0': { ok: false } } },
  };
  const stages = [0, 1].map((i) => ({ index: i, users: [25, 200][i], target: i, holdS: 480, label: '' }));
  const cfg = { runId: 'r', baseUrl: 'b', rttBaseMs: 20, uplinkMbps: null, tier: '' };
  const report = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'api_staged.js', scenario: 'A1', stages }, cfg, 'T1');
  assert.equal(report.verdict.breakingPoint, null);
  assert.match(report.verdict.breakingPointNote, /dropped_iterations 3/);
  assert.deepEqual(report.verdict.stages.map((x) => x.status), ['invalid', 'invalid']);
  assert.match(s.textReport(report), /breaking point: invalid/);
  // without drops the same stages pass
  delete metrics.dropped_iterations;
  const ok = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'api_staged.js', scenario: 'A1', stages }, cfg, 'T1');
  assert.deepEqual(ok.verdict.stages.map((x) => x.status), ['pass', 'pass']);
  assert.equal(ok.verdict.breakingPoint.lastPassingStage, 1);
});

test('summary: drops per scenario name the browse scenario of api_staged.js (BROWSE_PRE_VUS, not PRE_VUS)', async () => {
  const s = await lib('summary.js');
  const th = await lib('thresholds.js');
  assert.deepEqual(th.droppedReportThresholds(['flows', 'browse']),
    { 'dropped_iterations{scenario:flows}': ['count>=0'], 'dropped_iterations{scenario:browse}': ['count>=0'] });
  const pass = { values: { count: 10, med: 5, 'p(95)': 9, 'p(99)': 12 }, thresholds: { 'p(95)<120': { ok: true } } };
  const kept = (count) => ({ values: { count, rate: 0 }, thresholds: { 'count>=0': { ok: true } } });
  const metrics = {
    'http_req_duration{endpoint:poll,stage:0,phase:hold}': pass,
    'http_req_duration{endpoint:poll,stage:1,phase:hold}': pass,
    dropped_iterations: { values: { count: 12, rate: 0.01 }, thresholds: { 'count==0': { ok: false } } },
    'dropped_iterations{scenario:browse}': kept(12),
    'dropped_iterations{scenario:flows}': kept(0),
  };
  const stages = [0, 1].map((i) => ({ index: i, users: [7200, 9000][i], target: i, holdS: 480, label: '' }));
  const cfg = { runId: 'r', baseUrl: 'b', rttBaseMs: 20, uplinkMbps: null, tier: '' };
  const report = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'api_staged.js', scenario: 'A1', stages }, cfg, 'T1');
  const v = report.verdict;
  assert.deepEqual(v.droppedByScenario, { browse: 12, flows: 0 });
  assert.deepEqual(v.stages.map((x) => x.status), ['invalid', 'invalid']); // still no stage can own them
  assert.equal(v.breakingPoint, null);
  assert.match(v.breakingPointNote, /^dropped_iterations 12 > 0 \(browse 12, flows 0\) and k6 cannot tie them to a stage/);
  assert.match(v.breakingPointNote, /the browse drops need BROWSE_PRE_VUS \(api_staged\.js: PRE_VUS sizes the flows scenario only\)/);
  assert.match(s.textReport(report), /dropped_iterations 12 \(browse 12, flows 0\), vus_max/);
  // drops of the flows only: no BROWSE_PRE_VUS hint
  metrics['dropped_iterations{scenario:browse}'] = kept(0);
  metrics['dropped_iterations{scenario:flows}'] = kept(12);
  const flows = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'api_staged.js', scenario: 'A1', stages }, cfg, 'T1');
  assert.doesNotMatch(flows.verdict.breakingPointNote, /BROWSE_PRE_VUS/);
  // data without the per-scenario submetrics (before api_staged.js kept them, gpu_queue.js): null and the old wording
  delete metrics['dropped_iterations{scenario:browse}'];
  delete metrics['dropped_iterations{scenario:flows}'];
  const old = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'api_staged.js', scenario: 'A1', stages }, cfg, 'T1');
  assert.equal(old.verdict.droppedByScenario, null);
  assert.match(old.verdict.breakingPointNote, /^dropped_iterations 12 > 0 and k6 cannot tie them/);
  assert.match(s.textReport(old), /dropped_iterations 12, vus_max/);
  // a one-level-per-scenario script keeps its count==0 submetrics: they are listed too, and its drops stay with their stage
  const a2 = s.buildReport({ metrics: { 'http_req_duration{endpoint:poll,stage:0,phase:hold}': pass,
    'dropped_iterations{scenario:rps0}': { values: { count: 3 }, thresholds: { 'count==0': { ok: false } } },
    dropped_iterations: { values: { count: 3 }, thresholds: { 'count==0': { ok: false } } } }, setup_data: {}, state: {} },
  { script: 'poll_saturation.js', scenario: 'A2', stages: [{ index: 0, target: 100, holdS: 300, label: '', scenario: 'rps0' }] }, cfg, 'T1');
  assert.deepEqual(a2.verdict.droppedByScenario, { rps0: 3 });
  assert.deepEqual(a2.verdict.stages.map((x) => [x.status, x.dropped]), [['fail', 3]]);
});

test('thresholds: the status-class counters stay in the summary per judged scope, the 5xx codes for the whole run', async () => {
  const th = await lib('thresholds.js');
  assert.deepEqual(th.statusCountThresholds('stage:3,phase:hold'), {
    'load_status0Count{stage:3,phase:hold}': ['count>=0'],
    'load_status4xxCount{stage:3,phase:hold}': ['count>=0'],
    'load_status5xxCount{stage:3,phase:hold}': ['count>=0'],
  });
  assert.deepEqual(Object.keys(th.statusCountThresholds(null)), ['load_status0Count', 'load_status4xxCount', 'load_status5xxCount',
    'load_status5xxCount{status:500}', 'load_status5xxCount{status:502}', 'load_status5xxCount{status:503}', 'load_status5xxCount{status:504}']);
  // errorThresholds brings them along (every script judges errors), they never fail and never abort
  const hold = th.errorThresholds('stage:3,phase:hold', false);
  assert.deepEqual(hold['load_status5xxCount{stage:3,phase:hold}'], ['count>=0']);
  assert.equal(hold['load_status5xxCount{status:502}'], undefined);
  assert.deepEqual(hold['load_errorRatio{stage:3,phase:hold}'], ['rate<0.005']);
  const all = th.errorThresholds(null, true);
  assert.deepEqual(all['load_status5xxCount{status:504}'], ['count>=0']);
  assert.deepEqual(th.abortRules(all), [{ metric: 'load_errorRatio', threshold: 'rate<0.05' }]);
  // a drill (ABORT_ON_ERRORS=0) keeps the load on: same budget, no abort rule
  const drill = th.errorThresholds(null, true, true);
  assert.deepEqual(drill.load_errorRatio, ['rate<0.005']);
  assert.deepEqual(th.abortRules(drill), []);
});

test('summary: responses by status class per stage and overall, the 5xx by code (A2 collapse)', async () => {
  const s = await lib('summary.js');
  const pass = { values: { count: 10, med: 5, 'p(95)': 9, 'p(99)': 12 }, thresholds: { 'p(95)<120': { ok: true } } };
  const slow = { values: { count: 10, med: 5000, 'p(95)': 6300, 'p(99)': 8300 }, thresholds: { 'p(95)<120': { ok: false } } };
  const kept = (count) => ({ values: { count, rate: 0 }, thresholds: { 'count>=0': { ok: true } } });
  const perStage = (i, c0, c4, c5) => ({ [`load_status0Count{stage:${i},phase:hold}`]: kept(c0),
    [`load_status4xxCount{stage:${i},phase:hold}`]: kept(c4), [`load_status5xxCount{stage:${i},phase:hold}`]: kept(c5) });
  const metrics = Object.assign({
    'http_req_duration{endpoint:poll,stage:0,phase:hold}': pass,
    'http_req_duration{endpoint:poll,stage:1,phase:hold}': slow,
    load_status0Count: kept(12), load_status4xxCount: kept(0), load_status5xxCount: kept(7286),
    'load_status5xxCount{status:500}': kept(0), 'load_status5xxCount{status:502}': kept(7000),
    'load_status5xxCount{status:503}': kept(0), 'load_status5xxCount{status:504}': kept(280),
  }, perStage(0, 0, 0, 0), perStage(1, 12, 0, 7286), perStage(2, 0, 0, 0));
  const stages = [0, 1, 2].map((i) => ({ index: i, users: null, target: [900, 1200, 1800][i], holdS: 300, label: '' }));
  const cfg = { runId: 'r', baseUrl: 'b', rttBaseMs: 20, uplinkMbps: null, tier: '' };
  const report = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'poll_saturation.js', scenario: 'A2', stages }, cfg, 'T1');
  const v = report.verdict;
  // 7,286 - 7,000 - 280 = 6 other 5xx (e.g. 501): kept apart, not lost
  assert.deepEqual(v.httpStatus, { 0: 12, '4xx': 0, '5xx': 7286, '5xxByCode': { 500: 0, 502: 7000, 503: 0, 504: 280, other: 6 } });
  // the no-op counters do not decide anything: the stage that did not run stays "not_run", with no counts (not zeros)
  assert.deepEqual(v.stages.map((x) => [x.status, x.httpStatus]),
    [['pass', { 0: 0, '4xx': 0, '5xx': 0 }], ['fail', { 0: 12, '4xx': 0, '5xx': 7286 }], ['not_run', null]]);
  assert.equal(v.thresholdsOk, false);
  assert.deepEqual(v.failedThresholds, [{ metric: 'http_req_duration{endpoint:poll,stage:1,phase:hold}', threshold: 'p(95)<120' }]);
  const text = s.textReport(report);
  assert.match(text, /responses by status \(load_status\*Count\): 0 \(no response\) 12, 4xx 0, 5xx 7286 \(502 7000, 504 280, other 6\)\n/);
  assert.match(text, /\n1 +- +1200 +fail +poll=6300  http 0=12 5xx=7286\n/);
  assert.doesNotMatch(text, /\n0 [^\n]*http /);
  // data from before the counters (or an A2 SEED_ONLY run): null, not zeros, and no line
  const old = s.buildReport({ metrics: { 'http_req_duration{endpoint:poll,stage:0,phase:hold}': pass }, setup_data: {}, state: {} },
    { script: 'poll_saturation.js', scenario: 'A2', stages: stages.slice(0, 1) }, cfg, 'T1');
  assert.equal(old.verdict.httpStatus, null);
  assert.equal(old.verdict.stages[0].httpStatus, null);
  assert.doesNotMatch(s.textReport(old), /responses by status/);
  // a clean run keeps the zeros and prints no line
  const clean = s.buildReport({ metrics: { load_status0Count: kept(0), load_status4xxCount: kept(0), load_status5xxCount: kept(0) },
    setup_data: {}, state: {} }, { script: 'smoke.js', scenario: 'A0', stages: [] }, cfg, 'T1');
  assert.deepEqual(clean.verdict.httpStatus, { 0: 0, '4xx': 0, '5xx': 0, '5xxByCode': { 500: 0, 502: 0, 503: 0, 504: 0, other: 0 } });
  assert.doesNotMatch(s.textReport(clean), /responses by status/);
});

test('summary: failed checks and unexpected responses are counted and printed', async () => {
  const s = await lib('summary.js');
  const cfg = { runId: 'r', baseUrl: 'b', rttBaseMs: 20, uplinkMbps: null, tier: '' };
  const metrics = {
    checks: { values: { passes: 10, fails: 990, rate: 0.01 } },
    load_unexpectedCount: { values: { count: 990, rate: 3 }, thresholds: { 'count==0': { ok: false } } },
  };
  const report = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'poll_saturation.js', scenario: 'A2', stages: [] }, cfg, 'T1');
  assert.equal(report.verdict.checksFailed, 990);
  assert.equal(report.verdict.unexpectedCount, 990);
  const text = s.textReport(report);
  assert.match(text, /checks: 990 failed of 1000/);
  assert.match(text, /unexpected responses \(load_unexpectedCount\): 990/);
  const clean = s.textReport(s.buildReport({ metrics: { checks: { values: { passes: 5, fails: 0 } } }, setup_data: {}, state: {} },
    { script: 'x.js', scenario: 'A0', stages: [] }, cfg, 'T1'));
  assert.doesNotMatch(clean, /checks:|unexpected responses/);
});

test('summary: uplink ratio from the busiest stage, whole-run value kept apart', async () => {
  const s = await lib('summary.js');
  const metrics = {
    'http_req_duration{endpoint:create,stage:0,phase:hold}': { values: { count: 10, med: 5, 'p(95)': 9, 'p(99)': 12 }, thresholds: { 'p(95)<1500': { ok: true } } },
    'http_req_duration{endpoint:create,stage:1,phase:hold}': { values: { count: 10, med: 5, 'p(95)': 9, 'p(99)': 12 }, thresholds: { 'p(95)<1500': { ok: true } } },
    // 48 MB in a 480 s hold = 0.8 Mbps; 480 MB = 8 Mbps
    'load_bytesSent{stage:0,phase:hold}': { values: { count: 48e6, rate: 1 }, thresholds: { 'count>=0': { ok: true } } },
    'load_bytesSent{stage:1,phase:hold}': { values: { count: 480e6, rate: 1 }, thresholds: { 'count>=0': { ok: true } } },
    'data_sent': { values: { rate: 125000 } }, // 1 Mbps averaged over the whole run
  };
  const stages = [0, 1].map((i) => ({ index: i, users: null, target: i, holdS: 480, label: '' }));
  const report = s.buildReport({ metrics, setup_data: {}, state: {} }, { script: 'upload.js', scenario: 'A3-small', stages },
    { runId: 'r', baseUrl: 'b', rttBaseMs: 20, uplinkMbps: 10, tier: '' }, 'T1');
  const v = report.verdict;
  assert.equal(v.dataSentMbps, 1);
  assert.equal(v.uplinkRatioWholeRun, 0.1);
  assert.equal(v.peakStageSentMbps, 8);
  assert.equal(v.peakStage, 1);
  assert.equal(v.uplinkRatio, 0.8);
  assert.equal(v.uplinkSaturated, true); // judged on the busiest stage, not on the 0.1 run average
  assert.deepEqual(v.stages.map((x) => x.sentMbps), [0.8, 8]);
  assert.deepEqual(v.stages.map((x) => x.status), ['pass', 'pass']);
  assert.match(s.textReport(report), /peak stage 1 upload 8 Mbps/);
  // no per-stage bytes (e.g. poll_saturation.js): the whole-run value decides
  const flat = s.buildReport({ metrics: { data_sent: { values: { rate: 125000 } } }, setup_data: {}, state: {} },
    { script: 'x.js', scenario: 'A2', stages: [] }, { runId: 'r', baseUrl: 'b', rttBaseMs: 20, uplinkMbps: 10, tier: '' }, 'T1');
  assert.equal(flat.verdict.uplinkRatio, 0.1);
  assert.equal(flat.verdict.peakStageSentMbps, null);
  assert.equal(flat.verdict.uplinkSaturated, false);
});
