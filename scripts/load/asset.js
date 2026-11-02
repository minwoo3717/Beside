// A4 asset delivery ceiling: GET /api/v1/jobs/{id}/asset at stepped rates 1 -> 40 downloads/s (tiers.json load.a4)
// on ASSET_JOBS completed jobs seeded in setup. The asset must be the 1.5 MB synthetic GLB on the load-test volume
// (the repository's sample-dog.glb is 0 B), so setup stops when a job's asset is smaller than MIN_ASSET_BYTES.
// Bandwidth per stage = load_assetBytes{stage} x 8 / 1e6 / the seconds the hold ran (summary verdict.stages[].assetMbps,
// computed by lib/summary.js from heldS, so a hold an abort cut short is not divided by the full HOLD_S).
// Each level (rate) is its own ramping-arrival-rate scenario lvl0, lvl1, ... (lib/stages.js levelScenarios), so k6
// ties dropped iterations to their level: a level that drops reads "fail", the earlier ones keep their verdict.
//
//   k6 run -e BASE_URL=https://<domain> -e RUN_ID=a4r1 -e RTT_BASE_MS=23 scripts/load/asset.js
//
// Env: RATES=1,5 (subset), RAMP_S, HOLD_S, ASSET_JOBS (10), MIN_ASSET_BYTES (1000000; 0 for a local jar),
//      PRE_VUS (VUs of the top level: top rate x 6 s; lower levels in proportion to their rate),
//      MAX_VUS (all levels together, each may grow to its share; 800 or more), PROM_URL.
import exec from 'k6/execution';
import { CONFIG, ENV, photoPaths, readList, readNum, requireRtt } from './lib/config.js';
import { lazyBodies, openOnce } from './lib/bodies.js';
import { countedFlow, downloadAsset, makeContext } from './lib/flow.js';
import { guardLoop, guardScenario } from './lib/guard.js';
import { preflight, seedJobs } from './lib/seed.js';
import { buildLevels, holdScopes, levelScenarios, levelTagsAt, maxTarget, shareMaxVUs } from './lib/stages.js';
import { droppedThresholds, errorThresholds, flowCountThresholds, latencyThresholds, merge } from './lib/thresholds.js';
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
const TOP = maxTarget(LEVELS.stages);
// k6 (v2.3.0) ramping-arrival-rate drops an iteration whenever no pre-allocated VU is free and only then starts one more
// VU, so every VU started mid-test costs a dropped iteration and maxVUs only caps them: pre-allocated VUs, not MAX_VUS,
// are the knob against drops. Each level gets the same seconds of cover: PRE_VUS at the top rate, PRE_VUS x rate / top
// rate below it (at least 1). The default PRE_VUS covers downloads up to the asset p99 limit (6 s), so a level drops
// once its downloads are slower than that; that level then reads "fail" (the drops are its own), which is right for a
// level past its limit. To measure such a level without drops, give PRE_VUS = the top rate x the seconds it needs (at
// most the 60 s request timeout); an explicit PRE_VUS is used as given.
const PRE_VUS_SET = readNum(ENV, 'PRE_VUS', null);
const PRE_VUS = PRE_VUS_SET !== null ? PRE_VUS_SET : Math.max(5, Math.ceil((TOP / 60) * 6));
if (!(Number.isInteger(PRE_VUS) && PRE_VUS >= 1)) throw new Error(`asset.js: PRE_VUS ${PRE_VUS} must be a whole number of at least 1`);
// The 60 s request timeout + 60 s: the end of a level (and of the run) cuts off no download. A level keeps its VUs
// through this drain, so the VUs of the levels whose drains overlap exist at once: meta.vus.peakPreAllocated (two
// neighbouring levels with the tiers.json 30 s + 180 s levels).
const DRAIN_S = 120;
const LV = levelScenarios(LEVELS, 'lvl', DRAIN_S, (lv) => ({
  exec: 'download',
  timeUnit: '1m',
  preAllocatedVUs: Math.max(1, Math.ceil(TOP > 0 ? (PRE_VUS * lv.target) / TOP : PRE_VUS)),
}));
// MAX_VUS caps the VUs of all levels together (each level may grow to its share, meta.vus.maxByLevel, lib/stages.js
// shareMaxVUs); its default is 800 or the peak of the pre-allocated VUs, and an explicit one below that peak is refused.
const MAX_VUS = readNum(ENV, 'MAX_VUS', Math.max(800, LV.peakPreAllocated));
if (!(MAX_VUS >= LV.peakPreAllocated)) {
  throw new Error(`asset.js: the pre-allocated VUs of the levels that run at once reach ${LV.peakPreAllocated} (PRE_VUS ${PRE_VUS} ` +
    `at the top level) > MAX_VUS ${MAX_VUS}; raise MAX_VUS or lower PRE_VUS`);
}
shareMaxVUs(LV, MAX_VUS);

const scopes = holdScopes(LEVELS.segments);
const bandwidth = {};
scopes.forEach((s) => { bandwidth[`load_assetBytes{${s.scope}}`] = ['count>=0']; }); // keeps per-stage bytes in the summary

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: '20m',
  // the guard returns when the last level's downloads are done
  scenarios: Object.assign({}, LV.scenarios, guardScenario(CONFIG, LEVELS.totalS, LEVELS.totalS + DRAIN_S)),
  thresholds: merge.apply(null, [latencyThresholds(RTT, ['asset'], null), errorThresholds(null, true),
    { dropped_iterations: ['count==0'] }, droppedThresholds(LV.names), bandwidth, flowCountThresholds(scopes)].concat(scopes.map((s) =>
    merge(latencyThresholds(RTT, ['asset'], s.scope), errorThresholds(s.scope, false))))),
};

const ctx = makeContext({
  cfg: CFG,
  tags: () => levelTagsAt(LEVELS.segments, LV.startS, exec.scenario.name, exec.scenario.startTime, Date.now()),
});

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
    // verdict.stages[].assetMbps comes from load_assetBytes{stage:N,phase:hold} and the hold's heldS (lib/summary.js);
    // scenario: dropped_iterations{scenario:lvlN} fails level N (lib/summary.js stageVerdicts)
    stages: scopes.map((s) => ({ index: s.index, label: s.segment.label, users: null, target: s.segment.target / 60, holdS: HOLD_S,
      holdEndS: s.segment.end, scenario: `lvl${s.index}` })),
    extra: {
      ratesPerS: RATES,
      assetJobs: ASSET_JOBS,
      vus: { preAllocated: PRE_VUS, max: MAX_VUS, byLevel: LV.names.map((n) => LV.scenarios[n].preAllocatedVUs),
        peakPreAllocated: LV.peakPreAllocated, maxByLevel: LV.names.map((n) => LV.scenarios[n].maxVUs) },
      assetBytes: data.setup_data && data.setup_data.jobs ? data.setup_data.jobs[0].asset.bytes : null,
      server: (data.setup_data && data.setup_data.server) || null,
    },
  }, CFG);
}
