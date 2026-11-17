// A2 polling saturation: GET /api/v1/jobs/{id} at fixed rates 100 -> 300 -> 600 -> 900 -> 1,200 -> 1,800 RPS, each
// held 5 min (tiers.json load.a2), one constant-arrival-rate scenario per step so each step carries its own
// stage tag. The ids are COMPLETED and PENDING jobs (TRAFFIC_MODEL A2: 1,000 of each), from IDS_FILE or seeded in setup
// (SEED_COMPLETED completed jobs, waited for; SEED_PENDING jobs not waited for).
//
// On the mock the two kinds need two backend settings: COMPLETED seeds need mock.worker.pending-ms 0, PENDING seeds a
// long pending-ms, and a backend restart closes every PENDING job as FAILED (InterruptedJobRecovery), which still
// answers 200. So A2 is three runs against the same backend volume, in this order (SEED_ONLY=1 seeds, writes IDS_OUT and
// runs no load; its IDS_OUT is an absolute path in an existing folder):
//   1. on pending-ms 0:
//      k6 run -e BASE_URL=https://<domain> -e RUN_ID=a2seedc -e SEED_ONLY=1 -e SEED_COMPLETED=1000 -e IDS_OUT=/abs/c.json scripts/load/poll_saturation.js
//   2. restart the backend once with a pending-ms longer than steps 2 + 3 (the A2 load is about 33 min), then not again:
//      k6 run -e BASE_URL=https://<domain> -e RUN_ID=a2seedp -e SEED_ONLY=1 -e SEED_PENDING=1000 -e IDS_OUT=/abs/p.json scripts/load/poll_saturation.js
//   3. measure:
//      k6 run -e BASE_URL=https://<domain> -e RUN_ID=a2r1 -e RTT_BASE_MS=23 -e IDS_FILE=/abs/c.json,/abs/p.json scripts/load/poll_saturation.js
// A seeding run with SEED_PENDING but no COMPLETED seed and no SEED_ONLY is refused (it would run the whole load on PENDING
// ids only) unless PENDING_SHARE=1 asks for that.
// Setup GETs about 20 of the ids (10 + 10 when both lists have ids) and refuses to start unless each answers 200 with
// its list's state (completed -> COMPLETED, pending -> PENDING); seeded PENDING jobs are checked the same way just after
// they are created. teardown() GETs up to 20 pending ids again: load_pendingLeft (ids no longer PENDING because
// pending-ms ran out or the backend restarted during the run) must be 0. load_unexpectedCount (non-200 polls such as
// 404) must stay 0 overall and in every step: a fast 404 would otherwise pass the latency limit and leave
// load_errorRatio at 0.
//
// Env: RPS=100,300 (subset), HOLD_S, GAP_S (30 s between steps), IDS_FILE (one or more absolute paths, comma-separated,
//      each {"completed": [...], "pending": [...]}; the lists are joined), IDS_OUT (write the seeded ids; its folder must
//      exist, checked before seeding in a SEED_ONLY run), SEED_ONLY (1: seed, write IDS_OUT and stop, no load),
//      SEED_COMPLETED (1000, but 0 when SEED_PENDING is set), SEED_PENDING (0), SEED_WAIT_S (900), PENDING_SHARE (0.5
//      when pending ids exist), EXPECTED_LATENCY_MS (100: sizes VUs), MAX_VUS (2000), PROM_URL.
import { sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';
import { CONFIG, ENV, photoPaths, readList, readNum, readStr, requireRtt } from './lib/config.js';
import { lazyBodies, openOnce } from './lib/bodies.js';
import { call, makeContext } from './lib/flow.js';
import * as m from './lib/metrics.js';
import { guardLoop, guardScenario } from './lib/guard.js';
import { checkIds, joinIdLists, preflight, sampleIds, seedJobs } from './lib/seed.js';
import { sequentialScenarios } from './lib/stages.js';
import { errorThresholds, latencyThresholds, merge } from './lib/thresholds.js';
import { markReady, summaryOutputs } from './lib/summary.js';

const TIERS = JSON.parse(open('./tiers.json'));
const A2 = TIERS.load.a2;
const IDS_FILE = readStr(ENV, 'IDS_FILE', '');
const IDS_FILES = readList(ENV, 'IDS_FILE', []);
const IDS_OUT = readStr(ENV, 'IDS_OUT', '');
const SEED_ONLY = readNum(ENV, 'SEED_ONLY', 0) > 0;
const SEED_PENDING = readNum(ENV, 'SEED_PENDING', 0);
// A PENDING seed runs on a backend whose jobs stay PENDING, where a COMPLETED seed would wait SEED_WAIT_S for nothing.
const SEED_COMPLETED = readNum(ENV, 'SEED_COMPLETED', SEED_PENDING > 0 ? 0 : 1000);
const SEED_WAIT_S = readNum(ENV, 'SEED_WAIT_S', 900);
if (SEED_ONLY && IDS_FILE) throw new Error('SEED_ONLY makes new ids; it cannot be combined with IDS_FILE');
if (SEED_ONLY && !IDS_OUT) throw new Error('SEED_ONLY needs IDS_OUT (an absolute path in an existing folder): the seeded ids would be lost');
if (SEED_ONLY && SEED_COMPLETED + SEED_PENDING <= 0) throw new Error('SEED_ONLY with SEED_COMPLETED=0 and SEED_PENDING=0 seeds nothing');
if (!SEED_ONLY && !IDS_FILE && SEED_PENDING > 0 && SEED_COMPLETED <= 0 && !readStr(ENV, 'PENDING_SHARE', '')) {
  throw new Error('SEED_PENDING with no COMPLETED seed would run the whole A2 load on PENDING ids only: to seed the PENDING ' +
    'list for A2 add -e SEED_ONLY=1 (seeds, writes IDS_OUT, runs no load; the 3 runs are in the header of ' +
    'poll_saturation.js); to poll PENDING ids only on purpose set PENDING_SHARE=1');
}

const ABSOLUTE = /^([A-Za-z]:)?[\\/]/;

function errorText(e) {
  return e && e.message ? e.message : String(e);
}

// open() of a folder throws "open() can't be used with directories, path: ..." (k6 v2.3.0); of a missing path, a
// not-found error (GetFileAttributesEx ... on Windows).
function isFolderError(e) {
  return errorText(e).indexOf("can't be used with directories") >= 0;
}

// handleSummary hands IDS_OUT to k6, which writes it after the run: into a missing folder (or onto a folder) k6 only
// logs "could not open" and still exits 0, after a SEED_ONLY run has seeded every job. So the path is checked here,
// before setup. k6's open() resolves a relative path from scripts/load/ while the summary writes it from the current
// folder, so only an absolute path can be checked.
function requireOutFolder(path) {
  if (!ABSOLUTE.test(path)) {
    throw new Error(`SEED_ONLY needs an absolute IDS_OUT (k6 writes a relative one from the current folder, so its folder ` +
      `cannot be checked before seeding): ${path}`);
  }
  try {
    open(path); // an earlier file is fine (it is overwritten); a missing one is the usual case
  } catch (e) {
    if (isFolderError(e)) throw new Error(`IDS_OUT ${path} is a folder: name a file in it (e.g. .../ids.json)`);
  }
  let dir = path.replace(/[\\/]+[^\\/]*$/, '');
  if (dir === '' || /^[A-Za-z]:$/.test(dir)) dir += '/';
  try {
    open(dir);
  } catch (e) {
    if (isFolderError(e)) return;
    throw new Error(`IDS_OUT folder ${dir} cannot be used (${errorText(e)}): create it first, or k6 would seed, fail to ` +
      'write the ids and still exit 0');
  }
  throw new Error(`IDS_OUT folder ${dir} is a file, not a folder`);
}

if (SEED_ONLY) requireOutFolder(IDS_OUT);

function readIdsFile(path) {
  let text = null;
  try {
    text = open(path);
  } catch (e) {
    const relative = !ABSOLUTE.test(path);
    throw new Error(`IDS_FILE ${path} cannot be read (${errorText(e)})` +
      (relative ? '; k6 reads a relative path from scripts/load/, not from the current folder: give an absolute path' : ''));
  }
  try {
    return JSON.parse(text);
  } catch (e) {
    throw new Error(`IDS_FILE ${path} is not JSON (${e.message})`);
  }
}

let joined = null;
function idsFromFiles() {
  if (joined === null) joined = joinIdLists(IDS_FILES.map(readIdsFile), IDS_FILES);
  return joined;
}

const FROM_FILE = {
  completed: new SharedArray('completedIds', () => (IDS_FILE ? idsFromFiles().completed : [])),
  pending: new SharedArray('pendingIds', () => (IDS_FILE ? idsFromFiles().pending : [])),
};
const PATHS = photoPaths(ENV, TIERS.defaults.photoCount, './out/fixtures/photo-400k.png');
const FILES = IDS_FILE ? [] : openOnce(PATHS, (p) => open(p, 'b'));
const BODIES = lazyBodies(CONFIG.runId, FILES, CONFIG.failToken);

const RATES = (readList(ENV, 'RPS', null) || A2.rps).map(Number);
const HOLD_S = readNum(ENV, 'HOLD_S', A2.holdS);
const GAP_S = readNum(ENV, 'GAP_S', 30);
const LATENCY_S = readNum(ENV, 'EXPECTED_LATENCY_MS', 100) / 1000;
const MAX_VUS_CAP = readNum(ENV, 'MAX_VUS', 2000);
// A SEED_ONLY run judges no latency, so it needs no RTT_BASE_MS.
const RTT = SEED_ONLY ? 0 : requireRtt(CONFIG, 'poll_saturation.js');

const STEPS = sequentialScenarios('rps', RATES, HOLD_S, GAP_S, (rate) => {
  const pre = Math.min(MAX_VUS_CAP, Math.max(20, Math.ceil(rate * LATENCY_S * 2)));
  return {
    executor: 'constant-arrival-rate',
    exec: 'poll',
    rate,
    timeUnit: '1s',
    preAllocatedVUs: pre,
    maxVUs: Math.min(MAX_VUS_CAP, Math.max(pre * 4, 50)),
    gracefulStop: '10s',
  };
});

// The last step ends at LOAD_END_S (no gap after it); the guard returns when its polls are done (gracefulStop 10s).
const LOAD_END_S = (RATES.length - 1) * (HOLD_S + GAP_S) + HOLD_S;
const scenarios = Object.assign({}, STEPS.scenarios, guardScenario(CONFIG, LOAD_END_S, LOAD_END_S + 10));
// A poll that is not 200 (e.g. 404 for an id of another server) is "unexpected", not an error (lib/classify.js), and
// a fast 404 passes the latency limit, so load_unexpectedCount must stay 0 overall and in every step.
const perStage = RATES.map((rate, i) => {
  const scope = `stage:${i},phase:hold`;
  const extra = {};
  extra[`dropped_iterations{scenario:rps${i}}`] = ['count==0'];
  extra[`load_unexpectedCount{${scope}}`] = ['count==0'];
  return merge(latencyThresholds(RTT, ['poll'], scope), errorThresholds(scope, false), extra);
});

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: `${SEED_WAIT_S + 600}s`,
  // SEED_ONLY: setup() seeds, one no-op iteration lets k6 finish, and handleSummary writes IDS_OUT.
  scenarios: SEED_ONLY ? { seedOnly: { executor: 'shared-iterations', exec: 'seedOnly', vus: 1, iterations: 1, maxDuration: '60s' } } : scenarios,
  // load_pendingLeft: sampled pending ids that were no longer PENDING when the load ended (teardown)
  thresholds: SEED_ONLY ? {} : merge.apply(null, [latencyThresholds(RTT, ['poll'], null), errorThresholds(null, true),
    { dropped_iterations: ['count==0'], load_unexpectedCount: ['count==0'], load_pendingLeft: ['count==0'] }].concat(perStage)),
};

