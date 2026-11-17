// Pass criteria of docs/perf/load/TRAFFIC_MODEL.md §8 [제안값 — 계약 회의 전] as k6 thresholds. Pure module.
// Latency limits are relative to RTT_base (the healthz p50 that smoke.js measured), scoped by the endpoint tag and,
// where the script has stages, by the stage/phase tags so each level's hold part is judged on its own.

export function criteria(rttMs) {
  const rtt = Number(rttMs) || 0;
  return {
    healthz: { p95: rtt + 50, p99: rtt + 150 },
    poll: { p95: rtt + 100, p99: rtt + 300 },
    create: { p95: 1500, p99: 3000 },
    asset: { ttfbP95: rtt + 150, p95: 3000, p99: 6000 },
  };
}

function key(metric, endpoint, scope) {
  const tags = [];
  if (endpoint) tags.push(`endpoint:${endpoint}`);
  if (scope) tags.push(scope);
  return tags.length ? `${metric}{${tags.join(',')}}` : metric;
}

function fmt(ms) {
  return String(Math.round(ms * 100) / 100);
}

// Latency thresholds for the given endpoints, optionally restricted by a scope such as "stage:3,phase:hold".
export function latencyThresholds(rttMs, endpoints, scope) {
  const c = criteria(rttMs);
  const out = {};
  endpoints.forEach((ep) => {
    const lim = c[ep];
    if (!lim) return;
    out[key('http_req_duration', ep, scope)] = [`p(95)<${fmt(lim.p95)}`, `p(99)<${fmt(lim.p99)}`];
    if (lim.ttfbP95 !== undefined) out[key('http_req_waiting', ep, scope)] = [`p(95)<${fmt(lim.ttfbP95)}`];
  });
  return out;
}

// No-op thresholds whose only job is to keep http_req_duration{endpoint:<ep>,<scope>} in the summary for an endpoint
// that is reported but not judged (A3's 20 MB upload): k6 keeps a tag-filtered submetric only when a threshold names
// it, and lib/summary.js reads verdict.stages[].latency from these. "max>=0" holds for any sample and for none.
export function latencyReportThresholds(endpoints, scope) {
  const out = {};
  (endpoints || []).forEach((ep) => { out[key('http_req_duration', ep, scope)] = ['max>=0']; });
  return out;
}

// "Not one" thresholds on Counters, e.g. load_unexpectedCount where every request is valid and an unexpected answer
// (a 413/400/404) is a failure (A3), optionally restricted by a scope such as "stage:3,phase:hold".
export function zeroCountThresholds(metrics, scope) {
  const out = {};
  (metrics || []).forEach((name) => { out[key(name, null, scope)] = ['count==0']; });
  return out;
}

// dropped_iterations{scenario:<name>} == 0 for each scenario that runs one level (lib/stages.js levelScenarios). Besides
// judging, the threshold keeps the per-scenario submetric in the summary, where lib/summary.js reads it through
// stages[].scenario to fail the level that dropped (and only that one).
export function droppedThresholds(scenarioNames) {
  const out = {};
  (scenarioNames || []).forEach((name) => { out[key('dropped_iterations', null, `scenario:${name}`)] = ['count==0']; });
  return out;
}

// Error budget and the abort rule. k6 thresholds are cumulative, so "errors > 5 % for one minute" is approximated
// by the cumulative rate evaluated only after the first minute; lib/guard.js adds the real one-minute window from
// Prometheus when PROM_URL is set.
export function errorThresholds(scope, withAbort) {
  const out = {};
  const list = ['rate<0.005'];
  if (withAbort) list.push({ threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '1m' });
  out[key('load_errorRatio', null, scope)] = list;
  return out;
}

export function flowThresholds(scope) {
  const out = {};
  out[key('load_flowTimeoutRatio', null, scope)] = ['rate==0'];
  out[key('load_flowOkRatio', null, scope)] = ['rate>=0.95'];
  return out;
}

export function merge() {
  const out = {};
  for (let i = 0; i < arguments.length; i++) Object.assign(out, arguments[i]);
  return out;
}

// No-op thresholds whose only job is to keep load_bytesSent{<scope>} (upload body bytes per hold) in the summary,
// where lib/summary.js turns it into the stage's upload Mbps and the uplink ratio of the busiest stage.
export function bytesSentThresholds(scopes) {
  const out = {};
  (scopes || []).forEach((s) => { out[key('load_bytesSent', null, s.scope)] = ['count>=0']; });
  return out;
}

// No-op thresholds whose only job is to keep load_flowStarted{<scope>} and load_flowEnded{<scope>} (lib/metrics.js) in
// the summary, where lib/summary.js finds the holds whose flows k6 cut off (started > ended): such a hold is never "pass".
export function flowCountThresholds(scopes) {
  const out = {};
  (scopes || []).forEach((s) => {
    out[key('load_flowStarted', null, s.scope)] = ['count>=0'];
    out[key('load_flowEnded', null, s.scope)] = ['count>=0'];
  });
  return out;
}

// Everything a staged flow script judges: overall, then per hold scope (opts.uploads adds bytesSentThresholds,
// opts.flows adds flowCountThresholds).
export function stagedThresholds(opts) {
  const parts = [
    latencyThresholds(opts.rttMs, opts.endpoints, null),
    errorThresholds(null, true),
    opts.flows ? flowThresholds(null) : {},
    { dropped_iterations: ['count==0'] },
    opts.uploads ? bytesSentThresholds(opts.scopes) : {},
    opts.flows ? flowCountThresholds(opts.scopes) : {},
  ];
  (opts.scopes || []).forEach((s) => {
    parts.push(latencyThresholds(opts.rttMs, opts.endpoints, s.scope));
    parts.push(errorThresholds(s.scope, false));
    if (opts.flows) parts.push(flowThresholds(s.scope));
  });
  return merge.apply(null, parts);
}

// The abortOnFail thresholds of an options.thresholds object, as [{ metric, threshold }]. handleSummary's data does not
// carry the options' thresholds (k6 v2.3.0 data.options is only the summary settings), so the scripts pass them to
// lib/summary.js, which reads a failed one in a run that ended early as "aborted by a threshold". Inside handleSummary
// k6 v2.3.0 hands the script a host-backed copy of `options` whose lists are not JS arrays (no forEach, Array.isArray
// false), so the value is copied through JSON first.
export function abortRules(thresholds) {
  const plain = JSON.parse(JSON.stringify(thresholds || {}));
  const out = [];
  Object.keys(plain).forEach((metric) => {
    (Array.isArray(plain[metric]) ? plain[metric] : []).forEach((t) => {
      if (t && typeof t === 'object' && t.abortOnFail) out.push({ metric, threshold: t.threshold });
    });
  });
  return out;
}

// Parse a summary metric name such as "http_req_duration{endpoint:poll,stage:3,phase:hold}".
export function parseMetricName(name) {
  const open = name.indexOf('{');
  if (open < 0 || name.charAt(name.length - 1) !== '}') return { metric: name, tags: {} };
  const tags = {};
  name.slice(open + 1, -1).split(',').forEach((kv) => {
    const at = kv.indexOf(':');
    if (at > 0) tags[kv.slice(0, at).trim()] = kv.slice(at + 1).trim();
  });
  return { metric: name.slice(0, open), tags };
}
