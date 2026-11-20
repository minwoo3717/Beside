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

// A k6 Counter in the summary; a submetric that a no-op threshold keeps (count>=0) also carries its threshold.
function counter(count, kept) {
  const out = { values: { count, rate: count / 120 } };
  if (kept) out.thresholds = { 'count>=0': { ok: true } };
  return out;
}

// A G1 run of 120 s that created 40 jobs (the requested 0.35/s would be 42), each holding the slot about 2.09 s.
// load_postMs counts every POST /api/v1/jobs sent, load_createdCount the ones answered 202 (a job the server created).
function g1Metrics() {
  return { load_postMs: trend(30, 40), load_createdCount: counter(40), load_serverProcessingMs: trend(2089.325, 40),
    load_serverQueuedMs: trend(1926.15, 40) };
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

  // a G run with retries had failures (injected or not): the queue model does not apply, so no recompute
  const retried = summarize(mod, Object.assign(g1Metrics(), { load_retryMs: trend(20, 3) }), {});
  assert.equal(retried.doc.meta.scenario, 'G1');
  assert.equal(retried.doc.meta.measuredRetries, 3);
  assert.equal(retried.doc.meta.measuredJobsPerHour, 1200);
  assert.equal(retried.doc.meta.recompute, null);
  assert.match(retried.doc.meta.predictionNote,
    /^3 retries: failures were injected or occurred \(an F1a\/F2 run without SCENARIO\?\), so the queue model does not apply/);
  assert.match(retried.text, /^queue G1: .*, 3 retries; mean slot/m);
  assert.match(retried.text, /^ {2}3 retries: failures were injected or occurred \(an F1a\/F2 run without SCENARIO\?\), so no recompute$/m);
  assert.doesNotMatch(retried.text, /recompute: python|WARNING/);
  const once = summarize(mod, Object.assign(g1Metrics(), { load_retryMs: trend(20, 1) }), {});
  assert.equal(once.doc.meta.recompute, null);
  assert.match(once.text, /^queue G1: .*, 1 retry; mean slot/m);
  assert.match(once.text, /^ {2}1 retry: failures were injected/m);

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
  const metrics = { load_postMs: trend(30, 13), load_createdCount: counter(13), load_retryMs: trend(10, 2), load_serverProcessingMs: trend(3099.133, 15),
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

  // F1a as deploy/loadtest/README.md gives it (the G1 knobs, BESIDE_PASSTHROUGH_FAIL_RATE=0.2 on the stub) without
  // SCENARIO: k6 v2.3.0 against the real,prod backend (T_SEC=2, 60 s) created 22 jobs and retried 7 of them. The run is
  // named G1, but its retries take the recompute away (the 22 creates leave out the 7 retries the slot also served).
  const unnamed = await load('gpu_queue.js', Object.assign({}, G1, { DURATION_S: '60' }));
  const f1aMetrics = { load_postMs: trend(30, 22), load_createdCount: counter(22), load_retryMs: trend(15, 7), load_serverProcessingMs: trend(2047.4, 29),
    load_serverQueuedMs: trend(1500, 29) };
  const u = summarize(unnamed, f1aMetrics, { readyAtMs: Date.now() - 62000 });
  assert.equal(u.doc.meta.scenario, 'G1');
  assert.equal(u.doc.meta.measuredJobsPerHour, 1320);
  assert.equal(u.doc.meta.recompute, null);
  assert.match(u.text, /^queue G1: 22 jobs created in 60 s = 1320\/h \(requested 1260\/h\), 7 retries; /m);
  assert.match(u.text, /^ {2}7 retries: failures were injected or occurred \(an F1a\/F2 run without SCENARIO\?\), so no recompute$/m);
  assert.doesNotMatch(u.text, /--queue-rate-per-h/);

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

// The [계획] queue cap (agenda #21): the server refuses a create or a retry with 503 QUEUE_FULL + Retry-After. The
// refusals reach the summary through the submetrics the script keeps with no-op thresholds.
const REFUSED_CREATE = 'load_queueFullCount{endpoint:create}';
const REFUSED_RETRY = 'load_queueFullCount{endpoint:retry}';

function refusedMetrics(posts, refused, refusedRetries) {
  const out = Object.assign(g1Metrics(), { load_postMs: trend(30, posts) });
  out[REFUSED_CREATE] = counter(refused, true);
  out[REFUSED_RETRY] = counter(refusedRetries, true);
  return out;
}

function retryAfterChecks(passes, fails) {
  return { values: { rate: passes / (passes + fails), passes, fails } };
}

test('gpu_queue.js: measuredCreates counts the jobs created (202), not every POST; a refusal takes the recompute away', async () => {
  const mod = await load('gpu_queue.js', G1);
  assert.deepEqual(mod.options.thresholds[REFUSED_CREATE], ['count>=0']);
  assert.deepEqual(mod.options.thresholds[REFUSED_RETRY], ['count>=0']);

  // 45 POSTs: 40 answered 202, 5 refused with 503 QUEUE_FULL although QUEUE_CAP was not set
  const metrics = Object.assign(refusedMetrics(45, 5, 0), { load_retryAfterOkRatio: retryAfterChecks(5, 0) });
  const { doc, text } = summarize(mod, metrics, { readyAtMs: Date.now() - 122600 });
  const m = doc.meta;
  assert.equal(m.measuredCreates, 40);
  assert.equal(m.measuredCreatePosts, 45);
  assert.equal(m.measuredRefused, 5);
  assert.equal(m.measuredRefusedRetries, 0);
  assert.equal(m.measuredJobsPerHour, 1200); // the 40 created jobs, not the 45 POSTs (1350/h)
  assert.deepEqual(m.measuredRetryAfterChecks, { passed: 5, failed: 0 });
  assert.equal(m.queueCap, null);
  assert.equal(m.queueRetryAfterS, null);
  assert.equal(m.recompute, null);
  assert.match(m.predictionNote, /^QUEUE_CAP not set, yet 5 of 45 creates refused with 503 QUEUE_FULL: the server has a cap this run did not record \(set QUEUE_CAP\); the queue model assumes no job is refused/);
  assert.match(text, /^queue G1: 40 jobs created in 120 s = 1200\/h \(requested 1260\/h\), 0 retries; mean slot \(server processingMs\) 2089 ms, mean queuedMs 1926 ms$/m);
  assert.match(text, /^ {2}QUEUE_CAP not set, yet 5 of 45 creates refused with 503 QUEUE_FULL: the server has a cap this run did not record \(set QUEUE_CAP\), so no recompute \(the queue model assumes no job is refused\)$/m);
  assert.match(text, /^ {2}Retry-After on 503 QUEUE_FULL: 5 passed, 0 failed \(want whole seconds >= 1\)$/m);
  assert.doesNotMatch(text, /recompute: python/);

  // only POSTs and no 202: no job was created, so the rate is 0/h, not the POST rate
  const refusedOnly = summarize(mod, { load_postMs: trend(30, 3), [REFUSED_CREATE]: counter(3, true), load_serverProcessingMs: trend(2000, 1) }, {}).doc.meta;
  assert.deepEqual([refusedOnly.measuredCreates, refusedOnly.measuredCreatePosts, refusedOnly.measuredJobsPerHour], [0, 3, 0]);
  assert.equal(refusedOnly.recompute, null);

  // a refused retry alone also counts: the server has a cap
  const retryRefused = summarize(mod, Object.assign(refusedMetrics(40, 0, 1), { load_retryMs: trend(10, 2) }), {});
  assert.equal(retryRefused.doc.meta.measuredRefusedRetries, 1);
  assert.match(retryRefused.text, /^ {2}QUEUE_CAP not set, yet 0 of 40 creates and 1 of 2 retries refused with 503 QUEUE_FULL: /m);

  // no refusal, no cap: the kept submetrics read 0 and the report is as before (recompute, no cap or Retry-After line)
  const plain = summarize(mod, refusedMetrics(40, 0, 0), {});
  assert.deepEqual([plain.doc.meta.measuredRefused, plain.doc.meta.measuredRefusedRetries], [0, 0]);
  assert.deepEqual(plain.doc.meta.measuredRetryAfterChecks, { passed: 0, failed: 0 });
  assert.equal(plain.doc.meta.recompute, 'python scripts/load/model.py --queue-rate-per-h 1200 --queue-service-s 2.089 --queue-slots 1');
  assert.doesNotMatch(plain.text, /QUEUE_CAP|Retry-After/);

  // setup failed: no load ran, every count is null
  const none = summarize(mod, {}, undefined).doc.meta;
  assert.deepEqual([none.measuredCreates, none.measuredCreatePosts, none.measuredRefused, none.measuredRefusedRetries,
    none.measuredRetryAfterChecks], [null, null, null, null, null]);
});

test('gpu_queue.js: QUEUE_CAP is recorded in meta.queueCap and a capped run gets no recompute (no loss in the queue model)', async () => {
  const mod = await load('gpu_queue.js', Object.assign({}, G1, { QUEUE_CAP: '19', QUEUE_RETRY_AFTER: '165' }));
  // the cap never filled (no refusal): still no recompute, because the cap could have refused
  const idle = summarize(mod, refusedMetrics(40, 0, 0), {});
  assert.equal(idle.doc.meta.queueCap, 19);
  assert.equal(idle.doc.meta.queueRetryAfterS, 165);
  assert.equal(idle.doc.meta.measuredRefused, 0);
  assert.equal(idle.doc.meta.recompute, null);
  assert.match(idle.doc.meta.predictionNote,
    /^QUEUE_CAP=19: 0 of 40 creates refused with 503 QUEUE_FULL; the queue model assumes no job is refused \(M\/D\/1 and M\/M\/c have no loss\), so it does not apply and recompute is null$/);
  assert.match(idle.text, /^ {2}QUEUE_CAP=19: 0 of 40 creates refused with 503 QUEUE_FULL, so no recompute \(the queue model assumes no job is refused\)$/m);
  assert.doesNotMatch(idle.text, /recompute: python|Retry-After/);

  // refusals, one with a wrong Retry-After
  const busy = summarize(mod, Object.assign(refusedMetrics(45, 5, 0), { load_retryAfterOkRatio: retryAfterChecks(4, 1) }), {});
  assert.deepEqual(busy.doc.meta.measuredRetryAfterChecks, { passed: 4, failed: 1 });
  assert.match(busy.text, /^ {2}QUEUE_CAP=19: 5 of 45 creates refused with 503 QUEUE_FULL, so no recompute/m);
  assert.match(busy.text, /^ {2}Retry-After on 503 QUEUE_FULL: 4 passed, 1 failed \(want whole seconds >= 1, = QUEUE_RETRY_AFTER 165\)$/m);

  // the cap note comes before the retry note: a capped run with retries names the cap
  const retried = summarize(mod, Object.assign(refusedMetrics(40, 0, 0), { load_retryMs: trend(20, 3) }), {}).doc.meta;
  assert.equal(retried.recompute, null);
  assert.match(retried.predictionNote, /^QUEUE_CAP=19: /);

  // setup failed: the cap is still recorded
  const none = summarize(mod, {}, undefined);
  assert.equal(none.doc.meta.queueCap, 19);
  assert.match(none.text, /^ {2}QUEUE_CAP=19: no load ran, so no recompute/m);

  // a fault run with a cap: the fault line, then the cap line
  const f1b = await load('gpu_queue.js', Object.assign({}, F1B, { QUEUE_CAP: '19' }));
  const fault = summarize(f1b, Object.assign(refusedMetrics(13, 2, 0), { load_createdCount: counter(11), load_retryMs: trend(10, 2) }), {});
  assert.equal(fault.doc.meta.recompute, null);
  assert.match(fault.doc.meta.predictionNote, /^F1b is a fault run/);
  assert.match(fault.text, /^queue F1b: 11 jobs created in 120 s = 330\/h \(requested 540\/h\), 2 retries; fault run, so no queue prediction or recompute\n {2}QUEUE_CAP=19: 2 of 13 creates refused with 503 QUEUE_FULL$/m);

  // QUEUE_CAP=0 is the server's "cap off": recorded as 0, the recompute stays
  const off = await load('gpu_queue.js', Object.assign({}, G1, { QUEUE_CAP: '0' }));
  const o = summarize(off, refusedMetrics(40, 0, 0), {});
  assert.equal(o.doc.meta.queueCap, 0);
  assert.equal(o.doc.meta.recompute, 'python scripts/load/model.py --queue-rate-per-h 1200 --queue-service-s 2.089 --queue-slots 1');
  assert.doesNotMatch(o.text, /QUEUE_CAP/);
  // ... but refusals with the cap said to be off are reported like an unrecorded cap
  assert.match(summarize(off, refusedMetrics(45, 5, 0), {}).doc.meta.predictionNote, /^QUEUE_CAP 0, yet 5 of 45 creates refused/);

  await assert.rejects(load('gpu_queue.js', Object.assign({}, G1, { QUEUE_CAP: '-1' })), /QUEUE_CAP must be a whole number >= 0/);
  await assert.rejects(load('gpu_queue.js', Object.assign({}, G1, { QUEUE_CAP: '2.5' })), /QUEUE_CAP must be a whole number >= 0/);
});
