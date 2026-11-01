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
