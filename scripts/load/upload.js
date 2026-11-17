// A3 upload: POST /api/v1/jobs only (no polling), stepped arrival rates.
//   SIZE=small  2 x 0.4 MB photos (0.8 MB), 1 -> 20 uploads/s   (tiers.json load.a3Small)
//   SIZE=large  4 x ~5 MB photos (~20 MB, under the 20 MiB limit), 0.5 -> 2 uploads/s   (load.a3Large)
// Every upload creates a real job and stays on the server's disk until the load-test volume is wiped, so the script
// refuses to start when the expected upload volume exceeds DISK_BUDGET_GB (8) unless ALLOW_DISK=1.
// Every upload is valid, so any answer but 202 is a failed upload. lib/classify.js counts a 4xx (413/400 from Spring or
// the proxy, a proxy 404, a 429 without the RATE_LIMITED code) only in load_unexpectedCount, not in load_errorRatio,
// and a fast refusal even passes the latency limit: load_unexpectedCount must stay 0 overall and in every hold.
// 5xx and connection errors (status 0) stay in load_errorRatio with the §8 budget, and a 429 RATE_LIMITED / 503
// QUEUE_FULL ([계획] R1~R3 codes the backend does not send yet) only in load_rejectedCount, counted apart as §8 says;
// so no answer is counted twice. Whether a 429/503 refusal should fail an A3 hold is a criteria question, not decided.
// Each level (rate) is its own ramping-arrival-rate scenario lvl0, lvl1, ... (lib/stages.js levelScenarios), so k6
// ties dropped iterations to their level: a level that drops reads "fail", the earlier ones keep their verdict.
//
//   k6 run -e BASE_URL=https://<domain> -e RUN_ID=a3s1 -e RTT_BASE_MS=23 -e SIZE=small scripts/load/upload.js
//
// Env: SIZE, RATES=1,2,5 (per second, subset), RAMP_S, HOLD_S, LARGE_PHOTO (./out/fixtures/photo-5mb.png),
//      PHOTOS/PHOTO_n (small), DISK_BUDGET_GB, ALLOW_DISK, PRE_VUS (VUs of the top level: top rate x 3 s small,
//      x 10 s large; lower levels in proportion to their rate), MAX_VUS (all levels together, each may grow to its
//      share; 600 or more), REQUEST_TIMEOUT (120s; each level's drain is 60 s longer), PROM_URL.
import exec from 'k6/execution';
import { CONFIG, durationS, ENV, photoPaths, readList, readNum, readStr, requireRtt } from './lib/config.js';
import { lazyBodies, openOnce, totalBytes } from './lib/bodies.js';
import { countedFlow, createJob, makeContext } from './lib/flow.js';
import { guardLoop, guardScenario } from './lib/guard.js';
import { preflight } from './lib/seed.js';
import { buildLevels, expectedIterations, holdScopes, levelScenarios, levelTagsAt, maxTarget, shareMaxVUs } from './lib/stages.js';
import { bytesSentThresholds, droppedThresholds, errorThresholds, flowCountThresholds, latencyReportThresholds,
  latencyThresholds, merge, zeroCountThresholds } from './lib/thresholds.js';
import { markReady, summaryOutputs } from './lib/summary.js';

const TIERS = JSON.parse(open('./tiers.json'));
const SIZE = readStr(ENV, 'SIZE', 'small').toLowerCase();
if (SIZE !== 'small' && SIZE !== 'large') throw new Error(`SIZE must be small or large (got '${SIZE}')`);
const SPEC = SIZE === 'small' ? TIERS.load.a3Small : TIERS.load.a3Large;
const PATHS = SIZE === 'small'
  ? photoPaths(ENV, SPEC.photoCount, './out/fixtures/photo-400k.png')
  : Array.from({ length: SPEC.photoCount }, () => readStr(ENV, 'LARGE_PHOTO', './out/fixtures/photo-5mb.png'));
