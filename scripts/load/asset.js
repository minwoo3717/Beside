// A4 asset delivery ceiling: GET /api/v1/jobs/{id}/asset at stepped rates 1 -> 40 downloads/s (tiers.json load.a4)
// on ASSET_JOBS completed jobs seeded in setup. The asset must be the 1.5 MB synthetic GLB on the load-test volume
// (the repository's sample-dog.glb is 0 B), so setup stops when a job's asset is smaller than MIN_ASSET_BYTES.
// Bandwidth per stage = load_assetBytes{stage} / hold seconds (in the summary's stage list as assetMbps).
//
//   k6 run -e BASE_URL=https://<domain> -e RUN_ID=a4r1 -e RTT_BASE_MS=23 scripts/load/asset.js
//
// Env: RATES=1,5 (subset), RAMP_S, HOLD_S, ASSET_JOBS (10), MIN_ASSET_BYTES (1000000; 0 for a local jar),
//      MAX_VUS (800), PROM_URL.
import exec from 'k6/execution';
import { CONFIG, ENV, photoPaths, readList, readNum, requireRtt } from './lib/config.js';
import { lazyBodies, openOnce } from './lib/bodies.js';
import { downloadAsset, makeContext } from './lib/flow.js';
import { guardLoop, guardScenario } from './lib/guard.js';
import { preflight, seedJobs } from './lib/seed.js';
import { buildLevels, holdScopes, maxTarget, tagsAt } from './lib/stages.js';
import { errorThresholds, latencyThresholds, merge } from './lib/thresholds.js';
import { summaryOutputs } from './lib/summary.js';

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
const MAX_VUS = readNum(ENV, 'MAX_VUS', 800);
const PRE_VUS = Math.min(MAX_VUS, Math.max(5, Math.ceil((maxTarget(LEVELS.stages) / 60) * 3)));

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
      gracefulStop: '1m',
    },
  }, guardScenario(CONFIG, LEVELS.totalS, LEVELS.totalS + 60)), // the guard returns when the downloads are done
  thresholds: merge.apply(null, [latencyThresholds(RTT, ['asset'], null), errorThresholds(null, true),
    { dropped_iterations: ['count==0'] }, bandwidth].concat(scopes.map((s) =>
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
  return Object.assign(info, { jobs: seeded.completed });
}

export function download(data) {
  const job = data.jobs[Math.floor(Math.random() * data.jobs.length)];
  downloadAsset(ctx, job.id, { asset: job.asset });
}

export function guard() {
  guardLoop(CONFIG, LEVELS.totalS);
}

export function handleSummary(data) {
  const metrics = data.metrics || {};
  return summaryOutputs(data, {
    script: 'asset.js',
    scenario: 'A4',
    stages: scopes.map((s) => {
      const bytes = metrics[`load_assetBytes{${s.scope}}`];
      const count = bytes && bytes.values ? bytes.values.count : null;
      return { index: s.index, label: s.segment.label, users: null, target: s.segment.target / 60, holdS: HOLD_S,
        assetMbps: count === null ? null : Math.round(((count * 8) / 1e6 / HOLD_S) * 100) / 100 };
    }),
    extra: {
      ratesPerS: RATES,
      assetJobs: ASSET_JOBS,
      assetBytes: data.setup_data && data.setup_data.jobs ? data.setup_data.jobs[0].asset.bytes : null,
      server: (data.setup_data && data.setup_data.server) || null,
    },
  }, CFG);
}
