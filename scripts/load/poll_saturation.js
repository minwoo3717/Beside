// A2 polling saturation: GET /api/v1/jobs/{id} at fixed rates 100 -> 300 -> 600 -> 900 -> 1,200 -> 1,800 RPS, each
// held 5 min (tiers.json load.a2), one constant-arrival-rate scenario per step so each step carries its own
// stage tag. The ids are COMPLETED and PENDING jobs: from IDS_FILE ({"completed": [...], "pending": [...]}) or seeded
// in setup (SEED_COMPLETED completed jobs, waited for; SEED_PENDING jobs not waited for — they stay PENDING only on a
// server with a long mock.worker.pending-ms or a busy worker, see README).
//
//   k6 run -e BASE_URL=https://<domain> -e RUN_ID=a2r1 -e RTT_BASE_MS=23 -e IDS_FILE=/abs/ids.json scripts/load/poll_saturation.js
//
// Env: RPS=100,300 (subset), HOLD_S, GAP_S (30 s between steps), IDS_FILE, IDS_OUT (write the seeded ids),
//      SEED_COMPLETED (1000), SEED_PENDING (0), SEED_WAIT_S (900), PENDING_SHARE (0.5 when pending ids exist),
//      EXPECTED_LATENCY_MS (100: sizes VUs), MAX_VUS (2000), PROM_URL.
import { SharedArray } from 'k6/data';
import { CONFIG, ENV, photoPaths, readList, readNum, readStr, requireRtt } from './lib/config.js';
import { lazyBodies, openOnce } from './lib/bodies.js';
import { call, makeContext } from './lib/flow.js';
import * as m from './lib/metrics.js';
import { guardScenario, guardTick } from './lib/guard.js';
import { preflight, seedJobs } from './lib/seed.js';
import { sequentialScenarios } from './lib/stages.js';
import { errorThresholds, latencyThresholds, merge } from './lib/thresholds.js';
import { summaryOutputs } from './lib/summary.js';

const TIERS = JSON.parse(open('./tiers.json'));
const A2 = TIERS.load.a2;
const IDS_FILE = readStr(ENV, 'IDS_FILE', '');
const FROM_FILE = {
  completed: new SharedArray('completedIds', () => (IDS_FILE ? JSON.parse(open(IDS_FILE)).completed || [] : [])),
  pending: new SharedArray('pendingIds', () => (IDS_FILE ? JSON.parse(open(IDS_FILE)).pending || [] : [])),
};
const PATHS = photoPaths(ENV, TIERS.defaults.photoCount, './out/fixtures/photo-400k.png');
const FILES = IDS_FILE ? [] : openOnce(PATHS, (p) => open(p, 'b'));
const BODIES = lazyBodies(CONFIG.runId, FILES, CONFIG.failToken);

const RATES = (readList(ENV, 'RPS', null) || A2.rps).map(Number);
const HOLD_S = readNum(ENV, 'HOLD_S', A2.holdS);
const GAP_S = readNum(ENV, 'GAP_S', 30);
const LATENCY_S = readNum(ENV, 'EXPECTED_LATENCY_MS', 100) / 1000;
const MAX_VUS_CAP = readNum(ENV, 'MAX_VUS', 2000);
const SEED_COMPLETED = readNum(ENV, 'SEED_COMPLETED', 1000);
const SEED_PENDING = readNum(ENV, 'SEED_PENDING', 0);
const SEED_WAIT_S = readNum(ENV, 'SEED_WAIT_S', 900);
const RTT = requireRtt(CONFIG, 'poll_saturation.js');

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

const scenarios = Object.assign({}, STEPS.scenarios, guardScenario(CONFIG, STEPS.totalS));
const perStage = RATES.map((rate, i) => {
  const scope = `stage:${i},phase:hold`;
  const dropped = {};
  dropped[`dropped_iterations{scenario:rps${i}}`] = ['count==0'];
  return merge(latencyThresholds(RTT, ['poll'], scope), errorThresholds(scope, false), dropped);
});

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: `${SEED_WAIT_S + 600}s`,
  scenarios,
  thresholds: merge.apply(null, [latencyThresholds(RTT, ['poll'], null), errorThresholds(null, true),
    { dropped_iterations: ['count==0'] }].concat(perStage)),
};

const ctx = makeContext({ cfg: CONFIG });

export function setup() {
  const info = preflight(CONFIG);
  if (IDS_FILE) return Object.assign(info, { completed: [], pending: [], seeded: false });
  const done = seedJobs(CONFIG, BODIES.body(), SEED_COMPLETED, { wait: true, waitS: SEED_WAIT_S });
  const pend = SEED_PENDING > 0 ? seedJobs(CONFIG, BODIES.body(), SEED_PENDING, { wait: false }) : { created: [] };
  if (done.completed.length === 0 && pend.created.length === 0) throw new Error('setup: no job could be seeded');
  return Object.assign(info, {
    completed: done.completed.map((j) => j.id),
    pending: pend.created,
    seeded: true,
    seedReport: { requested: SEED_COMPLETED, completed: done.completed.length, failed: done.failed.length, unfinished: done.unfinished.length },
  });
}

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
  guardTick(CONFIG);
}

export function handleSummary(data) {
  const setup = data.setup_data || {};
  const extra = {};
  const idsOut = readStr(ENV, 'IDS_OUT', '');
  if (idsOut && setup.seeded) extra[idsOut] = JSON.stringify({ completed: setup.completed || [], pending: setup.pending || [] });
  return summaryOutputs(data, {
    script: 'poll_saturation.js',
    scenario: 'A2',
    stages: RATES.map((rate, i) => ({ index: i, label: `${rate} rps`, users: null, target: rate, holdS: HOLD_S })),
    extra: {
      rps: RATES,
      ids: IDS_FILE ? { file: IDS_FILE, completed: FROM_FILE.completed.length, pending: FROM_FILE.pending.length }
        : { seeded: setup.seedReport || null, pending: (setup.pending || []).length },
      server: setup.server || null,
    },
  }, CONFIG, extra);
}
