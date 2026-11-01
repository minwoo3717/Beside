#!/usr/bin/env python3
"""Beside backend performance report: results of run.py -> HTML (Korean, A4) -> PDF through headless Chrome/Edge.

    python scripts/perf/report.py --data docs/perf/data/<run> [--data more...] [--versions-file docs/perf/versions.json]
        [--notes docs/perf/notes] [--out docs/perf/backend-perf-report.pdf] [--html-out scripts/perf/out/report.html]
        [--browser PATH]

Suites of the same version label are pooled across repetitions (and across --data folders); the noise rule of
stats.compare decides better / worse / same against the baseline of the versions file. Charts are inline SVG built
here (reference palette of the dataviz method, validated: categorical slots in fixed order, colour follows the
version). Standard library only.
"""
from __future__ import annotations

import argparse
import html
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import mdlite  # noqa: E402
import stats  # noqa: E402

REPO = HERE.parent.parent

# Categorical slots of the reference palette (light), in fixed order; a version keeps the colour of its position in
# the versions file's "order", whichever versions a report shows. Validated with the dataviz validator (adjacent
# pairs, light surface): all checks pass; aqua/yellow/magenta are below 3:1 on the surface, so every chart carries
# direct value labels and a table.
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"]
OTHER = "#898781"
INK, INK2, MUTED, GRID, AXIS, SURFACE = "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7", "#fcfcfb"

SCENARIOS = [
    ("startup", "서버 기동", "프로세스 시작(Popen) → 첫 healthz 200, Spring 'Started' 로그 시간, 그 3초 뒤 RSS. 서버 A, 빈 저장소."),
    ("healthz", "헬스 체크", "GET /api/v1/healthz 순차, keep-alive 연결 하나."),
    ("upload_small", "작은 사진 업로드", "64×64 PNG(약 12 KB) 1장 POST /api/v1/jobs 순차."),
    ("upload_large", "큰 사진 업로드", "약 4.5 MB 노이즈 PNG 4장(요청 하나에 약 18 MB) POST 순차."),
    ("upload_12mp", "12 MP 사진 업로드", "4000×3000 그라디언트 PNG(약 1.1 MB, 디코딩 부담) 1장 POST 순차, 이어서 4스레드 동시."),
    ("e2e_mock", "E2E 흐름 (mock)", "POST → 10 ms 간격 폴링 → COMPLETED → GLB 다운로드(크기 확인), 순차."),
    ("concurrent_e2e", "동시 E2E 흐름", "16스레드가 각자 E2E 흐름을 반복, 폴링 간격 50 ms."),
    ("poll_load", "폴링 부하", "COMPLETED 작업 하나를 4프로세스 × 4스레드가 GET /api/v1/jobs/{id} 로 반복 조회."),
    ("scale", "작업 수에 따른 변화", "작업을 N 개까지 쌓은 뒤(8스레드 업로드) 목록 첫 페이지·중간 페이지, 단건 조회, 업로드."),
    ("resources", "자원", "mock 시나리오를 모두 마친 서버 A 의 최대 RSS(작업 집합 최대치)와 jar 크기."),
    ("pending_poll", "재기동·PENDING 폴링", "서버 A 의 저장소로 재기동(서버 B, 작업이 PENDING 에 머묾): 기동 시간, PENDING 작업 조회."),
    ("real_path", "real · path 전송", "real 프로파일 + 추론 스텁(패스스루, 모델 없음), 사진 경로 전달. 4.5 MB PNG 1장 E2E 순차, 이어서 5건 동시 접수."),
    ("real_upload", "real · upload 전송", "real_path 와 같고 /infer/upload 로 사진을 올리고 GLB 를 내려받는다."),
    ("real_upload_prod", "real,prod · upload 전송", "real,prod 프로파일: 사진 시그니처 검사, 작업마다 추론 서비스 healthz 확인. upload 전송."),
]
SCENARIO_TITLES = {key: title for key, title, _what in SCENARIOS}

SAMPLE_LABELS = {
    "healthzMs": "응답 시간", "postMs": "업로드 요청 (POST→202)", "postConcurrentMs": "동시 업로드 (4스레드)",
    "flowMs": "전체 흐름 (POST→GLB 수신)", "completeMs": "완료 관측 (POST→COMPLETED)", "assetMs": "GLB 다운로드",
    "pollMs": "상태 조회 GET /jobs/{id}", "pendingPollMs": "PENDING 작업 조회", "serverQueuedMs": "서버 queuedMs",
    "serverProcessingMs": "서버 processingMs", "serverTotalMs": "서버 totalMs", "queueGapMs": "작업 사이 빈 시간",
    "listFirstMs": "목록 첫 페이지 (limit 20)", "listDeepMs": "목록 중간 페이지 (limit 100)", "getMs": "단건 조회",
}
# name -> (label, unit, lower is better; None = shown without a verdict)
VALUE_META = {
    "bench.startupMs": ("기동 (Popen→healthz 200)", "ms", True),
    "bench.springStartedMs": ("Spring 'Started' 로그 시간", "ms", True),
    "bench.idleRssMB": ("유휴 RSS (healthz 3초 뒤)", "MiB", True),
    "bench.uploadMBps": ("업로드 처리량 (요청 바이트 ÷ p50)", "MB/s", False),
    "bench.pollsPerJob": ("작업당 폴링 횟수 (평균)", "회", None),
    "bench.jobsPerSec": ("처리량 (완료 흐름 ÷ 경과 시간)", "건/s", False),
    "bench.pollReqPerSec": ("처리량 (성공 요청 ÷ 경과 시간)", "요청/s", False),
    "bench.errors": ("오류 (실패한 요청·흐름)", "건", None),
    "bench.peakRssMB": ("최대 RSS", "MiB", True),
    "bench.jarBytes": ("jar 크기", "bytes", None),
    "bench.restartStartupMs": ("재기동 (Popen→healthz 200)", "ms", True),
    "bench.restartSpringStartedMs": ("재기동 Spring 'Started' 로그 시간", "ms", True),
    # CPU time: Windows charges whole scheduler ticks (~15.6 ms) and the two pinned logical CPUs share one core, so
    # its spread between repetitions is wide: shown with its change, never with a verdict.
    "bench.serverCpuMs": ("서버 CPU 점유 시간 (시나리오 + 뒤따른 백그라운드 일)", "ms", None),
    "bench.settleAfterMs": ("끝난 뒤 서버가 쉴 때까지 더 기다린 시간 (확인 창 제외)", "ms", None),
    "bench.settleTotalMs": ("측정 전마다 서버가 쉴 때까지 더 기다린 합계 (확인 창 제외)", "ms", None),
}
# Values that count events rather than measure them: summed over the repetitions (a failure in one repetition must
# not vanish in a median), shown in red when not zero, never given a change rate or a verdict.
COUNTERS = {"bench.errors"}
# Idle waits are counted in whole check windows (settings.settle windowS): 0.5 ms against 1,000 ms is one window more,
# not a change, so these are shown as values only: no change rate, no verdict, no chart.
WINDOW_COUNTED = {"bench.settleAfterMs", "bench.settleTotalMs"}
SERVER_LABELS = {"uploadMs": "서버 uploadMs", "totalMs": "서버 totalMs", "queuedMs": "서버 queuedMs",
                 "processingMs": "서버 processingMs"}
REAL_ROWS = [("sample", "flowMs"), ("sample", "completeMs"), ("sample", "assetMs"), ("sample", "postMs"),
             ("sample", "serverQueuedMs"), ("sample", "serverProcessingMs"), ("sample", "serverTotalMs"),
             ("sample", "queueGapMs"), ("value", "bench.startupMs"), ("value", "bench.serverCpuMs"),
             ("value", "bench.settleTotalMs")]
SETTLE_ROWS = [("value", "bench.serverCpuMs"), ("value", "bench.settleAfterMs")]
ROWS = {
    "startup": [("value", "bench.startupMs"), ("value", "bench.springStartedMs"), ("value", "bench.idleRssMB")],
    "healthz": [("sample", "healthzMs")] + SETTLE_ROWS,
    "upload_small": [("sample", "postMs"), ("server", "postMs", "uploadMs")] + SETTLE_ROWS,
    "upload_large": [("sample", "postMs"), ("value", "bench.uploadMBps"), ("server", "postMs", "uploadMs")]
    + SETTLE_ROWS + [("value", "bench.settleTotalMs")],
    "upload_12mp": [("sample", "postMs"), ("sample", "postConcurrentMs"), ("server", "postMs", "uploadMs"),
                    ("server", "postConcurrentMs", "uploadMs")] + SETTLE_ROWS + [("value", "bench.settleTotalMs")],
    "e2e_mock": [("sample", "flowMs"), ("sample", "completeMs"), ("sample", "assetMs"), ("value", "bench.pollsPerJob"),
                 ("server", "flowMs", "totalMs")] + SETTLE_ROWS,
    "concurrent_e2e": [("sample", "flowMs"), ("sample", "postMs"), ("sample", "pollMs"), ("sample", "assetMs"),
                       ("value", "bench.jobsPerSec"), ("value", "bench.errors"), ("server", "flowMs", "totalMs")]
    + SETTLE_ROWS,
    "poll_load": [("sample", "pollMs"), ("value", "bench.pollReqPerSec"), ("value", "bench.errors")] + SETTLE_ROWS,
    "resources": [("value", "bench.peakRssMB"), ("value", "bench.jarBytes")],
    "pending_poll": [("value", "bench.restartStartupMs"), ("sample", "pendingPollMs")],
    "real_path": REAL_ROWS, "real_upload": REAL_ROWS, "real_upload_prod": REAL_ROWS,
}
SCALE_METRICS = ["listFirstMs", "listDeepMs", "getMs", "postMs"]
# The cover table: (scenario, row, short label). "{N}" = the largest scale target in the data, "{NAME}" = the N it
# was really measured at (measured_n).
KEY_ROWS = [
    ("startup", ("value", "bench.startupMs"), "기동 시간"),
    ("startup", ("value", "bench.idleRssMB"), "유휴 메모리 (RSS)"),
    ("healthz", ("sample", "healthzMs"), "healthz 응답"),
    ("upload_small", ("sample", "postMs"), "작은 사진 업로드"),
    ("upload_large", ("sample", "postMs"), "큰 사진 4장 업로드"),
    ("upload_12mp", ("sample", "postMs"), "12 MP 사진 업로드"),
    ("upload_12mp", ("sample", "postConcurrentMs"), "12 MP 동시 업로드"),
    ("upload_large", ("value", "bench.serverCpuMs"), "큰 사진 업로드의 서버 CPU 점유"),
    ("e2e_mock", ("sample", "flowMs"), "E2E 흐름 (mock)"),
    ("concurrent_e2e", ("sample", "flowMs"), "동시 E2E 흐름"),
    ("concurrent_e2e", ("value", "bench.jobsPerSec"), "동시 E2E 처리량"),
    ("poll_load", ("sample", "pollMs"), "폴링 부하 응답"),
    ("poll_load", ("value", "bench.pollReqPerSec"), "폴링 처리량"),
    ("scale", ("sample", "listFirstMs@{N}"), "목록 첫 페이지 ({NAME})"),
    ("scale", ("sample", "listDeepMs@{N}"), "목록 중간 페이지 ({NAME})"),
    ("scale", ("sample", "getMs@{N}"), "단건 조회 ({NAME})"),
    ("scale", ("sample", "postMs@{N}"), "업로드 ({NAME})"),
    ("pending_poll", ("value", "bench.restartStartupMs"), "재기동 시간"),
    ("pending_poll", ("sample", "pendingPollMs"), "PENDING 조회"),
    ("resources", ("value", "bench.peakRssMB"), "최대 메모리 (RSS)"),
    ("real_path", ("sample", "flowMs"), "real · path 흐름"),
    ("real_upload", ("sample", "flowMs"), "real · upload 흐름"),
    ("real_upload_prod", ("sample", "flowMs"), "real,prod · upload 흐름"),
    ("real_upload_prod", ("sample", "queueGapMs"), "작업 사이 빈 시간 (prod)"),
]
VERDICTS = {"better": ("✓", "개선"), "worse": ("!", "악화"), "same": ("=", "차이 없음")}