const FILES = openOnce(PATHS, (p) => open(p, 'b'));
const BODIES = lazyBodies(CONFIG.runId, FILES, CONFIG.failToken, { tag: SIZE });

const RATES = (readList(ENV, 'RATES', null) || SPEC.ratesPerS).map(Number);
const RAMP_S = readNum(ENV, 'RAMP_S', SPEC.rampS);
const HOLD_S = readNum(ENV, 'HOLD_S', SPEC.holdS);
// timeUnit 1m keeps 0.5/s and 1.5/s whole numbers (30 and 90 per minute).
const LEVELS = buildLevels(RATES.map((r) => ({ target: Math.round(r * 60), rampS: RAMP_S, holdS: HOLD_S, label: `${r}/s` })));
const RTT = requireRtt(CONFIG, 'upload.js');
const TOP = maxTarget(LEVELS.stages);
// k6 (v2.3.0) ramping-arrival-rate drops an iteration whenever no pre-allocated VU is free and only then starts one more
// VU, so every VU started mid-test costs a dropped iteration and maxVUs only caps them: pre-allocated VUs, not MAX_VUS,
// are the knob against drops. Each level gets the same seconds of cover: PRE_VUS at the top rate, PRE_VUS x rate / top
// rate below it (at least 1). The default PRE_VUS covers uploads up to the create p99 limit (3 s; a 20 MB upload gets
// 10 s), so a level drops once its uploads are slower than that; that level then reads "fail" (the drops are its own),
// which is right for a level past its limit. To measure such a level without drops, give PRE_VUS = the top rate x the
// seconds it needs (at most REQUEST_TIMEOUT); an explicit PRE_VUS is used as given.
const PRE_VUS_SET = readNum(ENV, 'PRE_VUS', null);
const PRE_VUS = PRE_VUS_SET !== null ? PRE_VUS_SET : Math.max(5, Math.ceil((TOP / 60) * (SIZE === 'small' ? 3 : 10)));
if (!(Number.isInteger(PRE_VUS) && PRE_VUS >= 1)) throw new Error(`upload.js: PRE_VUS ${PRE_VUS} must be a whole number of at least 1`);
const REQUEST_TIMEOUT = readStr(ENV, 'REQUEST_TIMEOUT', '120s');
// The request timeout + 60 s: the end of a level (and of the run) cuts off no upload (one still running would make the
// summary "unfinished"). A level keeps its VUs through this drain, so the VUs of the levels whose drains overlap exist
// at once: meta.vus.peakPreAllocated (with SIZE=large every VU that ran an upload holds a 20 MB body).
const DRAIN_S = Math.ceil(durationS(REQUEST_TIMEOUT)) + 60;
const LV = levelScenarios(LEVELS, 'lvl', DRAIN_S, (lv) => ({
  exec: 'upload',
  timeUnit: '1m',
  preAllocatedVUs: Math.max(1, Math.ceil(TOP > 0 ? (PRE_VUS * lv.target) / TOP : PRE_VUS)),
}));
// MAX_VUS caps the VUs of all levels together (each level may grow to its share, meta.vus.maxByLevel, lib/stages.js
// shareMaxVUs); its default is 600 or the peak of the pre-allocated VUs, and an explicit one below that peak is refused.
const MAX_VUS = readNum(ENV, 'MAX_VUS', Math.max(600, LV.peakPreAllocated));
if (!(MAX_VUS >= LV.peakPreAllocated)) {
  throw new Error(`upload.js: the pre-allocated VUs of the levels that run at once reach ${LV.peakPreAllocated} (PRE_VUS ${PRE_VUS} ` +
    `at the top level) > MAX_VUS ${MAX_VUS}; raise MAX_VUS or lower PRE_VUS`);
}
shareMaxVUs(LV, MAX_VUS);