const ctx = makeContext({ cfg: CONFIG });
const pendingRechecked = new Counter('load_pendingRechecked');
const pendingLeft = new Counter('load_pendingLeft');

const LIST_STATE = { completed: 'COMPLETED', pending: 'PENDING' };

// What a sampled id in the wrong state most likely means.
function stateHint(list, bad) {
  if (bad.status !== 200) return 'ids of another server, or of a volume emptied since they were seeded';
  if (list === 'pending' && bad.state === 'FAILED') {
    return 'the backend restarted after the PENDING seed (any restart, `up -d backend` for a settings change included, ' +
      'closes every PENDING job as FAILED): seed PENDING again and run on that backend without restarting it';
  }
  if (list === 'pending') {
    return 'mock.worker.pending-ms ran out since the PENDING seed (or this backend does not keep jobs PENDING): restart ' +
      'once with a longer MOCK_WORKER_PENDINGMS, seed PENDING again and start right after';
  }
  return 'the "completed" list holds jobs that are not COMPLETED: use the IDS_OUT of a SEED_COMPLETED run';
}

// About 20 ids of `lists` (10 + 10 when both have ids) must answer 200 with their list's state, else setup stops with
// what was found, grouped. Returns { checked, pendingAgeS } (pendingAgeS: how long the oldest sampled PENDING id has
// waited, from its timings.queuedMs).
function requireStates(lists, what) {
  const nPending = lists.pending.length;
  const fromCompleted = Math.min(lists.completed.length, nPending > 0 ? 10 : 20);
  const results = {
    completed: checkIds(CONFIG, sampleIds(lists.completed, fromCompleted), [LIST_STATE.completed]),
    pending: checkIds(CONFIG, sampleIds(lists.pending, 20 - fromCompleted), [LIST_STATE.pending]),
  };
  const checked = results.completed.checked + results.pending.checked;
  const groups = {};
  const order = [];
  ['completed', 'pending'].forEach((list) => {
    results[list].bad.forEach((b) => {
      const key = `${list} -> ${b.status}${b.state === null || b.state === undefined ? '' : ` ${b.state}`}`;
      if (!groups[key]) {
        groups[key] = { n: 0, first: b.id, hint: stateHint(list, b) };
        order.push(key);
      }
      groups[key].n++;
    });
  });
  if (order.length > 0) {
    const nBad = order.reduce((sum, k) => sum + groups[k].n, 0);
    const found = order.map((k) => `${groups[k].n} x ${k} (first ${groups[k].first})`).join(', ');
    const hints = order.map((k) => groups[k].hint).filter((h, i, all) => all.indexOf(h) === i).join('; ');
    throw new Error(`setup: ${nBad} of ${checked} sampled ${what} did not return 200 with their list's state ` +
      `(completed -> COMPLETED, pending -> PENDING): ${found}. Likely: ${hints}`);
  }
  const q = results.pending.maxQueuedMs;
  return { checked, pendingAgeS: q === null ? null : Math.round(q / 100) / 10 };
}

