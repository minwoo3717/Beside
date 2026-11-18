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
//      retried once), EXPECTED_FLOW_S (75: sizes preAllocatedVUs), PRE_VUS, MAX_VUS (these three: the flows scenario),
//      BROWSE, BROWSE_PRE_VUS (the browse scenario's pre-allocated VUs, default the top browse rate x 10 s, at least 5),
//      BROWSE_MAX_VUS (200, or BROWSE_PRE_VUS if larger), GRACEFUL_STOP_S, PROM_URL/PROM_ENV (abort guard), plus the
//      common ones in lib/config.js.
import exec from 'k6/execution';
import { browsePerHourForUsers, CONFIG, ENV, flowDrainS, jobsPerHourForUsers, photoPaths, readList, readNum, readStr,
  requireRtt } from './lib/config.js';
import { lazyBodies, openOnce, totalBytes } from './lib/bodies.js';
import { browseOnly, makeContext, userFlow } from './lib/flow.js';
import { guardLoop, guardScenario } from './lib/guard.js';
import { preflight } from './lib/seed.js';
import { buildLevels, expectedIterations, holdScopes, maxTarget, tagsAt } from './lib/stages.js';
import { droppedReportThresholds, merge, stagedThresholds } from './lib/thresholds.js';
import { markReady, summaryOutputs } from './lib/summary.js';

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

// browse: one healthz per iteration. ramping-arrival-rate drops an iteration whenever no pre-allocated VU is free and
// only then starts one more (up to maxVUs), and this script cannot tie a drop to a stage, so one drop turns every "pass"
// "invalid" (lib/summary.js). On the VM on 2026-11-18 the 5 VUs this scenario then had dropped 12 healthz at 9,000 users
// (20/s, RTT 154 ms), and PRE_VUS only sizes the flows. The default covers 10 s of the top browse rate (200 VUs at 9,000
// users), above the 7.8-8.3 s poll p99 of the VM's collapse in A2; a browse VU builds no upload body (about 1.1 MB each,
// README "디스크와 VU"). An explicit BROWSE_PRE_VUS is used as given; an explicit BROWSE_MAX_VUS below it is refused.
const BROWSE_PRE_VUS = readNum(ENV, 'BROWSE_PRE_VUS', Math.max(5, Math.ceil((maxTarget(BROWSE.stages) / 3600) * 10)));
const BROWSE_MAX_VUS = readNum(ENV, 'BROWSE_MAX_VUS', Math.max(200, BROWSE_PRE_VUS));
if (BROWSE_ON && !(Number.isInteger(BROWSE_PRE_VUS) && BROWSE_PRE_VUS >= 1)) {
  throw new Error(`api_staged.js: BROWSE_PRE_VUS ${BROWSE_PRE_VUS} must be a whole number of at least 1`);
}
if (BROWSE_ON && !(BROWSE_MAX_VUS >= BROWSE_PRE_VUS)) {
  throw new Error(`api_staged.js: BROWSE_PRE_VUS ${BROWSE_PRE_VUS} > BROWSE_MAX_VUS ${BROWSE_MAX_VUS}; raise BROWSE_MAX_VUS or lower BROWSE_PRE_VUS`);
}

const MAX_PER_S = maxTarget(FLOW.stages) / 3600;
const EXPECTED_FLOW_S = readNum(ENV, 'EXPECTED_FLOW_S', 75);
const PRE_VUS = readNum(ENV, 'PRE_VUS', Math.max(5, Math.ceil(MAX_PER_S * EXPECTED_FLOW_S * 1.2)));
const MAX_VUS = readNum(ENV, 'MAX_VUS', Math.min(4000, Math.max(PRE_VUS, Math.ceil(MAX_PER_S * (CONFIG.maxWaitS + 60)))));
// Default: the longest flow (a FAILED wait, the retry and a second wait) + 120 s, so a normal end cuts off no flow; a
// flow still running when the drain ends makes the summary "unfinished" (no pass, no breaking point).
const GRACEFUL_S = readNum(ENV, 'GRACEFUL_STOP_S', flowDrainS(CONFIG));
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
    preAllocatedVUs: BROWSE_PRE_VUS,
    maxVUs: BROWSE_MAX_VUS,
    stages: BROWSE.stages,
    gracefulStop: '30s',
  };
}
// The guard returns when the flows (and the browse sessions) are done, at most at the end of their gracefulStop.
Object.assign(scenarios, guardScenario(CONFIG, FLOW.totalS, FLOW.totalS + Math.max(GRACEFUL_S, 30)));

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: '60s',
  scenarios,
  // dropped_iterations{scenario:flows|browse} only report (verdict.droppedByScenario): k6 tags a drop with its scenario
  // and no stage, so they say which scenario ran short of VUs, not which stage
  thresholds: merge(stagedThresholds({
    rttMs: RTT, endpoints: ['healthz', 'create', 'poll', 'asset'], flows: true, uploads: true, scopes: holdScopes(FLOW.segments),
  }), droppedReportThresholds(BROWSE_ON ? ['flows', 'browse'] : ['flows'])),
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
  return markReady(preflight(CONFIG));
}

export function flow() {
  userFlow(flowCtx);
}

export function browse() {
  browseOnly(browseCtx);
}

export function guard() {
  guardLoop(CONFIG, FLOW.totalS);
}

export function handleSummary(data) {
  const usersPerJob = (3600 * LEVELS[0].users) / Math.max(1, FLOW.stages[0].target);
  return summaryOutputs(data, {
    script: 'api_staged.js',
    scenario: LABELS[SCENARIO],
    // A5 goes 1,800 -> 5,400 -> 1,800 users and A6 holds one level: A1's "70 % of the last passing level" does not apply.
    breakingPoint: SCENARIO === 'staged',
    plannedS: FLOW.totalS,
    thresholds: options.thresholds,
    stages: FLOW.segments.filter((s) => s.phase === 'hold').map((s) => ({
      index: s.index, label: s.label, users: s.users, target: s.target, holdS: s.end - s.start, holdEndS: s.end,
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
      // preAllocated and max: the flows scenario; browse: its own (null with BROWSE=0)
      vus: { preAllocated: PRE_VUS, max: MAX_VUS, browse: BROWSE_ON ? { preAllocated: BROWSE_PRE_VUS, max: BROWSE_MAX_VUS } : null },
      server: (data.setup_data && data.setup_data.server) || null,
    },
  }, CONFIG);
}
