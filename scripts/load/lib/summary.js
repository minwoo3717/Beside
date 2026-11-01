// handleSummary helper: the k6 end-of-test data plus run metadata and a per-stage verdict, written as JSON to
// SUMMARY_PATH, and a short plain-text report on stdout. Pure module (no k6 imports, no remote jslib).
//
// How the run ended. k6 (checked on v2.3.0) calls handleSummary after every kind of end: a normal one, setup()
// throwing (exit code 107), exec.test.abort() from lib/guard.js (108), an abortOnFail threshold (99) and Ctrl+C (105).
// The data says nothing about which: data.state is only { testRunDurationMs, isStdOutTTY, isStdErrTTY }, and every
// threshold on a metric that got no sample reads ok. So the end is reconstructed here (verdict.aborted):
//   setup        data.setup_data is missing (every script's setup() returns an object)
//   guard        load_guardAbort has a sample (lib/guard.js sets it just before exec.test.abort())
//   threshold    the load stopped before its planned end, or flows were cut off, and an abortOnFail threshold
//                (info.thresholds) failed
//   interrupted  the load stopped before its planned end and nothing above explains it (Ctrl+C, REST API stop)
//   unfinished   the load reached its planned end but flows were still running when k6 ended: a Ctrl+C or REST stop
//                during the gracefulStop drain (k6 keeps running about 2 s after a REST stop, so one in the last
//                seconds of the load lands here too), or gracefulStop ran out
// The load's length is this summary's time minus setup_data.readyAtMs (markReady() at the end of setup());
// state.testRunDurationMs is not used because it includes setup, which is minutes of seeding in asset.js and
// poll_saturation.js. Its planned end is info.plannedS (seconds after the scenarios start), else the last holdEndS.
// Flows cut off: k6 records nothing for an iteration it interrupts, so lib/flow.js counts load_flowStarted and
// load_flowEnded (user flows; one upload in upload.js, one download in asset.js) and started - ended is the number of
// flows whose outcome is missing. Those are the slowest flows (the timeouts of an overloaded level), so a run with
// any is not a pass, and a hold whose own flows were cut off reads "incomplete" even when the hold itself ran to its end.

import { abortRules, parseMetricName } from './thresholds.js';

// A load that ended more than this before its planned end was cut short (scheduling slack of a normal end).
const END_SLACK_S = 1;

function failedThresholds(metrics) {
  const out = [];
  Object.keys(metrics).sort().forEach((name) => {
    const th = metrics[name].thresholds || {};
    Object.keys(th).forEach((expr) => {
      if (th[expr] && th[expr].ok === false) out.push({ metric: name, threshold: expr });
    });
  });
  return out;
}

function value(metrics, name, field) {
  const m = metrics[name];
  if (!m || !m.values) return null;
  const v = m.values[field];
  return v === undefined ? null : v;
}

function round(x, nd) {
  if (x === null || x === undefined || !isFinite(x)) return null;
  const f = Math.pow(10, nd === undefined ? 2 : nd);
  return Math.round(x * f) / f;
}

// For the end of setup(): stamps the moment the scenarios start (k6 starts them as soon as setup() returns) on the
// setup data, so the summary can tell how long the load ran.
export function markReady(setupData) {
  return Object.assign(setupData, { readyAtMs: Date.now() });
}

// dropped_iterations{scenario:<name>} of one scenario: { count, failed }, or null when the summary has no such
// submetric. k6 tags dropped_iterations with the scenario name only (not with scenario `tags`), so only a script that
// runs one scenario per stage (poll_saturation.js: stages[].scenario) can tie drops to a stage.
function droppedFor(metrics, scenario) {
  const m = metrics[`dropped_iterations{scenario:${scenario}}`];
  if (!m) return null;
  const th = m.thresholds || {};
  return { count: (m.values && m.values.count) || 0, failed: Object.keys(th).some((expr) => th[expr] && th[expr].ok === false) };
}

