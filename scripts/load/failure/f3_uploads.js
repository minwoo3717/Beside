// F3 bad uploads: each case must get exactly the status and error.code of docs/api/ERROR_CODES.md, while a
// background poller checks that GET /api/v1/jobs/{id} keeps its p95 (plan section 3, F3).
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
// The slow 10 kB/s upload of the plan is a curl command in scripts/load/README.md (k6 cannot throttle one request).
// Leftover files are checked on the server after the run (README); this script cannot see the disk.
//
//   k6 run -e BASE_URL=https://<domain> -e RUN_ID=f3r1 -e RTT_BASE_MS=23 scripts/load/failure/f3_uploads.js
//
// Env: F3_VUS (2), F3_REPEATS (10 per VU), POLL_RPS (20), F3_BG_S (180), VERIFY_MAGIC (1), PROXY_LIMIT_BYTES
//      (25000000 for an https BASE_URL, else 0 = no proxy), CASES=gif,photos_0 (subset).
import { Counter, Rate } from 'k6/metrics';
import { CONFIG, ENV, readList, readNum, requireRtt } from '../lib/config.js';
import { call, makeContext } from '../lib/flow.js';
import * as m from '../lib/metrics.js';
import { buildMultipart, photoParts, signedBytes, utf8 } from '../lib/multipart.js';
import { preflight, seedJobs } from '../lib/seed.js';
import { errorThresholds, latencyThresholds, merge } from '../lib/thresholds.js';
import { summaryOutputs } from '../lib/summary.js';

const matchRatio = new Rate('load_f3MatchRatio');
const resetCount = new Counter('load_f3ResetCount');

const VERIFY_MAGIC = readNum(ENV, 'VERIFY_MAGIC', 1) > 0;
const PROXY_LIMIT = readNum(ENV, 'PROXY_LIMIT_BYTES', /^https:/i.test(CONFIG.baseUrl) ? 25000000 : 0);
const RUN = CONFIG.runId;

function photos(kind, size, n, contentType, ext) {
  const out = [];
  for (let i = 0; i < n; i++) out.push(signedBytes(kind, size));
  return photoParts(RUN, out, { tag: 'f3', contentType, ext });
}

const magicCase = VERIFY_MAGIC ? { status: 400, code: 'UNSUPPORTED_IMAGE_TYPE' } : { status: 202, code: null };
const ALL_CASES = [
  { name: 'oversize_6mb', status: 413, code: 'PAYLOAD_TOO_LARGE', parts: () => photos('png', 6000000, 1, 'image/png', 'png') },
  { name: 'total_21mb', status: 413, code: 'PAYLOAD_TOO_LARGE', parts: () => photos('png', 4200000, 5, 'image/png', 'png') },
  { name: 'total_30mb', status: 413, code: 'PAYLOAD_TOO_LARGE', parts: () => photos('png', 4300000, 7, 'image/png', 'png') },
  { name: 'photos_11', status: 400, code: 'TOO_MANY_PHOTOS', parts: () => photos('png', 1024, 11, 'image/png', 'png') },
  { name: 'photos_0', status: 400, code: 'NO_PHOTOS', parts: () => [{ name: 'note', data: 'no photos here' }] },
  { name: 'empty_file', status: 400, code: 'INVALID_REQUEST', parts: () => photos('png', 0, 1, 'image/png', 'png') },
  { name: 'gif', status: 400, code: 'UNSUPPORTED_IMAGE_TYPE', parts: () => photos('gif', 2048, 1, 'image/gif', 'gif') },
  { name: 'fake_jpg', status: magicCase.status, code: magicCase.code,
    parts: () => photoParts(RUN, [utf8('this is not a jpeg, only text with a .jpg name')], { tag: 'f3', contentType: 'image/jpeg', ext: 'jpg' }) },
  { name: 'gif_as_jpeg', status: magicCase.status, code: magicCase.code, parts: () => photos('gif', 2048, 1, 'image/jpeg', 'jpg') },
];
const ONLY = readList(ENV, 'CASES', null);
const CASES = ONLY ? ALL_CASES.filter((c) => ONLY.indexOf(c.name) >= 0) : ALL_CASES;
if (CASES.length === 0) throw new Error('CASES matched no case');

const BG_S = readNum(ENV, 'F3_BG_S', 180);
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
      vus: readNum(ENV, 'F3_VUS', 2),
      iterations: readNum(ENV, 'F3_REPEATS', 10),
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
  thresholds: merge(caseThresholds, latencyThresholds(RTT, ['poll'], null), errorThresholds(null, false)),
};

const ctx = makeContext({ cfg: CONFIG, requestTimeout: '120s' });
const BODY_CACHE = {};

function bodyFor(c) {
  if (!BODY_CACHE[c.name]) BODY_CACHE[c.name] = buildMultipart(c.parts());
  return BODY_CACHE[c.name];
}

export function setup() {
  const info = preflight(CONFIG);
  const one = buildMultipart(photos('png', 4096, 2, 'image/png', 'png'));
  const seeded = seedJobs(CONFIG, one, 1, { wait: false });
  if (seeded.created.length === 0) throw new Error('setup: could not create the background job');
  return Object.assign(info, { pollJob: seeded.created[0] });
}

export function bad() {
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
}

export function background(data) {
  const r = call(ctx, 'poll', '/api/v1/jobs/{id}', 'GET', `${CONFIG.baseUrl}/api/v1/jobs/${data.pollJob}`, null, { expected: [200] });
  m.pollMs.add(r.res.timings.duration, r.tags);
}

export function handleSummary(data) {
  const metrics = data.metrics || {};
  return summaryOutputs(data, {
    script: 'failure/f3_uploads.js',
    scenario: 'F3',
    stages: [],
    extra: {
      verifyMagic: VERIFY_MAGIC,
      proxyLimitBytes: PROXY_LIMIT,
      cases: CASES.map((c) => {
        const mr = metrics[`load_f3MatchRatio{case:${c.name}}`];
        return { name: c.name, expectedStatus: c.status, expectedCode: c.code,
          matchRate: mr && mr.values ? mr.values.rate : null, checks: mr && mr.values ? mr.values.passes + mr.values.fails : null };
      }),
      resets: metrics.load_f3ResetCount ? metrics.load_f3ResetCount.values.count : 0,
      server: (data.setup_data && data.setup_data.server) || null,
    },
  }, CONFIG);
}