function logPendingAge(ageS) {
  if (ageS === null) return;
  console.log(`setup: the oldest sampled PENDING id has waited ${ageS} s and the load lasts ${LOAD_END_S} s, so the ` +
    `backend's mock.worker.pending-ms must be above ${Math.ceil(ageS + LOAD_END_S + 60) * 1000} or pending ids start to ` +
    'complete during the run (teardown re-checks them: load_pendingLeft)');
}

export function setup() {
  const info = preflight(CONFIG);
  if (IDS_FILE) {
    if (FROM_FILE.completed.length + FROM_FILE.pending.length === 0) throw new Error(`setup: IDS_FILE ${IDS_FILE} has no completed or pending ids`);
    const found = requireStates(FROM_FILE, 'IDS_FILE ids');
    logPendingAge(found.pendingAgeS);
    return markReady(Object.assign(info, { completed: [], pending: [], seeded: false, idsChecked: found.checked, pendingAgeS: found.pendingAgeS }));
  }
  const done = seedJobs(CONFIG, BODIES.body(), SEED_COMPLETED, { wait: true, waitS: SEED_WAIT_S });
  if (SEED_COMPLETED > 0 && done.completed.length === 0) {
    throw new Error(`setup: none of the ${SEED_COMPLETED} SEED_COMPLETED jobs completed within SEED_WAIT_S ${SEED_WAIT_S} s ` +
      `(${done.created.length} created, ${done.failed.length} FAILED, ${done.unfinished.length} unfinished): a COMPLETED seed needs ` +
      'a backend with mock.worker.pending-ms 0 (seed COMPLETED before restarting it with a long pending-ms for the PENDING seed)');
  }
  let pend = { created: [] };
  let pendingChecked = null;
  if (SEED_PENDING > 0) {
    pend = seedJobs(CONFIG, BODIES.body(), SEED_PENDING, { wait: false });
    if (pend.created.length > 0) {
      sleep(1); // a backend that does not keep jobs PENDING (pending-ms 0) has moved them on by now
      pendingChecked = requireStates({ completed: [], pending: pend.created }, 'SEED_PENDING jobs (just created)').checked;
    }
  }
  if (done.completed.length === 0 && pend.created.length === 0) throw new Error('setup: no job could be seeded');
  return markReady(Object.assign(info, {
    completed: done.completed.map((j) => j.id),
    pending: pend.created,
    seeded: true,
    seedReport: { requested: SEED_COMPLETED, completed: done.completed.length, failed: done.failed.length, unfinished: done.unfinished.length,
      pendingRequested: SEED_PENDING, pendingCreated: pend.created.length, pendingChecked },
  }));
}

