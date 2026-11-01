// G1~G3 GPU queue: the user flow with Poisson arrivals at lambda = RHO x SLOTS / T_SEC against real,prod and the
// inference stub in passthrough mode (BESIDE_PASSTHROUGH_DELAY_MS = T_SEC x 1000, BESIDE_PASSTHROUGH_SLOTS = SLOTS,
// and the backend's inference.max-concurrency = SLOTS, e.g. env INFERENCE_MAXCONCURRENCY).
// The stub stands in for the GPU: results are "queue behaviour with an assumed T", not generation times.
//   G1  SLOTS=1 T_SEC=10, RHO = 0.5 / 0.7 / 0.9 / 1.2 (one run each): measured queuedMs vs M/D/1 and M/M/1
//   G2  RHO=0.7, SLOTS = 1 / 2 / 4: does throughput scale with c
//   G3  RHO=1.5: queue growth and flows abandoned at 600 s (compare before/after the queue cap, R2)
//   F1b FAIL_TOKEN=slow FAIL_RATIO=0.2 with the stub's SLOW_TOKEN=slow (scripts/load/README.md): only that share of
//       flows carries "slow" in its photo names and times out in the backend; those flows ending FAILED count as
//       intended. RUN_ID must not contain the token (lib/config.js refuses it).
//
// Arrivals: k6's arrival-rate executors start iterations on a fixed tick (evenly spaced), which makes the queue
// D/D/c, not the M/D/c and M/M/c of the predictions. So the scenario runs lambda x ARRIVAL_THIN (K, default 20)
// iterations and each one starts a flow with probability 1/K (thinning): the gaps between flows are geometric
// multiples of the tick, which approach the exponential gaps of a Poisson process as K grows. The summary records
// meta.arrivalProcess. ARRIVAL_THIN=1 gives the old evenly spaced arrivals (then the M/x/c predictions do not apply).
//
// Predictions: meta.prediction uses T_SEC as the slot time. The backend holds a slot for the whole run (upload to the
// inference service, stub sleep, GLB download, cleanup), so the predictions to compare with measuredQueuedMs are
// recomputed from the measured mean slot time (mean server processingMs): meta.recompute is that model.py command
// (python scripts/load/model.py --queue-rate-per-h <jobsPerHour> --queue-service-s <mean s> --queue-slots <SLOTS>).
//
//   k6 run -e BASE_URL=https://<domain> -e RUN_ID=g1r07 -e RTT_BASE_MS=23 -e RHO=0.7 -e SLOTS=1 -e T_SEC=10 \
//          scripts/load/gpu_queue.js
//
// Env: RHO (0.7), SLOTS (1), T_SEC (10), DURATION_S (1800), ARRIVAL_THIN (20, whole number >= 1), FAIL_RATIO (0; F1a
//      uses the stub's own fail rate), FAIL_TOKEN, MAX_VUS, PROM_URL, plus lib/config.js.
import { CONFIG, ENV, photoPaths, readNum, requireRtt } from './lib/config.js';
import { lazyBodies, openOnce } from './lib/bodies.js';
import { makeContext, userFlow } from './lib/flow.js';
import { guardScenario, guardTick } from './lib/guard.js';
import { predict } from './lib/queue.js';
import { preflight } from './lib/seed.js';
import { errorThresholds, flowThresholds, latencyThresholds, merge } from './lib/thresholds.js';
import { summaryOutputs } from './lib/summary.js';

const TIERS = JSON.parse(open('./tiers.json'));
const PATHS = photoPaths(ENV, TIERS.defaults.photoCount, './out/fixtures/photo-400k.png');
const FILES = openOnce(PATHS, (p) => open(p, 'b'));
const BODIES = lazyBodies(CONFIG.runId, FILES, CONFIG.failToken, { tag: 'gq' });

