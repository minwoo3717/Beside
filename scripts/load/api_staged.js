// A1 (staged), A5 (SCENARIO=spike) and A6 (SCENARIO=soak): the mixed user flow at arrival rates converted from
// concurrent users. k6 VUs are not users: U users arrive as lambda = U / 1,800 flows per second (tiers.json T3
// ratio, CONVERSION_TIER to change it), plus U / 450 healthz-only sessions per second (BROWSE=0 turns them off) so the
// request mix matches model.py (one healthz per 6-minute session, 0.2 jobs per user-day).
//
//   k6 run -e BASE_URL=https://<domain> -e RUN_ID=a1r1 -e RTT_BASE_MS=23 -e SUMMARY_PATH=<run dir>/k6-summary.json \
//          scripts/load/api_staged.js
//
// Env: SCENARIO (staged|spike|soak), USERS=25,200,... (subset of A1 levels), RAMP_S, HOLD_S, SOAK_HOLD_S,
//      FAIL_RATIO (tiers failureRatio = 0.05; the photo filenames then carry FAIL_TOKEN and the job fails, then is
//      retried once), EXPECTED_FLOW_S (75: sizes preAllocatedVUs), PRE_VUS, MAX_VUS, BROWSE, GRACEFUL_STOP_S,
//      PROM_URL/PROM_ENV (abort guard), plus the common ones in lib/config.js.
import exec from 'k6/execution';
import { browsePerHourForUsers, CONFIG, ENV, jobsPerHourForUsers, photoPaths, readList, readNum, readStr, requireRtt }
  from './lib/config.js';
import { lazyBodies, openOnce, totalBytes } from './lib/bodies.js';
import { browseOnly, makeContext, userFlow } from './lib/flow.js';
import { guardScenario, guardTick } from './lib/guard.js';
import { preflight } from './lib/seed.js';
import { buildLevels, expectedIterations, holdScopes, maxTarget, tagsAt } from './lib/stages.js';
import { stagedThresholds } from './lib/thresholds.js';
import { summaryOutputs } from './lib/summary.js';

const TIERS = JSON.parse(open('./tiers.json'));
const PATHS = photoPaths(ENV, TIERS.defaults.photoCount, './out/fixtures/photo-400k.png');
const FILES = openOnce(PATHS, (p) => open(p, 'b'));
const BODIES = lazyBodies(CONFIG.runId, FILES, CONFIG.failToken);

const SCENARIO = readStr(ENV, 'SCENARIO', 'staged').toLowerCase();
const CONVERSION_TIER = readStr(ENV, 'CONVERSION_TIER', TIERS.load.conversionTier);
const LABELS = { staged: 'A1', spike: 'A5', soak: 'A6' };

function levelsFor(name) {
  if (name === 'staged') {
    const a1 = TIERS.load.a1;
    const users = readList(ENV, 'USERS', null);
    const rampS = readNum(ENV, 'RAMP_S', a1.rampS);
    const holdS = readNum(ENV, 'HOLD_S', a1.holdS);
    return (users ? users.map(Number) : a1.users).map((u) => ({ users: u, rampS, holdS, label: `${u} users` }));
  }
  if (name === 'spike') return TIERS.load.a5.map((l) => Object.assign({}, l));
  if (name === 'soak') {
    return TIERS.load.a6.map((l) => Object.assign({}, l, { holdS: readNum(ENV, 'SOAK_HOLD_S', l.holdS) }));
  }
  throw new Error(`SCENARIO must be staged, spike or soak (got '${name}')`);
}

const LEVELS = levelsFor(SCENARIO);
const FLOW = buildLevels(LEVELS.map((l) => ({
  target: jobsPerHourForUsers(TIERS, CONVERSION_TIER, l.users), users: l.users, rampS: l.rampS, holdS: l.holdS, label: l.label,
})));
const BROWSE_ON = readNum(ENV, 'BROWSE', 1) > 0;
const BROWSE = buildLevels(LEVELS.map((l) => ({
  target: browsePerHourForUsers(TIERS, CONVERSION_TIER, l.users), users: l.users, rampS: l.rampS, holdS: l.holdS, label: l.label,
})));

