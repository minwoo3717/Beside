// Optional abort guard (docs/perf/load/TRAFFIC_MODEL.md §8 "중단 조건"): when PROM_URL points at the run's Prometheus (SSH tunnel or
// Tailscale, e.g. http://localhost:9090), one VU checks every GUARD_EVERY_S seconds
//   - the server's 5xx ratio over the last minute  > 5 %  (GUARD_BREACHES checks in a row)
//   - the fullest disk the backend reports          > 80 %
// and stops the test with exec.test.abort(). Its requests are tagged endpoint=guard and are kept out of the
// load_* metrics. Without PROM_URL there is no guard scenario.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { readNum } from './config.js';

const ENVV = typeof __ENV !== 'undefined' ? __ENV : {};
const EVERY_S = readNum(ENVV, 'GUARD_EVERY_S', 15);
const BREACHES = readNum(ENVV, 'GUARD_BREACHES', 2);
const MAX_ERROR = readNum(ENVV, 'GUARD_MAX_ERROR_RATIO', 0.05);
const MAX_DISK = readNum(ENVV, 'GUARD_MAX_DISK_RATIO', 0.8);

export function guardQueries(env) {
  const sel = `env="${env}"`;
  return {
    errorRatio: `sum(rate(http_server_requests_seconds_count{${sel},status=~"5.."}[1m])) / clamp_min(sum(rate(http_server_requests_seconds_count{${sel}}[1m])), 1e-9)`,
    diskUsed: `max(1 - disk_free_bytes{${sel}} / disk_total_bytes{${sel}})`,
  };
}

export function guardScenario(cfg, totalS) {
  if (!cfg.promUrl) return {};
  return { guard: { executor: 'constant-vus', exec: 'guard', vus: 1, duration: `${Math.ceil(totalS)}s`, gracefulStop: '0s' } };
}

function instant(cfg, query) {
  const res = http.get(`${cfg.promUrl}/api/v1/query?query=${encodeURIComponent(query)}`,
    { tags: { endpoint: 'guard', name: 'prometheus' }, responseType: 'text', timeout: '10s' });
  if (res.status !== 200) return null;
  try {
    const doc = JSON.parse(res.body);
    const first = doc.data && doc.data.result && doc.data.result[0];
    return first ? Number(first.value[1]) : null;
  } catch (e) {
    return null;
  }
}

let streak = 0;

export function guardTick(cfg) {
  const q = guardQueries(cfg.promEnv);
  const errors = instant(cfg, q.errorRatio);
  const disk = instant(cfg, q.diskUsed);
  streak = errors !== null && errors > MAX_ERROR ? streak + 1 : 0;
  if (streak >= BREACHES) exec.test.abort(`guard: server 5xx ratio ${errors.toFixed(3)} > ${MAX_ERROR} over 1m (${streak} checks)`);
  if (disk !== null && disk > MAX_DISK) exec.test.abort(`guard: disk ${(disk * 100).toFixed(1)} % > ${MAX_DISK * 100} %`);
  sleep(EVERY_S);
}
