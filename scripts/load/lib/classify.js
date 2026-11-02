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
