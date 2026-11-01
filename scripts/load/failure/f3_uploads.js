// F3 bad uploads: each case must get exactly the status and error.code of docs/api/ERROR_CODES.md, while a
// background poller checks that GET /api/v1/jobs/{id} keeps its p95 (docs/perf/load/TRAFFIC_MODEL.md §9, F3).
//
//   case            body                                              expected
//   oversize_6mb    1 photo of 6,000,000 B                            413 PAYLOAD_TOO_LARGE (5 MB per photo)
//   total_21mb      5 photos of 4,200,000 B (21 MB)                   413 PAYLOAD_TOO_LARGE (20 MB per request)
//   total_30mb      7 photos of 4,300,000 B (30.1 MB)                 413 PAYLOAD_TOO_LARGE; behind Caddy
//                                                                     (request_body 25MB) a bare 413 from the proxy
//   photos_11       11 small PNGs                                     400 TOO_MANY_PHOTOS
//   photos_0        multipart with no `photos` part                   400 NO_PHOTOS
//   empty_file      one 0-byte image/png part                         400 INVALID_REQUEST
//   gif             image/gif part with GIF bytes                     400 UNSUPPORTED_IMAGE_TYPE
//   fake_jpg        image/jpeg part whose bytes are text              400 UNSUPPORTED_IMAGE_TYPE with upload.verify-magic
//   gif_as_jpeg     image/jpeg part whose bytes are a GIF             (prod profile); 202 without it (VERIFY_MAGIC=0)
// Each VU sends every case once per iteration (a "round"), F3_REPEATS rounds. A round is counted as a flow
// (load_flowStarted / load_flowEnded), so a round k6 cut off at the end of gracefulStop makes the run "unfinished"
// (lib/summary.js), and dropped_iterations count==0 fails a run whose rounds did not all start before maxDuration
// (F3_BG_S): either way fewer uploads than planned were judged (meta.cases[].checks vs expectedChecks).
//
// The slow 10 kB/s upload of F3 is failure/f3_throttled.py, started next to this script so that it runs while the
// background poller does (k6 cannot throttle one request body); see that file for the command and its expected result.
//
// Photo names, so that the server's disk can be checked for leftovers after the run (this script cannot see it):
//   lt-<RUN_ID>-f3bad-<case>-<n>.<ext>   a case the server must reject: none of these may be stored
//   lt-<RUN_ID>-f3acc-<case>-<n>.<ext>   a case the server accepts (fake_jpg, gif_as_jpeg with VERIFY_MAGIC=0)
//   lt-<RUN_ID>-f3bg-<n>.png             the background job of setup() (2 photos; meta.pollJob)
//   lt-<RUN_ID>-f3throttle-1.png         the throttled upload of failure/f3_throttled.py
// The server stores a photo as <jobId>_<name>, so a stored file containing "_lt-<RUN_ID>-f3bad-" is a leftover
// (f3_throttled.py --uploads-dir counts them for a local server).
//
//   k6 run -e BASE_URL=https://<domain> -e RUN_ID=f3r1 -e RTT_BASE_MS=23 scripts/load/failure/f3_uploads.js
//
// Env: F3_VUS (2), F3_REPEATS (10 rounds per VU), POLL_RPS (20), F3_BG_S (180), VERIFY_MAGIC (1), PROXY_LIMIT_BYTES
//      (25000000 for an https BASE_URL, else 0 = no proxy), CASES=gif,photos_0 (subset).
import { Counter, Rate } from 'k6/metrics';
import { CONFIG, ENV, readList, readNum, requireRtt } from '../lib/config.js';
import { call, makeContext } from '../lib/flow.js';
import * as m from '../lib/metrics.js';
import { buildMultipart, photoParts, signedBytes, utf8 } from '../lib/multipart.js';
import { preflight, seedJobs } from '../lib/seed.js';
import { errorThresholds, latencyThresholds, merge } from '../lib/thresholds.js';
import { markReady, summaryOutputs } from '../lib/summary.js';

const matchRatio = new Rate('load_f3MatchRatio');
const resetCount = new Counter('load_f3ResetCount');

const VERIFY_MAGIC = readNum(ENV, 'VERIFY_MAGIC', 1) > 0;
const PROXY_LIMIT = readNum(ENV, 'PROXY_LIMIT_BYTES', /^https:/i.test(CONFIG.baseUrl) ? 25000000 : 0);
const RUN = CONFIG.runId;

