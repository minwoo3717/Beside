// How one HTTP result counts (docs/perf/load/TRAFFIC_MODEL.md §8: "5xx and connection errors < 0.5 %; intended 4xx,
// 429 and 503 are counted separately"). Pure module.
//
//   ok          the status this request expects (e.g. 202 for POST /jobs, 400 for an intentionally bad upload)
//   error       status 0 (connection error, timeout, reset) or any 5xx that is not an intended rejection
//   rejected    429 RATE_LIMITED / 503 QUEUE_FULL — [계획] R1~R3 codes, not in the v1 contract yet; counted apart so a
//               protected server is not reported as failing
//   unexpected  any other status (e.g. 404 JOB_NOT_FOUND while polling a job the run created)

export const REJECTION_CODES = { 429: 'RATE_LIMITED', 503: 'QUEUE_FULL' };

export function errorCodeOf(body) {
  if (typeof body !== 'string' || body.length === 0 || body.charAt(0) !== '{') return null;
  try {
    const doc = JSON.parse(body);
    return doc && doc.error && typeof doc.error === 'object' && typeof doc.error.code === 'string' ? doc.error.code : null;
  } catch (e) {
    return null;
  }
}

export function classify(status, expected, body) {
  const want = Array.isArray(expected) ? expected : [expected];
  if (want.indexOf(status) >= 0) return 'ok';
  if (status === 0) return 'error';
  const code = errorCodeOf(body);
  if (REJECTION_CODES[status] && code === REJECTION_CODES[status]) return 'rejected';
  if (status >= 500) return 'error';
  return 'unexpected';
}

// What the responses were, apart from how they count above: lib/flow.js call() adds every response to the counter of
// its raw status class, whatever the request expected (F3's intended 413 is "ok" above and still a 4xx here):
//   '0'    no HTTP response: connection refused or reset, k6 request timeout, TLS or HTTP/2 error (k6 logs the reason)
//   '4xx'  400-499
//   '5xx'  500-599
// 2xx and 3xx are not counted (null). lib/summary.js reports them as verdict.httpStatus, overall and per stage.
export const STATUS_CLASSES = ['0', '4xx', '5xx'];
export const STATUS_METRIC = { '0': 'load_status0Count', '4xx': 'load_status4xxCount', '5xx': 'load_status5xxCount' };
// The 5xx codes kept one by one over the whole run (verdict.httpStatus["5xxByCode"]; any other 5xx is "other"): 500
// from the app, and the codes a gateway such as the reverse proxy answers with when the upstream fails it (RFC 9110:
// 502 Bad Gateway, 503 Service Unavailable, 504 Gateway Timeout; the app's 503 QUEUE_FULL is [계획]).
export const STATUS_5XX_CODES = ['500', '502', '503', '504'];

export function statusClass(status) {
  if (status === 0) return '0';
  if (status >= 400 && status < 500) return '4xx';
  if (status >= 500 && status < 600) return '5xx';
  return null;
}
