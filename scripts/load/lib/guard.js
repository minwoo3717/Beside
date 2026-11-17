// Optional abort guard (docs/perf/load/TRAFFIC_MODEL.md §8 "중단 조건"): when PROM_URL points at the run's Prometheus (SSH tunnel or
// Tailscale, e.g. http://localhost:9090), one VU checks every GUARD_EVERY_S seconds
//   - the server's 5xx ratio over the last minute  > 5 %  (GUARD_BREACHES checks in a row)
//   - the fullest disk the backend reports          > 80 %
// and stops the test with exec.test.abort(). Its requests are tagged endpoint=guard and are kept out of the
// load_* metrics. Without PROM_URL there is no guard scenario. Knobs (lib/config.js cfg.guard): GUARD_EVERY_S (15),
// GUARD_BREACHES (2), GUARD_MAX_ERROR_RATIO (0.05), GUARD_MAX_DISK_RATIO (0.8), GUARD_BLIND_ABORT (3, see guardCheck).
//
// What the guard leaves for the summary (lib/metrics.js load_guard*): every query, every unreadable answer, every
// reading, and the reason of its abort, because k6 passes handleSummary no sign that the run was aborted.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import * as m from './metrics.js';

export const GUARD_ABORT = { errors: 1, disk: 2, blind: 3 };
export const GUARD_FAIL = { noResponse: 0, notPrometheus: -1, noDiskSeries: -2 };

export function guardQueries(env) {
  const sel = `env="${env}"`;
  return {
    errorRatio: `sum(rate(http_server_requests_seconds_count{${sel},status=~"5.."}[1m])) / clamp_min(sum(rate(http_server_requests_seconds_count{${sel}}[1m])), 1e-9)`,
    diskUsed: `max(1 - disk_free_bytes{${sel}} / disk_total_bytes{${sel}})`,
  };
}

// The guard is one long iteration (per-vu-iterations, 1 VU, 1 iteration) that returns as soon as the load is over.
// k6 ends a run only when every scenario has ended; the old constant-vus guard ran for a fixed time (the load's
// stages plus its whole gracefulStop, up to 12 min) and so kept every PROM_URL run alive and idle after the last flow,
// which also diluted the whole-run rates (data_sent, http_reqs). loadEndS: the planned end of the last load stage in
// seconds after the scenarios start; maxS: the latest the load can end (stages + its gracefulStop), a hard limit.
export function guardScenario(cfg, loadEndS, maxS) {
  if (!cfg.promUrl) return {};
  return {
    guard: {
      executor: 'per-vu-iterations', exec: 'guard', vus: 1, iterations: 1,
      maxDuration: `${Math.ceil(Math.max(loadEndS, maxS || 0))}s`, gracefulStop: '0s',
    },
  };
}

// One instant query: { value } (value null = no series), or { failed: code, why } when the answer cannot be read.
function instant(cfg, query) {
  m.guardQueries.add(1);
  const res = http.get(`${cfg.promUrl}/api/v1/query?query=${encodeURIComponent(query)}`,
    { tags: { endpoint: 'guard', name: 'prometheus' }, responseType: 'text', timeout: '10s' });
  if (!res.status) return { failed: GUARD_FAIL.noResponse, why: `no response from ${cfg.promUrl} (${res.error || 'connection failed'})` };
  if (res.status !== 200) return { failed: res.status, why: `HTTP ${res.status} from ${cfg.promUrl}` };
  let doc = null;
  try {
    doc = JSON.parse(res.body);
  } catch (e) {
    doc = null;
  }
  if (!doc || doc.status !== 'success' || !doc.data || !Array.isArray(doc.data.result)) {
    return { failed: GUARD_FAIL.notPrometheus, why: `the answer of ${cfg.promUrl} is not a Prometheus query result` };
  }
  const first = doc.data.result[0];
  const value = first && first.value ? Number(first.value[1]) : NaN;
  return { value: isFinite(value) ? value : null };
}

let breachStreak = 0;
let blindStreak = 0; // checks in a row in which a query could not be read
let everRead = false; // some check has read both queries

function stop(code, message) {
  m.guardAbort.add(code);
  exec.test.abort(message);
}