// The name tags above (meta.photoNames); a case's tag follows its expected status, so "f3bad" always means "must not
// be stored", with or without VERIFY_MAGIC.
const NAMES = {
  rejected: `lt-${RUN}-f3bad-`,
  accepted: `lt-${RUN}-f3acc-`,
  background: `lt-${RUN}-f3bg-`,
  throttled: `lt-${RUN}-f3throttle-`,
};

function photos(kind, size, n, contentType, ext, tag) {
  const out = [];
  for (let i = 0; i < n; i++) out.push(signedBytes(kind, size));
  return photoParts(RUN, out, { tag, contentType, ext });
}

const magicCase = VERIFY_MAGIC ? { status: 400, code: 'UNSUPPORTED_IMAGE_TYPE' } : { status: 202, code: null };
const ALL_CASES = [
  { name: 'oversize_6mb', status: 413, code: 'PAYLOAD_TOO_LARGE', parts: (t) => photos('png', 6000000, 1, 'image/png', 'png', t) },
  { name: 'total_21mb', status: 413, code: 'PAYLOAD_TOO_LARGE', parts: (t) => photos('png', 4200000, 5, 'image/png', 'png', t) },
  { name: 'total_30mb', status: 413, code: 'PAYLOAD_TOO_LARGE', parts: (t) => photos('png', 4300000, 7, 'image/png', 'png', t) },
  { name: 'photos_11', status: 400, code: 'TOO_MANY_PHOTOS', parts: (t) => photos('png', 1024, 11, 'image/png', 'png', t) },
  { name: 'photos_0', status: 400, code: 'NO_PHOTOS', parts: () => [{ name: 'note', data: 'no photos here' }] },
  { name: 'empty_file', status: 400, code: 'INVALID_REQUEST', parts: (t) => photos('png', 0, 1, 'image/png', 'png', t) },
  { name: 'gif', status: 400, code: 'UNSUPPORTED_IMAGE_TYPE', parts: (t) => photos('gif', 2048, 1, 'image/gif', 'gif', t) },
  { name: 'fake_jpg', status: magicCase.status, code: magicCase.code,
    parts: (t) => photoParts(RUN, [utf8('this is not a jpeg, only text with a .jpg name')], { tag: t, contentType: 'image/jpeg', ext: 'jpg' }) },
  { name: 'gif_as_jpeg', status: magicCase.status, code: magicCase.code, parts: (t) => photos('gif', 2048, 1, 'image/jpeg', 'jpg', t) },
];
const ONLY = readList(ENV, 'CASES', null);
const CASES = ONLY ? ALL_CASES.filter((c) => ONLY.indexOf(c.name) >= 0) : ALL_CASES;
if (CASES.length === 0) throw new Error('CASES matched no case');

function tagFor(c) {
  return `${c.status >= 400 ? 'f3bad' : 'f3acc'}-${c.name}`;
}

const BG_S = readNum(ENV, 'F3_BG_S', 180);
const VUS = readNum(ENV, 'F3_VUS', 2);
const REPEATS = readNum(ENV, 'F3_REPEATS', 10);
const RTT = requireRtt(CONFIG, 'f3_uploads.js');
const caseThresholds = { load_f3MatchRatio: ['rate==1'] };
CASES.forEach((c) => { caseThresholds[`load_f3MatchRatio{case:${c.name}}`] = ['rate==1']; });

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: '5m',
  scenarios: {
    bad: {
      executor: 'per-vu-iterations',
      exec: 'bad',
      vus: VUS,
      iterations: REPEATS,
      maxDuration: `${BG_S}s`,
      startTime: '5s',
    },
    background: {
      executor: 'constant-arrival-rate',
      exec: 'background',
      rate: readNum(ENV, 'POLL_RPS', 20),
      timeUnit: '1s',
      duration: `${BG_S + 10}s`,
      preAllocatedVUs: 10,
      maxVUs: 50,
    },
  },
  // dropped_iterations: rounds that maxDuration (F3_BG_S) stopped before they started, and polls the poller could not
  // send. The case rates are rate==1, which holds however few samples there were, so without it a cut run passed.
  thresholds: merge(caseThresholds, latencyThresholds(RTT, ['poll'], null), errorThresholds(null, false),
    { dropped_iterations: ['count==0'] }),
};

const ctx = makeContext({ cfg: CONFIG, requestTimeout: '120s' });
const BODY_CACHE = {};

