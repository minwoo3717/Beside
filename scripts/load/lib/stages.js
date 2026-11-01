// Stage tagging without remote jslib imports: the scripts compute the stage from the time since their scenario
// started. Pure module (no k6 imports) so lib.test.mjs can check it under Node; the k6 caller passes
// exec.scenario.startTime and Date.now().
//
// A "level" is one load step: ramp to `target` over rampS seconds, then hold it for holdS seconds. Pass/fail is
// judged on the hold part (docs/perf/load/TRAFFIC_MODEL.md §8: the breaking point is the first level whose hold breaks
// a criterion).

export function buildLevels(levels) {
  const stages = [];
  const segments = [];
  let t = 0;
  levels.forEach((lv, index) => {
    const base = { index, target: lv.target, label: lv.label || '', users: lv.users === undefined ? null : lv.users };
    if (lv.rampS > 0) {
      stages.push({ duration: `${lv.rampS}s`, target: lv.target });
      segments.push(Object.assign({ phase: 'ramp', start: t, end: t + lv.rampS }, base));
      t += lv.rampS;
    }
    if (lv.holdS > 0) {
      stages.push({ duration: `${lv.holdS}s`, target: lv.target });
      segments.push(Object.assign({ phase: 'hold', start: t, end: t + lv.holdS }, base));
      t += lv.holdS;
    }
  });
  return { stages, segments, totalS: t };
}

// The segment that contains `elapsedS`; after the last one (graceful stop) the last level in phase "drain".
export function segmentAt(segments, elapsedS) {
  if (!segments || segments.length === 0) return null;
  for (let i = 0; i < segments.length; i++) {
    if (elapsedS < segments[i].end) return segments[i];
  }
  return Object.assign({}, segments[segments.length - 1], { phase: 'drain' });
}

export function tagsAt(segments, startTimeMs, nowMs) {
  const seg = segmentAt(segments, Math.max(0, (nowMs - startTimeMs) / 1000));
  return seg ? { stage: String(seg.index), phase: seg.phase } : {};
}

// Scope strings for thresholds, e.g. "stage:3,phase:hold", one per level that has a hold part.
export function holdScopes(segments) {
  return segments.filter((s) => s.phase === 'hold').map((s) => ({ index: s.index, scope: `stage:${s.index},phase:hold`, segment: s }));
}

// Iterations a ramping-arrival-rate executor starts over its stages (linear ramps), for disk and VU estimates.
// `stages` targets are per timeUnitS seconds.
export function expectedIterations(stages, timeUnitS, startRate) {
  let prev = startRate || 0;
  let total = 0;
  stages.forEach((s) => {
    const seconds = parseFloat(s.duration);
    total += (((prev + s.target) / 2) * seconds) / timeUnitS;
    prev = s.target;
  });
  return total;
}

export function maxTarget(stages) {
  let max = 0;
  stages.forEach((s) => { if (s.target > max) max = s.target; });
  return max;
}

// Sequential fixed-rate steps as separate scenarios (constant-arrival-rate has a single rate): scenario `name`
// starts at startTime and carries the stage tags itself, so no clock arithmetic is needed in the VU code.
export function sequentialScenarios(prefix, rates, holdS, gapS, make) {
  const scenarios = {};
  rates.forEach((rate, index) => {
    const name = `${prefix}${index}`;
    scenarios[name] = Object.assign(make(rate, index), {
      startTime: `${index * (holdS + gapS)}s`,
      duration: `${holdS}s`,
      tags: { stage: String(index), phase: 'hold' },
    });
  });
  return { scenarios, totalS: rates.length * (holdS + gapS) };
}