// One entry per stage index that has a hold part: ok when every threshold scoped to that hold passed, "not_run"
// when none of its scoped metrics got a sample (the run stopped earlier). A stage whose own scenario dropped
// iterations is "fail" (README: a stage with dropped iterations is not "pass"). k6 keeps a thresholded submetric that
// got no sample in the summary with every value 0 and count 0: that endpoint gets no latency entry ("no data", not 0 ms).
// heldS: the hold's seconds (holdS; buildReport shortens it for a hold an aborted run cut), null for "not_run".
export function stageVerdicts(metrics, stages) {
  const byStage = {};
  Object.keys(metrics).forEach((name) => {
    const parsed = parseMetricName(name);
    if (parsed.tags.phase !== 'hold' || parsed.tags.stage === undefined) return;
    const idx = parsed.tags.stage;
    if (!byStage[idx]) byStage[idx] = { names: [], ok: true, samples: 0, latency: {} };
    const entry = byStage[idx];
    entry.names.push(name);
    const m = metrics[name];
    const th = m.thresholds || {};
    Object.keys(th).forEach((expr) => { if (th[expr] && th[expr].ok === false) entry.ok = false; });
    const count = m.values ? (m.values.count !== undefined ? m.values.count : (m.values.passes || 0) + (m.values.fails || 0)) : 0;
    entry.samples += count || 0;
    if (parsed.metric === 'http_req_duration' && parsed.tags.endpoint && m.values && m.values.count !== 0) {
      entry.latency[parsed.tags.endpoint] = { med: round(m.values.med), p95: round(m.values['p(95)']), p99: round(m.values['p(99)']),
        count: m.values.count === undefined ? null : m.values.count };
    }
  });
  const info = {};
  (stages || []).forEach((s) => { info[String(s.index)] = s; });
  const indexes = Object.keys(Object.assign({}, info, byStage)).map(Number).sort((a, b) => a - b);
  return indexes.map((i) => {
    const e = byStage[String(i)];
    const s = info[String(i)] || {};
    const drop = s.scenario ? droppedFor(metrics, s.scenario) : null;
    let status = !e || e.samples === 0 ? 'not_run' : e.ok ? 'pass' : 'fail';
    if (drop && (drop.failed || drop.count > 0)) status = 'fail';
    const holdS = s.holdS === undefined ? null : s.holdS;
    return { stage: i, label: s.label || '', users: s.users === undefined ? null : s.users, target: s.target === undefined ? null : s.target,
      holdS, heldS: status === 'not_run' ? null : holdS, status, dropped: drop ? drop.count : null, latency: e ? e.latency : {} };
  });
}

// Dropped iterations that no stage owns (ramping-arrival-rate scripts cannot tag drops by stage) could have happened
// in any stage, so no stage may read as "pass" and there is no breaking point. Returns the reason, or null.
function unattributedDrops(verdicts, dropped) {
  if (!(dropped > 0) || verdicts.length === 0) return null;
  let attributed = 0;
  verdicts.forEach((v) => { if (typeof v.dropped === 'number') attributed += v.dropped; });
  if (dropped <= attributed) return null;
  verdicts.forEach((v) => { if (v.status === 'pass') v.status = 'invalid'; });
  return `dropped_iterations ${dropped} > 0 and k6 cannot tie them to a stage (ramping-arrival-rate): no stage counts as ` +
    'passed and there is no breaking point; raise PRE_VUS/MAX_VUS (or check the load PC) and run again';
}

// A1's capacity search (TRAFFIC_MODEL.md §8): the levels only go up, the operating limit is 70 % of the users of the
// last level that passed before the first failure. (users x 7) / 10, not users x 0.7: 0.7 is not exact in binary and
// Math.floor(5400 x 0.7) is 3779.
export function breakingPoint(verdicts) {
  const run = verdicts.filter((v) => v.status !== 'not_run');
  const firstFail = run.find((v) => v.status === 'fail');
  let lastPass = null;
  for (let i = 0; i < run.length; i++) {
    if (firstFail && run[i].stage >= firstFail.stage) break;
    if (run[i].status === 'pass') lastPass = run[i];
  }
  return { firstFailingStage: firstFail ? firstFail.stage : null, lastPassingStage: lastPass ? lastPass.stage : null,
    lastPassingUsers: lastPass ? lastPass.users : null, operatingLimitUsers: lastPass && lastPass.users ? Math.floor((lastPass.users * 7) / 10) : null };
}

