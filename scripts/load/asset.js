// A4 asset delivery ceiling: GET /api/v1/jobs/{id}/asset at stepped rates 1 -> 40 downloads/s (tiers.json load.a4)
// on ASSET_JOBS completed jobs seeded in setup. The asset must be the 1.5 MB synthetic GLB on the load-test volume
// (the repository's sample-dog.glb is 0 B), so setup stops when a job's asset is smaller than MIN_ASSET_BYTES.
// Bandwidth per stage = load_assetBytes{stage} x 8 / 1e6 / the seconds the hold ran (summary verdict.stages[].assetMbps,
// computed by lib/summary.js from heldS, so a hold an abort cut short is not divided by the full HOLD_S).
//
//   k6 run -e BASE_URL=https://<domain> -e RUN_ID=a4r1 -e RTT_BASE_MS=23 scripts/load/asset.js
//
// Env: RATES=1,5 (subset), RAMP_S, HOLD_S, ASSET_JOBS (10), MIN_ASSET_BYTES (1000000; 0 for a local jar),
//      PRE_VUS (top rate x 6 s), MAX_VUS (800, at least PRE_VUS), PROM_URL.
import exec from 'k6/execution';
import { CONFIG, ENV, photoPaths, readList, readNum, requireRtt } from './lib/config.js';
import { lazyBodies, openOnce } from './lib/bodies.js';
import { countedFlow, downloadAsset, makeContext } from './lib/flow.js';
import { guardLoop, guardScenario } from './lib/guard.js';
import { preflight, seedJobs } from './lib/seed.js';
import { buildLevels, holdScopes, maxTarget, tagsAt } from './lib/stages.js';
import { errorThresholds, flowCountThresholds, latencyThresholds, merge } from './lib/thresholds.js';
import { markReady, summaryOutputs } from './lib/summary.js';

const TIERS = JSON.parse(open('./tiers.json'));
const A4 = TIERS.load.a4;
const PATHS = photoPaths(ENV, TIERS.defaults.photoCount, './out/fixtures/photo-400k.png');
const FILES = openOnce(PATHS, (p) => open(p, 'b'));
const BODIES = lazyBodies(CONFIG.runId, FILES, CONFIG.failToken, { tag: 'asset' });

const RATES = (readList(ENV, 'RATES', null) || A4.ratesPerS).map(Number);
const RAMP_S = readNum(ENV, 'RAMP_S', A4.rampS);
const HOLD_S = readNum(ENV, 'HOLD_S', A4.holdS);
const LEVELS = buildLevels(RATES.map((r) => ({ target: Math.round(r * 60), rampS: RAMP_S, holdS: HOLD_S, label: `${r}/s` })));
const ASSET_JOBS = readNum(ENV, 'ASSET_JOBS', 10);
const MIN_ASSET = readNum(ENV, 'MIN_ASSET_BYTES', 1000000);
const CFG = Object.assign({}, CONFIG, { minAssetBytes: MIN_ASSET });
const RTT = requireRtt(CONFIG, 'asset.js');
// k6 (v2.3.0) ramping-arrival-rate drops an iteration whenever no pre-allocated VU is free and only then starts one more
// VU, so every VU started mid-test costs a dropped iteration and maxVUs only caps them: PRE_VUS, not MAX_VUS, is the
// knob against drops (a run with drops has no breaking point, lib/summary.js). The default covers downloads up to the
// asset p99 limit (6 s) at the top rate, so a stage drops only once it is past that limit. An explicit PRE_VUS is used
// as given and lifts the default MAX_VUS; one above an explicit MAX_VUS is refused.
const PRE_VUS_SET = readNum(ENV, 'PRE_VUS', null);
const MAX_VUS = readNum(ENV, 'MAX_VUS', Math.max(800, PRE_VUS_SET || 0));
const PRE_VUS = PRE_VUS_SET !== null ? PRE_VUS_SET : Math.min(MAX_VUS, Math.max(5, Math.ceil((maxTarget(LEVELS.stages) / 60) * 6)));
if (!(Number.isInteger(PRE_VUS) && PRE_VUS >= 1 && PRE_VUS <= MAX_VUS)) {
  throw new Error(`asset.js: PRE_VUS ${PRE_VUS} must be a whole number from 1 to MAX_VUS ${MAX_VUS}`);
}

const scopes = holdScopes(LEVELS.segments);
const bandwidth = {};
scopes.forEach((s) => { bandwidth[`load_assetBytes{${s.scope}}`] = ['count>=0']; }); // keeps per-stage bytes in the summary

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: '20m',
  scenarios: Object.assign({
    downloads: {
      executor: 'ramping-arrival-rate',
      exec: 'download',
      startRate: 0,
      timeUnit: '1m',
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      stages: LEVELS.stages,
      gracefulStop: '2m', // the 60 s request timeout + 60 s: a normal end cuts off no download
    },
  }, guardScenario(CONFIG, LEVELS.totalS, LEVELS.totalS + 120)), // the guard returns when the downloads are done
  thresholds: merge.apply(null, [latencyThresholds(RTT, ['asset'], null), errorThresholds(null, true),
    { dropped_iterations: ['count==0'] }, bandwidth, flowCountThresholds(scopes)].concat(scopes.map((s) =>
    merge(latencyThresholds(RTT, ['asset'], s.scope), errorThresholds(s.scope, false))))),
};

const ctx = makeContext({ cfg: CFG, tags: () => tagsAt(LEVELS.segments, exec.scenario.startTime, Date.now()) });

export function setup() {
  const info = preflight(CONFIG);
  const seeded = seedJobs(CONFIG, BODIES.body(), ASSET_JOBS, { wait: true, waitS: readNum(ENV, 'SEED_WAIT_S', 900) });
  if (seeded.completed.length === 0) throw new Error('setup: no seeded job reached COMPLETED');
  const small = seeded.completed.filter((j) => !j.asset || j.asset.bytes < MIN_ASSET);
  if (small.length > 0) {
    throw new Error(`setup: asset is ${small[0].asset ? small[0].asset.bytes : 'missing'} bytes < MIN_ASSET_BYTES ${MIN_ASSET}; ` +
      'put the 1.5 MB synthetic GLB on the load-test volume (deploy/loadtest) or lower MIN_ASSET_BYTES for a local check');
  }
  return markReady(Object.assign(info, { jobs: seeded.completed }));
}

export function download(data) {
  const job = data.jobs[Math.floor(Math.random() * data.jobs.length)];
  countedFlow(ctx, () => downloadAsset(ctx, job.id, { asset: job.asset }));
}

export function guard() {
  guardLoop(CONFIG, LEVELS.totalS);
}

export function handleSummary(data) {
  return summaryOutputs(data, {
    script: 'asset.js',
    scenario: 'A4',
    plannedS: LEVELS.totalS,
    thresholds: options.thresholds,
    // verdict.stages[].assetMbps comes from load_assetBytes{stage:N,phase:hold} and the hold's heldS (lib/summary.js)
    stages: scopes.map((s) => ({ index: s.index, label: s.segment.label, users: null, target: s.segment.target / 60, holdS: HOLD_S,
      holdEndS: s.segment.end })),
    extra: {
      ratesPerS: RATES,
      assetJobs: ASSET_JOBS,
      vus: { preAllocated: PRE_VUS, max: MAX_VUS },
      assetBytes: data.setup_data && data.setup_data.jobs ? data.setup_data.jobs[0].asset.bytes : null,
      server: (data.setup_data && data.setup_data.server) || null,
    },
  }, CFG);
}
