"""Trace-driven check of the GPU slot queue for one round-2 run (single slot unless --slots 2).
Usage: python g_trace.py <run dir> [--h0 MS] [--skip-s S] [--slots N]
Per job (COMPLETED, created inside the k6 window):
  created  = finishedAt - totalMs
  request  = created + preprocessMs          (the job asks for a slot after the cutout)
  pstart   = created + queuedMs              (processing starts: after the slot and the slot's /healthz call)
  pend     = pstart + processingMs
Handover gap: for a job that waited (its request came before the previous job's pend), pstart - pend(prev) =
  previous job's DELETE + this job's /healthz (+ writes): the real slot overhead between two busy jobs.
Lindley replay: W(n) = max(0, D(n-1) - request(n)) with D(n) = request(n) + W(n) + slot(n), slot(n) = h + processingMs + g,
  h + g = the median handover gap (busy) -> predicted wait for these exact arrivals and service times, vs measured
  wait = pstart - request - h_own (h_own = the job's own healthz, taken as the median handover's healthz share = h0)."""
import argparse
import datetime as dt
import json
import statistics as st


def p(x):
    return dt.datetime.fromisoformat(x.replace("Z", "+00:00"))


def q(v, f):
    v = sorted(v)
    if not v:
        return None
    k = (len(v) - 1) * f
    lo = int(k)
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--h0", type=float, default=557.0)
    ap.add_argument("--skip-s", type=float, default=300.0)
    ap.add_argument("--slots", type=int, default=1)
    a = ap.parse_args()
    m = json.load(open(f"{a.run}/k6-summary.json", encoding="utf-8"))["meta"]
    t0, t1 = p(m["startedAt"]), p(m["endedAt"])
    jobs = []
    for line in open(f"{a.run}/vm/events.jsonl", encoding="utf-8"):
        if not line.strip():
            continue
        e = json.loads(line)
        if e.get("result") != "COMPLETED":
            continue
        fin = p(e["finishedAt"])
        created = fin - dt.timedelta(milliseconds=e["totalMs"])
        if not (t0 <= created <= t1):
            continue
        ms = lambda k: e.get(k) or 0
        c = (created - t0).total_seconds() * 1000
        jobs.append({"created": c, "request": c + ms("preprocessMs"), "pstart": c + ms("queuedMs"),
                     "proc": ms("processingMs"), "pend": c + ms("queuedMs") + ms("processingMs")})
    jobs.sort(key=lambda j: j["pstart"])
    # handover gaps (single slot: consecutive processing; with 2 slots this is only indicative)
    gaps = []
    for prev, cur in zip(jobs, jobs[1:]):
        if cur["request"] < prev["pend"] and cur["pstart"] >= prev["pend"]:
            gaps.append(cur["pstart"] - prev["pend"])
    out = {"run": a.run, "jobs": len(jobs)}
    if gaps:
        out["handover_gap_ms"] = {"n": len(gaps), "p10": q(gaps, .1), "p50": q(gaps, .5), "p90": q(gaps, .9), "mean": st.fmean(gaps)}
    hg = q(gaps, .5) if gaps else 2 * a.h0
    # Lindley replay in request order, c servers (earliest-free)
    order = sorted(jobs, key=lambda j: j["request"])
    free = [0.0] * a.slots
    pred, meas, pred_all, meas_all = [], [], [], []
    for j in order:
        k = min(range(a.slots), key=lambda i: free[i])
        start = max(j["request"], free[k])
        w = start - j["request"]
        free[k] = start + hg + j["proc"]
        mw = j["pstart"] - j["request"] - a.h0
        pred_all.append(w); meas_all.append(mw)
        if j["created"] >= a.skip_s * 1000:
            pred.append(w); meas.append(mw)
    def d(v):
        return {"n": len(v), "mean": st.fmean(v) if v else None, "p50": q(v, .5), "p90": q(v, .9), "p95": q(v, .95), "max": max(v) if v else None}
    out["slot_overhead_used_ms"] = hg
    out["replay_wait_ms_all"] = d(pred_all)
    out["measured_wait_ms_all"] = d(meas_all)
    out[f"replay_wait_ms_after_{int(a.skip_s)}s"] = d(pred)
    out[f"measured_wait_ms_after_{int(a.skip_s)}s"] = d(meas)
    # busy-period service: mean processing + handover while the slot never idled
    busy = [cur["pstart"] - prev["pstart"] for prev, cur in zip(jobs, jobs[1:]) if cur["request"] < prev["pend"]]
    if busy:
        out["busy_start_to_start_ms"] = {"n": len(busy), "mean": st.fmean(busy), "p50": q(busy, .5)}
    out["mean_slot_ms"] = st.fmean([j["proc"] for j in jobs]) + hg if jobs else None
    print(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