// Mbps of each stage's hold from a byte Counter: <metric>{stage:N,phase:hold} (kept in the summary by a no-op
// threshold) x 8 / 1e6 / the seconds that hold ran (verdict heldS). load_bytesSent gives the upload Mbps (sentMbps),
// load_assetBytes the asset delivery Mbps of asset.js (assetMbps). Returns { "<stage>": Mbps } for the stages that have
// both; a stage that did not run has no heldS, so its all-zero submetric gives no 0 Mbps.
export function stageMbps(metrics, verdicts, metric) {
  const held = {};
  (verdicts || []).forEach((v) => { if (v.heldS > 0) held[String(v.stage)] = v.heldS; });
  const out = {};
  Object.keys(metrics).forEach((name) => {
    const parsed = parseMetricName(name);
    if (parsed.metric !== metric || parsed.tags.phase !== 'hold' || parsed.tags.stage === undefined) return;
    const heldS = held[parsed.tags.stage];
    const count = metrics[name].values ? metrics[name].values.count : null;
    if (!heldS || typeof count !== 'number') return;
    out[parsed.tags.stage] = (count * 8) / 1e6 / heldS;
  });
  return out;
}

export function stageSentMbps(metrics, verdicts) {
  return stageMbps(metrics, verdicts, 'load_bytesSent');
}

// Flows whose outcome is missing (load_flowStarted - load_flowEnded, lib/metrics.js): { total, byStage: { "<stage>": n
// for the holds that have both submetrics } }. total is null when the script does not count flows (poll_saturation.js,
// f3_uploads.js) or the data predates the counters.
export function unfinishedFlows(metrics) {
  const counts = { started: {}, ended: {} };
  Object.keys(metrics).forEach((name) => {
    const parsed = parseMetricName(name);
    const which = parsed.metric === 'load_flowStarted' ? 'started' : parsed.metric === 'load_flowEnded' ? 'ended' : null;
    if (which === null || parsed.tags.phase !== 'hold' || parsed.tags.stage === undefined) return;
    counts[which][parsed.tags.stage] = (metrics[name].values && metrics[name].values.count) || 0;
  });
  const byStage = {};
  Object.keys(counts.started).forEach((k) => {
    if (counts.ended[k] !== undefined) byStage[k] = Math.max(0, counts.started[k] - counts.ended[k]);
  });
  const started = value(metrics, 'load_flowStarted', 'count');
  const total = started === null ? null : Math.max(0, started - (value(metrics, 'load_flowEnded', 'count') || 0));
  return { total, byStage };
}

// Seconds from the scenarios' start to this summary (null without setup_data.readyAtMs, e.g. hand-made data) and the
// planned end of the load (info.plannedS, else the latest stages[].holdEndS, else null).
function loadTimes(data, info, endedAt) {
  const setup = data.setup_data || {};
  const end = Date.parse(endedAt);
  const ranS = typeof setup.readyAtMs === 'number' && isFinite(end) ? (end - setup.readyAtMs) / 1000 : null;
  let plannedS = typeof info.plannedS === 'number' ? info.plannedS : null;
  if (plannedS === null) {
    (info.stages || []).forEach((s) => { if (typeof s.holdEndS === 'number' && (plannedS === null || s.holdEndS > plannedS)) plannedS = s.holdEndS; });
  }
  return { ranS, plannedS };
}

function guardFailureText(code, cfg) {
  if (code === null) return null;
  if (code >= 100) return `HTTP ${code}`;
  if (code === 0) return 'no response (connection refused, timeout or DNS)';
  if (code === -1) return 'not a Prometheus query result';
  if (code === -2) return `no disk_free_bytes series for env="${cfg.promEnv}" (PROM_ENV vs the backend's METRICS_ENV?)`;
  return `failure code ${code}`;
}