esc = html.escape


# ----------------------------------------------------------------------------------------------- loading


def load_data(dirs) -> dict:
    """{"runs": [{"dir", "run", "files"}], "suites": [suite + "_dir"/"_file"]} from the --data folders."""
    runs, suites = [], []
    for folder in dirs:
        folder = Path(folder)
        if not folder.is_dir():
            raise SystemExit(f"--data folder not found: {folder}")
        run_file = folder / "run.json"
        run = json.loads(run_file.read_text(encoding="utf-8")) if run_file.is_file() else None
        files = []
        for path in sorted(folder.glob("*-r*.json")):
            doc = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(doc, dict) or "scenarios" not in doc or "label" not in doc:
                continue
            doc["_dir"], doc["_file"] = str(folder), path.name
            suites.append(doc)
            files.append(path.name)
        runs.append({"dir": folder, "run": run, "files": files})
    if not suites:
        raise SystemExit("no suite files (<label>-r<rep>.json) in the --data folders")
    return {"runs": runs, "suites": suites}


def version_meta(data: dict, versions_file):
    """(order, baseline, {label: {title, description, ref, shas}}) from the versions file, else from run.json."""
    doc = None
    if versions_file and Path(versions_file).is_file():
        doc = json.loads(Path(versions_file).read_text(encoding="utf-8"))
    order, baseline, meta = [], None, {}
    if doc:
        order = list(doc.get("order") or doc.get("versions", {}).keys())
        baseline = doc.get("baseline")
        for label, info in (doc.get("versions") or {}).items():
            meta[label] = {"title": info.get("title", ""), "description": info.get("description", ""),
                           "ref": info.get("ref", "")}
    for run in data["runs"]:
        run_doc = run["run"] or {}
        for label in run_doc.get("versionOrder") or []:
            if label not in order:
                order.append(label)
        baseline = baseline or run_doc.get("baseline")
        for label, info in (run_doc.get("versions") or {}).items():
            meta.setdefault(label, {"title": info.get("title", ""), "description": info.get("description", ""),
                                    "ref": info.get("ref", "")})
    for suite in data["suites"]:
        label = suite["label"]
        if label not in order:
            order.append(label)
        info = meta.setdefault(label, {"title": suite.get("version", {}).get("title", ""),
                                       "description": suite.get("version", {}).get("description", ""),
                                       "ref": suite.get("version", {}).get("ref", "")})
        info.setdefault("shas", [])
        sha = suite.get("version", {}).get("sha")
        if sha and sha not in info["shas"]:
            info["shas"].append(sha)
    return order, baseline, meta


def by_label(suites) -> dict:
    out = {}
    for suite in sorted(suites, key=lambda s: (s["_dir"], s.get("rep", 0))):
        out.setdefault(suite["label"], []).append(suite)
    return out


def colors_for(order) -> dict:
    return {label: (SERIES[i] if i < len(SERIES) else OTHER) for i, label in enumerate(order)}


# ----------------------------------------------------------------------------------------------- numbers


def _results(suites, scenario):
    return [s["scenarios"][scenario] for s in suites if scenario in s.get("scenarios", {})]


def state_of(suites, scenario):
    """(state, reason): ok | skipped | error | missing (the version was not measured at all)."""
    results = _results(suites, scenario)
    if not suites or not results:
        return "missing", "측정 데이터 없음"
    statuses = [r.get("status") for r in results]
    if all(s == "skipped" for s in statuses):
        reason = results[0].get("reason", "")
        if reason == "capability" and results[0].get("missing"):
            reason += ": " + ", ".join(results[0]["missing"])
        return "skipped", reason
    errors = [r.get("reason", "") for r in results if r.get("status") == "error"]
    if errors:
        return "error", "; ".join(dict.fromkeys(errors))
    return "ok", ""


def reps_for(suites, scenario, row):
    """One list of numbers per repetition (repetitions without data are left out)."""
    out = []
    for result in _results(suites, scenario):
        if result.get("status") == "skipped":
            continue
        if row[0] == "sample":
            values = (result.get("samples") or {}).get(row[1])
        elif row[0] == "value":
            value = (result.get("values") or {}).get(row[1])
            values = None if value is None else [value]
        else:  # ("server", group, field)
            values = (((result.get("server") or {}).get(row[1]) or {}).get("samples") or {}).get(row[2])
        if values:
            out.append([float(v) for v in values])
    return out


def row_meta(row):
    """(label, unit, lower_is_better) of a table row; lower_is_better None = no verdict."""
    if row[0] == "sample":
        base, _, n = row[1].partition("@")
        label = SAMPLE_LABELS.get(base, base)
        return (f"{label} (N={n})" if n else label), "ms", True
    if row[0] == "value":
        if row[1].startswith("bench.actualN@"):
            return f"실제 작업 수 (목표 N={row[1].partition('@')[2]})", "개", None
        return VALUE_META.get(row[1], (row[1], "", True))
    return f"{SERVER_LABELS.get(row[2], row[2])} · events", "ms", True


def row_name(row) -> str:
    return row[1] if row[0] != "server" else f"{row[1]} → {row[2]}"


def is_counter(row) -> bool:
    return row[0] == "value" and row[1] in COUNTERS


def is_window_counted(row) -> bool:
    return row[0] == "value" and row[1] in WINDOW_COUNTED


def entry(suites, scenario, row) -> dict:
    """What one version measured for one row: state, repetitions, pooled summary / value (median of the
    repetitions; the sum for COUNTERS)."""
    state, reason = state_of(suites, scenario)
    reps = reps_for(suites, scenario, row) if state in ("ok", "error") else []
    out = {"state": state, "reason": reason, "reps": reps}
    if state in ("ok", "error") and not reps:
        out["state"] = "nodata" if state == "ok" else state
        return out
    if reps:
        pooled = [x for rep in reps for x in rep]
        out["summary"] = stats.summary(pooled)
        if row[0] == "value":
            per_rep = [rep[0] for rep in reps]
            out["value"] = math.fsum(per_rep) if is_counter(row) else stats.median(per_rep)
            out["range"] = (min(per_rep), max(per_rep))
    return out


def fmt_ms(value) -> str:
    if value is None:
        return "–"
    a = abs(value)
    if a >= 1000:
        return f"{value:,.0f}"
    if a >= 100:
        return f"{value:.0f}"
    if a >= 10:
        return f"{value:.1f}"
    return f"{value:.2f}"


def fmt_value(value, unit) -> str:
    if value is None:
        return "–"
    if unit == "ms":
        return fmt_ms(value)
    if unit == "bytes":
        return f"{value / 2 ** 20:,.1f} MiB"
    if unit in ("회",):
        return f"{value:.2f}"
    if unit in ("건", "개"):
        return f"{value:,.0f}" if float(value).is_integer() else f"{value:,.1f}"
    return f"{value:,.1f}"


def fmt_delta(pct) -> str:
    if pct is None:
        return "Δ –"
    if abs(pct) < 0.05:
        return "±0.0%"
    return ("+" if pct > 0 else "−") + f"{abs(pct):.1f}%"


def badge(verdict) -> str:
    icon, text = VERDICTS[verdict]
    return f'<span class="badge {verdict}"><span aria-hidden="true">{icon}</span> {text}</span>'


def unit_label(unit) -> str:
    return {"bytes": ""}.get(unit, unit)


def cell(e: dict, row, base: dict, is_base: bool, lower) -> str:
    """One table cell: p50 / p95 (or the value), then Δ% against the baseline with the verdict badge, or "비교 불가"
    when the baseline is 0 and this version is not. Counters (COUNTERS) show the sum over the repetitions, in red
    when it is not 0, with no change rate."""
    if e["state"] == "missing":
        return '<td class="na">–</td>'
    if e["state"] == "skipped":
        return f'<td class="na">측정 안 함<div class="why">{esc(short_reason(e["reason"]))}</div></td>'
    if e["state"] == "nodata":
        return '<td class="na">값 없음</td>'
    if not e.get("reps"):
        return f'<td class="na err">오류<div class="why">{esc(short_reason(e["reason"]))}</div></td>'
    _label, unit, _lower = row_meta(row)
    counter = is_counter(row)
    if row[0] == "value":
        main = fmt_value(e["value"], unit)
        reps_text = f'{len(e["reps"])}회 합계' if counter else f'{len(e["reps"])}회'
        extra = f'<span class="n">{reps_text}</span>' if len(e["reps"]) > 1 else ""
    else:
        s = e["summary"]
        main = f'{fmt_ms(s["p50"])} <span class="sep">/</span> {fmt_ms(s["p95"])}'
        extra = f'<span class="n">n={s["n"]}</span>'
    parts = [f'<div class="v">{main} {extra}</div>']
    if e["state"] == "error":
        parts.append(f'<div class="why err">오류 포함: {esc(short_reason(e["reason"]))}</div>')
    if unit == "개" or counter or is_window_counted(row):
        pass  # counts and window-counted waits, not measurements: no change rate, no verdict
    elif is_base:
        parts.append('<div class="d"><span class="basetag">기준</span></div>')
    elif base and base.get("reps") and e.get("reps"):
        cmp = stats.compare(base["reps"], e["reps"], lower_is_better=(lower is not False))
        if cmp and cmp["verdict"] == "n/a":
            parts.append('<div class="d">비교 불가 <span class="why">(기준 0)</span></div>')
        elif cmp:
            verdict = badge(cmp["verdict"]) if lower is not None else ""
            parts.append(f'<div class="d">{fmt_delta(cmp["deltaPct"])} {verdict}</div>')
    td_class = ' class="err"' if counter and e["value"] else ""
    return f"<td{td_class}>" + "".join(parts) + "</td>"


