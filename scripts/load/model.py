"""Beside traffic model: tiers.json [가정] -> derived load table [파생] (standard library only).

    python scripts/load/model.py            # Markdown tables (docs/perf/load/TRAFFIC_MODEL.md §3, §5)
    python scripts/load/model.py --json     # the same numbers as JSON
    python scripts/load/model.py --tiers other.json --t 30
    python scripts/load/model.py --queue-rate-per-h 280 --queue-service-s 11.8 --queue-slots 1 [--json]
        # G series: rho, M/M/c Wq and (c = 1) M/D/1 Wq from the measured arrival rate and mean slot time of a
        # gpu_queue.js run (its summary's meta.recompute is this command)

Assumptions are TRAFFIC_MODEL.md §2 (tiers.json), formulas §4:
  DAU = MAU x DAU/MAU; peak concurrent users = DAU x peak-hour share x session minutes / 60;
  job arrival rate lambda = DAU x jobs per DAU x peak-hour share / 3600 per second; spike = x spikeFactor.
  Jobs in the system L = lambda x (Wq + T); the app polls every 2 s, so polling RPS = L / 2; it gives up after
  600 s, so polling is at most 300 x lambda when the GPU is saturated.
  Total RPS = healthz (one per session start) + POST (lambda) + asset (lambda x (1 - f)) + retry (lambda x f) + polls.
  The second wait of a retried job (lambda x f more polls and GPU work) is left out: under 5 % at f = 0.05.
  GPUs needed for utilisation rho <= target: c = ceil(lambda T / rho). Wq is M/M/c (Erlang C); generation time is
  nearly constant, so the real wait is shorter (M/D/c) and M/M/c is an upper bound.
  Queue cap Q_max = floor(c x (600 - T) / T): a job further back would time out in the app anyway.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
EPS = 1e-9


def load_tiers(path) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def tier_params(doc: dict, name: str) -> dict:
    """The defaults overlaid with one tier's own values."""
    params = dict(doc["defaults"])
    params.update(doc["tiers"][name])
    return params


def erlang_c(c: int, a: float) -> float:
    """Probability that an arrival waits in M/M/c with offered load a = lambda x T Erlangs (1.0 when a >= c).

    Uses the Erlang B recurrence B(k) = a B(k-1) / (k + a B(k-1)), which stays finite for hundreds of servers.
    """
    if c < 1:
        raise ValueError("c must be at least 1")
    if a <= 0:
        return 0.0
    if a >= c:
        return 1.0
    b = 1.0
    for k in range(1, c + 1):
        b = a * b / (k + a * b)
    return c * b / (c - a * (1 - b))


def wq_mmc(c: int, rho: float, t_sec: float):
    """Mean wait in queue (s) for M/M/c at utilisation rho = lambda T / c; None when rho >= 1 (no steady state)."""
    if rho >= 1:
        return None
    a = rho * c
    return erlang_c(c, a) * t_sec / (c - a)


def wq_mdc_approx(c: int, rho: float, t_sec: float):
    """M/D/c wait: exactly half of M/M/1 for c = 1 (Pollaczek-Khinchine); the usual half-of-M/M/c estimate above."""
    value = wq_mmc(c, rho, t_sec)
    return None if value is None else value / 2