function guardAbortReason(metrics, cfg, code) {
  const g = cfg.guard || {};
  if (code === 1) {
    return `server 5xx ratio ${round(value(metrics, 'load_guardErrorRatio', 'value'), 3)} > GUARD_MAX_ERROR_RATIO ${g.maxErrorRatio} over 1m ` +
      `(${g.breaches} checks in a row)`;
  }
  if (code === 2) return `disk ${round(value(metrics, 'load_guardDiskRatio', 'value') * 100, 1)} % used > GUARD_MAX_DISK_RATIO ${g.maxDiskRatio}`;
  if (code === 3) {
    return `Prometheus at ${cfg.promUrl} could not be read in the first ${g.blindAbort} checks ` +
      `(${guardFailureText(value(metrics, 'load_guardLastFailure', 'value'), cfg)})`;
  }
  return `guard abort code ${code}`;
}

// verdict.aborted: null for a run whose load ran to its planned end with every flow ended, else
// { by, reason, atS, plannedS, cutStages, unfinishedFlows }. atS = seconds after the scenarios started at which the
// load stopped (null when unknown or for "setup"); unfinishedFlows = flows with no outcome (null when not counted).
function detectAbort(data, metrics, info, cfg, times, open) {
  const n = open.total || 0;
  const still = n > 0 ? `; ${n} flow(s) were still running and have no outcome` : '';
  const ended = (by, reason) => ({ by, reason, atS: by === 'setup' ? null : round(times.ranS, 1), plannedS: times.plannedS, cutStages: [],
    unfinishedFlows: by === 'setup' ? null : open.total });
  if (data.setup_data === undefined || data.setup_data === null) {
    return ended('setup', 'setup() did not finish (it threw, timed out or was interrupted; the error is on k6 stderr, exit code 107): no load ran');
  }
  const code = value(metrics, 'load_guardAbort', 'value');
  if (code !== null) return ended('guard', `${guardAbortReason(metrics, cfg, code)} (k6 exit code 108)${still}`);
  const early = times.ranS !== null && times.plannedS !== null && times.ranS < times.plannedS - END_SLACK_S;
  if (!early && n === 0) return null;
  const crossed = abortRules(info.thresholds).filter((r) => {
    const th = metrics[r.metric] && metrics[r.metric].thresholds;
    return th && th[r.threshold] && th[r.threshold].ok === false;
  });
  if (crossed.length > 0) {
    return ended('threshold', `${crossed.map((r) => `${r.metric} ${r.threshold}`).join(', ')} crossed (k6 exit code 99)${still}`);
  }
  if (early) {
    return ended('interrupted', 'the load stopped before its planned end and neither the guard nor an abortOnFail threshold did it: ' +
      `Ctrl+C (k6 exit code 105), a REST API stop (103) or another exec.test.abort()${still}`);
  }
  return ended('unfinished', `${n} flow(s) were still running when k6 ended and have no outcome: a Ctrl+C (exit code 105) or ` +
    'REST API stop (103) during the gracefulStop drain after the planned end, or gracefulStop ran out (GRACEFUL_STOP_S). ' +
    'They are the slowest flows, so the results below are biased low');
}

// The holds an aborted run cut: (1) a hold whose planned end (stages[].holdEndS) is after the moment the load stopped,
// heldS = the seconds it ran; (2) a hold whose own flows were cut off (load_flowStarted > load_flowEnded of that hold),
// even if the hold ran to its end (A1's flows outlast their hold), heldS stays holdS. A cut hold that had passed so far
// reads "incomplete" (never "pass", so never the last passing stage); one that had already failed stays "fail".
// Without the times, the last stage that got samples is taken as cut short. "unfinished" reached the planned end, so
// only (2) applies. Returns the indexes.
function markCut(verdicts, stages, aborted, open) {
  const byIndex = {};
  (stages || []).forEach((s) => { byIndex[String(s.index)] = s; });
  const ran = verdicts.filter((v) => v.status !== 'not_run');
  const timed = aborted.atS !== null && ran.every((v) => byIndex[String(v.stage)] && typeof byIndex[String(v.stage)].holdEndS === 'number');
  let short = [];
  if (aborted.by !== 'unfinished') short = timed ? ran.filter((v) => aborted.atS < byIndex[String(v.stage)].holdEndS) : ran.slice(-1);
  short.forEach((v) => {
    const s = byIndex[String(v.stage)] || {};
    v.heldS = timed && typeof s.holdS === 'number' ? round(Math.max(0, aborted.atS - (s.holdEndS - s.holdS)), 1) : null;
  });
  const cut = ran.filter((v) => short.indexOf(v) >= 0 || open.byStage[String(v.stage)] > 0);
  cut.forEach((v) => { if (v.status === 'pass') v.status = 'incomplete'; });
  return cut.map((v) => v.stage);
}

