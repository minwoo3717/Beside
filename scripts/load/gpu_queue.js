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
//   F1a, F2 the G1 knobs with the failure set on the stub (BESIDE_PASSTHROUGH_FAIL_RATE) or by stopping it: k6 cannot
//       see these, so name the run with SCENARIO=F1a / SCENARIO=F2.
// meta.scenario (and the stdout banner) is SCENARIO when set, else F1b for FAIL_RATIO > 0 with FAIL_TOKEN=slow (F1a for
// another token), else G3 (RHO >= 1.5), G2 (SLOTS > 1) or G1 (lib/queue.js scenarioLabel).
//
// Arrivals: k6's arrival-rate executors start iterations on a fixed tick (evenly spaced), which makes the queue
// D/D/c, not the M/D/c and M/M/c of the predictions. So the scenario runs lambda x ARRIVAL_THIN (K, default 20)
// iterations and each one starts a flow with probability 1/K (thinning): the gaps between flows are geometric
// multiples of the tick, which approach the exponential gaps of a Poisson process as K grows. The summary records
// meta.arrivalProcess. ARRIVAL_THIN=1 gives the old evenly spaced arrivals (then the M/x/c predictions do not apply).
//
// Predictions: meta.prediction uses T_SEC as the slot time and the requested rate. A run differs in both:
// - Arrival rate: the number of jobs a finite run creates is a Poisson count around lambda x DURATION_S (sd = its
//   square root, about 10 % for the 90 jobs of a 1,800 s G1 at RHO 0.5), so the summary records what the run created:
//   meta.measuredCreates (load_postMs count) in meta.measuredArrivalWindowS (DURATION_S, or less when the load stopped
//   early) as meta.measuredJobsPerHour (docs/perf/load/TRAFFIC_MODEL.md §9).
// - Slot time: the backend holds a slot longer than T_SEC. With prod's inference.offline-wait-ms > 0 it first calls the
//   stub's /healthz afresh, then uploads the photos, waits out the stub's sleep and downloads the GLB (only these three
//   are the server's processingMs), then writes job_runs / events.jsonl and sends the cleanup DELETE. Mean processingMs
//   is therefore a little short of the slot (about two stub round trips plus the writes per job). The /healthz call
//   counts in the job's own queuedMs, so queuedMs holds about one stub round trip even when the queue is empty.
// meta.recompute is the model.py command with both measured values, to compare with meta.measuredQueuedMs:
//   python scripts/load/model.py --queue-rate-per-h <meta.measuredJobsPerHour>
//     --queue-service-s <meta.measuredProcessingMs.mean / 1000> --queue-slots <SLOTS>
// It is null for the fault runs (F1a, F1b, F2): retries add arrivals and failures or timeouts are in the slot time.
// meta.measuredRetries counts the retries (load_retryMs); a G run should have none. The stdout report ends with these.
//
//   k6 run -e BASE_URL=https://<domain> -e RUN_ID=g1r07 -e RTT_BASE_MS=23 -e RHO=0.7 -e SLOTS=1 -e T_SEC=10 \
//          scripts/load/gpu_queue.js
//
// Env: RHO (0.7), SLOTS (1), T_SEC (10), DURATION_S (1800), ARRIVAL_THIN (20, whole number >= 1), FAIL_RATIO (0; F1a
//      uses the stub's own fail rate), FAIL_TOKEN, SCENARIO (G1|G2|G3|F1a|F1b|F2, see above), MAX_VUS (whole number
//      >= 1; the VUs set up front are the computed pre-allocation capped at MAX_VUS, both in meta.preAllocatedVUs and
//      meta.maxVUs), PROM_URL, plus lib/config.js.
import { CONFIG, ENV, flowDrainS, photoPaths, readNum, readStr, requireRtt } from './lib/config.js';
import { lazyBodies, openOnce } from './lib/bodies.js';
import { makeContext, userFlow } from './lib/flow.js';
import { guardLoop, guardScenario } from './lib/guard.js';
import { isQueueRun, predict, realizedPerHour, scenarioLabel } from './lib/queue.js';
import { preflight } from './lib/seed.js';
import { errorThresholds, flowThresholds, latencyThresholds, merge } from './lib/thresholds.js';
import { markReady, summaryOutputs } from './lib/summary.js';

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
const LABEL = scenarioLabel({ scenario: readStr(ENV, 'SCENARIO', ''), rho: RHO, slots: SLOTS, failRatio: FAIL_RATIO,
  failToken: CONFIG.failToken });
const QUEUE_RUN = isQueueRun(LABEL);
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
if (!(MAX_VUS >= 1) || Math.floor(MAX_VUS) !== MAX_VUS) throw new Error(`gpu_queue.js: MAX_VUS must be a whole number >= 1 (got ${MAX_VUS})`);
// k6 refuses a scenario whose maxVUs is below its preAllocatedVUs, so a smaller MAX_VUS caps the pre-allocation too (as
// in upload.js and asset.js); a capped run that runs short of VUs fails dropped_iterations count==0.
const PRE_ALLOCATED_VUS = Math.min(PRE_VUS, MAX_VUS);
// Long enough for the longest flow (a FAILED wait, the retry and a second wait), so a normal end cuts off no flow.
const DRAIN_S = flowDrainS(CONFIG);

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
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: `${DRAIN_S}s`,
      tags: { stage: '0', phase: 'hold' },
    },
  }, guardScenario(CONFIG, DURATION_S, DURATION_S + DRAIN_S)), // returns when the last flow is done
  // Overload (rho >= 1) is expected to time flows out, so the flow criteria apply only below saturation.
  thresholds: merge(latencyThresholds(RTT, ['healthz', 'create', 'poll', 'asset'], null), errorThresholds(null, true),
    RHO < 1 ? flowThresholds(null) : {}, { dropped_iterations: ['count==0'] }),
};

