// Custom k6 metrics. Names use the load_ prefix (the "load.*" values of docs/perf/load/TRAFFIC_MODEL.md §11; k6
// forbids dots). They are perf-only like bench.* and are not registered in docs/METRICS.md (agenda #15).
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';

export const healthzMs = new Trend('load_healthzMs', true);
export const postMs = new Trend('load_postMs', true);
export const pollMs = new Trend('load_pollMs', true);
export const assetMs = new Trend('load_assetMs', true);
export const assetTtfbMs = new Trend('load_assetTtfbMs', true);
export const retryMs = new Trend('load_retryMs', true);
export const flowMs = new Trend('load_flowMs', true);
export const waitMs = new Trend('load_waitMs', true);
export const pollsPerWait = new Trend('load_pollsPerWait');
export const serverQueuedMs = new Trend('load_serverQueuedMs', true);
export const serverProcessingMs = new Trend('load_serverProcessingMs', true);
export const serverTotalMs = new Trend('load_serverTotalMs', true);

// Per request: 1 for status 0 or an unintended 5xx, else 0 (lib/classify.js).
export const errorRatio = new Rate('load_errorRatio');
// Per flow: 1 when the app would have given up (MAX_WAIT_S without COMPLETED/FAILED).
export const flowTimeoutRatio = new Rate('load_flowTimeoutRatio');
// Per flow: 1 when it ended as intended (COMPLETED, or FAILED after the retry for a fail-injected flow).
export const flowOkRatio = new Rate('load_flowOkRatio');

export const unexpectedCount = new Counter('load_unexpectedCount');
export const rejectedCount = new Counter('load_rejectedCount');
export const assetBytes = new Counter('load_assetBytes');
// Upload body bytes of POST /api/v1/jobs, tagged like the request, so the uplink use of each stage's hold can be
// computed (data_sent is only a whole-run average). Staged scripts keep the per-stage submetric in the summary with a
// no-op threshold (lib/thresholds.js bytesSentThresholds).
export const bytesSent = new Counter('load_bytesSent');

// lib/guard.js (only with PROM_URL). k6 v2.3.0 tells handleSummary nothing about how a run ended (data.state is only
// testRunDurationMs and the TTY flags), so the guard leaves its readings and its abort here for lib/summary.js. None of
// them has a threshold: a metric with no sample stays out of the summary, so "present" means "it happened".
export const guardQueries = new Counter('load_guardQueries'); // Prometheus queries sent
export const guardFailures = new Counter('load_guardFailures'); // queries that could not be read
// The last unreadable answer: its HTTP status, or 0 no response, -1 not a Prometheus answer, -2 no disk series.
export const guardLastFailure = new Gauge('load_guardLastFailure');
export const guardErrorRatio = new Gauge('load_guardErrorRatio'); // each reading of the server 5xx ratio over 1m
export const guardDiskRatio = new Gauge('load_guardDiskRatio'); // each reading of the fullest disk's used ratio
// Set once, just before exec.test.abort(): 1 = 5xx ratio, 2 = disk, 3 = Prometheus unreadable from the start.
export const guardAbort = new Gauge('load_guardAbort');