def short_reason(reason: str) -> str:
    reason = reason or ""
    if reason.startswith("capability"):
        detail = reason.partition(":")[2].replace("this version has no", "").strip()
        return "이 버전에 없는 기능" + (f" ({detail})" if detail else "")
    if reason.startswith("stub unavailable"):
        return "추론 스텁 없음"
    if reason.startswith("not selected"):
        return "--only 로 제외"
    return reason[:140]


# ----------------------------------------------------------------------------------------------- charts


def nice_ticks(vmax: float, count: int = 5):
    """Round tick values from 0 covering vmax (steps of 1, 2, 2.5, 5 x 10^k)."""
    if not vmax or vmax <= 0 or not math.isfinite(vmax):
        return [0.0, 1.0], 1.0
    raw = vmax / count
    magnitude = 10 ** math.floor(math.log10(raw))
    step = magnitude
    for m in (1, 2, 2.5, 5, 10):
        step = m * magnitude
        if step >= raw:
            break
    top = math.ceil(vmax / step - 1e-9) * step
    n = int(round(top / step))
    return [i * step for i in range(n + 1)], top


def fmt_tick(value: float) -> str:
    if value >= 1000:
        k = value / 1000
        return (f"{k:.0f}" if float(k).is_integer() else f"{k:.1f}".rstrip("0").rstrip(".")) + "K"
    if float(value).is_integer():
        return f"{value:.0f}"
    return f"{value:.2f}".rstrip("0").rstrip(".")


def _t(x, y, text, cls, anchor="start", extra=""):
    return f'<text x="{x:.1f}" y="{y:.1f}" class="{cls}" text-anchor="{anchor}"{extra}>{esc(str(text))}</text>'


def _bar_path(x0, y, width, height, radius=4.0) -> str:
    """A bar growing right from the baseline: square at x0, 4px rounded data end."""
    r = max(0.0, min(radius, width, height / 2))
    x1 = x0 + width
    return (f"M{x0:.2f},{y:.2f}H{x1 - r:.2f}Q{x1:.2f},{y:.2f} {x1:.2f},{y + r:.2f}V{y + height - r:.2f}"
            f"Q{x1:.2f},{y + height:.2f} {x1 - r:.2f},{y + height:.2f}H{x0:.2f}Z")


def svg_bars(facets, title: str) -> str:
    """Grouped horizontal bars, one facet per metric with its own axis: bar = p50 (value metrics: median of the
    repetitions), thin whisker to p95, p50 printed at the tip. facets: [{"title", "unit", "rows": [{"label",
    "color", "p50", "p95", "tip", "note"}]}]."""
    width, label_w, right_m = 660, 112, 64
    x0, x1 = label_w, width - right_m
    row_h, bar_h, title_h, axis_h, gap = 17, 11, 19, 20, 12
    parts, y = [], 0
    for facet in facets:
        rows = facet["rows"]
        highs = [r["p95"] if r.get("p95") is not None else r["p50"] for r in rows if r.get("p50") is not None]
        ticks, top = nice_ticks(max(highs) if highs else 1.0)

        def sx(v, top=top):
            return x0 + (x1 - x0) * (v / top)

        parts.append(_t(0, y + 12, facet["title"], "ct"))
        plot_top = y + title_h
        plot_bottom = plot_top + row_h * len(rows)
        for tick in ticks:
            parts.append(f'<line x1="{sx(tick):.1f}" y1="{plot_top - 2:.1f}" x2="{sx(tick):.1f}" '
                         f'y2="{plot_bottom:.1f}" class="grid"/>')
        parts.append(f'<line x1="{x0:.1f}" y1="{plot_top - 2:.1f}" x2="{x0:.1f}" y2="{plot_bottom:.1f}" class="base"/>')
        for i, r in enumerate(rows):
            cy = plot_top + i * row_h + row_h / 2
            parts.append(_t(x0 - 8, cy + 3.5, r["label"], "cl", "end"))
            if r.get("p50") is None:
                parts.append(_t(x0 + 6, cy + 3.5, r.get("note") or "측정 안 함", "cn"))
                continue
            w = max(0.0, sx(r["p50"]) - x0)
            tip = f'<title>{esc(r.get("tip", ""))}</title>' if r.get("tip") else ""
            parts.append(f'<g>{tip}<path d="{_bar_path(x0, cy - bar_h / 2, w, bar_h)}" fill="{r["color"]}"/>')
            end = sx(r["p50"])
            if r.get("p95") is not None and r["p95"] > r["p50"]:
                xp = sx(r["p95"])
                parts.append(f'<line x1="{end:.1f}" y1="{cy:.1f}" x2="{xp:.1f}" y2="{cy:.1f}" class="whisker"/>'
                             f'<line x1="{xp:.1f}" y1="{cy - 4:.1f}" x2="{xp:.1f}" y2="{cy + 4:.1f}" class="whisker"/>')
                end = xp
            parts.append(_t(end + 5, cy + 3.5, fmt_value(r["p50"], facet["unit"]), "cv") + "</g>")
        for tick in ticks:
            parts.append(_t(sx(tick), plot_bottom + 12, fmt_tick(tick), "ca", "middle"))
        parts.append(_t(x1 + 18, plot_bottom + 12, unit_label(facet["unit"]), "ca"))
        y = plot_bottom + axis_h + gap
    height = max(y - gap, 10)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height:.0f}" width="{width}" '
            f'height="{height:.0f}" role="img" aria-label="{esc(title)}"><title>{esc(title)}</title>'
            + "".join(parts) + "</svg>")


def svg_lines(title: str, unit: str, series, x_label: str = "작업 수 N") -> str:
    """p50 against N, one 2px line per version with 8px markers ringed in the surface colour."""
    width, height = 330, 206
    x0, x1, y0, y1 = 46, width - 16, 40, height - 36
    xs = [p[0] for s in series for p in s["points"]]
    ys = [p[1] for s in series for p in s["points"]]
    xticks, xtop = nice_ticks(max(xs) if xs else 1.0, 4)
    yticks, ytop = nice_ticks(max(ys) if ys else 1.0, 4)

    def sx(v):
        return x0 + (x1 - x0) * (v / xtop)

    def sy(v):
        return y1 - (y1 - y0) * (v / ytop)

    parts = [_t(0, 13, title, "ct")]
    for tick in yticks:
        parts.append(f'<line x1="{x0}" y1="{sy(tick):.1f}" x2="{x1}" y2="{sy(tick):.1f}" class="grid"/>')
        parts.append(_t(x0 - 6, sy(tick) + 3.5, fmt_tick(tick), "ca", "end"))
    parts.append(f'<line x1="{x0}" y1="{y1}" x2="{x1}" y2="{y1}" class="base"/>')
    for tick in xticks:
        parts.append(_t(sx(tick), y1 + 13, fmt_tick(tick), "ca", "middle"))
    parts.append(_t((x0 + x1) / 2, y1 + 28, x_label, "ca", "middle"))
    parts.append(_t(x0 - 6, y0 - 12, unit, "ca", "end"))
    for s in series:
        points = sorted(s["points"])
        if not points:
            continue
        path = "M" + " L".join(f"{sx(px):.1f},{sy(py):.1f}" for px, py in points)
        parts.append(f'<path d="{path}" fill="none" stroke="{s["color"]}" class="line"/>')
        for px, py in points:
            parts.append(f'<g><title>{esc(s["label"])} · N={px:.0f} · p50 {fmt_ms(py)} ms</title>'
                         f'<circle cx="{sx(px):.1f}" cy="{sy(py):.1f}" r="4" fill="{s["color"]}" class="dot"/></g>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" width="{width}" '
            f'height="{height}" role="img" aria-label="{esc(title)}"><title>{esc(title)}</title>'
            + "".join(parts) + "</svg>")


def legend(labels, colors, meta) -> str:
    items = "".join(f'<span><span class="sw" style="background:{colors[label]}"></span>{esc(label)}'
                    f'<span class="lt"> {esc(meta.get(label, {}).get("title", ""))}</span></span>' for label in labels)
    return f'<div class="legend">{items}</div>'


# ----------------------------------------------------------------------------------------------- report