const ctx = makeContext({ cfg: CONFIG, body: BODIES.body, makeFailBody: BODIES.makeFailBody, failRatio: FAIL_RATIO });

export function setup() {
  return markReady(preflight(CONFIG));
}

export function flow() {
  if (Math.random() >= 1 / THIN) return; // thinning: a flow with probability 1/K per iteration (Poisson arrivals)
  userFlow(ctx);
}

export function guard() {
  guardLoop(CONFIG, DURATION_S);
}

function trendValues(data, name) {
  const m = data.metrics && data.metrics[name] ? data.metrics[name].values : null;
  return m ? { mean: m.avg, med: m.med, p95: m['p(95)'], count: m.count } : null;
}

// Requests of one endpoint metric (load_postMs, load_retryMs): 0 when none was sent (k6 leaves a metric with no sample
// out of the summary), null when no load ran (setup() failed: no setup_data).
function requestCount(data, name) {
  if (!data.setup_data) return null;
  const m = data.metrics && data.metrics[name] ? data.metrics[name].values : null;
  return (m && m.count) || 0;
}

// Seconds in which flows could start: DURATION_S, or less when the load stopped early (seconds since markReady() at the
// end of setup(), as lib/summary.js measures meta.loadRanS); DURATION_S without that stamp, null when no load ran.
function arrivalWindowS(data) {
  if (!data.setup_data) return null;
  const ready = data.setup_data.readyAtMs;
  if (typeof ready !== 'number') return DURATION_S;
  return Math.round(Math.min(DURATION_S, Math.max(0, (Date.now() - ready) / 1000)) * 10) / 10;
}

function meanMs(t) {
  return t && t.count > 0 ? `${Math.round(t.mean)} ms` : '-';
}

// The stdout lines under the common report: what the run created, and for a G run the measured means and the recompute.
function queueLines(x) {
  const created = x.measuredCreates === null ? 'no load ran'
    : `${x.measuredCreates} jobs created in ${x.measuredArrivalWindowS} s = ${x.measuredJobsPerHour === null ? '-' : x.measuredJobsPerHour}/h ` +
      `(requested ${x.prediction.jobsPerHour}/h), ${x.measuredRetries} retries`;
  if (!QUEUE_RUN) return `queue ${LABEL}: ${created}; fault run, so no queue prediction or recompute\n`;
  const lines = [`queue ${LABEL}: ${created}; mean slot (server processingMs) ${meanMs(x.measuredProcessingMs)}, ` +
    `mean queuedMs ${meanMs(x.measuredQueuedMs)}`];
  if (x.measuredRetries > 0) lines.push('  WARNING: a G run should have no retry: retries add arrivals that the recompute leaves out');
  lines.push(`  recompute: ${x.recompute === null ? '- (no job created or no processingMs)' : x.recompute}`);
  return lines.join('\n') + '\n';
}

export function handleSummary(data) {
  const queued = trendValues(data, 'load_serverQueuedMs');
  const processing = trendValues(data, 'load_serverProcessingMs');
  const slotS = processing && processing.mean > 0 ? Math.round(processing.mean) / 1000 : null;
  const creates = requestCount(data, 'load_postMs');
  const windowS = arrivalWindowS(data);
  const perHour = realizedPerHour(creates, windowS);
  const extra = {
    durationS: DURATION_S,
    arrivalProcess: THIN > 1 ? `poisson (thinned x${THIN})` : 'deterministic (constant-arrival-rate, ARRIVAL_THIN=1)',
    arrivalThin: THIN,
    iterationsPerHour: PREDICTION.jobsPerHour * THIN,
    preAllocatedVUs: PRE_ALLOCATED_VUS,
    maxVUs: MAX_VUS,
    prediction: PREDICTION,
    predictionNote: QUEUE_RUN
      ? 'prediction uses T_SEC as the slot time and the requested rate; compare measuredQueuedMs with the values that ' +
        '`recompute` prints (the jobs the run created, measuredJobsPerHour; mean server processingMs as the slot time, ' +
        'a little short of it; Poisson arrivals)'
      : `${LABEL} is a fault run: the queue model does not apply (retries add arrivals, failures and timeouts are in the ` +
        'slot time), so recompute is null',
    measuredQueuedMs: queued,
    measuredProcessingMs: processing,
    measuredCreates: creates,
    measuredRetries: requestCount(data, 'load_retryMs'),
    measuredArrivalWindowS: windowS,
    measuredJobsPerHour: perHour,
    recompute: !QUEUE_RUN || slotS === null || !(perHour > 0) ? null
      : `python scripts/load/model.py --queue-rate-per-h ${perHour} --queue-service-s ${slotS} --queue-slots ${SLOTS}`,
    failRatio: FAIL_RATIO,
    failToken: FAIL_RATIO > 0 ? CONFIG.failToken : null,
    stubNote: 'stub passthrough: BESIDE_PASSTHROUGH_DELAY_MS = T_SEC x 1000, BESIDE_PASSTHROUGH_SLOTS = SLOTS and the backend\'s ' +
      'inference.max-concurrency = SLOTS (otherwise c is the smaller of the two)',
    server: (data.setup_data && data.setup_data.server) || null,
  };
  const out = summaryOutputs(data, { script: 'gpu_queue.js', scenario: LABEL, plannedS: DURATION_S, thresholds: options.thresholds,
    stages: [], extra }, CONFIG);
  out.stdout += queueLines(extra);
  return out;
}
