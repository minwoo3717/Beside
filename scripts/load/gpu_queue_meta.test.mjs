// gpu_queue.js summary meta: the recompute from the jobs a run really created, the run's name (G1~G3 or a fault run that
// reuses the G1 knobs) and the VU pre-allocation under a small MAX_VUS. The data shapes copy what k6 v2.3.0 passed to
// handleSummary in short G1 / F1b runs against the real,prod backend and the passthrough stub (T_SEC=2).
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';
import { test } from 'node:test';
import { BASE_ENV, HERE, lib, load } from './testing/harness.mjs';

const G1 = { RTT_BASE_MS: '20', RHO: '0.7', SLOTS: '1', T_SEC: '2', DURATION_S: '120' };
const F1B = { RTT_BASE_MS: '20', RHO: '0.3', SLOTS: '1', T_SEC: '2', DURATION_S: '120', FAIL_TOKEN: 'slow', FAIL_RATIO: '0.2' };

function trend(avg, count) {
  return { values: { avg, med: avg, 'p(95)': avg, count } };
}

// A G1 run of 120 s that created 40 jobs (the requested 0.35/s would be 42), each holding the slot about 2.09 s.
function g1Metrics() {
  return { load_postMs: trend(30, 40), load_serverProcessingMs: trend(2089.325, 40), load_serverQueuedMs: trend(1926.15, 40) };
}

function summarize(mod, metrics, setupData) {
  const out = mod.handleSummary({ metrics, setup_data: setupData, state: {} });
  return { doc: JSON.parse(out[BASE_ENV.SUMMARY_PATH]), text: out.stdout };
}

test('queue.js: the realized rate and the run names', async () => {
  const q = await lib('queue.js');
  assert.equal(q.realizedPerHour(40, 120), 1200);
  assert.equal(q.realizedPerHour(70, 150), 1680);
  assert.equal(q.realizedPerHour(0, 120), 0);
  assert.equal(q.realizedPerHour(null, 120), null);
  assert.equal(q.realizedPerHour(40, null), null);
  assert.equal(q.realizedPerHour(40, 0), null);
  const label = (o) => q.scenarioLabel(Object.assign({ scenario: '', rho: 0.7, slots: 1, failRatio: 0, failToken: 'fail' }, o));
  assert.equal(label({}), 'G1');
  assert.equal(label({ slots: 2 }), 'G2');
  assert.equal(label({ rho: 1.5 }), 'G3');
  assert.equal(label({ rho: 1.2 }), 'G1');
  assert.equal(label({ failRatio: 0.2, failToken: 'slow' }), 'F1b');
  assert.equal(label({ failRatio: 0.2 }), 'F1a'); // another token: the stub's BESIDE_PASSTHROUGH_FAIL_TOKEN
  assert.equal(label({ scenario: 'f2' }), 'F2');
  assert.equal(label({ scenario: 'F1a' }), 'F1a');
  assert.equal(label({ scenario: 'g2', slots: 1 }), 'G2');
  assert.throws(() => label({ scenario: 'G4' }), /SCENARIO must be one of G1, G2, G3, F1a, F1b, F2 \(got 'G4'\)/);
  assert.throws(() => label({ scenario: 'G1', failRatio: 0.2 }), /a G run injects no failure/);
  assert.throws(() => label({ scenario: 'F1b' }), /SCENARIO=F1b needs FAIL_RATIO > 0/);
  assert.deepEqual(['G1', 'G2', 'G3', 'F1a', 'F1b', 'F2'].map(q.isQueueRun), [true, true, true, false, false, false]);
});