let EXPECTED = 0;
LV.names.forEach((n) => { EXPECTED += expectedIterations(LV.scenarios[n].stages, 60, LV.scenarios[n].startRate); });
const BYTES = totalBytes(FILES);
const EXPECTED_GB = (EXPECTED * BYTES) / 1e9;
const BUDGET_GB = readNum(ENV, 'DISK_BUDGET_GB', 8);
if (EXPECTED_GB > BUDGET_GB && readNum(ENV, 'ALLOW_DISK', 0) <= 0) {
  throw new Error(`upload.js: about ${EXPECTED_GB.toFixed(1)} GB of uploads expected (> DISK_BUDGET_GB ${BUDGET_GB}); ` +
    'shorten HOLD_S/RATES or set ALLOW_DISK=1 after checking df on the server');
}
// The 1.5 s / 3 s criteria are for the 0.8 MB upload (docs/perf/load/TRAFFIC_MODEL.md §8). A 20 MB upload is reported
// per hold (verdict.stages[].latency.create, kept in the summary by a no-op threshold), not judged.
const LATENCY_EPS = SIZE === 'small' ? ['create'] : [];
const REPORT_EPS = SIZE === 'small' ? [] : ['create'];
// Unexpected answers to a valid upload (see the header): not one, overall and in every hold.
const UNEXPECTED = ['load_unexpectedCount'];
const SCOPES = holdScopes(LEVELS.segments);

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: '60s',
  // the guard returns when the last level's uploads are done
  scenarios: Object.assign({}, LV.scenarios, guardScenario(CONFIG, LEVELS.totalS, LEVELS.totalS + DRAIN_S)),
  // bytesSentThresholds: per-stage upload bytes for the uplink ratio of the busiest stage (lib/summary.js)
  thresholds: merge.apply(null, [latencyThresholds(RTT, LATENCY_EPS, null), errorThresholds(null, true),
    zeroCountThresholds(UNEXPECTED, null), { dropped_iterations: ['count==0'] }, droppedThresholds(LV.names),
    bytesSentThresholds(SCOPES), flowCountThresholds(SCOPES)].concat(SCOPES.map((s) => merge(latencyThresholds(RTT, LATENCY_EPS, s.scope),
    latencyReportThresholds(REPORT_EPS, s.scope), errorThresholds(s.scope, false), zeroCountThresholds(UNEXPECTED, s.scope))))),
};

const ctx = makeContext({
  cfg: CONFIG,
  tags: () => levelTagsAt(LEVELS.segments, LV.startS, exec.scenario.name, exec.scenario.startTime, Date.now()),
  requestTimeout: REQUEST_TIMEOUT,
});

export function setup() {
  return markReady(preflight(CONFIG));
}

export function upload() {
  countedFlow(ctx, () => createJob(ctx, BODIES.body()));
}

export function guard() {
  guardLoop(CONFIG, LEVELS.totalS);
}

export function handleSummary(data) {
  return summaryOutputs(data, {
    script: 'upload.js',
    scenario: `A3-${SIZE}`,
    plannedS: LEVELS.totalS,
    thresholds: options.thresholds,
    // scenario: dropped_iterations{scenario:lvlN} fails level N (lib/summary.js stageVerdicts)
    stages: SCOPES.map((s) => ({ index: s.index, label: s.segment.label, users: null,
      target: s.segment.target / 60, holdS: HOLD_S, holdEndS: s.segment.end, scenario: `lvl${s.index}` })),
    extra: {
      size: SIZE,
      ratesPerS: RATES,
      photos: PATHS,
      bodyPhotoBytes: BYTES,
      expectedUploads: Math.round(EXPECTED),
      expectedUploadGB: Math.round(EXPECTED_GB * 100) / 100,
      vus: { preAllocated: PRE_VUS, max: MAX_VUS, byLevel: LV.names.map((n) => LV.scenarios[n].preAllocatedVUs),
        peakPreAllocated: LV.peakPreAllocated, maxByLevel: LV.names.map((n) => LV.scenarios[n].maxVUs) },
      server: (data.setup_data && data.setup_data.server) || null,
    },
  }, CONFIG);
}