const RHO = readNum(ENV, 'RHO', 0.7);
const SLOTS = readNum(ENV, 'SLOTS', 1);
const T_SEC = readNum(ENV, 'T_SEC', 10);
const DURATION_S = readNum(ENV, 'DURATION_S', 1800);
const FAIL_RATIO = readNum(ENV, 'FAIL_RATIO', 0);
const THIN = readNum(ENV, 'ARRIVAL_THIN', 20);
if (!(THIN >= 1) || Math.floor(THIN) !== THIN) throw new Error(`gpu_queue.js: ARRIVAL_THIN must be a whole number >= 1 (got ${THIN})`);
const PREDICTION = predict(RHO, SLOTS, T_SEC, CONFIG.maxWaitS);
if (PREDICTION.jobsPerHour < 1) throw new Error('gpu_queue.js: RHO x SLOTS / T_SEC rounds to 0 jobs per hour');
const RTT = requireRtt(CONFIG, 'gpu_queue.js');
// Flows last T + Wq (+ polling); with rho >= 1 they run until the app's 600 s limit.
const FLOW_S = PREDICTION.wqMMcSec === null ? CONFIG.maxWaitS + 30 : Math.min(CONFIG.maxWaitS + 30, T_SEC + PREDICTION.wqMMcSec + 15);
// With Poisson arrivals the flows in progress are about Poisson(m), m = lambda x FLOW_S (x 1.5 for longer flows), so
// the VUs cover m + 3 sqrt(m); the thinned-away iterations end at once and need only the 2 extra VUs.
const FLOWS_IN_PROGRESS = PREDICTION.lambdaPerS * FLOW_S * 1.5;
const PRE_VUS = Math.max(5, Math.ceil(FLOWS_IN_PROGRESS + 3 * Math.sqrt(FLOWS_IN_PROGRESS))) + 2;
const MAX_VUS = readNum(ENV, 'MAX_VUS', Math.max(PRE_VUS, Math.ceil(PREDICTION.lambdaPerS * (CONFIG.maxWaitS + 60) * 1.2) + 2));

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: '60s',
  scenarios: Object.assign({
    queue: {
      executor: 'constant-arrival-rate',
      exec: 'flow',
      rate: PREDICTION.jobsPerHour * THIN,
      timeUnit: '1h',
      duration: `${DURATION_S}s`,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: `${CONFIG.maxWaitS + 120}s`,
      tags: { stage: '0', phase: 'hold' },
    },
  }, guardScenario(CONFIG, DURATION_S + CONFIG.maxWaitS + 120)),
  // Overload (rho >= 1) is expected to time flows out, so the flow criteria apply only below saturation.
  thresholds: merge(latencyThresholds(RTT, ['healthz', 'create', 'poll', 'asset'], null), errorThresholds(null, true),
    RHO < 1 ? flowThresholds(null) : {}, { dropped_iterations: ['count==0'] }),
};

const ctx = makeContext({ cfg: CONFIG, body: BODIES.body, makeFailBody: BODIES.makeFailBody, failRatio: FAIL_RATIO });

export function setup() {
  return preflight(CONFIG);
}

export function flow() {
  if (Math.random() >= 1 / THIN) return; // thinning: a flow with probability 1/K per iteration (Poisson arrivals)
  userFlow(ctx);
}

export function guard() {
  guardTick(CONFIG);
}

function trendValues(data, name) {
  const m = data.metrics && data.metrics[name] ? data.metrics[name].values : null;
  return m ? { mean: m.avg, med: m.med, p95: m['p(95)'], count: m.count } : null;
}

export function handleSummary(data) {
  const queued = trendValues(data, 'load_serverQueuedMs');
  const processing = trendValues(data, 'load_serverProcessingMs');
  const slotS = processing && processing.mean > 0 ? Math.round(processing.mean) / 1000 : null;
  return summaryOutputs(data, {
    script: 'gpu_queue.js',
    scenario: RHO >= 1.5 ? 'G3' : SLOTS > 1 ? 'G2' : 'G1',
    stages: [],
    extra: {
      durationS: DURATION_S,
      arrivalProcess: THIN > 1 ? `poisson (thinned x${THIN})` : 'deterministic (constant-arrival-rate, ARRIVAL_THIN=1)',
      arrivalThin: THIN,
      iterationsPerHour: PREDICTION.jobsPerHour * THIN,
      prediction: PREDICTION,
      predictionNote: 'prediction uses T_SEC as the slot time; compare measuredQueuedMs with the values that ' +
        '`recompute` prints (mean server processingMs as the slot time, Poisson arrivals)',
      measuredQueuedMs: queued,
      measuredProcessingMs: processing,
      recompute: slotS === null ? null
        : `python scripts/load/model.py --queue-rate-per-h ${PREDICTION.jobsPerHour} --queue-service-s ${slotS} --queue-slots ${SLOTS}`,
      failRatio: FAIL_RATIO,
      failToken: FAIL_RATIO > 0 ? CONFIG.failToken : null,
      stubNote: 'stub passthrough: BESIDE_PASSTHROUGH_DELAY_MS = T_SEC x 1000, BESIDE_PASSTHROUGH_SLOTS = SLOTS and the backend\'s ' +
        'inference.max-concurrency = SLOTS (otherwise c is the smaller of the two)',
      server: (data.setup_data && data.setup_data.server) || null,
    },
  }, CONFIG);
}
