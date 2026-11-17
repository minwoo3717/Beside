// A0 smoke test: one user flow every 10 s for 3 min. Checks that the flow works end to end and measures RTT_base,
// the healthz p50 that every later script's latency criteria are relative to (summary meta.rttBaseMsMeasured).
//
//   k6 run -e BASE_URL=http://localhost:18080 -e RUN_ID=local1 scripts/load/smoke.js
//
// Env: BASE_URL, RUN_ID, PHOTOS or PHOTO_1/PHOTO_2, SMOKE_VUS (5), SMOKE_MAX_VUS (20), FAIL_RATIO (0),
//      RTT_BASE_MS (optional here), SUMMARY_PATH, UPLINK_MBPS, MIN_ASSET_BYTES.
import { CONFIG, ENV, flowDrainS, photoPaths, readNum } from './lib/config.js';
import { lazyBodies, openOnce } from './lib/bodies.js';
import { makeContext, userFlow } from './lib/flow.js';
import { preflight } from './lib/seed.js';
import { errorThresholds, flowThresholds, latencyThresholds, merge } from './lib/thresholds.js';
import { markReady, summaryOutputs } from './lib/summary.js';

const TIERS = JSON.parse(open('./tiers.json'));
const SMOKE = TIERS.load.smoke;
const PATHS = photoPaths(ENV, TIERS.defaults.photoCount, './out/fixtures/photo-400k.png');
const FILES = openOnce(PATHS, (p) => open(p, 'b'));
const BODIES = lazyBodies(CONFIG.runId, FILES, CONFIG.failToken);

const EVERY_S = readNum(ENV, 'SMOKE_EVERY_S', SMOKE.flowEveryS);
const DURATION_S = readNum(ENV, 'SMOKE_DURATION_S', SMOKE.durationS);
const VUS = readNum(ENV, 'SMOKE_VUS', SMOKE.vus);
const MAX_VUS = Math.max(VUS, readNum(ENV, 'SMOKE_MAX_VUS', 20));
const FAIL_RATIO = readNum(ENV, 'FAIL_RATIO', 0);
// Latency criteria only when RTT_base is known (or the target is this PC); smoke is what measures it.
const RTT = CONFIG.rttBaseMs !== null ? CONFIG.rttBaseMs : CONFIG.local ? 0 : null;

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: '60s',
  scenarios: {
    smoke: {
      executor: 'constant-arrival-rate',
      exec: 'flow',
      rate: 1,
      timeUnit: `${EVERY_S}s`,
      duration: `${DURATION_S}s`,
      preAllocatedVUs: VUS,
      maxVUs: MAX_VUS,
      gracefulStop: `${flowDrainS(CONFIG)}s`, // the longest flow (with its retry) still ends: none is cut off
      tags: { stage: '0', phase: 'hold' },
    },
  },
  thresholds: merge(
    RTT === null ? {} : latencyThresholds(RTT, ['healthz', 'create', 'poll', 'asset'], null),
    errorThresholds(null, false),
    flowThresholds(null),
    { dropped_iterations: ['count==0'] },
  ),
};

const ctx = makeContext({ cfg: CONFIG, body: BODIES.body, makeFailBody: BODIES.makeFailBody, failRatio: FAIL_RATIO });

export function setup() {
  return markReady(preflight(CONFIG));
}

export function flow() {
  userFlow(ctx);
}

export function handleSummary(data) {
  return summaryOutputs(data, {
    script: 'smoke.js',
    scenario: 'A0',
    plannedS: DURATION_S,
    thresholds: options.thresholds,
    stages: [],
    extra: {
      flowEveryS: EVERY_S,
      durationS: DURATION_S,
      photos: PATHS,
      failRatio: FAIL_RATIO,
      server: (data.setup_data && data.setup_data.server) || null,
      note: 'RTT_BASE_MS for the other scripts = meta.rttBaseMsMeasured (healthz p50) of an A0 run against the same target.',
    },
  }, CONFIG);
}