export function seedOnly() {}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export function poll(data) {
  const completed = IDS_FILE ? FROM_FILE.completed : data.completed;
  const pending = IDS_FILE ? FROM_FILE.pending : data.pending;
  const share = readNum(ENV, 'PENDING_SHARE', pending.length > 0 ? 0.5 : 0);
  const usePending = pending.length > 0 && (completed.length === 0 || Math.random() < share);
  const id = usePending ? pick(pending) : pick(completed);
  const r = call(ctx, 'poll', '/api/v1/jobs/{id}', 'GET', `${CONFIG.baseUrl}/api/v1/jobs/${id}`, null,
    { expected: [200], tags: { jobState: usePending ? 'pending' : 'completed' } });
  m.pollMs.add(r.res.timings.duration, r.tags);
}

export function guard() {
  guardLoop(CONFIG, LOAD_END_S);
}

// Up to 20 pending ids again: a pending-ms that ran out during the run (or a restart) turned the jobState=pending polls
// into polls of other states part of the way, which setup could not see. Another state or a 404 counts as left; an
// answer that tells nothing (no response, 5xx) is not counted either way.
export function teardown(data) {
  if (SEED_ONLY || !data) return;
  const ids = sampleIds(IDS_FILE ? FROM_FILE.pending : data.pending || [], 20);
  if (ids.length === 0) return;
  const res = checkIds(CONFIG, ids, [LIST_STATE.pending]);
  const left = res.bad.filter((b) => b.status === 200 || b.status === 404);
  const unknown = res.bad.length - left.length;
  pendingRechecked.add(res.checked - unknown);
  pendingLeft.add(left.length);
  if (left.length > 0) {
    const b = left[0];
    console.warn(`teardown: ${left.length} of ${res.checked - unknown} re-checked pending ids are no longer PENDING (first ${b.id} -> ` +
      `${b.status}${b.state ? ` ${b.state}` : ''}): mock.worker.pending-ms ran out or the backend restarted during the run`);
  }
  if (unknown > 0) console.warn(`teardown: ${unknown} of ${res.checked} pending ids could not be re-checked (no response or 5xx)`);
}