CSS = """
@page { size: A4; margin: 14mm; @bottom-right { content: counter(page) " / " counter(pages); font-size: 7.5pt; color: #898781; } }
:root { color-scheme: light; }
html, body { background: #ffffff; }
body { margin: 0; color: #0b0b0b; font-family: "Malgun Gothic", "Noto Sans KR", "Apple SD Gothic Neo", sans-serif;
       font-size: 9.3pt; line-height: 1.5; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
h1 { font-size: 16pt; margin: 0 0 2.5mm; line-height: 1.25; }
h2 { font-size: 12pt; margin: 6mm 0 2mm; line-height: 1.3; break-after: avoid; }
h3 { font-size: 10.3pt; margin: 5mm 0 1.5mm; break-after: avoid; }
p { margin: 0 0 2mm; }
ul, ol { margin: 0 0 2mm; padding-left: 5mm; }
li { margin: 0.4mm 0; }
code { font-family: Consolas, "D2Coding", "Courier New", monospace; font-size: 0.9em; background: #f3f2ee;
       padding: 0 0.6mm; border-radius: 2px; }
pre { background: #f3f2ee; padding: 2mm; border-radius: 2px; font-size: 8pt; white-space: pre-wrap; }
section.major { break-before: page; }
.cover .sub { color: #52514e; margin-bottom: 4mm; }
table { border-collapse: collapse; width: 100%; margin: 1.5mm 0 3mm; font-size: 8.3pt;
        font-variant-numeric: tabular-nums; }
th, td { border-bottom: 1px solid #e1e0d9; padding: 1mm 1.4mm; text-align: left; vertical-align: top; }
thead th { border-bottom: 1px solid #c3c2b7; color: #52514e; font-weight: 600; white-space: nowrap; }
thead { display: table-header-group; }
tr { break-inside: avoid; }
tbody th { font-weight: 600; word-break: keep-all; }
table.metrics th:first-child { width: 30%; }
table.pairs thead th { white-space: normal; word-break: keep-all; }
table.pairs tbody th, table.pairs td { padding-top: 0.6mm; padding-bottom: 0.6mm; }
.keep { break-inside: avoid; }
tbody th .unit { font-weight: 400; color: #898781; font-size: 7.5pt; }
td .v { white-space: nowrap; }
td .sep, td .n { color: #898781; }
td .n { font-size: 7.2pt; margin-left: 0.6mm; }
td .d { font-size: 7.8pt; color: #52514e; white-space: nowrap; }
td.na { color: #898781; }
.why { font-size: 7.2pt; color: #898781; white-space: normal; }
.err { color: #a32020; }
.badge { display: inline-block; padding: 0 1.2mm; border-radius: 3px; font-size: 7.3pt; line-height: 1.45; }
.badge.better { color: #006300; background: #e6f4e6; }
.badge.worse { color: #a32020; background: #fbe9e9; }
.badge.same { color: #52514e; background: #f0efec; }
.basetag { color: #52514e; font-size: 7.3pt; border: 1px solid #c3c2b7; border-radius: 3px; padding: 0 1.2mm; }
.sw { display: inline-block; width: 8px; height: 8px; border-radius: 2px; margin-right: 1.4mm; }
.legend { display: flex; flex-wrap: wrap; gap: 1mm 5mm; font-size: 8pt; color: #52514e; margin: 1mm 0 1.5mm; }
.legend .lt { color: #898781; }
figure { margin: 2mm 0 4mm; break-inside: avoid; }
figure.bars { break-inside: auto; }
.facet { break-inside: avoid; }
.facet + .facet { margin-top: 3mm; }
.legend { break-after: avoid; break-inside: avoid; }
figcaption { break-before: avoid; }
figure svg { width: 100%; height: auto; display: block; }
figcaption { font-size: 7.6pt; color: #898781; margin-top: 1mm; }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 3mm 6mm; }
.banner { border-left: 3px solid #fab219; background: #fff8e6; padding: 1.6mm 2.4mm; margin: 2mm 0 3mm;
          font-size: 8.5pt; }
.banner b { color: #7a5200; }
.scenario { break-inside: auto; }
.scenario h3 { break-before: auto; }
.head { break-after: avoid; }
.what { color: #52514e; font-size: 8.6pt; }
.notes { font-size: 7.6pt; color: #898781; margin: 0 0 2.5mm; break-inside: avoid; }
.notes ul { margin: 0.4mm 0 0; padding-left: 4mm; }
.notes li { margin: 0.2mm 0; }
.muted { color: #898781; }
.small { font-size: 8pt; }
svg text { font-family: "Malgun Gothic", "Noto Sans KR", "Apple SD Gothic Neo", sans-serif; }
svg .ct { font-size: 11px; font-weight: 600; fill: #0b0b0b; }
svg .cl { font-size: 10.5px; fill: #52514e; }
svg .cv { font-size: 10px; fill: #52514e; }
svg .cn { font-size: 10px; fill: #898781; }
svg .ca { font-size: 9.5px; fill: #898781; }
svg .grid { stroke: #e1e0d9; stroke-width: 1; }
svg .base { stroke: #c3c2b7; stroke-width: 1; }
svg .whisker { stroke: #52514e; stroke-width: 1.2; }
svg .line { stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
svg .dot { stroke: #fcfcfb; stroke-width: 2; }
.md-table { font-size: 8.3pt; }
"""


def _version_cells(labels, colors):
    return "".join(f'<th><span class="sw" style="background:{colors[l]}"></span>{esc(l)}</th>' for l in labels)


def metrics_table(rows, labels, groups, colors, baseline, first_header="지표") -> str:
    """rows: [(scenario, row, label)]; columns = versions."""
    out = [f'<table class="metrics"><thead><tr><th>{esc(first_header)}</th>{_version_cells(labels, colors)}'
           "</tr></thead><tbody>"]
    for scenario, row, label in rows:
        name, unit, lower = row_meta(row)
        what = "p50 / p95" if row[0] != "value" else "값"
        unit_text = unit_label(unit) if unit != "bytes" else ""
        head = (f'<th>{esc(label)}<div class="unit"><code>{esc(row_name(row))}</code> · '
                f'{esc(unit_text + " · " if unit_text else "")}{what}</div></th>')
        entries = {l: entry(groups.get(l, []), scenario, row) for l in labels}
        base = entries.get(baseline) if baseline in entries else None
        cells = "".join(cell(entries[l], row, base, l == baseline, lower) for l in labels)
        out.append(f"<tr>{head}{cells}</tr>")
    out.append("</tbody></table>")
    return "".join(out)


def version_pairs(versions_file, labels):
    """([(from, to, title)], [dropped "from → to"]) from the versions file's optional "pairs" list of
    {"from", "to", "title"?}: each pair is compared on its own (from = the reference). Pairs naming a version that was
    not measured are dropped, and listed so the report can say so."""
    if not versions_file or not Path(versions_file).is_file():
        return [], []
    doc = json.loads(Path(versions_file).read_text(encoding="utf-8"))
    pairs, dropped = [], []
    for pair in doc.get("pairs") or []:
        a, b = pair.get("from"), pair.get("to")
        if a in labels and b in labels and a != b:
            pairs.append((a, b, pair.get("title") or f"{a} → {b}"))
        else:
            dropped.append(f"{a} → {b}")
    return pairs, dropped


def pair_cell(ea: dict, eb: dict, row, lower) -> str:
    """One cell of the pairs table: from → to (p50, or the median of the repetitions for values), then the change of
    `to` against `from` with the same rule and badge as the main tables; – when a side has no data or the row is a
    count."""
    _label, unit, _lower = row_meta(row)
    if unit == "개" or is_counter(row) or is_window_counted(row) or not ea.get("reps") or not eb.get("reps"):
        return '<td class="na">–</td>'
    cmp = stats.compare(ea["reps"], eb["reps"], lower_is_better=(lower is not False))
    if not cmp:
        return '<td class="na">–</td>'
    parts = [f'<div class="v">{fmt_value(cmp["baseline"], unit)} → {fmt_value(cmp["candidate"], unit)}</div>']
    if cmp["verdict"] == "n/a":
        parts.append('<div class="d">비교 불가 <span class="why">(기준 0)</span></div>')
    else:
        verdict = badge(cmp["verdict"]) if lower is not None else ""
        parts.append(f'<div class="d">{fmt_delta(cmp["deltaPct"])} {verdict}</div>')
    if "error" in (ea["state"], eb["state"]):
        parts.append('<div class="why err">오류 포함</div>')
    return "<td>" + "".join(parts) + "</td>"


def pairs_table(rows, pairs, groups) -> str:
    """rows: [(scenario, row, label)] as in metrics_table; columns = the version pairs [(from, to, title)]."""
    heads = "".join(f'<th>{esc(title)}<div class="unit">{esc(a)} → {esc(b)}</div></th>' for a, b, title in pairs)
    out = [f'<table class="metrics pairs"><thead><tr><th>지표</th>{heads}</tr></thead><tbody>']
    for scenario, row, label in rows:
        _name, unit, lower = row_meta(row)
        unit_text = unit_label(unit) if unit != "bytes" else ""
        head = (f'<th>{esc(label)}<div class="unit"><code>{esc(row_name(row))}</code>'
                f'{esc(" · " + unit_text if unit_text else "")}</div></th>')
        cells = "".join(pair_cell(entry(groups.get(a, []), scenario, row), entry(groups.get(b, []), scenario, row),
                                  row, lower) for a, b, _title in pairs)
        out.append(f"<tr>{head}{cells}</tr>")
    out.append("</tbody></table>")
    return "".join(out)


def scenario_chart(scenario, rows, labels, groups, colors, meta) -> str:
    facets = []
    for row in rows:
        label, unit, _lower = row_meta(row)
        if row[0] == "value" and unit not in ("ms", "MiB", "MB/s", "건/s", "요청/s"):
            continue
        if row[0] == "value" and (row[1] == "bench.errors" or is_window_counted(row) or scenario.startswith("real")):
            continue  # errors and waits stay in the table; server C start-up is not what a real chart is about
        bars = []
        for version in labels:
            e = entry(groups.get(version, []), scenario, row)
            note = {"skipped": "측정 안 함", "missing": "측정 안 함", "nodata": "값 없음"}.get(e["state"])
            if e.get("reps"):
                if row[0] == "value":
                    p50, p95 = e["value"], None
                    tip = f"{version} · {label} {fmt_value(p50, unit)} {unit_label(unit)} (반복 {len(e['reps'])}회 중앙값)"
                else:
                    p50, p95 = e["summary"]["p50"], e["summary"]["p95"]
                    tip = f"{version} · {label} p50 {fmt_ms(p50)} ms · p95 {fmt_ms(p95)} ms (n={e['summary']['n']})"
                bars.append({"label": version, "color": colors[version], "p50": p50, "p95": p95, "tip": tip})
            else:
                bars.append({"label": version, "color": colors[version], "p50": None, "p95": None,
                             "note": note or "오류"})
        if any(b["p50"] is not None for b in bars):
            facets.append({"title": f"{label} · {row_name(row)}", "unit": unit, "rows": bars})
    if not facets:
        return ""
    # one SVG per metric: a real scenario has eight, taller than a page as one picture, so the page now breaks
    # between two charts instead of through one (each row names its version, so later charts need no legend)
    scenario_title = SCENARIO_TITLES.get(scenario, scenario)
    charts = "".join(f'<div class="facet">{svg_bars([facet], f"{scenario_title}: {facet["title"]}, 버전별 p50(막대)과 p95(가는 선)")}</div>'
                     for facet in facets)
    return (f'<figure class="bars">{legend(labels, colors, meta)}{charts}'
            "<figcaption>막대 = p50(반복을 합친 표본; 값 지표는 반복의 중앙값), 가는 선 = p95, 숫자 = p50. "
            "지표마다 축이 따로다.</figcaption></figure>")


def scale_targets(groups) -> list:
    targets = set()
    for suites in groups.values():
        for result in _results(suites, "scale"):
            for name in (result.get("samples") or {}):
                base, _, n = name.partition("@")
                if base in SCALE_METRICS and n.isdigit():
                    targets.add(int(n))
    return sorted(targets)


def measured_n(groups, labels, target) -> str:
    """The N a scale row was really measured at: "N=390 (목표 200)" when earlier scenarios had already made more jobs
    than the target (a full run makes about 390 before scale starts), "N=200" when it matches, a range when the
    versions or repetitions differ."""
    values = []
    for version in labels:
        values += [rep[0] for rep in reps_for(groups.get(version, []), "scale", ("value", f"bench.actualN@{target}"))]
    if not values:
        return f"N={target}"
    low, high = min(values), max(values)
    shown = f"{low:.0f}" if low == high else f"{low:.0f}–{high:.0f}"
    return f"N={shown}" if low == high == target else f"N={shown} (목표 {target})"  # no break inside "(목표 50)"


