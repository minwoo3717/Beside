// Queue predictions for gpu_queue.js (G1~G3), the same formulas as model.py. Pure module.

export function erlangC(c, a) {
  if (c < 1) throw new Error('c must be at least 1');
  if (a <= 0) return 0;
  if (a >= c) return 1;
  let b = 1;
  for (let k = 1; k <= c; k++) b = (a * b) / (k + a * b);
  return (c * b) / (c - a * (1 - b));
}

// Mean wait in queue in seconds; null when rho >= 1 (the queue grows without bound).
export function wqMMc(c, rho, tSec) {
  if (rho >= 1) return null;
  const a = rho * c;
  return (erlangC(c, a) * tSec) / (c - a);
}

// M/D/c: exact half of M/M/1 for c = 1 (Pollaczek-Khinchine), the usual half-of-M/M/c estimate for c > 1.
export function wqMDcApprox(c, rho, tSec) {
  const v = wqMMc(c, rho, tSec);
  return v === null ? null : v / 2;
}

export function qMax(c, tSec, maxWaitS) {
  return Math.max(0, Math.floor((c * (maxWaitS - tSec)) / tSec + 1e-9));
}

// Arrival rate for utilisation rho on c slots of T seconds, as whole jobs per hour (k6 rates are integers).
export function jobsPerHourFor(rho, c, tSec) {
  return Math.round((rho * c * 3600) / tSec);
}

// The runs gpu_queue.js carries (meta.scenario). G1~G3 are queue runs: no failure is injected, so the queue model applies.
// The fault runs reuse the G1 knobs: F1b is the only one the k6 env shows (FAIL_RATIO > 0, FAIL_TOKEN=slow); F1a (the
// stub's BESIDE_PASSTHROUGH_FAIL_RATE) and F2 (stub stopped, backend restarted) are set on the stub / VM side, so only
// SCENARIO can name them.
export const QUEUE_SCENARIOS = ['G1', 'G2', 'G3', 'F1a', 'F1b', 'F2'];

// opts: { scenario (SCENARIO, '' when not set), rho, slots, failRatio, failToken }. A named scenario is matched without
// regard to case and checked against the failure knobs; without one, FAIL_RATIO > 0 is F1b (FAIL_TOKEN=slow, the stub's
// SLOW_TOKEN) or F1a (any other token, e.g. the stub's BESIDE_PASSTHROUGH_FAIL_TOKEN), else G3 (rho >= 1.5), G2 (c > 1)
// or G1.
export function scenarioLabel(opts) {
  const named = opts.scenario ? String(opts.scenario) : '';
  let label;
  if (named) {
    label = QUEUE_SCENARIOS.filter((s) => s.toLowerCase() === named.toLowerCase())[0];
    if (!label) throw new Error(`SCENARIO must be one of ${QUEUE_SCENARIOS.join(', ')} (got '${named}')`);
  } else if (opts.failRatio > 0) {
    label = opts.failToken === 'slow' ? 'F1b' : 'F1a';
  } else {
    label = opts.rho >= 1.5 ? 'G3' : opts.slots > 1 ? 'G2' : 'G1';
  }
  if (isQueueRun(label) && opts.failRatio > 0) {
    throw new Error(`SCENARIO=${label} with FAIL_RATIO ${opts.failRatio}: a G run injects no failure ` +
      '(F1b is FAIL_TOKEN=slow FAIL_RATIO=0.2, or name the fault run with SCENARIO)');
  }
  if (label === 'F1b' && !(opts.failRatio > 0)) {
    throw new Error('SCENARIO=F1b needs FAIL_RATIO > 0 (FAIL_TOKEN=slow FAIL_RATIO=0.2): without it no flow is slow');
  }
  return label;
}

// G1~G3: the queue model (and so a recompute of the prediction) applies. Not for F1a/F1b/F2: retries add arrivals, and
// failures and timeouts are in the slot time.
export function isQueueRun(label) {
  return label.charAt(0) === 'G';
}

// Jobs per hour a run really created: `count` creates (load_postMs count) in `windowS` seconds of arrivals. Thinning
// makes the count of a finite run differ from lambda x duration (Poisson, sd = sqrt(count)), so the recompute uses
// this, not the requested rate (docs/perf/load/TRAFFIC_MODEL.md §9). null when the window is unknown or empty.
export function realizedPerHour(count, windowS) {
  if (count === null || windowS === null || !(windowS > 0)) return null;
  return Math.round((count * 3600) / windowS);
}

export function predict(rho, c, tSec, maxWaitS) {
  const perHour = jobsPerHourFor(rho, c, tSec);
  const lam = perHour / 3600;
  const actualRho = (lam * tSec) / c;
  const mm = wqMMc(c, actualRho, tSec);
  const md = wqMDcApprox(c, actualRho, tSec);
  return {
    rhoRequested: rho,
    rho: actualRho,
    slots: c,
    tSec,
    jobsPerHour: perHour,
    lambdaPerS: lam,
    wqMMcSec: mm,
    wqMDcSec: md,
    queueGrowthPerMin: Math.max(0, (lam - c / tSec) * 60),
    qMax: qMax(c, tSec, maxWaitS),
  };
}