def queue_prediction(rate_per_h: float, service_s: float, slots: int) -> dict:
    """G series prediction recomputed from a run's measured arrival rate and mean slot time (TRAFFIC_MODEL.md §4, §9).

    rate_per_h  the arrival rate the k6 run really had: jobs it created per hour (gpu_queue.js meta.measuredJobsPerHour,
                load_postMs count / arrival window x 3600), not the requested meta.prediction.jobsPerHour, which a
                finite Poisson run misses by about the square root of its job count
    service_s   the measured mean slot time: the mean of the server's processingMs / 1000 (upload to the inference
                service, stub sleep, GLB download), not the stub delay T_SEC. It is a little short of the time a job
                holds the slot: prod's /healthz call before it and the job_runs write and cleanup DELETE after it are
                not in processingMs (about two stub round trips plus the writes per job)
    slots       c, the backend's inference.max-concurrency (= the stub's BESIDE_PASSTHROUGH_SLOTS)
    M/M/c and M/D/1 assume Poisson arrivals, which gpu_queue.js produces by thinning (ARRIVAL_THIN).
    """
    if not rate_per_h > 0:
        raise ValueError("rate per hour must be positive")
    if not service_s > 0:
        raise ValueError("service time must be positive")
    if slots < 1 or int(slots) != slots:
        raise ValueError("slots must be a whole number >= 1")
    slots = int(slots)
    lam = rate_per_h / 3600
    rho = lam * service_s / slots
    return {
        "schema": "beside-queue-prediction/1",
        "status": "[파생] 측정한 도착률과 평균 슬롯 시간으로 다시 계산한 예측 (푸아송 도착 가정)",
        "lambdaPerHour": rate_per_h,
        "lambdaPerS": lam,
        "serviceSec": service_s,
        "slots": slots,
        "rho": rho,
        "wqMMcSec": wq_mmc(slots, rho, service_s),
        "wqMD1Sec": wq_mdc_approx(1, rho, service_s) if slots == 1 else None,
        "queueGrowthPerMin": max(0.0, (lam - slots / service_s) * 60),
    }


def queue_text(p: dict) -> str:
    lines = ["# G 시리즈 큐 예측 [파생] — 측정한 도착률과 평균 슬롯 시간으로 다시 계산", "",
             f"λ = {p['lambdaPerHour']:g} 건/h ({p['lambdaPerS']:.4f}/s), 평균 슬롯 시간 S = {p['serviceSec']:g} s, "
             f"슬롯 c = {p['slots']}",
             f"ρ = λ·S/c = {p['rho']:.3f}"]
    if p["wqMMcSec"] is None:
        lines.append(f"ρ ≥ 1: 정상 상태가 없다. 큐가 분당 {p['queueGrowthPerMin']:.2f}건씩 늘어난다")
    else:
        lines.append(f"M/M/c Wq = {p['wqMMcSec']:.1f} s")
        if p["wqMD1Sec"] is not None:
            lines.append(f"M/D/1 Wq = {p['wqMD1Sec']:.1f} s")
    lines.append("가정: 푸아송 도착(gpu_queue.js 의 솎아내기, ARRIVAL_THIN). M/D/1 은 c = 1 일 때만 적는다.")
    return "\n".join(lines) + "\n"


def gpus_needed(lam: float, t_sec: float, rho: float) -> int:
    return max(1, math.ceil(lam * t_sec / rho - EPS))


def q_max(c: int, t_sec: float, max_wait_s: float) -> int:
    if t_sec <= 0:
        raise ValueError("T must be positive")
    return max(0, math.floor(c * (max_wait_s - t_sec) / t_sec + EPS))


def _rps_parts(sessions_per_s, lam, f, polls_per_s, healthz_per_session):
    return {
        "healthz": sessions_per_s * healthz_per_session,
        "create": lam,
        "asset": lam * (1 - f),
        "retry": lam * f,
        "poll": polls_per_s,
    }


def _r(x, nd=6):
    return None if x is None else round(x, nd)