// One check: both queries, the readings and failures into load_guard*, then abort if a limit is crossed.
//
// Unreadable Prometheus (non-200, not a query result, no answer, or no disk series for PROM_ENV): the run is unguarded
// while it lasts, so the guard warns on stderr at the start of each such streak and when it ends, and counts every
// unreadable query (summary verdict.guard). Whether it aborts depends on when it happens:
//   - before any check has read Prometheus: GUARD_BLIND_ABORT (3) such checks in a row abort the run. The guard has
//     never worked, which is a set-up mistake (wrong PROM_URL or PROM_ENV, tunnel not up) made while the operator is
//     still at the terminal; going on would be a long run that only looks guarded. 0 turns this off.
//   - after Prometheus has answered once: never. A tunnel drop or a Prometheus restart in the middle of a long run
//     should not throw the run away; it is reported (warning, verdict.guard.failures) instead.
// An empty 5xx result is read as 0 (no 5xx series yet); an empty disk result is not, since the backend always
// exports disk_free_bytes, so it means PROM_ENV does not match the backend's METRICS_ENV or nothing is scraped.
export function guardCheck(cfg) {
  const g = cfg.guard;
  const q = guardQueries(cfg.promEnv);
  const errors = instant(cfg, q.errorRatio);
  let disk = instant(cfg, q.diskUsed);
  if (disk.failed === undefined && disk.value === null) {
    disk = { failed: GUARD_FAIL.noDiskSeries, why: `no disk_free_bytes series for env="${cfg.promEnv}" (PROM_ENV vs the backend's METRICS_ENV?)` };
  }
  const failures = [errors, disk].filter((r) => r.failed !== undefined);
  const why = failures.map((r) => r.why).filter((w, i, all) => all.indexOf(w) === i).join('; ');
  failures.forEach((r) => {
    m.guardFailures.add(1);
    m.guardLastFailure.add(r.failed);
  });
  if (failures.length > 0) {
    if (blindStreak === 0) {
      console.warn(`guard: cannot read Prometheus: ${why}. The run is NOT guarded until it answers.`);
    }
    blindStreak++;
  } else {
    if (blindStreak > 0) console.warn(`guard: Prometheus answers again after ${blindStreak} unreadable check(s)`);
    blindStreak = 0;
    everRead = true;
  }
  const errorRatio = errors.failed === undefined ? (errors.value === null ? 0 : errors.value) : null;
  if (errorRatio !== null) m.guardErrorRatio.add(errorRatio);
  if (disk.failed === undefined) m.guardDiskRatio.add(disk.value);
  breachStreak = errorRatio !== null && errorRatio > g.maxErrorRatio ? breachStreak + 1 : 0;
  if (breachStreak >= g.breaches) {
    stop(GUARD_ABORT.errors, `guard: server 5xx ratio ${errorRatio.toFixed(3)} > ${g.maxErrorRatio} over 1m (${breachStreak} checks)`);
  }
  if (disk.failed === undefined && disk.value > g.maxDiskRatio) {
    stop(GUARD_ABORT.disk, `guard: disk ${(disk.value * 100).toFixed(1)} % > ${g.maxDiskRatio * 100} %`);
  }
  if (!everRead && g.blindAbort > 0 && blindStreak >= g.blindAbort) {
    stop(GUARD_ABORT.blind, `guard: Prometheus could not be read in the first ${blindStreak} checks (${why}); ` +
      'fix PROM_URL/PROM_ENV, or unset PROM_URL to run without the guard');
  }
}

// The guard scenario's iteration: check, wait GUARD_EVERY_S in steps of at most 1 s, and return once the load is over:
// past loadEndS and no active VU but this one. On k6 v2.3.0 exec.instance.vusActive stays above 1 while flows of the
// gracefulStop drain are in flight and falls to 1 when the last one ends (between poll_saturation.js steps it is 1 as
// well, hence the loadEndS condition).
export function guardLoop(cfg, loadEndS) {
  const startMs = exec.scenario.startTime;
  const every = Math.max(0.5, cfg.guard.everyS);
  const over = () => (Date.now() - startMs) / 1000 >= loadEndS && exec.instance.vusActive <= 1;
  for (;;) {
    guardCheck(cfg);
    for (let waited = 0; waited < every;) {
      if (over()) return;
      const step = Math.min(1, every - waited);
      sleep(step);
      waited += step;
    }
  }
}
