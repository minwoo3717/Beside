// Pure k6 libs: plan section 2 thresholds, queue predictions, summary verdicts.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { join } from 'node:path';
import { test } from 'node:test';
import { BASE_ENV, HERE, PHOTO, TMP, fakeServer, lib, load, samples, state } from './testing/harness.mjs';

test('thresholds: plan section 2 criteria relative to RTT_base', async () => {
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
