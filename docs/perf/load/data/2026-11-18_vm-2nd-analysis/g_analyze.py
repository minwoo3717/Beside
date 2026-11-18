"""Round-2 (real GPU) per-run server analysis from <run>/vm/events.jsonl and <run>/k6-summary.json.
Usage: python g_analyze.py <run dir> [--h0 MS] [--s0 S] [--slots N] [--json OUT]
Jobs belong to the run when created (finishedAt - totalMs) inside [meta.startedAt, meta.endedAt].
h  = queuedMs - preprocessMs  (the healthz call and any wait for a slot; h0 = its median in G0)
Wq = h - h0                   (wait for a slot)
xfer = processingMs - inferMs - convertMs  (upload + download + Pod-side overhead)"""
import argparse
import datetime as dt
import json
import math
import statistics as st
import sys


def p(x):
    return dt.datetime.fromisoformat(x.replace("Z", "+00:00"))


def q(vals, f):
    if not vals:
        return None
    v = sorted(vals)
    k = (len(v) - 1) * f
    lo, hi = math.floor(k), math.ceil(k)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def desc(vals):
    if not vals:
        return {"n": 0}
    return {"n": len(vals), "mean": st.fmean(vals), "sd": st.pstdev(vals) if len(vals) > 1 else 0.0,
            "p50": q(vals, 0.5), "p90": q(vals, 0.9), "p95": q(vals, 0.95), "max": max(vals), "min": min(vals)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--h0", type=float, default=None)
    ap.add_argument("--s0", type=float, default=None)
    ap.add_argument("--slots", type=int, default=1)
    ap.add_argument("--json", default=None)
    a = ap.parse_args()
    summ = json.load(open(f"{a.run}/k6-summary.json", encoding="utf-8"))
    meta = summ.get("meta", {})
    t0, t1 = p(meta["startedAt"]), p(meta["endedAt"])
    ev = []
    for line in open(f"{a.run}/vm/events.jsonl", encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        e = json.loads(line)
        fin = p(e["finishedAt"])
        created = fin - dt.timedelta(milliseconds=e.get("totalMs") or 0)
        e["_created"], e["_fin"] = created, fin
        if t0 <= created <= t1:
            ev.append(e)
    ev.sort(key=lambda e: e["_created"])
    out = {"run": a.run, "window": [meta["startedAt"], meta["endedAt"]], "jobsInWindow": len(ev)}
    res = {}
    for e in ev:
        res[e.get("result")] = res.get(e.get("result"), 0) + 1
    out["results"] = res
    out["attempts>1"] = sum(1 for e in ev if (e.get("attempt") or 1) > 1)
    out["passthrough"] = sorted({str(e.get("passthrough")) for e in ev})
    out["modelVersion"] = sorted({str(e.get("modelVersion")) for e in ev})
    out["checkResult"] = {k: sum(1 for e in ev if e.get("checkResult") == k) for k in {e.get("checkResult") for e in ev}}
    out["preprocessResult"] = {k: sum(1 for e in ev if e.get("preprocessResult") == k) for k in {e.get("preprocessResult") for e in ev}}
    out["errorCodes"] = {}
    for e in ev:
        c = e.get("errorCode") or e.get("error")
        if c:
            out["errorCodes"][str(c)] = out["errorCodes"].get(str(c), 0) + 1
    ok = [e for e in ev if e.get("result") == "COMPLETED"]
    num = lambda k, s=ok: [e[k] for e in s if isinstance(e.get(k), (int, float))]
    out["queuedMs"] = desc(num("queuedMs", ev))
    out["processingMs"] = desc(num("processingMs"))
    out["totalMs"] = desc(num("totalMs"))
    out["preprocessMs"] = desc(num("preprocessMs", ev))
    out["checkMs"] = desc(num("checkMs", ev))
    out["uploadMs"] = desc(num("uploadMs", ev))
    out["inferMs"] = desc(num("inferMs"))
    out["convertMs"] = desc(num("convertMs"))
    out["gpuPeakMB"] = desc(num("gpuPeakMB"))
    out["glbBytes"] = desc(num("glbBytes"))
    out["outputTriangles"] = desc(num("outputTriangles"))
    h = [e["queuedMs"] - (e.get("preprocessMs") or 0) for e in ev if isinstance(e.get("queuedMs"), (int, float))]
    out["h_ms"] = desc(h)
    xfer = [e["processingMs"] - e["inferMs"] - e["convertMs"] for e in ok
            if all(isinstance(e.get(k), (int, float)) for k in ("processingMs", "inferMs", "convertMs"))]
    out["xferMs"] = desc(xfer)
    proc = num("processingMs")
    if a.h0 is not None:
        wq = [x - a.h0 for x in h]
        out["Wq_ms"] = desc(wq)
        # batch means (5 batches in creation order) for a rough confidence interval of the mean wait
        if len(wq) >= 10:
            k = len(wq) // 5
            bm = [st.fmean(wq[i * k:(i + 1) * k]) for i in range(5)]
            m, s = st.fmean(bm), st.stdev(bm)
            out["Wq_batch_means_ms"] = {"batches": [round(x) for x in bm], "mean": m, "ci95_half": 2.776 * s / math.sqrt(5)}
    # measured arrival rate and utilisation
    span = (t1 - t0).total_seconds()
    lam = len(ev) / span if span > 0 else 0
    out["lambda_per_h"] = lam * 3600
    if a.s0:
        rho = lam * a.s0 / a.slots
        out["rho_measured"] = rho
        if a.slots == 1 and rho < 1 and proc:
            S = a.s0
            # M/D/1 and M/M/1 with S0; P-K with the measured slot-time variance (processingMs spread as a proxy)
            sd = st.pstdev([x / 1000 for x in proc]) if len(proc) > 1 else 0.0
            out["pred_Wq_s"] = {"MD1": rho * S / (2 * (1 - rho)), "MM1": rho * S / (1 - rho),
                                "PK": lam * (S * S + sd * sd) / (2 * (1 - rho)), "slot_sd_s": sd}
    # completions while busy: gaps between consecutive finishes (for overload S_busy)
    fins = sorted(e["_fin"] for e in ok)
    gaps = [(b - a_).total_seconds() for a_, b in zip(fins, fins[1:])]
    out["finish_gaps_s"] = desc(gaps)
    # backlog curve: jobs created but not finished at each finish time
    if ev:
        allfin = sorted(e["_fin"] for e in ev)
        created = [e["_created"] for e in ev]
        peak, peak_t = 0, None
        for tt in sorted(created + allfin):
            n = sum(1 for c in created if c <= tt) - sum(1 for f in allfin if f <= tt)
            if n > peak:
                peak, peak_t = n, tt
        out["peak_in_system"] = {"n": peak, "at": peak_t.isoformat() if peak_t else None}
        out["last_finish"] = allfin[-1].isoformat()
    k6 = {k: meta.get(k) for k in ("scenario", "measuredCreates", "measuredJobsPerHour", "measuredRetries",
                                   "measuredQueuedMs", "measuredProcessingMs", "prediction", "recompute",
                                   "rttBaseMsMeasured", "preAllocatedVUs", "maxVUs", "arrivalProcess")}
    out["k6meta"] = {k: v for k, v in k6.items() if v is not None}
    s = json.dumps(out, ensure_ascii=False, indent=1, default=str)
    if a.json:
        open(a.json, "w", encoding="utf-8").write(s)
    print(s)


if __name__ == "__main__":
    sys.exit(main())