// verdict.guard: what lib/guard.js saw (null without PROM_URL). failures > 0 means the run was not fully guarded.
function guardReport(metrics, cfg) {
  if (!cfg.promUrl) return null;
  const failures = value(metrics, 'load_guardFailures', 'count') || 0;
  return {
    promUrl: cfg.promUrl,
    env: cfg.promEnv,
    queries: value(metrics, 'load_guardQueries', 'count') || 0,
    failures,
    lastError: failures > 0 ? guardFailureText(value(metrics, 'load_guardLastFailure', 'value'), cfg) : null,
    maxErrorRatio: round(value(metrics, 'load_guardErrorRatio', 'max'), 4),
    maxDiskRatio: round(value(metrics, 'load_guardDiskRatio', 'max'), 4),
  };
}

// info: { script, scenario, stages: [{ index, label, users, target, holdS, holdEndS, scenario? }], plannedS,
//         thresholds (the script's options.thresholds, for its abortOnFail rules), breakingPoint (false: the levels
//         are not an increasing search), extra }
export function buildReport(data, info, cfg, endedAt) {
  const metrics = data.metrics || {};
  const stages = info.stages || [];
  const failed = failedThresholds(metrics);
  const verdicts = stageVerdicts(metrics, stages);
  const times = loadTimes(data, info, endedAt);
  const open = unfinishedFlows(metrics);
  const aborted = detectAbort(data, metrics, info, cfg, times, open);
  if (aborted && aborted.by !== 'setup') aborted.cutStages = markCut(verdicts, stages, aborted, open);
  const sentMbps = value(metrics, 'data_sent', 'rate') === null ? null : (value(metrics, 'data_sent', 'rate') * 8) / 1e6;
  const recvMbps = value(metrics, 'data_received', 'rate') === null ? null : (value(metrics, 'data_received', 'rate') * 8) / 1e6;
  // data_sent.rate is total bytes / whole test time, which understates a staged script's busiest hold. The uplink
  // ratio uses the larger of the busiest stage's upload Mbps and the whole-run average; the latter is kept apart.
  // Both per-stage rates divide by heldS, which markCut shortened for a hold the run cut short.
  const perStage = stageSentMbps(metrics, verdicts);
  const perStageAsset = stageMbps(metrics, verdicts, 'load_assetBytes');
  let peakStage = null;
  Object.keys(perStage).forEach((k) => { if (peakStage === null || perStage[k] > perStage[peakStage]) peakStage = k; });
  verdicts.forEach((v) => {
    const k = String(v.stage);
    v.sentMbps = perStage[k] === undefined ? null : round(perStage[k], 3);
    v.assetMbps = perStageAsset[k] === undefined ? null : round(perStageAsset[k], 3);
  });
  const peakSent = peakStage === null ? null : perStage[peakStage];
  const highest = [peakSent, sentMbps].filter((x) => x !== null);
  const uplinkRatio = cfg.uplinkMbps && highest.length ? Math.max.apply(null, highest) / cfg.uplinkMbps : null;
  const uplinkRatioWholeRun = cfg.uplinkMbps && sentMbps !== null ? sentMbps / cfg.uplinkMbps : null;
  const dropped = value(metrics, 'dropped_iterations', 'count') || 0;
  const notes = [];
  if (aborted && aborted.by === 'setup') notes.push('setup() failed and no load ran: no breaking point');
  else if (aborted && aborted.by === 'unfinished') {
    notes.push(`${aborted.unfinishedFlows} flow(s) have no outcome (verdict.aborted "unfinished"): no breaking point is computed; ` +
      'read verdict.stages (a hold whose flows were cut off reads "incomplete", never "pass")');
  } else if (aborted) {
    notes.push(`the run was aborted (${aborted.by}): no breaking point is computed for an aborted run; read verdict.stages ` +
      '(a hold cut short, or whose flows were cut off, reads "incomplete", never "pass")');
  }
  const dropNote = unattributedDrops(verdicts, dropped);
  if (dropNote) notes.push(dropNote);
  if (info.breakingPoint === false) {
    notes.push('the levels are not an increasing search (A5 spike and recovery, A6 one soak level): no breaking point; ' +
      'read verdict.stages level by level');
  }
  const setup = data.setup_data || {};
  const meta = Object.assign({
    script: info.script,
    scenario: info.scenario,
    tier: cfg.tier || info.tier || '',
    runId: cfg.runId,
    baseUrl: cfg.baseUrl,
    startedAt: setup.startedAt || null,
    endedAt,
    testRunDurationMs: data.state ? data.state.testRunDurationMs : null,
    loadPlannedS: times.plannedS,
    loadRanS: round(times.ranS, 1),
    rttBaseMsUsed: cfg.rttBaseMs,
    rttBaseMsMeasured: round(value(metrics, 'load_healthzMs', 'med'), 3),
    uplinkMbps: cfg.uplinkMbps,
    stages,
    status: '[실험 결과] 후보 — 조건(VM 사양, 커밋 SHA, k6 버전, 업링크, 사진 크기)은 collect.py 의 run.json 에 붙인다. 로컬 실행은 동작 확인용으로만 쓴다.',
  }, info.extra || {});
  const verdict = {
    // false for an aborted run too: a run that did not run to its planned end, or cut flows off, is never a pass
    thresholdsOk: failed.length === 0 && aborted === null,
    aborted,
    // flows with no outcome (load_flowStarted - load_flowEnded); null for a script that does not count flows
    unfinishedFlows: open.total,
    failedThresholds: failed,
    stages: verdicts,
    breakingPoint: notes.length === 0 ? breakingPoint(verdicts) : null,
    breakingPointNote: notes.length === 0 ? null : notes.join('; '),
    guard: guardReport(metrics, cfg),
    httpReqs: value(metrics, 'http_reqs', 'count'),
    httpReqRate: round(value(metrics, 'http_reqs', 'rate')),
    droppedIterations: dropped,
    checksFailed: value(metrics, 'checks', 'fails') || 0,
    checksPassed: value(metrics, 'checks', 'passes') || 0,
    unexpectedCount: value(metrics, 'load_unexpectedCount', 'count') || 0,
    vusMax: value(metrics, 'vus_max', 'max') || value(metrics, 'vus_max', 'value'),
    dataSentMbps: round(sentMbps, 3),
    dataReceivedMbps: round(recvMbps, 3),
    peakStage: peakStage === null ? null : Number(peakStage),
    peakStageSentMbps: round(peakSent, 3),
    uplinkRatio: round(uplinkRatio, 3),
    uplinkRatioWholeRun: round(uplinkRatioWholeRun, 3),
    uplinkSaturated: uplinkRatio === null ? null : uplinkRatio > 0.7,
    honesty: [
      'k6 VU 수는 동시 사용자 수가 아니다. 사용자 수는 stages[].users(도착률 환산)로 적는다.',
      'T3 통과는 T3 단계 10분 유지, dropped_iterations 0, 기준 충족, 클라이언트 CPU < 70 %, 업링크 비포화를 모두 만족할 때만 쓴다.',
    ],
  };
  return { schema: 'beside-load-summary/1', meta, verdict, k6: data };
}

