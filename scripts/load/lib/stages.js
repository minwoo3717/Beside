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

// The levels of buildLevels() as one ramping-arrival-rate scenario each, `${prefix}<index>` (upload.js, asset.js). k6
// (v2.3.0) tags dropped_iterations with the scenario name only, so with one scenario for all levels a level that broke
// hard enough to drop leaves drops no level owns, and lib/summary.js then turns every earlier "pass" into "invalid".
// With one scenario per level, stages[].scenario ties the drops to their level (dropped_iterations{scenario:<name>},
// as poll_saturation.js does): the level that dropped reads "fail" and the earlier levels keep their verdict.
// Level i starts at its ramp (startTime = its start on the whole-run timeline), ramps from the previous level's target
// (or starts at its own target when it has no ramp) and holds, so the arrival schedule is that of buildLevels().stages.
// A level keeps its VUs from its start until its gracefulStop (drainS) ends, so the levels whose windows overlap hold
// VUs together and k6 sums them: peakPreAllocated is the most pre-allocated VUs it initializes (shareMaxVUs sets maxVUs).
// make(level) returns the fields that differ by script (exec, timeUnit, preAllocatedVUs); level is
// { index, target, startS, endS }. startS maps a scenario name to its start, for levelTagsAt.
export function levelScenarios(levels, prefix, drainS, make) {
  const byIndex = {};
  const order = [];
  levels.segments.forEach((s) => {
    if (!byIndex[s.index]) {
      byIndex[s.index] = { index: s.index, target: s.target, startS: s.start, endS: s.end, ramp: false, stages: [] };
      order.push(s.index);
    }
    const lv = byIndex[s.index];
    lv.stages.push({ duration: `${s.end - s.start}s`, target: s.target });
    lv.endS = s.end;
    if (s.phase === 'ramp') lv.ramp = true;
  });
  const scenarios = {};
  const startS = {};
  const windows = [];
  let prev = 0;
  order.forEach((i) => {
    const lv = byIndex[i];
    const name = `${prefix}${i}`;
    const fields = make({ index: i, target: lv.target, startS: lv.startS, endS: lv.endS });
    scenarios[name] = Object.assign({ executor: 'ramping-arrival-rate', startTime: `${lv.startS}s`, startRate: lv.ramp ? prev : lv.target,
      stages: lv.stages }, fields, { gracefulStop: `${drainS}s` });
    startS[name] = lv.startS;
    windows.push({ from: lv.startS, to: lv.endS + drainS, vus: fields.preAllocatedVUs || 0 });
    prev = lv.target;
  });
  let peak = 0;
  windows.forEach((w) => {
    let sum = 0;
    windows.forEach((o) => { if (o.from <= w.from && w.from < o.to) sum += o.vus; });
    if (sum > peak) peak = sum;
  });
  return { scenarios, names: order.map((i) => `${prefix}${i}`), startS, peakPreAllocated: peak };
}

// maxVUs of the levelScenarios() scenarios as one cap on all levels together, as the single scenario's maxVUs was: k6
// sums the maxVUs of overlapping scenarios (k6 inspect --execution-requirements), so each level gets its share,
// maxVUs x its pre-allocated VUs / peakPreAllocated (at least its pre-allocated VUs). With maxVUs >= peakPreAllocated
// the levels that run at once never exceed maxVUs. Returns lv.
export function shareMaxVUs(lv, maxVUs) {
  const peak = lv.peakPreAllocated;
  lv.names.forEach((name) => {
    const pre = lv.scenarios[name].preAllocatedVUs || 0;
    lv.scenarios[name].maxVUs = Math.max(pre, peak > 0 ? Math.floor((maxVUs * pre) / peak) : maxVUs);
  });
  return lv;
}

// tagsAt for an iteration of a levelScenarios() scenario: that scenario started startS[name] seconds into the run, so
// the whole-run clock started that much earlier. A scenario not in startS is taken to start with the run.
export function levelTagsAt(segments, startS, scenarioName, scenarioStartMs, nowMs) {
  const offsetS = typeof startS[scenarioName] === 'number' ? startS[scenarioName] : 0;
  return tagsAt(segments, scenarioStartMs - offsetS * 1000, nowMs);
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
