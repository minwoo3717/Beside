// Settings shared by every load script, read from the environment (k6 -e NAME=value or the shell).
// Pure module: no k6 imports, so lib.test.mjs can run it under Node. Only public API paths are used; the
// Basic-auth paths of the Caddyfile (job list, admin, pets) are never called, so no credentials exist here.

const RAW_ENV = typeof __ENV !== 'undefined' ? __ENV : {};

export function readNum(env, name, dflt) {
  const raw = env[name];
  if (raw === undefined || raw === null || raw === '') return dflt;
  const value = Number(raw);
  if (!isFinite(value)) throw new Error(`${name} must be a number, got '${raw}'`);
  return value;
}

export function readStr(env, name, dflt) {
  const raw = env[name];
  return raw === undefined || raw === null || raw === '' ? dflt : String(raw);
}

export function readList(env, name, dflt) {
  const raw = readStr(env, name, '');
  if (!raw) return dflt;
  return raw.split(',').map((s) => s.trim()).filter((s) => s.length > 0);
}

// RUN_ID ends up in every filename (lt-<RUN_ID>-...) and Idempotency-Key (lt-<RUN_ID>-<uuid>, at most 128 chars).
// The mock worker fails any job whose filename contains the fail token, so a RUN_ID containing it would fail all jobs.
export function validRunId(runId, failToken) {
  if (!/^[A-Za-z0-9_.-]{1,40}$/.test(runId)) {
    throw new Error(`RUN_ID must be 1-40 characters of A-Z a-z 0-9 _ . - (got '${runId}')`);
  }
  if (failToken && runId.toLowerCase().indexOf(failToken.toLowerCase()) >= 0) {
    throw new Error(`RUN_ID '${runId}' contains the fail token '${failToken}': every job would end FAILED`);
  }
  return runId;
}

export function isLocalUrl(url) {
  return /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(\/|$)/i.test(url);
}

export function parseConfig(env) {
  const failToken = readStr(env, 'FAIL_TOKEN', 'fail');
  const baseUrl = readStr(env, 'BASE_URL', 'http://localhost:18080').replace(/\/+$/, '');
  const runId = validRunId(readStr(env, 'RUN_ID', 'local'), failToken);
  const rttRaw = readStr(env, 'RTT_BASE_MS', '');
  return {
    baseUrl,
    runId,
    failToken,
    local: isLocalUrl(baseUrl),
    rttBaseMs: rttRaw === '' ? null : readNum(env, 'RTT_BASE_MS', 0),
    pollIntervalS: readNum(env, 'POLL_INTERVAL_S', 2),
    maxWaitS: readNum(env, 'MAX_WAIT_S', 600),
    retryWaitS: readNum(env, 'RETRY_WAIT_S', 3),
    minAssetBytes: readNum(env, 'MIN_ASSET_BYTES', 0),
    healthzPerFlow: readNum(env, 'HEALTHZ_PER_FLOW', 1) > 0,
    summaryPath: readStr(env, 'SUMMARY_PATH', `scripts/load/out/k6-summary-${runId}.json`),
    uplinkMbps: readNum(env, 'UPLINK_MBPS', null),
    tier: readStr(env, 'TIER', ''),
    promUrl: readStr(env, 'PROM_URL', '').replace(/\/+$/, ''),
    promEnv: readStr(env, 'PROM_ENV', 'loadtest'),
  };
}

// The latency criteria are relative to RTT_base (A0 healthz p50). A remote run without it would judge against 0 ms
// of network delay, so it is refused instead of silently passing or failing.
export function requireRtt(cfg, script) {
  if (cfg.rttBaseMs !== null) return cfg.rttBaseMs;
  if (cfg.local) return 0;
  throw new Error(`${script}: set RTT_BASE_MS (healthz p50 from smoke.js) for a remote BASE_URL`);
}

// Photo files for POST /api/v1/jobs: PHOTOS=a.png,b.png or PHOTO_1/PHOTO_2..., else the default repeated `count` times.
// Relative paths are resolved by k6 against the calling script; the defaults assume a script in scripts/load/.
export function photoPaths(env, count, dflt) {
  const list = readList(env, 'PHOTOS', null);
  if (list) return list;
  const out = [];
  for (let i = 1; i <= count; i++) out.push(readStr(env, `PHOTO_${i}`, dflt));
  return out;
}

export function tierParams(doc, name) {
  if (!doc.tiers[name]) throw new Error(`unknown tier ${name}`);
  return Object.assign({}, doc.defaults, doc.tiers[name]);
}

// Users -> arrivals, from the model's two formulas: concurrent = DAU x share x sessionMin / 60 and
// lambda = DAU x jobsPerDau x share / 3600, so concurrent / lambda = sessionMin x 60 / jobsPerDau seconds.
// With the T3 values (6 min, 0.2 jobs per DAU) that is lambda = U / 1,800 per second, as in the plan.
export function usersPerJobPerS(doc, tierName) {
  const p = tierParams(doc, tierName);
  return (p.sessionMin * 60) / p.jobsPerDau;
}

export function jobsPerHourForUsers(doc, tierName, users) {
  return Math.round((users * 3600) / usersPerJobPerS(doc, tierName));
}

// Sessions that only open the app (healthz) without making a job: sessions/s minus jobs/s, per hour.
export function browsePerHourForUsers(doc, tierName, users) {
  const p = tierParams(doc, tierName);
  const sessionsPerHour = (users * 3600) / (p.sessionMin * 60);
  return Math.max(0, Math.round(sessionsPerHour * p.healthzPerSession - jobsPerHourForUsers(doc, tierName, users)));
}

export function uuid4() {
  const hex = [];
  for (let i = 0; i < 32; i++) hex.push(Math.floor(Math.random() * 16).toString(16));
  hex[12] = '4';
  hex[16] = ((parseInt(hex[16], 16) & 0x3) | 0x8).toString(16);
  const s = hex.join('');
  return `${s.slice(0, 8)}-${s.slice(8, 12)}-${s.slice(12, 16)}-${s.slice(16, 20)}-${s.slice(20)}`;
}

export const ENV = RAW_ENV;
export const CONFIG = parseConfig(RAW_ENV);