def tier_table(p: dict, ts, rho: float) -> dict:
    dau = p["mau"] * p["dauPerMau"]
    session_s = p["sessionMin"] * 60
    conc = dau * p["peakHourShare"] * p["sessionMin"] / 60
    jobs_day = dau * p["jobsPerDau"]
    lam_h = jobs_day * p["peakHourShare"]
    lam = lam_h / 3600
    f = p["failureRatio"]
    poll = p["pollIntervalS"]
    max_wait = p["appMaxWaitS"]
    sessions = conc / session_s
    spike = p["spikeFactor"]
    lam_s, conc_s, sessions_s = lam * spike, conc * spike, sessions * spike
    photo_mb = p["photoMB"] * p["photoCount"]
    hz = p["healthzPerSession"]

    def per_t(t):
        c = gpus_needed(lam, t, rho)
        wq = wq_mmc(c, lam * t / c, t)
        polls = lam * (wq + t) / poll
        parts = _rps_parts(sessions, lam, f, polls, hz)
        c_s = gpus_needed(lam_s, t, rho)
        wq_s = wq_mmc(c_s, lam_s * t / c_s, t)
        parts_s = _rps_parts(sessions_s, lam_s, f, lam_s * (wq_s + t) / poll, hz)
        return {
            "tSec": t, "gpusNeeded": c, "rho": _r(lam * t / c), "wqSec": _r(wq), "jobsInSystem": _r(lam * (wq + t)),
            "rpsNormal": _r(sum(parts.values())), "rpsParts": {k: _r(v) for k, v in parts.items()},
            "spikeGpusNeeded": c_s, "spikeRpsNormal": _r(sum(parts_s.values())),
        }

    sat_parts = _rps_parts(sessions, lam, f, lam * max_wait / poll, hz)
    sat_parts_s = _rps_parts(sessions_s, lam_s, f, lam_s * max_wait / poll, hz)
    disk_day = jobs_day * (photo_mb + p["glbMB"]) / 1000
    return {
        "label": p.get("label", ""),
        "mau": p["mau"], "dau": _r(dau), "peakConcurrent": _r(conc), "sessionsPerS": _r(sessions),
        "jobsPerDay": _r(jobs_day), "lambdaPerHour": _r(lam_h), "lambdaPerS": _r(lam),
        "usersPerJobPerS": _r(conc / lam) if lam else None,
        "rpsSaturated": _r(sum(sat_parts.values())),
        "assetMbps": _r(lam * (1 - f) * p["glbMB"] * 8), "assetMbpsWorst": _r(lam * (1 - f) * p["glbWorstMB"] * 8),
        "uploadMbps": _r(lam * photo_mb * 8),
        "diskGBPerDay": _r(disk_day),
        "singleGpuMaxTSec": _r(rho / lam) if lam else None,
        "spike": {
            "factor": spike, "minutes": p["spikeMin"], "lambdaPerS": _r(lam_s), "peakConcurrent": _r(conc_s),
            "rpsSaturated": _r(sum(sat_parts_s.values())), "assetMbps": _r(lam_s * (1 - f) * p["glbMB"] * 8),
        },
        "perT": [per_t(t) for t in ts],
        "note": p.get("note", ""),
    }


def compute(doc: dict, ts=None) -> dict:
    ts = list(ts or doc["illustrativeT"])
    rho = doc["targetRho"]
    max_wait = doc["defaults"]["appMaxWaitS"]
    out = {
        "schema": "beside-traffic-table/1",
        "status": "[파생] tiers.json 의 [가정] 으로 계산",
        "targetRho": rho,
        "referenceT": doc.get("referenceT", 60),
        "illustrativeT": ts,
        "assumptions": doc["defaults"],
        "tiers": {},
    }
    for name in doc["tiers"]:
        p = tier_params(doc, name)
        table = tier_table(p, ts, rho)
        budget = doc.get("diskBudgetGB")
        table["daysToFillDiskBudget"] = _r(budget / table["diskGBPerDay"]) if budget and table["diskGBPerDay"] else None
        out["tiers"][name] = table
    out["diskBudgetGB"] = doc.get("diskBudgetGB")
    out["singleGpu"] = [{"tSec": t, "jobsPerHourMax": _r(3600 / t), "jobsPerHourAtRho": _r(3600 * rho / t)} for t in ts]
    out["qMax"] = [{"c": c, "tSec": t, "qMax": q_max(c, t, max_wait)} for c in (1, 2, 4) for t in ts]
    g1 = doc.get("g1", {"c": 1, "tSec": 10, "rhos": [0.5, 0.7, 0.9, 1.2]})
    rows = []
    for r in g1["rhos"]:
        c, t = g1["c"], g1["tSec"]
        lam = r * c / t
        rows.append({"rho": r, "c": c, "tSec": t, "lambdaPerS": _r(lam), "jobsPerHour": _r(lam * 3600),
                     "wqMM1Sec": _r(wq_mmc(c, r, t)), "wqMD1Sec": _r(wq_mdc_approx(c, r, t)),
                     "queueGrowthPerMin": _r(max(0.0, (lam - c / t) * 60))})
    out["g1"] = rows
    return out