def scale_section(labels, groups, colors, meta, baseline) -> str:
    targets = scale_targets(groups)
    if not targets:
        return ""
    names = {n: measured_n(groups, labels, n) for n in targets}
    rows = [("scale", ("sample", f"{m}@{n}"), f"{SAMPLE_LABELS[m]} · {names[n]}") for m in SCALE_METRICS
            for n in targets]
    rows += [("scale", ("value", f"bench.actualN@{n}"), f"실제 작업 수 (목표 N={n})") for n in targets]
    table = metrics_table(rows, labels, groups, colors, baseline)
    charts = []
    for metric in SCALE_METRICS:
        series = []
        for version in labels:
            points = []
            for n in targets:
                e = entry(groups.get(version, []), "scale", ("sample", f"{metric}@{n}"))
                actual = entry(groups.get(version, []), "scale", ("value", f"bench.actualN@{n}"))
                if e.get("reps"):
                    x = actual.get("value") if actual.get("reps") else n
                    points.append((float(x), e["summary"]["p50"]))
            if points:
                series.append({"label": version, "color": colors[version], "points": points})
        if series:
            charts.append(svg_lines(f"{SAMPLE_LABELS[metric]} · {metric} p50", "ms", series))
    figure = ""
    if charts:
        figure = (f"<figure>{legend(labels, colors, meta)}<div class=\"grid2\">{''.join(charts)}</div>"
                  "<figcaption>가로축 = 측정 때 서버에 있던 작업 수(이전 시나리오가 만든 작업 포함), 세로축 = p50. "
                  "목록은 최신 작업부터, 중간 페이지는 서버가 준 nextCursor 로 약 N/2 위치에서 시작.</figcaption></figure>")
    return table + figure


def env_table(runs) -> str:
    rows = [("OS", "os"), ("CPU", "cpuModel"), ("논리 CPU", "logicalCpus"), ("메모리 (GB)", "ramGB"),
            ("Java", "java"), ("Python (부하 생성기)", "python"), ("JVM 옵션", "jvmArgs"),
            ("서버 CPU 고정", "pinnedCpus"), ("전원", "power")]
    envs = [(r["dir"].name, (r["run"] or {}).get("env") or {}) for r in runs]
    head = "".join(f"<th>{esc(name)}</th>" for name, _ in envs) if len(envs) > 1 else "<th>값</th>"
    out = [f"<table><thead><tr><th>항목</th>{head}</tr></thead><tbody>"]
    for title, key in rows:
        cells = []
        for _name, env in envs:
            value = env.get(key)
            if key == "java" and value:
                value = str(value).splitlines()[0]
            elif key == "jvmArgs" and value:
                value = " ".join(value)
            elif key == "pinnedCpus":
                cores = env.get("pinnedCores")
                value = ((", ".join(map(str, value)) + " (마지막 논리 CPU" + (f", 물리 코어 {cores}개" if cores else "")
                          + ")") if value else "고정 안 함")
            elif key == "power" and isinstance(value, dict):
                value = ", ".join(f"{k} {v}" for k, v in value.items()) or "–"
            cells.append(f"<td>{esc(str(value if value not in (None, '') else '–'))}</td>")
        out.append(f"<tr><th>{esc(title)}</th>{''.join(cells)}</tr>")
    out.append("</tbody></table>")
    return "".join(out)


def counts_text(counts: dict, scenario: str) -> str:
    c = counts or {}
    g = c.get

    def both(warm, n):
        return f"워밍업 {g(warm)} + 측정 {g(n)}"

    texts = {
        "startup": "서버당 1회",
        "healthz": both("healthz.warmup", "healthz.n"),
        "upload_small": both("upload_small.warmup", "upload_small.n"),
        "upload_large": both("upload_large.warmup", "upload_large.n") + f", 요청당 {g('upload_large.files')}장",
        "upload_12mp": both("upload_12mp.warmup", "upload_12mp.n")
                       + f", 동시 {g('upload_12mp.threads')}스레드 × {g('upload_12mp.concurrentPerThread')}",
        "e2e_mock": both("e2e_mock.warmup", "e2e_mock.n") + f", 폴링 {g('e2e_mock.pollMs')} ms",
        "concurrent_e2e": f"{g('concurrent_e2e.threads')}스레드 × {g('concurrent_e2e.perThread')}흐름, "
                          f"폴링 {g('concurrent_e2e.pollMs')} ms",
        "poll_load": f"{g('poll_load.processes')}프로세스 × {g('poll_load.threads')}스레드 × "
                     f"{g('poll_load.perThread')} (스레드마다 연결용 워밍업 {g('poll_load.warmupPerThread')})",
        "scale": f"N = {', '.join(map(str, g('scale.targets') or []))}; 목록 첫 {g('scale.listFirst')}, "
                 f"중간 {g('scale.listDeep')}, 조회 {g('scale.get')}, 업로드 {g('scale.post')} "
                 f"(각 워밍업 {g('scale.warmup')}, 업로드 제외)",
        "resources": "1회",
        "pending_poll": f"PENDING 작업 {g('pending_poll.jobs')}개, " + both("pending_poll.warmup", "pending_poll.n"),
    }
    real = both("real.warmup", "real.n") + f", 폴링 {g('real.pollMs')} ms, 동시 접수 {g('real.batch')}건"
    return texts.get(scenario, real if scenario.startswith("real") else "")


def scenario_notes(labels, groups, key, per_version: int = 4) -> list:
    """The notes of one scenario as "<label> r<rep>[·r<rep>]: <note>", in version and repetition order. A note that
    several repetitions of a version share is shown once with all of them; at most per_version notes per version."""
    out = []
    for label in labels:
        found = {}
        for suite in sorted(groups.get(label, []), key=lambda s: (s.get("rep") or 0, s.get("_dir", ""))):
            for note in (((suite.get("scenarios") or {}).get(key) or {}).get("notes") or []):
                reps = found.setdefault(str(note), [])
                if f"r{suite.get('rep')}" not in reps:
                    reps.append(f"r{suite.get('rep')}")
        items = [f"{label} {'·'.join(reps)}: {note}" for note, reps in found.items()]
        if len(items) > per_version:
            items = items[:per_version] + [f"{label}: 메모 {len(items) - per_version}개 더 (결과 JSON 의 notes)"]
        out.extend(items)
    return out


def condition_of(suite) -> tuple:
    """What a suite was measured with: (--quick, the counts, the idle waits, the priority), the last three as
    canonical JSON. Suites measured without idle waits (older tool) differ from those measured with them."""
    settings = suite.get("settings") or {}
    return (bool(suite.get("quick")), json.dumps(settings.get("counts") or {}, sort_keys=True),
            json.dumps(settings.get("settle"), sort_keys=True), json.dumps(settings.get("priority")))


def differing_counts(conditions) -> list:
    """The count names whose values differ between these conditions (condition_of tuples)."""
    docs = [json.loads(condition[1]) for condition in conditions]
    names = sorted(set().union(*docs)) if docs else []
    return [name for name in names if len({json.dumps(d.get(name), sort_keys=True) for d in docs}) > 1]


def _method_name(condition, conditions) -> str:
    """'full' / '--quick', plus the idle-wait and priority method when those differ between the conditions."""
    quick, _counts, settle, priority = condition
    name = "--quick" if quick else "full"
    if len({c[2] for c in conditions}) > 1:
        name += ", no idle waits" if settle == "null" else ", idle waits"
    if len({c[3] for c in conditions}) > 1:
        name += f", priority {json.loads(priority)}"
    return name


def _suite_name(suite) -> str:
    return f"{Path(suite.get('_dir', '')).name}/{suite.get('_file', '?')}"


def check_pooling(groups) -> None:
    """Suites of one version become one pooled p50/p95 and one input of the noise rule, so they must have been
    measured the same way. Raises ValueError (listing the files) when --quick or the counts differ."""
    problems = []
    for label, suites in groups.items():
        by_condition = {}
        for suite in suites:
            by_condition.setdefault(condition_of(suite), []).append(_suite_name(suite))
        if len(by_condition) > 1:
            parts = [f"{_method_name(condition, by_condition)} [{', '.join(files)}]"
                     for condition, files in by_condition.items()]
            differ = differing_counts(list(by_condition))
            problems.append(f"{label}: " + " vs ".join(parts)
                            + (f"; counts differ in {', '.join(differ[:8])}" + (" ..." if len(differ) > 8 else "")
                               if differ else ""))
    if problems:
        raise ValueError("suites of one version were measured with different settings and are not pooled; pass "
                         "--data folders measured the same way (or report them separately):\n  " + "\n  ".join(problems))


def conditions_list(data) -> str:
    """When the --data folders were measured with different counts (only possible across versions): which folder
    used what, the differing counts only. Empty when all agree."""
    per_folder = {}
    for suite in data["suites"]:
        per_folder.setdefault(Path(suite.get("_dir", "")).name, {}).setdefault(condition_of(suite), set()).add(
            suite["label"])
    conditions = {c for folder in per_folder.values() for c in folder}
    if len(conditions) < 2:
        return ""
    differ = differing_counts(list(conditions))
    items = []
    for folder, by_condition in per_folder.items():
        for condition, versions in by_condition.items():
            quick, counts_json, settle, _priority = condition
            counts = json.loads(counts_json)
            shown = ", ".join(f"{name}={json.dumps(counts.get(name))}" for name in differ[:10])
            shown += f" 외 {len(differ) - 10}개" if len(differ) > 10 else ""
            if len({c[2] for c in conditions}) > 1:
                shown = ("대기 없음(이전 도구)" if settle == "null" else "유휴 대기") + (f", {shown}" if shown else "")
            items.append(f"<li><code>{esc(folder)}</code> ({esc(', '.join(sorted(versions)))}): "
                         f"{'--quick' if quick else '보고용 횟수'} — {esc(shown)}</li>")
    return ("<h2>데이터별 조건</h2><p class=\"small\">--data 폴더마다 측정 횟수나 방법이 달라 버전 사이 비교는 참고용이다. "
            "서로 다른 항목만 적는다. 위 시나리오 표의 횟수는 기준 버전 데이터의 것이다.</p><ul class=\"small\">"
            + "".join(items) + "</ul>")


def per_run_text(runs, make) -> str:
    """make(run doc) for every --data folder that has a run.json: one text when they agree, otherwise each text
    prefixed with its folders."""
    texts = {}
    for run in runs:
        doc = run["run"] or {}
        if doc:
            texts.setdefault(make(doc), []).append(run["dir"].name)
    if len(texts) <= 1:
        return next(iter(texts), "")
    return " ".join(f"[{esc(', '.join(names))}] {text}" for text, names in texts.items())


def _active_processors(env):
    return next((a.partition("=")[2] for a in env.get("jvmArgs") or [] if a.startswith("-XX:ActiveProcessorCount=")),
                None)


