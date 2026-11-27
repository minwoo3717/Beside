// Start and end marks of a load run in the server logs (obs-wave3 A4): one GET /api/v1/healthz with
// X-Request-Id lt-<RUN_ID>-begin at the end of setup() and lt-<RUN_ID>-end in teardown(). Caddy logs the header, so the
// Grafana dashboards draw the two marks as annotations and the 'Beside 로그' load-test row finds the run's requests by
// lt-<RUN_ID>-. Not call() of lib/flow.js: tagged endpoint=marker, the mark stays out of every endpoint threshold and
// of load_errorRatio, and a server that does not answer never fails or stops the run.
import http from 'k6/http';

export const MARKER_TIMEOUT = '5s';

// lt-<RUN_ID>-begin|end; RUN_ID is at most 40 characters (lib/config.js), so this stays under the server's 64.
export function markerId(runId, which) {
  return `lt-${runId}-${which}`;
}

export function mark(cfg, which) {
  try {
    http.get(`${cfg.baseUrl}/api/v1/healthz`, {
      headers: { 'X-Request-Id': markerId(cfg.runId, which) },
      tags: { endpoint: 'marker', name: 'marker' },
      timeout: MARKER_TIMEOUT,
    });
  } catch (e) {
    // a mark is a convenience for reading the logs: never a reason to fail the run
  }
}
