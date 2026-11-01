// A3 upload: POST /api/v1/jobs only (no polling), stepped arrival rates.
//   SIZE=small  2 x 0.4 MB photos (0.8 MB), 1 -> 20 uploads/s   (tiers.json load.a3Small)
//   SIZE=large  4 x ~5 MB photos (~20 MB, under the 20 MiB limit), 0.5 -> 2 uploads/s   (load.a3Large)
// Every upload creates a real job and stays on the server's disk until the load-test volume is wiped, so the script
// refuses to start when the expected upload volume exceeds DISK_BUDGET_GB (8) unless ALLOW_DISK=1.
//
//   k6 run -e BASE_URL=https://<domain> -e RUN_ID=a3s1 -e RTT_BASE_MS=23 -e SIZE=small scripts/load/upload.js
//
// Env: SIZE, RATES=1,2,5 (per second, subset), RAMP_S, HOLD_S, LARGE_PHOTO (./out/fixtures/photo-5mb.png),
//      PHOTOS/PHOTO_n (small), DISK_BUDGET_GB, ALLOW_DISK, MAX_VUS (600), REQUEST_TIMEOUT (120s; the drain is 60 s
//      longer), PROM_URL.
import exec from 'k6/execution';
import { CONFIG, durationS, ENV, photoPaths, readList, readNum, readStr, requireRtt } from './lib/config.js';
import { lazyBodies, openOnce, totalBytes } from './lib/bodies.js';
import { countedFlow, createJob, makeContext } from './lib/flow.js';
import { guardLoop, guardScenario } from './lib/guard.js';
import { preflight } from './lib/seed.js';
import { buildLevels, expectedIterations, holdScopes, maxTarget, tagsAt } from './lib/stages.js';
import { bytesSentThresholds, errorThresholds, flowCountThresholds, latencyThresholds, merge } from './lib/thresholds.js';
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
const EXPECTED = expectedIterations(LEVELS.stages, 60, 0);
const BYTES = totalBytes(FILES);
const EXPECTED_GB = (EXPECTED * BYTES) / 1e9;
const BUDGET_GB = readNum(ENV, 'DISK_BUDGET_GB', 8);
if (EXPECTED_GB > BUDGET_GB && readNum(ENV, 'ALLOW_DISK', 0) <= 0) {
  throw new Error(`upload.js: about ${EXPECTED_GB.toFixed(1)} GB of uploads expected (> DISK_BUDGET_GB ${BUDGET_GB}); ` +
    'shorten HOLD_S/RATES or set ALLOW_DISK=1 after checking df on the server');
}
const RTT = requireRtt(CONFIG, 'upload.js');
const MAX_PER_S = maxTarget(LEVELS.stages) / 60;
const MAX_VUS = readNum(ENV, 'MAX_VUS', 600);
const PRE_VUS = Math.min(MAX_VUS, Math.max(5, Math.ceil(MAX_PER_S * (SIZE === 'small' ? 2 : 10))));
// The 1.5 s / 3 s criteria are for the 0.8 MB upload (docs/perf/load/TRAFFIC_MODEL.md §8); a 20 MB upload is
// reported, not judged.
const LATENCY_EPS = SIZE === 'small' ? ['create'] : [];
const SCOPES = holdScopes(LEVELS.segments);
const REQUEST_TIMEOUT = readStr(ENV, 'REQUEST_TIMEOUT', '120s');
// The request timeout + 60 s: a normal end cuts off no upload (one still running would make the summary "unfinished").
const DRAIN_S = Math.ceil(durationS(REQUEST_TIMEOUT)) + 60;

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: '60s',
  scenarios: Object.assign({
    uploads: {
      executor: 'ramping-arrival-rate',
      exec: 'upload',
      startRate: 0,
      timeUnit: '1m',
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      stages: LEVELS.stages,
      gracefulStop: `${DRAIN_S}s`,
    },
  }, guardScenario(CONFIG, LEVELS.totalS, LEVELS.totalS + DRAIN_S)), // the guard returns when the uploads are done
  // bytesSentThresholds: per-stage upload bytes for the uplink ratio of the busiest stage (lib/summary.js)
  thresholds: merge.apply(null, [latencyThresholds(RTT, LATENCY_EPS, null), errorThresholds(null, true),
    { dropped_iterations: ['count==0'] }, bytesSentThresholds(SCOPES), flowCountThresholds(SCOPES)].concat(SCOPES.map((s) =>
    merge(latencyThresholds(RTT, LATENCY_EPS, s.scope), errorThresholds(s.scope, false))))),
};

const ctx = makeContext({
  cfg: CONFIG,
  tags: () => tagsAt(LEVELS.segments, exec.scenario.startTime, Date.now()),
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
    stages: SCOPES.map((s) => ({ index: s.index, label: s.segment.label, users: null,
      target: s.segment.target / 60, holdS: HOLD_S, holdEndS: s.segment.end })),
    extra: {
      size: SIZE,
      ratesPerS: RATES,
      photos: PATHS,
      bodyPhotoBytes: BYTES,
      expectedUploads: Math.round(EXPECTED),
      expectedUploadGB: Math.round(EXPECTED_GB * 100) / 100,
      server: (data.setup_data && data.setup_data.server) || null,
    },
  }, CONFIG);
}