def cpu_text(doc) -> str:
    """The pinning sentence of the server conditions, from run.json (serverCpus, pinnedCpus, pinnedCores, jvmArgs)."""
    env = doc.get("env") or {}
    cpus = env.get("pinnedCpus")
    if not cpus:
        why = "--server-cpus 0" if env.get("serverCpus") == 0 else "이 OS 에서는 고정할 수 없음"
        return (f"서버 CPU 를 고정하지 않았다({esc(why)}): 서버, 부하 생성기(Python), 추론 스텁이 모든 논리 CPU 를 "
                "나눠 쓴다.")
    listed = esc(", ".join(map(str, cpus)))
    cores = env.get("pinnedCores")
    if cores is None:
        smt = ("SMT CPU 에서는 이 논리 CPU 들이 물리 코어 하나를 나눠 쓰는 두 스레드일 수 있다 — 이 실행은 코어 구성을 "
               "기록하지 않았다")
    elif cores < len(cpus):
        smt = f"물리 코어 {cores}개를 SMT 로 나눈 논리 CPU {len(cpus)}개라 계산 자원은 코어 {cores}개 분량이다"
    else:
        smt = f"물리 코어 {cores}개"
    active = _active_processors(env)
    jvm = (f" JVM 에는 <code>-XX:ActiveProcessorCount={esc(active)}</code> 로 CPU {esc(active)}개라고 알렸다."
           if active else "")
    return (f"서버 프로세스는 마지막 논리 CPU {listed} 에 고정했다({smt}).{jvm} 부하 생성기(Python)와 추론 스텁은 "
            "고정하지 않는다.")


def storage_text(doc) -> str:
    work = str(doc.get("workDir") or "")
    where = f"작업 폴더 <code>{esc(work)}</code> 의 실행 폴더 아래에" if work else "작업 폴더의 실행 폴더 아래에"
    synced = (" 작업 폴더가 OneDrive 안이라 동기화가 디스크 I/O 에 섞일 수 있다." if "onedrive" in work.lower()
              else "")
    return (f"저장소(H2 파일 DB, 업로드, 결과, events.jsonl)는 {where} 서버 A 와 서버 C(real 시나리오마다)를 띄울 때마다 "
            "새로 만든다. 서버 B 는 A 의 저장소를 그대로 다시 연다(재기동 시나리오). 모든 <code>storage.*</code> 경로는 "
            "절대 경로로 지정." + synced)


def shared_pc_text(doc) -> str:
    env = doc.get("env") or {}
    cpus = env.get("pinnedCpus")
    if cpus:
        cores = env.get("pinnedCores")
        core_text = f", 물리 코어 {cores}개" if cores else ""
        pinned = (f"서버는 논리 CPU {esc(', '.join(map(str, cpus)))}({len(cpus)}개{core_text})에 고정했지만 부하 생성기와 "
                  "추론 스텁은 고정하지 않았고")
    else:
        pinned = "서버 CPU 를 고정하지 않아 서버와 부하 생성기가 같은 CPU 를 다투고"
    return (f"서버와 클라이언트가 같은 PC 에서 돈다. {pinned}, 디스크·메모리 대역은 공유한다. 백신 실시간 검사 등 "
            "OS 활동도 포함된다.")


def fresh_open_text(doc) -> str:
    """What run.json's env.freshFileOpen says about opening a just-written file ('' when not recorded)."""
    fresh = (doc.get("env") or {}).get("freshFileOpen") or {}
    first, second = fresh.get("firstMs"), fresh.get("secondMs")
    if first is None or second is None:
        return ""
    size = f"{(fresh.get('bytes') or 0) / 1e6:.1f} MB"
    if first >= 20 and first >= 10 * max(second, 0.1):
        return (f"갓 쓴 파일의 첫 열기가 느리다: {size} 사진 사본을 쓰자마자 열어 읽는 데 {first:.0f} ms, 다시 읽으면 "
                f"{second:.1f} ms 였다(실시간 백신 검사로 보인다). 업로드 직후 사진을 여는 서버 코드(요청 안에서 만드는 "
                "썸네일, 추론 서비스로의 업로드 전송)는 이 대기를 포함하고, 그런 검사가 없는 Linux VM 에서는 이만큼 빠진다.")
    return f"갓 쓴 파일의 첫 열기: {size} 사진 사본 {first:.1f} ms, 다시 읽기 {second:.1f} ms(검사 대기로 보이는 차이 없음)."


def settle_text(suites) -> str:
    """How the suites waited for an idle server, from their settings.settle (one sentence; older data had none)."""
    kinds = {json.dumps((s.get("settings") or {}).get("settle"), sort_keys=True) for s in suites}
    settles = [json.loads(k) for k in kinds if k != "null"]
    if not settles:
        return ("서버가 쉴 때까지 기다리지 않았다: 시나리오가 끝나자마자 다음 시나리오를 시작했다(이 결과를 만든 도구 버전). "
                "응답 뒤에 남은 일(백그라운드 썸네일 큐, GC, JIT)이 다음 측정에 섞일 수 있다.")
    st = settles[0]
    text = (f"서버가 쉴 때까지 기다린다: 서버 프로세스가 한 창 동안 CPU 를 한 코어의 {st.get('busyPct', 5):g} % 이하로 쓰면 쉬는 "
            f"것으로 본다. 서버 A 의 시나리오가 끝날 때마다 {st.get('windowS', 1):g}초 창(최대 {st.get('maxS', 120):g}초), "
            f"큰 사진·12 MP 업로드와 real 흐름은 하나마다 시작 전에 {st.get('perRequestWindowS', 0.5):g}초 창(최대 "
            f"{st.get('perRequestMaxS', 30):g}초), 12 MP 동시 업로드·scale 의 측정 전과 PENDING 작업을 만든 뒤에도 기다린다. "
            "응답 뒤에 남은 일(백그라운드 썸네일 큐, GC, JIT)이 다음 측정에 섞이지 않게 하려는 것이다(실사용에서 업로드는 "
            "몇 분 간격). 쉬는 것을 확인한 창 하나를 뺀 대기는 <code>bench.settleAfterMs</code>·<code>bench.settleTotalMs</code>, "
            "시나리오 시작부터 대기 끝까지 서버가 쓴 CPU 시간은 <code>bench.serverCpuMs</code> 다. CPU 시간은 Windows 에서 "
            "스케줄러 틱(약 15.6 ms) 단위이고 고정한 두 논리 CPU 가 한 물리 코어를 나눠 쓰므로 반복 사이 흔들림이 커서 "
            "변화율만 보이고 판정하지 않는다.")
    if len(kinds) > 1:
        text += " 데이터마다 대기 방식이 달라(대기 없는 이전 결과 포함) 버전 사이 비교는 참고용이다."
    return text


def priority_text(doc) -> str:
    priority = (doc.get("env") or {}).get("priority") or {}
    if priority.get("servers") == "above_normal" and priority.get("loadGenerator") != "above_normal":
        return ("프로세스 우선순위: 서버·추론 스텁은 '보통 이상'(Windows ABOVE_NORMAL)으로 띄웠지만 부하 생성기는 올리지 "
                f"못했다({esc(str(priority.get('loadGenerator')))}). 다른 프로그램과 CPU 를 다툰 클라이언트 지연이 섞일 수 있다.")
    if priority.get("servers") == "above_normal":
        return ("프로세스 우선순위: 서버·추론 스텁·부하 생성기를 '보통 이상'(Windows ABOVE_NORMAL)으로 띄웠다. 같은 PC 의 "
                "다른 프로그램이 서버를 고정한 CPU 를 차지해 서버가 몇 초씩 멈추는 일을 줄이려는 것이다(클라우드 VM 에는 "
                "다른 프로그램이 없다).")
    if priority:
        return "프로세스 우선순위: 바꾸지 않았다(이 OS 에서는 권한이 필요하다)."
    return "프로세스 우선순위: 기록 없음(이 결과를 만든 도구 버전은 우선순위를 바꾸지 않았다)."


def clock_text(doc) -> str:
    env = doc.get("env") or {}
    java = str(env.get("java") or "").strip().splitlines()
    facts = [fact for fact in (java[0].strip() if java else "", env.get("os") or "") if fact]
    where = f" (이 측정의 서버: {esc(' · '.join(facts))})" if facts else ""
    return f"서버 쪽 시간(events.jsonl)은 ms 단위 정수(버림)라 1 ms 보다 작은 차이는 보이지 않는다{where}."


def failed_suites(suites) -> list:
    """"<label> r<rep> (<scenarios with errors>)" for every suite with an error, sorted by label and repetition."""
    out = []
    for suite in sorted(suites, key=lambda s: (s["label"], s.get("rep") or 0, s.get("_dir", ""))):
        bad = [name for name, result in (suite.get("scenarios") or {}).items() if result.get("status") == "error"]
        if suite.get("errors"):
            bad.append("스위트 실행")
        if bad or suite.get("status") == "error":
            out.append(f"{suite['label']} r{suite.get('rep')}" + (f" ({', '.join(bad)})" if bad else ""))
    return out


BUSY_BANNER_PCT = 10.0  # background load (other programs) from which the cover warns


def background_load(suites):
    """(min, median, max, count) of settings.backgroundCpuPct over the suites that have it, or None."""
    values = [float(v) for v in ((s.get("settings") or {}).get("backgroundCpuPct") for s in suites)
              if isinstance(v, (int, float))]
    if not values:
        return None
    return min(values), stats.median(values), max(values), len(values)


def background_text(load) -> str:
    low, mid, high, count = load
    return (f"다른 프로그램의 부하: 스위트마다 시작 직전 2초 동안(이 도구의 서버는 꺼진 상태) 잰 PC 전체 CPU 사용률이 "
            f"최소 {low:.0f} %, 중앙 {mid:.0f} %, 최대 {high:.0f} % 였다(스위트 {count}개). 서버를 고정한 CPU 도 다른 "
            "프로그램이 쓸 수 있어 이만큼 잡음이 섞인다.")