const MAX_PER_S = maxTarget(FLOW.stages) / 3600;
const EXPECTED_FLOW_S = readNum(ENV, 'EXPECTED_FLOW_S', 75);
const PRE_VUS = readNum(ENV, 'PRE_VUS', Math.max(5, Math.ceil(MAX_PER_S * EXPECTED_FLOW_S * 1.2)));
const MAX_VUS = readNum(ENV, 'MAX_VUS', Math.min(4000, Math.max(PRE_VUS, Math.ceil(MAX_PER_S * (CONFIG.maxWaitS + 60)))));
const GRACEFUL_S = readNum(ENV, 'GRACEFUL_STOP_S', CONFIG.maxWaitS + 120);
const FAIL_RATIO = readNum(ENV, 'FAIL_RATIO', TIERS.defaults.failureRatio);
const RTT = requireRtt(CONFIG, 'api_staged.js');
const EXPECTED_FLOWS = expectedIterations(FLOW.stages, 3600, 0);

const scenarios = {
  flows: {
    executor: 'ramping-arrival-rate',
    exec: 'flow',
    startRate: 0,
    timeUnit: '1h',
    preAllocatedVUs: PRE_VUS,
    maxVUs: MAX_VUS,
    stages: FLOW.stages,
    gracefulStop: `${GRACEFUL_S}s`,
  },
};
if (BROWSE_ON) {
  scenarios.browse = {
    executor: 'ramping-arrival-rate',
    exec: 'browse',
    startRate: 0,
    timeUnit: '1h',
    preAllocatedVUs: 5,
    maxVUs: readNum(ENV, 'BROWSE_MAX_VUS', 200),
    stages: BROWSE.stages,
    gracefulStop: '30s',
  };
}
Object.assign(scenarios, guardScenario(CONFIG, FLOW.totalS + GRACEFUL_S));

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: '60s',
  scenarios,
  thresholds: stagedThresholds({
    rttMs: RTT, endpoints: ['healthz', 'create', 'poll', 'asset'], flows: true, scopes: holdScopes(FLOW.segments),
  }),
};

const flowCtx = makeContext({
  cfg: CONFIG,
  body: BODIES.body,
  makeFailBody: BODIES.makeFailBody,
  failRatio: FAIL_RATIO,
  tags: () => tagsAt(FLOW.segments, exec.scenario.startTime, Date.now()),
});
const browseCtx = makeContext({ cfg: CONFIG, tags: () => tagsAt(BROWSE.segments, exec.scenario.startTime, Date.now()) });

export function setup() {
  return preflight(CONFIG);
}

export function flow() {
  userFlow(flowCtx);
}

export function browse() {
  browseOnly(browseCtx);
}

export function guard() {
  guardTick(CONFIG);
}

export function handleSummary(data) {
  const usersPerJob = (3600 * LEVELS[0].users) / Math.max(1, FLOW.stages[0].target);
  return summaryOutputs(data, {
    script: 'api_staged.js',
    scenario: LABELS[SCENARIO],
    stages: FLOW.segments.filter((s) => s.phase === 'hold').map((s) => ({
      index: s.index, label: s.label, users: s.users, target: s.target, holdS: s.end - s.start,
    })),
    extra: {
      conversionTier: CONVERSION_TIER,
      usersPerJobPerS: Math.round(usersPerJob),
      flowRatesPerHour: FLOW.stages.map((s) => s.target),
      browseRatesPerHour: BROWSE_ON ? BROWSE.stages.map((s) => s.target) : [],
      failRatio: FAIL_RATIO,
      photos: PATHS,
      photoBytes: totalBytes(FILES),
      expectedFlows: Math.round(EXPECTED_FLOWS),
      expectedUploadGB: Math.round((EXPECTED_FLOWS * totalBytes(FILES)) / 1e7) / 100,
      vus: { preAllocated: PRE_VUS, max: MAX_VUS },
      server: (data.setup_data && data.setup_data.server) || null,
    },
  }, CONFIG);
}