function bodyFor(c) {
  if (!BODY_CACHE[c.name]) BODY_CACHE[c.name] = buildMultipart(c.parts(tagFor(c)));
  return BODY_CACHE[c.name];
}

export function setup() {
  const info = preflight(CONFIG);
  const one = buildMultipart(photos('png', 4096, 2, 'image/png', 'png', 'f3bg'));
  const seeded = seedJobs(CONFIG, one, 1, { wait: false });
  if (seeded.created.length === 0) throw new Error('setup: could not create the background job');
  return markReady(Object.assign(info, { pollJob: seeded.created[0] }));
}

export function bad() {
  m.flowStarted.add(1);
  CASES.forEach((c) => {
    const body = bodyFor(c);
    const viaProxy = PROXY_LIMIT > 0 && body.bytes > PROXY_LIMIT;
    const r = call(ctx, 'create', '/api/v1/jobs', 'POST', `${CONFIG.baseUrl}/api/v1/jobs`, body.body, {
      expected: [c.status],
      responseType: 'text',
      headers: { 'Content-Type': body.contentType, 'Idempotency-Key': `lt-${RUN}-f3-${c.name}-${Math.random().toString(16).slice(2)}` },
      tags: { case: c.name },
    });
    if (r.res.status === 0) resetCount.add(1, { case: c.name });
    let code = null;
    try { code = r.res.json('error.code'); } catch (e) { code = null; }
    // The proxy answers an over-limit body with its own 413 (no v1 envelope); the code is checked only past Spring.
    const codeOk = c.code === null || code === c.code || (viaProxy && c.status === 413 && r.res.status === 413);
    matchRatio.add(r.res.status === c.status && codeOk ? 1 : 0, { case: c.name });
  });
  m.flowEnded.add(1);
}

export function background(data) {
  const r = call(ctx, 'poll', '/api/v1/jobs/{id}', 'GET', `${CONFIG.baseUrl}/api/v1/jobs/${data.pollJob}`, null, { expected: [200] });
  m.pollMs.add(r.res.timings.duration, r.tags);
}

function orDash(x) {
  return x === null || x === undefined ? '-' : x;
}

// One line per case on stdout: a run that judged fewer uploads than planned shows here, not only in the JSON.
function caseLines(cases) {
  const lines = [`cases (planned uploads per case: F3_VUS ${VUS} x F3_REPEATS ${REPEATS} = ${VUS * REPEATS}):`];
  cases.forEach((c) => {
    const expected = `${c.expectedStatus} ${c.expectedCode || ''}`.trim();
    const short = c.checks !== c.expectedChecks ? '  FEWER THAN PLANNED' : '';
    lines.push(`  ${c.name.padEnd(13)} ${expected.padEnd(27)} match ${String(orDash(c.matchRate)).padEnd(6)} ` +
      `uploads ${orDash(c.checks)}/${c.expectedChecks}${short}`);
  });
  lines.push(`leftover check after the run: no stored upload may contain "_${NAMES.rejected}" (failure/f3_throttled.py --uploads-dir)`);
  return lines.join('\n') + '\n';
}

export function handleSummary(data) {
  const metrics = data.metrics || {};
  const cases = CASES.map((c) => {
    const mr = metrics[`load_f3MatchRatio{case:${c.name}}`];
    const checks = mr && mr.values ? (mr.values.passes || 0) + (mr.values.fails || 0) : null;
    // k6 keeps a thresholded submetric with no sample at rate 0: no upload of that case was judged, so null, not 0
    return { name: c.name, expectedStatus: c.status, expectedCode: c.code, matchRate: checks ? mr.values.rate : null, checks,
      expectedChecks: VUS * REPEATS, photoPrefix: c.name === 'photos_0' ? null : `lt-${RUN}-${tagFor(c)}-` };
  });
  const out = summaryOutputs(data, {
    script: 'failure/f3_uploads.js',
    scenario: 'F3',
    plannedS: BG_S + 10, // the background poller's duration
    thresholds: options.thresholds,
    stages: [],
    extra: {
      verifyMagic: VERIFY_MAGIC,
      proxyLimitBytes: PROXY_LIMIT,
      cases,
      resets: metrics.load_f3ResetCount ? metrics.load_f3ResetCount.values.count : 0,
      server: (data.setup_data && data.setup_data.server) || null,
      pollJob: (data.setup_data && data.setup_data.pollJob) || null,
      photoNames: NAMES,
    },
  }, CONFIG);
  out.stdout += caseLines(cases);
  return out;
}