def build_html(data, versions_file, notes_dir, report_command) -> str:
    """The whole report. Raises ValueError when suites of one version were measured with different settings."""
    order, baseline, meta = version_meta(data, versions_file)
    groups = by_label(data["suites"])
    check_pooling(groups)
    labels = [label for label in order if label in groups]
    colors = colors_for(order)
    if baseline not in groups:
        baseline = None
    runs = data["runs"]
    run_docs = [r["run"] or {} for r in runs]
    dates = sorted({(d.get("startedAt") or "")[:10] for d in run_docs if d.get("startedAt")}
                   or {s.get("startedAt", "")[:10] for s in data["suites"]})
    quick_labels = sorted({s["label"] for s in data["suites"] if s.get("quick")})
    mixed = len({condition_of(s) for s in data["suites"]}) > 1
    reps = {label: len(groups[label]) for label in labels}
    failed = failed_suites(data["suites"])
    reference = (groups.get(baseline) or data["suites"])[0]  # the counts shown are those of the baseline's data
    counts = ((reference.get("settings") or {}).get("counts")
              or next((d.get("counts") for d in run_docs if d.get("counts")), {}))

    out = ['<!doctype html><html lang="ko"><head><meta charset="utf-8">'
           '<meta name="viewport" content="width=device-width, initial-scale=1">'
           f"<title>Beside 백엔드 성능 보고서</title><style>{CSS}</style></head><body>"]

    # 1. cover and summary
    out.append('<section class="cover"><h1>Beside 백엔드 성능 보고서</h1>')
    rep_text = ", ".join(f"{l} {reps[l]}회" for l in labels)
    out.append(f'<p class="sub">측정일 {esc(", ".join(dates) or "–")} · 반복 {esc(rep_text)} · '
               f'기준 {esc(baseline or "없음")}</p>')
    banners = []
    if quick_labels and len(quick_labels) == len(groups):
        banners.append("<b>빠른 측정(--quick)</b>: 모든 횟수가 1/10 이고 N 이 작다. 동작 확인용이며 보고용 수치가 아니다.")
    elif quick_labels:
        banners.append("<b>빠른 측정(--quick)이 섞여 있다</b>: " + esc(", ".join(quick_labels)) + " 은(는) 횟수 1/10 로 잰 "
                       "동작 확인용 값이라 다른 버전과 비교하지 않는다.")
    if mixed:
        banners.append("<b>측정 조건이 다른 데이터</b>: --data 폴더마다 측정 횟수가 달라 버전 사이 비교·판정은 참고용이다 "
                       "(측정 방법의 '데이터별 조건').")
    if any(n < 2 for n in reps.values()):
        banners.append("<b>반복 1회</b>인 버전이 있다. 판정의 '반복별 p50 범위'가 한 점이라 판정은 참고용이다 "
                       "(보고용은 --reps 3 이상).")
    if failed:
        banners.append("<b>오류가 있는 측정</b>: " + esc("; ".join(failed)) + ". 실패한 요청은 표본에서 빠졌다. "
                       "해당 칸의 '오류 포함' 이유와 오류 수를 확인한다.")
    if baseline is None:
        banners.append("<b>기준 버전 없음</b>: versions 파일의 baseline 이 데이터에 없어 비교·판정을 하지 않았다.")
    load = background_load(data["suites"])
    if load and load[2] >= BUSY_BANNER_PCT:
        banners.append(f"<b>다른 프로그램이 돌던 PC</b>: 스위트 직전 CPU 사용률 중앙 {load[1]:.0f} %, 최대 {load[2]:.0f} % "
                       "(측정 방법의 '한계'). 버전 순서를 반복마다 돌려 한 버전에만 몰리지 않게 했고, 판정 규칙이 반복 사이 "
                       "흔들림을 걸러 낸다.")
    for text in banners:
        out.append(f'<div class="banner">⚠ {text}</div>')
    out.append('<h2>버전</h2><table><thead><tr><th>라벨</th><th>제목</th><th>커밋</th><th>설명</th></tr></thead><tbody>')
    for label in labels:
        info = meta.get(label, {})
        shas = ", ".join(sha[:9] for sha in info.get("shas", [])) or "–"
        note = " (기준)" if label == baseline else ""
        out.append(f'<tr><th><span class="sw" style="background:{colors[label]}"></span>{esc(label)}{note}</th>'
                   f'<td>{esc(info.get("title", ""))}</td><td><code>{esc(shas)}</code>'
                   f'<div class="why">{esc(info.get("ref", ""))}</div></td><td>{esc(info.get("description", ""))}</td></tr>')
    out.append("</tbody></table>")
    targets = scale_targets(groups)
    n_max = str(targets[-1]) if targets else "?"
    n_name = measured_n(groups, labels, targets[-1]) if targets else "N=?"
    key_rows = [(s, (r[0], r[1].replace("{N}", n_max)), t.replace("{NAME}", n_name)) for s, r, t in KEY_ROWS
                if targets or s != "scale"]
    out.append("<h2>핵심 지표</h2>")
    out.append('<p class="small muted">칸: p50 / p95 (ms) 또는 값, 아래 줄은 기준 대비 p50 변화율과 판정. '
               "판정은 |Δ| ≥ 10% 이고 반복별 p50 범위가 기준과 겹치지 않을 때만 개선/악화, 나머지는 차이 없음(잡음). "
               "처리량은 클수록 개선. 기준이 0 이면 비교 불가.</p>")
    out.append(metrics_table(key_rows, labels, groups, colors, baseline))
    pairs, dropped_pairs = version_pairs(versions_file, labels)
    if pairs:
        out.append("<h2>쌍 비교</h2>")
        out.append('<p class="small muted">versions 파일의 <code>pairs</code>: 열마다 앞 버전을 기준으로 뒤 버전을 비교한다. '
                   "칸: 앞 → 뒤의 p50(값 지표는 반복별 값의 중앙값), 아래 줄은 변화율과 판정(위 표와 같은 규칙)."
                   + (f" 측정하지 않은 버전이 든 쌍은 뺐다: {esc(', '.join(dropped_pairs))}." if dropped_pairs else "")
                   + "</p>")
        out.append(pairs_table(key_rows, pairs, groups))
    out.append("</section>")

    # 2. method
    out.append('<section class="major"><h1>측정 방법</h1><h2>환경</h2>')
    out.append(env_table(runs))
    env = next((d.get("env") for d in run_docs if d.get("env")), {}) or {}
    cpus = env.get("pinnedCpus")
    out.append("<h2>서버 조건</h2><ul>")
    out.append(f"<li>JVM: <code>{esc(' '.join(env.get('jvmArgs') or []))}</code>, 버전마다 <code>bootJar</code> 로 만든 jar 를 "
               "<code>java -jar</code> 로 실행.</li>")
    out.append(f"<li>{per_run_text(runs, cpu_text) or ('CPU 고정: ' + esc(str(cpus or '기록 없음')))}</li>")
    out.append("<li>서버 A·B: 프로파일 <code>mock</code>, 지연 0 (<code>mock.worker.pending-ms=0</code>, "
               "<code>processing-ms=0</code>), 실패 트리거 끔, 결과 GLB 는 합성 GLB(1,500,000 바이트). "
               "서버 B 는 A 의 저장소로 다시 띄우고 <code>pending-ms=600000</code> 으로 작업을 PENDING 에 둔다.</li>")
    out.append("<li>서버 C: 프로파일 <code>real</code> 또는 <code>real,prod</code>, 추론 서비스 스텁(<code>generation/"
               "inference_service/app.py</code>, 패스스루: 모델 없이 같은 GLB 를 즉시 돌려줌), 전송 path / upload, 토큰 없음.</li>")
    out.append(f"<li>{per_run_text(runs, storage_text) or storage_text({})}</li>")
    out.append("<li>클라이언트: Python <code>http.client</code>, 127.0.0.1 루프백, 스레드마다 keep-alive 연결. "
               "시간은 요청 쓰기 직전부터 응답 본문을 다 읽을 때까지(<code>perf_counter_ns</code>), 연결 수립은 제외 — "
               "E2E 흐름(<code>flowMs</code>·<code>completeMs</code>)도 흐름 안에서 다시 연결한 시간(Tomcat 은 100번째 응답에 "
               "연결을 닫는다)을 뺀다. POST 마다 새 <code>Idempotency-Key</code>.</li>")
    out.append("<li>반복마다 버전 순서를 돌린다(1회차 v0,v1,v2 → 2회차 v1,v2,v0 → …). 워밍업 요청은 기록하지 않는다.</li>")
    out.append(f"<li>{settle_text(data['suites'])}</li>")
    out.append(f"<li>{per_run_text(runs, priority_text) or priority_text({})}</li></ul>")
    out.append("<h2>시나리오</h2><table><thead><tr><th>이름</th><th>무엇을</th><th>횟수</th></tr></thead><tbody>")
    for key, title, what in SCENARIOS:
        out.append(f"<tr><th>{esc(title)}<div class=\"unit\"><code>{esc(key)}</code></div></th><td>{esc(what)}</td>"
                   f"<td>{esc(counts_text(counts, key))}</td></tr>")
    out.append("</tbody></table>")
    if mixed:
        out.append(conditions_list(data))
    out.append("<h2>통계와 판정</h2><ul>"
               "<li>p50·p95 등 백분위수는 반복을 합친 표본에서 선형 보간(numpy 'linear', Hyndman–Fan type 7)으로 구한다. "
               "원시 표본은 0.01 ms 단위로 저장한다.</li>"
               "<li>Δ% = (후보 p50 − 기준 p50) ÷ 기준 p50 × 100. 값 지표(기동 시간, RSS, 처리량)는 반복별 값의 중앙값끼리 비교한다.</li>"
               "<li>판정: |Δ%| ≥ 10 이고 반복별 p50(값 지표는 반복별 값)의 [최소, 최대] 범위가 기준의 범위와 겹치지 않을 때만 "
               "<b>개선</b> 또는 <b>악화</b>. 그 밖에는 <b>차이 없음</b>(잡음으로 본다). 시간은 작을수록, 처리량은 클수록 개선. "
               "기준이 0 이고 후보가 0 이 아니면 변화율이 없으므로 <b>비교 불가</b>.</li>"
               "<li>오류 수(<code>bench.errors</code>)는 측정값이 아니라 사건 수라 반복을 <b>합한</b> 값을 보이고, 0 이 아니면 빨간색이며 "
               "판정하지 않는다. 실패한 요청이 하나라도 있으면 그 시나리오는 '오류 포함'으로 표시되고, 표본(p50·p95)은 성공한 "
               "요청만으로 계산된다.</li></ul>")
    out.append("<h2>이름 규칙</h2><ul>"
               "<li><code>bench.*</code> 값과 클라이언트 표본 이름(<code>postMs</code>, <code>flowMs</code>, <code>pollMs</code>, "
               "<code>assetMs</code> 등)은 이 벤치마크 전용이며 docs/METRICS.md 에 등록된 이름이 아니다. 앱의 "
               "<code>uploadMs</code>(METRICS §3)와 섞지 않는다.</li>"
               "<li>서버 값 <code>uploadMs</code>, <code>queuedMs</code>, <code>processingMs</code>, <code>totalMs</code> "
               "(표의 '· events' 행과 real 시나리오의 <code>server*</code> 표본)은 서버 events.jsonl 의 METRICS §1 정의 그대로다. "
               "real 시나리오는 스텁 패스스루라 <code>passthrough=true</code> 이고 모델 측정이 아니다.</li></ul>")
    out.append("<h2>한계</h2><ul>"
               "<li>로컬 루프백(127.0.0.1)에서 잰 값이다. 클라우드 VM, Tailscale, 실제 GPU 추론 경로가 아니다.</li>"
               "<li>real 시나리오는 모델 없는 스텁이라 Spring 쪽 오버헤드(전송·저장·대기열)만 보인다.</li>"
               "<li>부하 생성기는 Python(GIL, http.client)이라 동시 시나리오에는 클라이언트 자신의 지연이 섞일 수 있다.</li>"
               f"<li>{per_run_text(runs, shared_pc_text) or shared_pc_text({})}</li>"
               + (f"<li>{esc(background_text(load))}</li>" if load else "")
               + (f"<li>{fresh}</li>" if (fresh := per_run_text(runs, fresh_open_text)) else "") +
               f"<li>{per_run_text(runs, clock_text) or clock_text({})}</li></ul>")
    out.append("</section>")

    # 3. results per scenario
    out.append('<section class="major"><h1>시나리오별 결과</h1>')
    out.append('<p class="small muted">표: p50 / p95 와 표본 수 n(값 지표는 반복의 중앙값), 아래 줄은 기준 대비 Δ% 와 판정. '
               "그림: 같은 값을 막대로.</p>")
    for index, (key, title, what) in enumerate(SCENARIOS, 1):
        # heading, description and (short) table stay on one page; the chart may move to the next one. The scale
        # table is too long for that, so its heading and description only stay with the table's start ("head")
        out.append(f'<div class="scenario"><div class="{"keep head" if key == "scale" else "keep"}">'
                   f'<h3>{index}. {esc(title)} <code>{esc(key)}</code></h3>'
                   f'<p class="what">{esc(what)} {esc(counts_text(counts, key))}.</p>')
        states = {label: state_of(groups.get(label, []), key) for label in labels}
        if all(state in ("skipped", "missing") for state, _ in states.values()):
            reasons = sorted({short_reason(reason) for _state, reason in states.values()})
            out.append(f'<p class="muted">측정 안 함 — {esc("; ".join(reasons))}</p></div></div>')
            continue
        for label, (state, reason) in states.items():
            if state == "skipped":
                out.append(f'<p class="small muted">{esc(label)}: 측정 안 함 — {esc(short_reason(reason))}</p>')
            elif state == "error":
                out.append(f'<p class="small err">{esc(label)}: 오류 — {esc(short_reason(reason))}</p>')
        if key == "scale":
            out.append("</div>" + scale_section(labels, groups, colors, meta, baseline))
        else:
            rows = [(key, row, row_meta(row)[0]) for row in ROWS.get(key, [])]
            out.append(metrics_table(rows, labels, groups, colors, baseline) + "</div>")
            out.append(scenario_chart(key, ROWS.get(key, []), labels, groups, colors, meta))
        notes = scenario_notes(labels, groups, key)
        if notes:
            out.append('<div class="notes">메모<ul>' + "".join(f"<li>{esc(note)}</li>" for note in notes)
                       + "</ul></div>")
        out.append("</div>")
    out.append("</section>")

    # 4. narrative notes
    if notes_dir and Path(notes_dir).is_dir():
        for path in sorted(Path(notes_dir).glob("*.md")):
            out.append(f'<section class="major note">{mdlite.render(path.read_text(encoding="utf-8"))}</section>')

    # 5. appendix
    out.append('<section class="major"><h1>부록</h1><h2>데이터 파일</h2><ul>')
    for run in runs:
        folder = run["dir"]
        try:
            shown = folder.resolve().relative_to(REPO).as_posix()
        except ValueError:
            shown = str(folder)
        out.append(f"<li><code>{esc(shown)}</code>: run.json, {esc(', '.join(run['files']))}</li>")
    out.append("</ul><h2>명령</h2><ul>")
    for run_doc in run_docs:
        if run_doc.get("command"):
            out.append(f"<li>측정: <code>{esc(run_doc['command'])}</code> ({esc(run_doc.get('startedAt', ''))} ~ "
                       f"{esc(run_doc.get('finishedAt') or '')}, 상태 {esc(run_doc.get('status', ''))})</li>")
    out.append(f"<li>보고서: <code>{esc(report_command)}</code></li></ul>")
    out.append("<h2>도구와 버전 커밋</h2><ul>")
    for run_doc in run_docs:
        tool = run_doc.get("tool") or {}
        if tool.get("commit"):
            out.append(f"<li>측정 도구 커밋 <code>{esc(tool['commit'])}</code>"
                       + (" (scripts/perf 에 커밋 안 된 변경 있음)" if tool.get("dirty") else "") + "</li>")
    tool_line = report_tool_line()
    if tool_line:
        out.append(tool_line)
    for label in labels:
        for sha in meta.get(label, {}).get("shas", []):
            out.append(f"<li>{esc(label)}: <code>{esc(sha)}</code> ({esc(meta[label].get('ref', ''))})</li>")
    out.append("</ul>")
    out.append(f'<p class="small muted">생성 {esc(datetime.now().astimezone().isoformat(timespec="seconds"))}</p>')
    out.append("</section></body></html>")
    return "\n".join(out)