function pad(text, n) {
  const s = String(text);
  return s.length >= n ? s : s + ' '.repeat(n - s.length);
}

function orDash(x) {
  return x === null || x === undefined ? '-' : x;
}

function abortLine(a) {
  if (a.by === 'setup') return `SETUP FAILED: ${a.reason}. Nothing below is a result.`;
  let head;
  if (a.by === 'unfinished') {
    head = `FLOWS CUT OFF${a.atS === null ? '' : ` at ${a.atS} s (the load was planned for ${orDash(a.plannedS)} s, then the drain)`}`;
  } else {
    const when = a.atS === null ? '' : ` after ${a.atS} s of the ${orDash(a.plannedS)} s planned`;
    head = a.by === 'interrupted' ? `STOPPED EARLY${when}`
      : `ABORTED by the ${a.by === 'guard' ? 'Prometheus guard' : 'abortOnFail threshold'}${when}`;
  }
  const cut = a.cutStages.length ? `; stage ${a.cutStages.join(', ')} incomplete (hold cut short or its flows cut off), not a pass` : '';
  return `${head}: ${a.reason}${cut}. NOT a passing run.`;
}

export function textReport(report) {
  const m = report.meta;
  const v = report.verdict;
  const lines = [];
  lines.push(`== beside load: ${m.script} (${m.scenario}) run=${m.runId} base=${m.baseUrl}`);
  if (v.aborted) lines.push(abortLine(v.aborted));
  lines.push(`requests ${v.httpReqs} (${v.httpReqRate}/s), dropped_iterations ${v.droppedIterations}, vus_max ${v.vusMax}`);
  lines.push(`data sent ${v.dataSentMbps} Mbps (whole-run average)` +
    (v.peakStageSentMbps === null ? '' : `, peak stage ${v.peakStage} upload ${v.peakStageSentMbps} Mbps`) +
    `, received ${v.dataReceivedMbps} Mbps` +
    (v.uplinkRatio === null ? ' (UPLINK_MBPS not set)'
      : `, uplink ratio ${v.uplinkRatio} (whole run ${v.uplinkRatioWholeRun})${v.uplinkSaturated ? ' SATURATED (> 0.7)' : ''}`));
  if (m.rttBaseMsMeasured !== null) lines.push(`healthz p50 (RTT_base candidate) ${m.rttBaseMsMeasured} ms`);
  if (v.guard) {
    const g = v.guard;
    lines.push(`guard ${g.promUrl}: ${g.queries} queries, ${g.failures} unreadable` +
      (g.failures > 0 ? ` (last: ${g.lastError}): the run was NOT fully guarded` : '') +
      `; max 5xx ratio ${orDash(g.maxErrorRatio)}, max disk ${orDash(g.maxDiskRatio)}`);
  }
  if (v.checksFailed > 0) lines.push(`checks: ${v.checksFailed} failed of ${v.checksFailed + v.checksPassed}`);
  if (v.unexpectedCount > 0) lines.push(`unexpected responses (load_unexpectedCount): ${v.unexpectedCount}`);
  if (v.stages.length) {
    lines.push('stage  users     target   status     latency p95 (ms)');
    v.stages.forEach((s) => {
      const lat = Object.keys(s.latency).sort().map((ep) => `${ep}=${orDash(s.latency[ep].p95)}`).join(' ') || '-';
      lines.push(`${pad(s.stage, 6)} ${pad(orDash(s.users), 9)} ${pad(orDash(s.target), 8)} ${pad(s.status, 10)} ${lat}`);
    });
    const bp = v.breakingPoint;
    if (bp === null) {
      const label = v.stages.some((s) => s.status === 'invalid') ? 'invalid' : 'none';
      lines.push(`breaking point: ${label} — ${v.breakingPointNote}`);
    } else {
      lines.push(`last passing stage ${orDash(bp.lastPassingStage)}, first failing stage ${orDash(bp.firstFailingStage)}`);
    }
  }
  if (v.aborted && v.aborted.by === 'setup') lines.push('thresholds: not judged (setup failed, no load ran)');
  else if (v.failedThresholds.length) lines.push(`thresholds: ${v.failedThresholds.length} failed`);
  else if (v.aborted && v.aborted.by === 'unfinished') {
    lines.push('thresholds: none failed among the flows that ended, but some flows have no outcome: NOT a pass');
  } else lines.push(v.aborted ? 'thresholds: none failed before the stop, but the run was cut short: NOT a pass' : 'thresholds: all passed');
  v.failedThresholds.slice(0, 40).forEach((f) => lines.push(`  FAIL ${f.metric} ${f.threshold}`));
  return lines.join('\n') + '\n';
}

// Return value for k6's handleSummary: { <SUMMARY_PATH>: json, stdout: text } (+ extra files, e.g. the job ids).
export function summaryOutputs(data, info, cfg, extraFiles) {
  const report = buildReport(data, info, cfg, new Date().toISOString());
  const out = {};
  out[cfg.summaryPath] = JSON.stringify(report, null, 2);
  out.stdout = textReport(report);
  Object.assign(out, extraFiles || {});
  return out;
}