test('gpu_queue.js: the recompute uses the jobs the run created, not the requested rate', async () => {
  const mod = await load('gpu_queue.js', G1);
  // 122.6 s since setup ended: the load ran its 120 s and the drain, so the arrival window is DURATION_S
  const { doc, text } = summarize(mod, g1Metrics(), { readyAtMs: Date.now() - 122600 });
  const m = doc.meta;
  assert.equal(m.scenario, 'G1');
  assert.equal(m.prediction.jobsPerHour, 1260);
  assert.equal(m.measuredCreates, 40);
  assert.equal(m.measuredRetries, 0); // no load_retryMs in the summary: no retry was sent
  assert.equal(m.measuredArrivalWindowS, 120);
  assert.equal(m.measuredJobsPerHour, 1200);
  assert.equal(m.recompute, 'python scripts/load/model.py --queue-rate-per-h 1200 --queue-service-s 2.089 --queue-slots 1');
  assert.match(m.predictionNote, /measuredJobsPerHour/);
  assert.match(text, /^== beside load: gpu_queue\.js \(G1\) /m);
  assert.match(text, /^queue G1: 40 jobs created in 120 s = 1200\/h \(requested 1260\/h\), 0 retries; mean slot \(server processingMs\) 2089 ms, mean queuedMs 1926 ms$/m);
  assert.match(text, /^ {2}recompute: python scripts\/load\/model\.py --queue-rate-per-h 1200 --queue-service-s 2\.089 --queue-slots 1$/m);
  assert.doesNotMatch(text, /WARNING/);

  // stopped 60 s in: the jobs were created in 60 s, not in DURATION_S
  const early = summarize(mod, g1Metrics(), { readyAtMs: Date.now() - 60000 }).doc.meta;
  assert.ok(early.measuredArrivalWindowS >= 60 && early.measuredArrivalWindowS < 60.5, String(early.measuredArrivalWindowS));
  assert.equal(early.measuredJobsPerHour, Math.round((40 * 3600) / early.measuredArrivalWindowS));

  // a G run with retries says so (they add arrivals the recompute leaves out)
  const retried = summarize(mod, Object.assign(g1Metrics(), { load_retryMs: trend(20, 3) }), {}).text;
  assert.match(retried, /^queue G1: .*, 3 retries;/m);
  assert.match(retried, /^ {2}WARNING: a G run should have no retry/m);

  // setup() failed: no load ran, so no rate and no recompute
  const none = summarize(mod, { load_serverProcessingMs: trend(0, 0) }, undefined);
  assert.equal(none.doc.meta.measuredCreates, null);
  assert.equal(none.doc.meta.measuredArrivalWindowS, null);
  assert.equal(none.doc.meta.measuredJobsPerHour, null);
  assert.equal(none.doc.meta.recompute, null);
  assert.match(none.text, /^queue G1: no load ran; mean slot \(server processingMs\) -, mean queuedMs -$/m);
  assert.match(none.text, /^ {2}recompute: - \(no job created or no processingMs\)$/m);

  // a run that created no job has a rate of 0/h, which model.py cannot take: no recompute
  const idle = summarize(mod, { load_serverProcessingMs: trend(2000, 1) }, {}).doc.meta;
  assert.equal(idle.measuredJobsPerHour, 0);
  assert.equal(idle.recompute, null);
});

test('gpu_queue.js: the recompute command runs in model.py and gives rho = created rate x mean slot / c', async (t) => {
  const mod = await load('gpu_queue.js', G1);
  const cmd = summarize(mod, g1Metrics(), {}).doc.meta.recompute;
  const args = cmd.split(' ').slice(2); // after "python scripts/load/model.py"
  const res = spawnSync('python', ['-I', join(HERE, 'model.py'), ...args, '--json'], { encoding: 'utf-8' });
  if (res.error && res.error.code === 'ENOENT') {
    t.skip('python is not on PATH');
    return;
  }
  assert.equal(res.status, 0, res.stderr);
  const p = JSON.parse(res.stdout);
  assert.equal(p.lambdaPerHour, 1200);
  assert.ok(Math.abs(p.rho - (1200 * 2.089) / 3600) < 1e-9, String(p.rho));
  assert.ok(Math.abs(p.wqMD1Sec - (p.rho * 2.089) / (1 - p.rho) / 2) < 1e-9);
});