def report_tool_line() -> str:
    """The appendix line naming the commit this report was generated with, flagged when scripts/perf has uncommitted
    changes (the same rule as the measurement tool's record in run.json)."""
    commit = _git("rev-parse", "HEAD")
    if not commit:
        return ""
    dirty = bool(_git("status", "--porcelain", "--", "scripts/perf"))
    return (f"<li>보고서 생성 도구 커밋 <code>{esc(commit)}</code>"
            + (" (scripts/perf 에 커밋 안 된 변경 있음)" if dirty else "") + "</li>")


def _git(*args) -> str:
    try:
        done = subprocess.run(["git", *args], cwd=str(REPO), capture_output=True, timeout=30)
        return done.stdout.decode("utf-8", "replace").strip() if done.returncode == 0 else ""
    except (OSError, subprocess.SubprocessError):
        return ""


# ----------------------------------------------------------------------------------------------- PDF


def find_browser(explicit: str = None):
    if explicit:
        return explicit if Path(explicit).is_file() or shutil.which(explicit) else None
    candidates = []
    if os.name == "nt":
        local = os.environ.get("LOCALAPPDATA", "")
        candidates += [r"C:\Program Files\Google\Chrome\Application\chrome.exe",
                       r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
                       os.path.join(local, r"Google\Chrome\Application\chrome.exe") if local else "",
                       r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                       r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"]
    elif sys.platform == "darwin":
        candidates += ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                       "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                       "/Applications/Chromium.app/Contents/MacOS/Chromium"]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    for name in ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge"):
        found = shutil.which(name)
        if found:
            return found
    return None


def _ascii_temp_dir() -> Path:
    base = Path(tempfile.gettempdir())
    if not str(base).isascii():
        for alt in (Path(os.environ.get("SystemDrive", "C:") + "\\") / "Users" / "Public", Path("/tmp")):
            if alt.is_dir() and str(alt).isascii() and os.access(alt, os.W_OK):
                base = alt
                break
    return Path(tempfile.mkdtemp(prefix="beside-report-", dir=str(base)))


def print_pdf(browser: str, html_text: str, out_pdf: Path, timeout_s: float = 120.0) -> str:
    """Print the HTML to out_pdf with a headless Chromium browser; returns "" or what went wrong."""
    import procs  # starts the browser like a server: job object, tree kill on timeout
    work = _ascii_temp_dir()
    try:
        page = work / "report.html"
        page.write_text(html_text, encoding="utf-8")
        pdf = work / "report.pdf"
        cmd = [browser, "--headless=new", "--disable-gpu", "--no-pdf-header-footer", "--print-to-pdf-no-header",
               "--no-first-run", "--no-default-browser-check", "--disable-extensions",
               f"--user-data-dir={work / 'profile'}", f"--print-to-pdf={pdf}", page.as_uri()]
        env, _removed = procs.clean_env()
        proc = procs.start("browser", cmd, cwd=work, log_path=work / "browser.log", env=env)
        try:
            proc.popen.wait(timeout=timeout_s)
        except subprocess.TimeoutExpired:
            procs.kill_tree(proc)
            return f"the browser did not finish within {timeout_s:.0f} s"
        finally:
            procs.stop(proc, timeout_s=5)
        if not pdf.is_file() or pdf.stat().st_size == 0:
            return "the browser wrote no PDF:\n" + proc.log_tail(10)
        out_pdf.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(pdf, out_pdf)
        return ""
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace") if stream.isatty() else stream.reconfigure(encoding="utf-8",
                                                                                              errors="replace")
        except (AttributeError, ValueError, OSError):
            pass
    argv = list(sys.argv[1:] if argv is None else argv)
    parser = argparse.ArgumentParser(description="Beside backend performance report (HTML + PDF).")
    parser.add_argument("--data", action="append", required=True, help="a results folder of run.py (repeatable)")
    parser.add_argument("--versions-file", default=str(REPO / "docs" / "perf" / "versions.json"),
                        help="titles, order and baseline (default docs/perf/versions.json; run.json is the fallback)")
    parser.add_argument("--notes", default=str(REPO / "docs" / "perf" / "notes"),
                        help="folder of *.md narrative sections (sorted by file name)")
    parser.add_argument("--out", default=str(REPO / "docs" / "perf" / "backend-perf-report.pdf"))
    parser.add_argument("--html-out", default=str(REPO / "scripts" / "perf" / "out" / "report.html"))
    parser.add_argument("--browser", help="Chrome or Edge executable (default: common install paths, then PATH)")
    args = parser.parse_args(argv)
    data = load_data(args.data)
    if not Path(args.versions_file).is_file():
        print(f"versions file {args.versions_file} not found: titles and baseline come from run.json")
    command = "python scripts/perf/report.py " + " ".join(
        subprocess.list2cmdline([a]) if os.name == "nt" else a for a in argv)
    try:
        page = build_html(data, args.versions_file, args.notes, command)
    except ValueError as error:
        print(f"report not built: {error}")
        return 2
    html_out = Path(args.html_out)
    html_out.parent.mkdir(parents=True, exist_ok=True)
    html_out.write_text(page, encoding="utf-8")
    print(f"HTML: {html_out}")
    browser = find_browser(args.browser)
    if not browser:
        if args.browser:
            print(f"--browser {args.browser} not found: wrote the HTML only")
            return 1
        print("no Chrome/Edge found (pass --browser): wrote the HTML only")
        return 0
    problem = print_pdf(browser, page, Path(args.out))
    if problem:
        print(f"PDF failed with {browser}: {problem}")
        return 1
    print(f"PDF: {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