function countOf(data, name) {
  const metric = (data.metrics || {})[name];
  return metric && metric.values && typeof metric.values.count === 'number' ? metric.values.count : null;
}

export function handleSummary(data) {
  const setup = data.setup_data || {};
  const extra = {};
  if (IDS_OUT && setup.seeded) extra[IDS_OUT] = JSON.stringify({ completed: setup.completed || [], pending: setup.pending || [] });
  const rechecked = countOf(data, 'load_pendingRechecked');
  const endCheck = rechecked === null ? null : { checked: rechecked, leftPending: countOf(data, 'load_pendingLeft') || 0 };
  const ids = IDS_FILE
    ? { file: IDS_FILE, files: IDS_FILES, completed: FROM_FILE.completed.length, pending: FROM_FILE.pending.length,
      checkedInSetup: setup.idsChecked === undefined ? null : setup.idsChecked,
      pendingAgeS: setup.pendingAgeS === undefined ? null : setup.pendingAgeS, endCheck }
    : { seeded: setup.seedReport || null, pending: (setup.pending || []).length, idsOut: IDS_OUT || null, endCheck };
  const out = summaryOutputs(data, {
    script: 'poll_saturation.js',
    scenario: 'A2',
    plannedS: SEED_ONLY ? 0 : LOAD_END_S,
    thresholds: options.thresholds,
    // scenario: dropped_iterations{scenario:rpsN} fails step N (lib/summary.js stageVerdicts)
    stages: SEED_ONLY ? [] : RATES.map((rate, i) => ({ index: i, label: `${rate} rps`, users: null, target: rate, holdS: HOLD_S,
      holdEndS: i * (HOLD_S + GAP_S) + HOLD_S, scenario: `rps${i}` })),
    extra: { mode: SEED_ONLY ? 'seed-only' : 'load', rps: SEED_ONLY ? [] : RATES, ids, server: setup.server || null },
  }, CONFIG, extra);
  if (SEED_ONLY && setup.seeded) {
    // k6 writes IDS_OUT after this returns and cannot tell the script whether it could: say where else the ids are
    out.stdout = `SEED ONLY: ${(setup.completed || []).length} COMPLETED and ${(setup.pending || []).length} PENDING ids ` +
      `for IDS_OUT ${IDS_OUT} (also in SUMMARY_PATH under k6.setup_data; a k6 error "could not open" for this path means ` +
      `IDS_OUT was not written); no load ran, so this run is not an A2 result\n${out.stdout}`;
  }
  if (endCheck && endCheck.leftPending > 0) {
    out.stdout += `pending ids: ${endCheck.leftPending} of ${endCheck.checked} sampled were no longer PENDING when the load ended ` +
      '(mock.worker.pending-ms ran out or the backend restarted during the run): part of the jobState=pending polls were ' +
      'not PENDING jobs, so the run does not have the A2 mix\n';
  }
  return out;
}