test('gpu_queue.js: fault runs carry their own name and get no queue recompute', async () => {
  // F1b: FAIL_TOKEN=slow FAIL_RATIO=0.2. The processing mean holds the timeouts (5 s in the scaled-down check)
  const f1b = await load('gpu_queue.js', F1B);
  const metrics = { load_postMs: trend(30, 13), load_retryMs: trend(10, 2), load_serverProcessingMs: trend(3099.133, 15),
    load_serverQueuedMs: trend(1150.667, 15) };
  const { doc, text } = summarize(f1b, metrics, {});
  assert.equal(doc.meta.scenario, 'F1b');
  assert.equal(doc.meta.recompute, null);
  assert.match(doc.meta.predictionNote, /^F1b is a fault run: the queue model does not apply/);
  assert.equal(doc.meta.measuredCreates, 13);
  assert.equal(doc.meta.measuredRetries, 2);
  assert.equal(doc.meta.measuredJobsPerHour, 390);
  assert.match(text, /^== beside load: gpu_queue\.js \(F1b\) /m);
  assert.match(text, /^queue F1b: 13 jobs created in 120 s = 390\/h \(requested 540\/h\), 2 retries; fault run, so no queue prediction or recompute$/m);
  assert.doesNotMatch(text, /recompute: python/);

  // F1a and F2 are set on the stub / VM side with the G1 knobs: only SCENARIO names them
  const f1a = await load('gpu_queue.js', Object.assign({}, G1, { SCENARIO: 'F1a' }));
  const a = summarize(f1a, g1Metrics(), {}).doc.meta;
  assert.equal(a.scenario, 'F1a');
  assert.equal(a.recompute, null);
  const f2 = await load('gpu_queue.js', Object.assign({}, G1, { SCENARIO: 'f2' }));
  assert.equal(summarize(f2, g1Metrics(), {}).doc.meta.scenario, 'F2');
  // a named G2 run keeps its recompute
  const g2 = await load('gpu_queue.js', Object.assign({}, G1, { SCENARIO: 'G2', SLOTS: '2' }));
  const two = summarize(g2, g1Metrics(), {}).doc.meta;
  assert.equal(two.scenario, 'G2');
  assert.equal(two.recompute, 'python scripts/load/model.py --queue-rate-per-h 1200 --queue-service-s 2.089 --queue-slots 2');

  await assert.rejects(load('gpu_queue.js', Object.assign({}, G1, { SCENARIO: 'G4' })), /SCENARIO must be one of/);
  await assert.rejects(load('gpu_queue.js', Object.assign({}, F1B, { SCENARIO: 'G1' })), /a G run injects no failure/);
  await assert.rejects(load('gpu_queue.js', Object.assign({}, G1, { SCENARIO: 'F1b' })), /needs FAIL_RATIO > 0/);
});

test('gpu_queue.js: a MAX_VUS below the computed pre-allocation caps it instead of a k6 config error', async () => {
  const dflt = await load('gpu_queue.js', G1);
  assert.equal(dflt.options.scenarios.queue.preAllocatedVUs, 24);
  const capped = await load('gpu_queue.js', Object.assign({}, G1, { MAX_VUS: '5' }));
  const q = capped.options.scenarios.queue;
  assert.equal(q.preAllocatedVUs, 5); // k6 v2.3.0 refused 24 > 5 with exit code 104
  assert.equal(q.maxVUs, 5);
  const m = summarize(capped, g1Metrics(), {}).doc.meta;
  assert.deepEqual([m.preAllocatedVUs, m.maxVUs], [5, 5]);
  // a larger MAX_VUS leaves the pre-allocation alone; the documented G1 knobs are unchanged (14 / 58)
  assert.equal((await load('gpu_queue.js', Object.assign({}, G1, { MAX_VUS: '100' }))).options.scenarios.queue.preAllocatedVUs, 24);
  const g1 = await load('gpu_queue.js', { RTT_BASE_MS: '20', RHO: '0.7', SLOTS: '1', T_SEC: '10' });
  assert.deepEqual([g1.options.scenarios.queue.preAllocatedVUs, g1.options.scenarios.queue.maxVUs], [14, 58]);
  await assert.rejects(load('gpu_queue.js', Object.assign({}, G1, { MAX_VUS: '0' })), /MAX_VUS must be a whole number >= 1/);
  await assert.rejects(load('gpu_queue.js', Object.assign({}, G1, { MAX_VUS: '2.5' })), /MAX_VUS must be a whole number >= 1/);
});
