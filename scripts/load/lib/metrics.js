// Custom k6 metrics. Names use the load_ prefix (the "load.*" values of the plan; k6 forbids dots). They are perf-only
// like bench.* and are not registered in docs/METRICS.md (agenda #15).
import { Counter, Rate, Trend } from 'k6/metrics';

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
