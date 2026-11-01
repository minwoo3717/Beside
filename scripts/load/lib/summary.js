// handleSummary helper: the k6 end-of-test data plus run metadata and a per-stage verdict, written as JSON to
// SUMMARY_PATH, and a short plain-text report on stdout. Pure module (no k6 imports, no remote jslib).

import { parseMetricName } from './thresholds.js';

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

// One entry per stage index that has a hold part: ok when every threshold scoped to that hold passed, "not_run"
// when none of its scoped metrics got a sample (the run stopped earlier).
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
    if (parsed.metric === 'http_req_duration' && parsed.tags.endpoint && m.values) {
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
    const status = !e || e.samples === 0 ? 'not_run' : e.ok ? 'pass' : 'fail';
    return { stage: i, label: s.label || '', users: s.users === undefined ? null : s.users, target: s.target === undefined ? null : s.target,
      holdS: s.holdS === undefined ? null : s.holdS, status, latency: e ? e.latency : {} };
  });
}

export function breakingPoint(verdicts) {
  const run = verdicts.filter((v) => v.status !== 'not_run');
  const firstFail = run.find((v) => v.status === 'fail');
  let lastPass = null;
  for (let i = 0; i < run.length; i++) {
    if (firstFail && run[i].stage >= firstFail.stage) break;
    if (run[i].status === 'pass') lastPass = run[i];
  }
  return { firstFailingStage: firstFail ? firstFail.stage : null, lastPassingStage: lastPass ? lastPass.stage : null,
    lastPassingUsers: lastPass ? lastPass.users : null, operatingLimitUsers: lastPass && lastPass.users ? Math.floor(lastPass.users * 0.7) : null };
}

// Upload Mbps of each stage's hold: load_bytesSent{stage:N,phase:hold} (bytes, kept in the summary by a no-op
// threshold) x 8 / 1e6 / that stage's holdS. Returns { "<stage>": Mbps } for the stages that have both.
export function stageSentMbps(metrics, stages) {
  const hold = {};
  (stages || []).forEach((s) => { if (s.holdS > 0) hold[String(s.index)] = s.holdS; });
  const out = {};
  Object.keys(metrics).forEach((name) => {
    const parsed = parseMetricName(name);
    if (parsed.metric !== 'load_bytesSent' || parsed.tags.phase !== 'hold' || parsed.tags.stage === undefined) return;
    const holdS = hold[parsed.tags.stage];
    const count = metrics[name].values ? metrics[name].values.count : null;
    if (!holdS || typeof count !== 'number') return;
    out[parsed.tags.stage] = (count * 8) / 1e6 / holdS;
  });
  return out;
}

export function buildReport(data, info, cfg, endedAt) {
  const metrics = data.metrics || {};
  const failed = failedThresholds(metrics);
  const verdicts = stageVerdicts(metrics, info.stages || []);
  const sentMbps = value(metrics, 'data_sent', 'rate') === null ? null : (value(metrics, 'data_sent', 'rate') * 8) / 1e6;
  const recvMbps = value(metrics, 'data_received', 'rate') === null ? null : (value(metrics, 'data_received', 'rate') * 8) / 1e6;
  // data_sent.rate is total bytes / whole test time, which understates a staged script's busiest hold. The uplink
  // ratio uses the larger of the busiest stage's upload Mbps and the whole-run average; the latter is kept apart.
  const perStage = stageSentMbps(metrics, info.stages || []);
  let peakStage = null;
  Object.keys(perStage).forEach((k) => { if (peakStage === null || perStage[k] > perStage[peakStage]) peakStage = k; });
  verdicts.forEach((v) => { v.sentMbps = perStage[String(v.stage)] === undefined ? null : round(perStage[String(v.stage)], 3); });
  const peakSent = peakStage === null ? null : perStage[peakStage];
  const highest = [peakSent, sentMbps].filter((x) => x !== null);
  const uplinkRatio = cfg.uplinkMbps && highest.length ? Math.max.apply(null, highest) / cfg.uplinkMbps : null;
  const uplinkRatioWholeRun = cfg.uplinkMbps && sentMbps !== null ? sentMbps / cfg.uplinkMbps : null;
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
    rttBaseMsUsed: cfg.rttBaseMs,
    rttBaseMsMeasured: round(value(metrics, 'load_healthzMs', 'med'), 3),
    uplinkMbps: cfg.uplinkMbps,
    stages: info.stages || [],
    status: '[실험 결과] 후보 — 조건(VM 사양, 커밋 SHA, k6 버전, 업링크, 사진 크기)은 collect.py 의 run.json 에 붙인다. 로컬 실행은 동작 확인용으로만 쓴다.',
  }, info.extra || {});
  const verdict = {
    thresholdsOk: failed.length === 0,
    failedThresholds: failed,
    stages: verdicts,
    breakingPoint: breakingPoint(verdicts),
    httpReqs: value(metrics, 'http_reqs', 'count'),
    httpReqRate: round(value(metrics, 'http_reqs', 'rate')),
    droppedIterations: value(metrics, 'dropped_iterations', 'count') || 0,
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

export function textReport(report) {
  const m = report.meta;
  const v = report.verdict;
  const lines = [];
  lines.push(`== beside load: ${m.script} (${m.scenario}) run=${m.runId} base=${m.baseUrl}`);
  lines.push(`requests ${v.httpReqs} (${v.httpReqRate}/s), dropped_iterations ${v.droppedIterations}, vus_max ${v.vusMax}`);
  lines.push(`data sent ${v.dataSentMbps} Mbps (whole-run average)` +
    (v.peakStageSentMbps === null ? '' : `, peak stage ${v.peakStage} upload ${v.peakStageSentMbps} Mbps`) +
    `, received ${v.dataReceivedMbps} Mbps` +
    (v.uplinkRatio === null ? ' (UPLINK_MBPS not set)'
      : `, uplink ratio ${v.uplinkRatio} (whole run ${v.uplinkRatioWholeRun})${v.uplinkSaturated ? ' SATURATED (> 0.7)' : ''}`));
  if (m.rttBaseMsMeasured !== null) lines.push(`healthz p50 (RTT_base candidate) ${m.rttBaseMsMeasured} ms`);
  if (v.stages.length) {
    lines.push('stage  users     target   status   latency p95 (ms)');
    v.stages.forEach((s) => {
      const lat = Object.keys(s.latency).sort().map((ep) => `${ep}=${s.latency[ep].p95}`).join(' ');
      lines.push(`${pad(s.stage, 6)} ${pad(s.users === null ? '-' : s.users, 9)} ${pad(s.target === null ? '-' : s.target, 8)} ${pad(s.status, 8)} ${lat}`);
    });
    const bp = v.breakingPoint;
    lines.push(`last passing stage ${bp.lastPassingStage === null ? '-' : bp.lastPassingStage}, first failing stage ${bp.firstFailingStage === null ? '-' : bp.firstFailingStage}`);
  }
  lines.push(v.thresholdsOk ? 'thresholds: all passed' : `thresholds: ${v.failedThresholds.length} failed`);
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