def _fmt(x, nd=1):
    if x is None:
        return "—"
    if float(x).is_integer():
        return f"{int(x):,}"
    return f"{x:,.{nd}f}"


def markdown(table: dict, ref_t: int) -> str:
    names = list(table["tiers"])
    tiers = table["tiers"]
    lines = ["# Beside 목표 트래픽 표 [파생]", "",
             f"tiers.json 의 [가정] 으로 계산했다. ρ 목표 {table['targetRho']}, 기준 T = {ref_t} s. "
             "Wq 는 M/M/c(Erlang C) 상한이다.", ""]
    head = "| | " + " | ".join(f"{n} {tiers[n]['label']}" for n in names) + " | " + \
        " | ".join(f"{n} 스파이크" for n in names[-1:]) + " |"
    lines += [head, "|---" * (len(names) + 2) + "|"]

    def per_t(n):
        return next((r for r in tiers[n]["perT"] if r["tSec"] == ref_t), None)

    def row(label, values):
        lines.append(f"| {label} | " + " | ".join(values) + " |")

    last = names[-1]
    row("MAU → DAU", [f"{_fmt(tiers[n]['mau'])} → {_fmt(tiers[n]['dau'])}" for n in names] + ["—"])
    row("피크 동시 사용자", [_fmt(tiers[n]["peakConcurrent"]) for n in names] + [_fmt(tiers[last]["spike"]["peakConcurrent"])])
    row("생성 요청 λ (건/h, /s)", [f"{_fmt(tiers[n]['lambdaPerHour'])} ({tiers[n]['lambdaPerS']:.3f}/s)" for n in names]
        + [f"{tiers[last]['spike']['lambdaPerS']:.1f}/s"])
    row(f"전체 RPS — GPU 충분(T={ref_t}) / 포화",
        [f"{_fmt(per_t(n)['rpsNormal'])} / {_fmt(tiers[n]['rpsSaturated'])}" if per_t(n) else "—" for n in names]
        + [f"{_fmt(per_t(last)['spikeRpsNormal'])} / {_fmt(tiers[last]['spike']['rpsSaturated'])}" if per_t(last) else "—"])
    row("에셋 송신 Mbps (보통 / 최악 GLB)", [f"{tiers[n]['assetMbps']:.2f} / {tiers[n]['assetMbpsWorst']:.1f}" for n in names]
        + [f"{tiers[last]['spike']['assetMbps']:.1f}"])
    row("업로드 수신 Mbps", [f"{tiers[n]['uploadMbps']:.2f}" for n in names] + ["—"])
    row("디스크 증가 GB/일", [f"{tiers[n]['diskGBPerDay']:.2f}" for n in names] + ["—"])
    row(f"{table['diskBudgetGB']} GB 가 차는 날수", [_fmt(tiers[n]["daysToFillDiskBudget"]) for n in names] + ["—"])
    row("1대로 처리 가능한 최대 T (s)", [_fmt(tiers[n]["singleGpuMaxTSec"]) for n in names] + ["—"])
    lines += ["", "## GPU 대수 (ρ ≤ 목표)", "",
              "| T [가정] | 1대 최대 건/h | 1대 (ρ≤목표) 건/h | " + " | ".join(f"{n} 필요 GPU" for n in names) + " |",
              "|---" * (3 + len(names)) + "|"]
    for cap in table["singleGpu"]:
        t = cap["tSec"]
        gpus = [str(next(r for r in tiers[n]["perT"] if r["tSec"] == t)["gpusNeeded"]) for n in names]
        lines.append(f"| {t} s | {_fmt(cap['jobsPerHourMax'])} | {_fmt(cap['jobsPerHourAtRho'])} | " + " | ".join(gpus) + " |")
    lines += ["", "## RPS 구성 (기준 T)", "", "| 티어 | healthz | POST | asset | retry | poll | 합계 | Wq (s) |",
              "|---|---|---|---|---|---|---|---|"]
    for n in names:
        r = per_t(n)
        if r:
            p = r["rpsParts"]
            lines.append(f"| {n} | {p['healthz']:.3f} | {p['create']:.3f} | {p['asset']:.3f} | {p['retry']:.3f} | "
                         f"{p['poll']:.3f} | {r['rpsNormal']:.2f} | {r['wqSec']:.2f} |")
    lines += ["", "## 큐 상한 Q_max = ⌊c·(600−T)/T⌋ [제안]", "", "| c | " + " | ".join(f"T={t}s" for t in table["illustrativeT"]) + " |",
              "|---" * (1 + len(table["illustrativeT"])) + "|"]
    for c in sorted({r["c"] for r in table["qMax"]}):
        vals = [str(r["qMax"]) for r in table["qMax"] if r["c"] == c]
        lines.append(f"| {c} | " + " | ".join(vals) + " |")
    lines += ["", "## G1 예측 (c, T 고정, 대기 Wq)", "", "| ρ | λ (건/h) | M/M/1 Wq (s) | M/D/1 Wq (s) | 큐 증가 (건/분) |",
              "|---|---|---|---|---|"]
    for r in table["g1"]:
        lines.append(f"| {r['rho']} | {_fmt(r['jobsPerHour'])} | {_fmt(r['wqMM1Sec'])} | {_fmt(r['wqMD1Sec'])} | "
                     f"{_fmt(r['queueGrowthPerMin'], 2)} |")
    notes = [f"- {n}: {tiers[n]['note']}" for n in names if tiers[n].get("note")]
    if notes:
        lines += ["", "## 티어별 가정 메모", ""] + notes
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--tiers", default=str(HERE / "tiers.json"))
    ap.add_argument("--json", action="store_true", help="print JSON instead of Markdown")
    ap.add_argument("--t", type=float, action="append", help="generation time T in seconds (repeatable)")
    ap.add_argument("--queue-rate-per-h", type=float,
                    help="G series: jobs the run created per hour (gpu_queue.js meta.measuredJobsPerHour)")
    ap.add_argument("--queue-service-s", type=float,
                    help="G series: measured mean slot time in seconds (mean server processingMs / 1000)")
    ap.add_argument("--queue-slots", type=int, default=1, help="G series: slots c (inference.max-concurrency), default 1")
    args = ap.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if args.queue_rate_per_h is not None or args.queue_service_s is not None:
        if args.queue_rate_per_h is None or args.queue_service_s is None:
            ap.error("--queue-rate-per-h and --queue-service-s go together (--queue-slots defaults to 1)")
        try:
            pred = queue_prediction(args.queue_rate_per_h, args.queue_service_s, args.queue_slots)
        except ValueError as exc:
            ap.error(str(exc))
        if args.json:
            json.dump(pred, sys.stdout, ensure_ascii=False, indent=2)
            sys.stdout.write("\n")
        else:
            sys.stdout.write(queue_text(pred))
        return 0
    doc = load_tiers(args.tiers)
    ts = [int(t) if float(t).is_integer() else t for t in args.t] if args.t else None
    table = compute(doc, ts)
    ref_t = table["referenceT"] if table["referenceT"] in table["illustrativeT"] else table["illustrativeT"][0]
    if args.json:
        json.dump(table, sys.stdout, ensure_ascii=False, indent=2)
        sys.stdout.write("\n")
    else:
        sys.stdout.write(markdown(table, ref_t))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
