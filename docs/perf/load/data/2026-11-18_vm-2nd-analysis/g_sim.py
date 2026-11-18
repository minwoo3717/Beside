"""Monte Carlo of one finite G1 run: Poisson arrivals at lambda for D seconds into an empty single slot, slot times
resampled from the run's own (processingMs + handover) values; returns the distribution of the run's mean wait.
Usage: python g_sim.py <run dir> --lam-per-h L --dur-s D [--handover-ms 834] [--n 2000] [--skip-s 0] [--seed 1]"""
import argparse
import datetime as dt
import json
import random
import statistics as st


def p(x):
    return dt.datetime.fromisoformat(x.replace("Z", "+00:00"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--lam-per-h", type=float, required=True)
    ap.add_argument("--dur-s", type=float, required=True)
    ap.add_argument("--handover-ms", type=float, default=834.0)
    ap.add_argument("--n", type=int, default=2000)
    ap.add_argument("--skip-s", type=float, default=0.0)
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    m = json.load(open(f"{a.run}/k6-summary.json", encoding="utf-8"))["meta"]
    t0, t1 = p(m["startedAt"]), p(m["endedAt"])
    slots = []
    for line in open(f"{a.run}/vm/events.jsonl", encoding="utf-8"):
        if line.strip():
            e = json.loads(line)
            c = p(e["finishedAt"]) - dt.timedelta(milliseconds=e["totalMs"])
            if e.get("result") == "COMPLETED" and t0 <= c <= t1:
                slots.append((e["processingMs"] + a.handover_ms) / 1000)
    rng = random.Random(a.seed)
    lam = a.lam_per_h / 3600
    means = []
    for _ in range(a.n):
        t, free, ws = 0.0, 0.0, []
        while True:
            t += rng.expovariate(lam)
            if t > a.dur_s:
                break
            start = max(t, free)
            if t >= a.skip_s:
                ws.append(start - t)
            free = start + rng.choice(slots)
        if ws:
            means.append(st.fmean(ws))
    means.sort()
    q = lambda f: means[int(f * (len(means) - 1))]
    S = st.fmean(slots)
    S2 = st.fmean([s * s for s in slots])
    rho = lam * S
    pk = lam * S2 / (2 * (1 - rho)) if rho < 1 else None
    print(json.dumps({"run": a.run, "lam_per_h": a.lam_per_h, "dur_s": a.dur_s, "slot_mean_s": S, "slot_cv": (S2 - S * S) ** .5 / S,
                      "rho": rho, "steady_state_PK_wait_s": pk, "MD1_s": rho * S / (2 * (1 - rho)) if rho < 1 else None,
                      "finite_run_mean_wait_s": {"mean": st.fmean(means), "p05": q(.05), "p25": q(.25), "p50": q(.5), "p75": q(.75), "p95": q(.95)},
                      "n_sims": len(means)}, indent=1))


if __name__ == "__main__":
    main()
