"""Queue cap model for contract agenda #21: the cap rule, a trace replay, a Monte Carlo and retry traffic ([계산]).

    python scripts/load/cap_model.py formula            # N, worst wait and Retry-After for target waits W (c=1, c=2)
    python scripts/load/cap_model.py fill --cap 19 --rate 471 --capacity 415
    python scripts/load/cap_model.py replay <run dir> [--cap 12 --cap 19] [--slots 1] [--h0 S]
    python scripts/load/cap_model.py montecarlo <run dir> [--rate 471 --rate 517] [--cap 19] [--minutes 20] [--runs 400]
    python scripts/load/cap_model.py retry <run dir> [--rate 517] [--cap 19] [--retry-after 60 --retry-after 165]
    python scripts/load/cap_model.py all --out docs/perf/load/data/2026-11-20_queue-cap/cap_model-output.txt

The cap ([계획], not on main): POST /api/v1/jobs and /retry get 503 QUEUE_FULL + Retry-After while the PENDING jobs
(plus requests being admitted) number N or more. A refused request leaves no job behind; throughput does not change.

One job, from a run folder's events.jsonl (the backend's job_runs lines; the same reading as the round-2 g_trace.py and
the round-4 g4_analyze.py under docs/perf/load/data/):
  a    created = finishedAt - totalMs: the INSERT as PENDING (the time the gate would count it from)
  r    a + preprocessMs: the job asks for an inference slot after its cutout
  ps   a + queuedMs: PENDING -> PROCESSING, after the slot and the slot's own /healthz call (h)
  pe   ps + processingMs = finishedAt; the slot is free again after the cleanup DELETE (g)
  h + g is the median handover gap (previous pe -> next ps, when the next job had already asked): h = g = half of it
  unless --h0 is given. A slot is held for h + processingMs + g, and slots go first come first served by request time
  (the backend's fair Semaphore). Only first attempts created inside run.json's window count: events.jsonl can also
  hold lines of the run before.
Wait = created -> COMPLETED (pe - a): what the app polls for after its 202 and gives up on at 600 s.
PENDING = [a, ps). An arrival is refused when it finds N or more PENDING. Standard library only; the Monte Carlo draws
come from random.Random(seed).random() alone (the reproducible part of the module), so a seed gives the same draws on
every Python 3 version.
"""
from __future__ import annotations

import argparse
import datetime as dt
import heapq
import itertools
import json
import math
import random
import re
import statistics as st
import sys
from collections import deque
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
DATA = REPO / "docs" / "perf" / "load" / "data"
EPS = 1e-9

# Measured inputs (docs/perf/load/2026-11-20_vm-4th.md summary and section 4; 2026-11-18_vm-2nd.md section 2).
S_BUSY_C1 = 8.67      # c=1 busy start-to-start, g3c1 (4th round) -> 415/h
MU_C1 = 415.0
MU_C2 = 495.0         # c=2 counted lower bound, g2c2t (4th round)
T_C2 = 9.86           # c=2 mean processingMs, g2c2t
H0_C2 = 0.293         # own /healthz before processing, g0d4 (4th round): c=2 slot time T2 + 2 h0
S_DRIFT = 11.5        # S0 of the round-2 Pod (keep-alive 5 s)
APP_MAX_WAIT_S = 600.0
OFFLINE_WAIT_S = 1800.0
T2_PEAK_PER_H = 120.0  # TRAFFIC_MODEL.md section 3, tier T2 peak hour [가정]
UPLOAD_MB = 0.8        # tiers.json photoMB 0.4 x photoCount 2 [가정]
RUNS = {"g3c1": "2026-11-20_0256-g3c1", "g3r165": "2026-11-18_2033-g3r165"}

FREE, START, REQUEST, ARRIVAL = 0, 1, 2, 3   # event order at equal times


# ---------------------------------------------------------------------------------------------------------------- rule

def _positive(name: str, x: float) -> None:
    if not x > 0:
        raise ValueError(f"{name} must be positive")


def cap_c1(w_s: float, s_busy: float) -> int:
    """c=1: the largest N whose worst wait (N + 1) x S stays within W, i.e. N = floor(W / S) - 1."""
    _positive("W", w_s)
    _positive("S", s_busy)
    n = math.floor(w_s / s_busy + EPS) - 1
    if n < 1:
        raise ValueError(f"W {w_s:g} s is below 2 x S ({2 * s_busy:g} s): no cap >= 1 meets it")
    return n


def worst_c1(n: int, s_busy: float) -> float:
    """c=1 worst wait of an admitted job: N - 1 waiting ahead, the slot holder, then its own slot."""
    return (n + 1) * s_busy


def cap_rate(w_s: float, mu_per_h: float, t_s: float) -> int:
    """Any c: N = floor(mu x (W - T) / 3600) with mu the measured throughput of the configured slots and T one job's
    processing. An admitted job starts after at most N departures and then runs for T. c=1 is mu = 3600 / S, T = S."""
    _positive("W", w_s)
    _positive("mu", mu_per_h)
    _positive("T", t_s)
    n = math.floor(mu_per_h * (w_s - t_s) / 3600 + EPS)
    if n < 1:
        raise ValueError(f"W {w_s:g} s leaves less than one departure after T {t_s:g} s: no cap >= 1 meets it")
    return n


def worst_rate(n: int, mu_per_h: float, t_s: float) -> float:
    return n * 3600 / mu_per_h + t_s


def retry_after(n: int, mu_per_h: float) -> float:
    """Seconds the slots need to drain N jobs: a refusal happens only with N PENDING, so this is the time a refused
    client should stay away."""
    _positive("mu", mu_per_h)
    return n * 3600 / mu_per_h


def retry_after_header(n: int, mu_per_h: float) -> int:
    """The Retry-After header value: whole seconds, at least 1 (the draft contract's minimum), rounded half up."""
    return max(1, math.floor(retry_after(n, mu_per_h) + 0.5))


def fill_minutes(n: int, rate_per_h: float, capacity_per_h: float):
    """Fluid time for an empty queue to reach N: N / (arrivals - capacity); None when it never fills."""
    net = rate_per_h - capacity_per_h
    return None if net <= 0 else n / net * 60


# ---------------------------------------------------------------------------------------------------------------- data

_TS = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})?$")


def parse_time(text: str) -> dt.datetime:
    """ISO time with any number of fraction digits (events.jsonl has nanoseconds), cut to microseconds."""
    m = _TS.match(text.strip())
    if not m:
        raise ValueError(f"not an ISO time: {text!r}")
    base, frac, tz = m.groups()
    frac = (frac or "")[:6].ljust(6, "0")
    tz = "+00:00" if tz in (None, "Z") else tz
    return dt.datetime.fromisoformat(f"{base}.{frac}{tz}")


def events_path(run_dir: Path, run: dict) -> Path:
    for att in run.get("attachments") or []:
        if att.get("name") == "events.jsonl" and att.get("file") and (run_dir / att["file"]).is_file():
            return run_dir / att["file"]
    for rel in ("attachments/events.jsonl", "vm/events.jsonl"):
        if (run_dir / rel).is_file():
            return run_dir / rel
    raise FileNotFoundError(f"no events.jsonl in {run_dir}")


def load_run(run_dir) -> dict:
    """First attempts created inside run.json's window, sorted by creation; times in seconds from window.start."""
    run_dir = Path(run_dir)
    run = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
    t0, t1 = parse_time(run["window"]["start"]), parse_time(run["window"]["end"])
    span = (t1 - t0).total_seconds()
    path = events_path(run_dir, run)
    lines = outside = retries = unprocessed = 0
    jobs = []
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            if not raw.strip():
                continue
            lines += 1
            e = json.loads(raw)
            a = (parse_time(e["finishedAt"]) - t0).total_seconds() - e["totalMs"] / 1000
            if not 0 <= a <= span:
                outside += 1
                continue
            if e.get("attempt", 1) != 1:
                retries += 1
                continue
            if e.get("processingMs") is None or e.get("queuedMs") is None:
                unprocessed += 1
                continue
            pre = (e.get("preprocessMs") or 0) / 1000
            queued, proc = e["queuedMs"] / 1000, e["processingMs"] / 1000
            jobs.append({"id": e.get("jobId"), "a": a, "pre": pre, "r": a + pre, "ps": a + queued, "proc": proc,
                         "pe": a + queued + proc, "sojourn": queued + proc, "result": e.get("result")})
    jobs.sort(key=lambda j: j["a"])
    meta = (run.get("k6") or {}).get("meta") or {}
    arrival_s = float(meta.get("measuredArrivalWindowS") or meta.get("durationS") or span)
    return {"dir": run_dir, "runId": run.get("runId") or run_dir.name, "start": run["window"]["start"],
            "end": run["window"]["end"], "span": span, "arrival_s": arrival_s, "events": path, "lines": lines,
            "outside": outside, "retries": retries, "unprocessed": unprocessed, "jobs": jobs}


def handover(jobs: list):
    """Median gap between a job's processing end and the next start when the next job had already asked (= the next
    job's /healthz + this job's DELETE): the slot time outside processingMs. None when no job waited."""
    js = sorted(jobs, key=lambda j: j["ps"])
    gaps = [cur["ps"] - prv["pe"] for prv, cur in zip(js, js[1:]) if cur["r"] < prv["pe"] and cur["ps"] >= prv["pe"]]
    return quantile(gaps, 0.5) if gaps else None


def s_busy(jobs: list):
    """Mean processing start-to-start while the slot did not idle (the later job asked before the earlier one ended)."""
    js = sorted(jobs, key=lambda j: j["ps"])
    gaps = [cur["ps"] - prv["ps"] for prv, cur in zip(js, js[1:]) if cur["r"] < prv["pe"]]
    return st.fmean(gaps) if gaps else None


def measured_peak_seen(jobs: list) -> int:
    """The most PENDING jobs any arrival found in the measured trace (what the gate would compare with N)."""
    return max((sum(1 for o in jobs if o is not j and o["a"] <= j["a"] < o["ps"]) for j in jobs), default=0)


# ---------------------------------------------------------------------------------------------------------------- simulation

def simulate(users: list, slots: int = 1, cap: int = 0, h: float = 0.0, g: float = 0.0, retry_after_s=None,
             max_tries: int = 1, give_up_s=None) -> dict:
    """Event simulation of the gate and the slots. users = [(first POST time, cutout s, processing s)].

    An arrival (a POST) finding cap or more PENDING is refused (cap 0 = off). A refused user posts again after
    retry_after_s while it has tries left (max_tries counts the first POST) and, with give_up_s, while the retry is
    within give_up_s of its first POST. An admitted job is PENDING until its slot's /healthz (h) is over."""
    if slots < 1:
        raise ValueError("slots must be at least 1")
    out = [{"a": a, "admitted": False, "tries": 0, "seen": None, "seen_admitted": None, "admit_t": None,
            "first_refused_t": None, "ps": None, "pe": None} for a, _, _ in users]
    seq = itertools.count()
    heap = [(a, ARRIVAL, next(seq), i) for i, (a, _, _) in enumerate(users)]
    heapq.heapify(heap)
    pending, free, fifo = 0, slots, deque()
    posts = refused = 0

    def begin(i: int, t: float) -> None:
        heapq.heappush(heap, (t + h, START, next(seq), i))
        heapq.heappush(heap, (t + h + users[i][2] + g, FREE, next(seq), -1))

    while heap:
        t, kind, _, i = heapq.heappop(heap)
        if kind == ARRIVAL:
            u = out[i]
            u["tries"] += 1
            posts += 1
            if u["seen"] is None:
                u["seen"] = pending
            if cap > 0 and pending >= cap:
                refused += 1
                if u["first_refused_t"] is None:
                    u["first_refused_t"] = t
                if retry_after_s is not None and u["tries"] < max_tries:
                    nt = t + retry_after_s
                    if give_up_s is None or nt - u["a"] <= give_up_s + EPS:
                        heapq.heappush(heap, (nt, ARRIVAL, next(seq), i))
                continue
            u["admitted"], u["admit_t"], u["seen_admitted"] = True, t, pending
            pending += 1
            heapq.heappush(heap, (t + users[i][1], REQUEST, next(seq), i))
        elif kind == REQUEST:
            if free > 0:
                free -= 1
                begin(i, t)
            else:
                fifo.append(i)
        elif kind == START:
            pending -= 1
            out[i]["ps"], out[i]["pe"] = t, t + users[i][2]
        elif fifo:
            begin(fifo.popleft(), t)
        else:
            free += 1
    return {"users": out, "posts": posts, "refused": refused}


def slot_overheads(jobs: list, h0=None):
    hg = handover(jobs) or 0.0
    h = hg / 2 if h0 is None else h0
    return hg, h, max(0.0, hg - h)


def replay(run: dict, cap: int = 0, slots: int = 1, h0=None) -> dict:
    """The measured arrivals and per-job cutout and processing times through a gate with cap N."""
    jobs = run["jobs"]
    hg, h, g = slot_overheads(jobs, h0)
    res = simulate([(j["a"], j["pre"], j["proc"]) for j in jobs], slots, cap, h, g)
    users = res["users"]
    adm = [u for u in users if u["admitted"]]
    first = [u["first_refused_t"] for u in users if u["first_refused_t"] is not None]
    return {"cap": cap, "slots": slots, "handover": hg, "h": h, "g": g, "n": len(jobs), "refused": res["refused"],
            "users": users, "peak_seen": max((u["seen"] for u in users), default=0),
            "peak_seen_admitted": max((u["seen_admitted"] for u in adm), default=0),
            "sojourn": [u["pe"] - u["a"] for u in adm], "wait": [u["ps"] - u["a"] for u in adm],
            "first_refusal": min(first) if first else None}


def poisson_users(rng: random.Random, samples: list, rate_per_h: float, duration_s: float, t0: float = 0.0) -> list:
    """Poisson arrivals on [t0, t0 + duration_s) with (cutout, processing) drawn from the samples."""
    lam = rate_per_h / 3600
    users, t = [], t0
    while True:
        t += -math.log(1.0 - rng.random()) / lam
        if t >= t0 + duration_s:
            return users
        pre, proc = samples[int(rng.random() * len(samples))]
        users.append((t, pre, proc))


def monte_carlo(samples: list, rate_per_h: float, minutes: float, cap: int, runs: int, seed: int, slots: int = 1,
                h: float = 0.0, g: float = 0.0) -> dict:
    """Runs of the given length from an empty queue (as a k6 run starts), each with its own Poisson arrivals. The same
    seed gives the same arrival streams for every cap, so caps are compared on the same traffic."""
    rng = random.Random(seed)
    out = {"rate": rate_per_h, "minutes": minutes, "cap": cap, "runs": runs, "seed": seed, "n": [], "refused": [],
           "refused_frac": [], "max_sojourn": [], "mean_sojourn": [], "first_refusal": []}
    for _ in range(runs):
        users = poisson_users(rng, samples, rate_per_h, minutes * 60)
        res = simulate(users, slots, cap, h, g)
        soj = [u["pe"] - u["a"] for u in res["users"] if u["admitted"]]
        first = [u["first_refused_t"] for u in res["users"] if u["first_refused_t"] is not None]
        n = len(users)
        out["n"].append(n)
        out["refused"].append(res["refused"])
        out["refused_frac"].append(res["refused"] / n if n else 0.0)
        out["max_sojourn"].append(max(soj) if soj else 0.0)
        out["mean_sojourn"].append(st.fmean(soj) if soj else 0.0)
        out["first_refusal"].append(min(first) if first else None)
    return out


def steady_state(samples: list, rate_per_h: float, cap: int, hours: float, warmup_h: float, seed: int, slots: int = 1,
                 h: float = 0.0, g: float = 0.0) -> dict:
    """One long run; arrivals in the first warmup_h hours are simulated but not counted."""
    rng = random.Random(seed)
    users = poisson_users(rng, samples, rate_per_h, hours * 3600)
    res = simulate(users, slots, cap, h, g)
    counted = [u for u in res["users"] if u["a"] >= warmup_h * 3600]
    adm = [u for u in counted if u["admitted"]]
    soj = [u["pe"] - u["a"] for u in adm]
    n = len(counted)
    return {"rate": rate_per_h, "cap": cap, "hours": hours, "warmup_h": warmup_h, "seed": seed, "n": n,
            "refused": n - len(adm), "refused_frac": (n - len(adm)) / n if n else 0.0,
            "admitted_per_h": len(adm) / (hours - warmup_h) if hours > warmup_h else None,
            "sojourn_mean": st.fmean(soj) if soj else None, "sojourn_p95": quantile(soj, 0.95) if soj else None,
            "sojourn_max": max(soj) if soj else None}


def retry_tail_s(retry_after_s, max_tries: int, give_up_s=None) -> float:
    """The longest time from a user's first POST to its last retry under simulate's rules (0 without retries)."""
    if retry_after_s is None or max_tries <= 1:
        return 0.0
    _positive("Retry-After", retry_after_s)
    k = max_tries - 1
    if give_up_s is not None:
        k = min(k, math.floor((give_up_s + EPS) / retry_after_s))
    return max(0, k) * retry_after_s


def retry_counts(res: dict, warmup_s: float, end_s: float) -> dict:
    """Counts over the users whose first POST is in [warmup_s, end_s), each followed to its last retry; per-hour rates
    divide by the window. In steady state that cohort's POSTs per hour of first POSTs is the POST rate."""
    counted = [u for u in res["users"] if warmup_s <= u["a"] < end_s]
    adm = [u for u in counted if u["admitted"]]
    posts = sum(u["tries"] for u in counted)
    span = (end_s - warmup_s) / 3600
    late = [u["admit_t"] - u["a"] for u in adm if u["tries"] > 1]
    return {"users": len(counted), "posts": posts, "refused_posts": posts - len(adm), "admitted": len(adm),
            "gave_up": len(counted) - len(adm), "max_tries_used": max((u["tries"] for u in counted), default=0),
            "posts_per_h": posts / span if span > 0 else None,
            "refused_per_h": (posts - len(adm)) / span if span > 0 else None,
            "admitted_per_h": len(adm) / span if span > 0 else None,
            "admitted_after_retry": len(late), "first_post_to_admit_mean": st.fmean(late) if late else None,
            "sojourn_after_admit_max": max((u["pe"] - u["admit_t"] for u in adm), default=None)}


def retry_traffic(samples: list, rate_per_h: float, cap: int, retry_after_s: float, max_tries: int, hours: float,
                  warmup_h: float, seed: int, slots: int = 1, h: float = 0.0, g: float = 0.0, give_up_s=None,
                  run_on_s=None) -> dict:
    """Users arrive as Poisson at rate_per_h; a refused user posts again after retry_after_s (see simulate). Counts
    cover users whose first POST is in [warmup_h, hours), followed to their last retry. Arrivals go on for run_on_s
    past hours (default: retry_tail_s + 1 s), so those last retries meet live traffic, not a queue that drains once the
    arrivals stop (that would admit more of them and inflate the per-hour rates of a short run)."""
    tail = retry_tail_s(retry_after_s, max_tries, give_up_s)
    run_on = tail + 1.0 if run_on_s is None else run_on_s
    rng = random.Random(seed)
    users = poisson_users(rng, samples, rate_per_h, hours * 3600 + run_on)
    res = simulate(users, slots, cap, h, g, retry_after_s=retry_after_s, max_tries=max_tries, give_up_s=give_up_s)
    out = {"rate": rate_per_h, "cap": cap, "retry_after_s": retry_after_s, "max_tries": max_tries,
           "give_up_s": give_up_s, "hours": hours, "warmup_h": warmup_h, "seed": seed, "run_on_s": run_on}
    out.update(retry_counts(res, warmup_h * 3600, hours * 3600))
    return out


# ---------------------------------------------------------------------------------------------------------------- report

def quantile(v, f):
    """Linear between order statistics (as g4_analyze.py)."""
    v = sorted(v)
    if not v:
        return float("nan")
    k = (len(v) - 1) * f
    lo = int(k)
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def dist(v, nd=1) -> str:
    if not v:
        return "n 0"
    return (f"n {len(v)} mean {st.fmean(v):.{nd}f} p50 {quantile(v, .5):.{nd}f} p95 {quantile(v, .95):.{nd}f} "
            f"max {max(v):.{nd}f}")


def rel(path) -> str:
    try:
        return Path(path).resolve().relative_to(REPO).as_posix()
    except ValueError:
        return Path(path).name


def head(section: str, title: str) -> str:
    return f"== {section}. {title}" if section else f"== {title}"


def formula_text(s: float = S_BUSY_C1, mu: float = MU_C1, ws=(120, 180, 300, 600), mu2: float = MU_C2,
                 t2: float = T_C2, s_drift: float = S_DRIFT, section: str = "") -> str:
    lines = [head(section, "cap rule for a target worst wait W (wait = created -> COMPLETED, what the app's 600 s counts)"),
             f"  c=1: N = floor(W/S) - 1, worst wait (N+1)*S, Retry-After R = N*3600/mu; S {s:g} s (S_busy), mu {mu:g}/h"]
    caps = []
    for w in ws:
        n = cap_c1(w, s)
        caps.append(n)
        worst = worst_c1(n, s)
        lines.append(f"    W {w:g} s: N {n}, worst {worst:.1f} s, left of the app's {APP_MAX_WAIT_S:g} s {APP_MAX_WAIT_S - worst:.1f} s, "
                     f"R {retry_after(n, mu):.1f} s -> Retry-After {retry_after_header(n, mu)}")
    lines.append(f"  slot time drift: the same N with S = {s_drift:g} s (round-2 Pod S0)")
    lines.append("    " + "; ".join(f"N {n} -> worst {worst_c1(n, s_drift):.1f} s" + (" (over 600 s)" if worst_c1(n, s_drift) > APP_MAX_WAIT_S else "")
                                    for n in caps))
    lines.append(f"  c=2: N = floor(mu2*(W - T2)/3600), worst N*3600/mu2 + T2, R = N*3600/mu2; mu2 {mu2:g}/h (counted lower "
                 f"bound), T2 {t2:g} s (mean processing)")
    for w in ws:
        n = cap_rate(w, mu2, t2)
        lines.append(f"    W {w:g} s: N {n}, worst {worst_rate(n, mu2, t2):.1f} s, R {retry_after(n, mu2):.1f} s -> "
                     f"Retry-After {retry_after_header(n, mu2)}")
    n2 = 2 * cap_c1(ws[0], s)
    slot2 = t2 + 2 * H0_C2
    q2 = math.floor(2 * (APP_MAX_WAIT_S - slot2) / slot2 + EPS)
    lines.append(f"  c=2 the wrong ways: doubling the c=1 cap for W {ws[0]:g} s gives N {n2} -> worst {worst_rate(n2, mu2, t2):.1f} s; "
                 f"the 600 s formula floor(c*(600-T)/T) with c 2, T {slot2:.2f} s gives N {q2} -> worst {worst_rate(q2, mu2, t2):.1f} s "
                 f"if the capacity is the lower bound")
    lines.append(f"  outage (prod keeps jobs PENDING up to {OFFLINE_WAIT_S:g} s while the inference service is down), "
                 f"arrivals {T2_PEAK_PER_H:g}/h (tier T2 peak, [가정]):")
    pile = T2_PEAK_PER_H * OFFLINE_WAIT_S / 3600
    lines.append(f"    no cap: {pile:.0f} jobs pile up, draining them takes {pile * s:.0f} s once the GPU is back")
    for n in caps[:2]:
        lines.append(f"    N {n}: backlog {n}, drain {n * s:.0f} s; {pile - n:.0f} of the {pile:.0f} outage arrivals refused")
    return "\n".join(lines) + "\n"


def fill_text(caps=(12, 19, 33, 68), rates=(471.0, 517.0), capacity: float = MU_C1, section: str = "") -> str:
    lines = [head(section, f"time to fill N from an empty queue (fluid: N / (arrivals - capacity)), capacity {capacity:g}/h")]
    for rate in rates:
        net = rate - capacity
        if net <= 0:
            lines.append(f"  arrivals {rate:g}/h: never fills (net {net:g}/h)")
            continue
        lines.append(f"  arrivals {rate:g}/h, net {net:g}/h: " + ", ".join(f"N {n} {fill_minutes(n, rate, capacity):.1f} min" for n in caps))
    return "\n".join(lines) + "\n"


def replay_text(run: dict, caps=(12, 19), slots: int = 1, h0=None, section: str = "") -> str:
    jobs = run["jobs"]
    free = replay(run, 0, slots, h0)
    sb = s_busy(jobs)
    lines = [head(section, f"trace replay: {rel(run['dir'])} (window {run['start']} - {run['end']}; {rel(run['events'])} "
                  f"lines {run['lines']}, created outside the window {run['outside']}, retries {run['retries']}, never "
                  f"processed {run['unprocessed']}; jobs {len(jobs)}, {len(jobs) / run['arrival_s'] * 3600:.0f}/h over the "
                  f"{run['arrival_s']:g} s arrival window; times below are seconds after the window start)"),
             f"  measured: handover p50 {free['handover']:.3f} s -> h {free['h']:.3f} + g {free['g']:.3f} s; "
             + (f"S_busy {sb:.2f} s -> {3600 / sb:.0f}/h; " if sb else "")
             + f"peak PENDING found by an arrival {measured_peak_seen(jobs)}; slots {slots}",
             f"  measured wait (created -> COMPLETED) {dist([j['sojourn'] for j in jobs])} s",
             f"  measured PENDING (created -> processing start) {dist([j['ps'] - j['a'] for j in jobs])} s",
             f"  uncapped replay wait {dist(free['sojourn'])} s; PENDING {dist(free['wait'])} s; peak PENDING found "
             f"{free['peak_seen']}",
             f"  uncapped replay - measured, per job: wait {dist([u['pe'] - u['a'] - j['sojourn'] for u, j in zip(free['users'], jobs)], 2)} s; "
             f"mean wait {(st.fmean(free['sojourn']) / st.fmean([j['sojourn'] for j in jobs]) - 1) * 100:+.1f} %"]
    for cap in caps:
        r = replay(run, cap, slots, h0)
        bound = f" (bound (N+1)*S_busy {worst_c1(cap, sb):.1f} s)" if sb and slots == 1 else ""
        first = f", first at {r['first_refusal']:.0f} s" if r["first_refusal"] is not None else ""
        lines.append(f"  cap N {cap}: refused {r['refused']} of {r['n']} ({r['refused'] / r['n'] * 100:.1f} %){first}; admitted wait "
                     f"mean {st.fmean(r['sojourn']):.1f} p95 {quantile(r['sojourn'], .95):.1f} max {max(r['sojourn']):.1f} s{bound}; "
                     f"most PENDING an admitted job found {r['peak_seen_admitted']}")
    lines.append(f"  any cap N > {free['peak_seen']} refuses nothing in this trace")
    return "\n".join(lines) + "\n"


def samples_of(run: dict) -> list:
    return [(j["pre"], j["proc"]) for j in run["jobs"]]


def montecarlo_text(run: dict, rates=(471.0, 517.0), caps=(12, 19, 68), minutes=(20, 40), runs: int = 400,
                    seed: int = 1, slots: int = 1, h0=None, steady_hours: float = 100.0, warmup_h: float = 1.0,
                    section: str = "") -> str:
    hg, h, g = slot_overheads(run["jobs"], h0)
    smp = samples_of(run)
    cap_h = 3600 / (st.fmean(p for _, p in smp) + hg) * slots
    lines = [head(section, f"Monte Carlo: Poisson arrivals, (cutout, processing) pairs drawn from {run['runId']} ({len(smp)} "
                  f"jobs), h {h:.3f} + g {g:.3f} s, slots {slots} (capacity about {cap_h:.0f}/h), start empty, {runs} runs per "
                  f"row, seed {seed}"),
             "  row: refused share median (5-95 %); runs with any refusal; first refusal median (5-95 %) of the runs that refused; "
             "admitted max wait per run median / p95; admitted mean wait median"]
    for rate in rates:
        for m in minutes:
            for cap in caps:
                r = monte_carlo(smp, rate, m, cap, runs, seed, slots, h, g)
                fr = [x * 100 for x in r["refused_frac"]]
                first = [x for x in r["first_refusal"] if x is not None]
                ftxt = (f"{quantile(first, .5):.0f} s ({quantile(first, .05):.0f}-{quantile(first, .95):.0f})" if first else "-")
                lines.append(f"  {rate:g}/h {m:g} min N {cap}: refused {quantile(fr, .5):.1f} % ({quantile(fr, .05):.1f}-{quantile(fr, .95):.1f}); "
                             f"any {len(first) / runs * 100:.1f} %; first {ftxt}; max wait {quantile(r['max_sojourn'], .5):.1f} / "
                             f"{quantile(r['max_sojourn'], .95):.1f} s; mean wait {quantile(r['mean_sojourn'], .5):.1f} s")
    lines.append(f"  steady state: one {steady_hours:g} h run per row, first {warmup_h:g} h not counted, seed {seed}; fluid refusal 1 - capacity/arrivals")
    for rate in rates:
        for cap in caps:
            r = steady_state(smp, rate, cap, steady_hours, warmup_h, seed, slots, h, g)
            fluid = max(0.0, 1 - cap_h / rate) * 100
            lines.append(f"  {rate:g}/h N {cap}: refused {r['refused_frac'] * 100:.1f} % (fluid {fluid:.1f} %), admitted "
                         f"{r['admitted_per_h']:.0f}/h, admitted wait mean {r['sojourn_mean']:.1f} p95 {r['sojourn_p95']:.1f} "
                         f"max {r['sojourn_max']:.1f} s")
    return "\n".join(lines) + "\n"


def retry_text(run: dict, rate: float = 517.0, cap: int = 19, retry_afters=(10.0, 60.0, 165.0), max_tries: int = 5,
               give_up_s: float = APP_MAX_WAIT_S, hours: float = 20.0, warmup_h: float = 1.0, seed: int = 1,
               slots: int = 1, h0=None, upload_mb: float = UPLOAD_MB, section: str = "") -> str:
    hg, h, g = slot_overheads(run["jobs"], h0)
    smp = samples_of(run)
    lines = [head(section, f"retry traffic: users arrive at {rate:g}/h, a refused client posts the whole upload again "
                  f"after Retry-After R; N {cap}, slots {slots}, samples {run['runId']}; one {hours:g} h run per row, first "
                  f"{warmup_h:g} h not counted, seed {seed}; refused upload at {upload_mb:g} MB per POST ([가정] tiers.json)")]
    for label, tries, budget in ((f"at most {max_tries} POSTs per user", max_tries, None),
                                 (f"posts again while within {give_up_s:g} s of the first POST", 10 ** 6, give_up_s)):
        lines.append(f"  policy: {label}")
        for ra in retry_afters:
            r = retry_traffic(smp, rate, cap, ra, tries, hours, warmup_h, seed, slots, h, g, budget)
            late = f"{r['first_post_to_admit_mean']:.0f} s" if r["first_post_to_admit_mean"] is not None else "-"
            lines.append(f"    R {ra:g} s: POSTs {r['posts_per_h']:.0f}/h ({r['posts'] / r['users']:.2f} per user), refused "
                         f"{r['refused_per_h']:.0f}/h = {r['refused_per_h'] * upload_mb:.0f} MB/h, admitted {r['admitted_per_h']:.0f}/h, "
                         f"gave up {r['gave_up'] / r['users'] * 100:.1f} % of users, admitted after a retry "
                         f"{r['admitted_after_retry'] / r['users'] * 100:.1f} % (first POST -> admitted mean {late})")
    return "\n".join(lines) + "\n"


def all_text(runs: int = 400, seed: int = 1) -> str:
    g3c1 = load_run(DATA / RUNS["g3c1"])
    g3r165 = load_run(DATA / RUNS["g3r165"])
    top = (f"# cap_model.py all: queue cap model for contract agenda #21 [계산]; standard library only; seed {seed}\n"
           "# inputs [실험 결과]: 4th round g3c1 (c=1, S_busy 8.67 s -> 415/h), g2c2t (c=2 counted >= 495/h, mean "
           "processing 9.86 s), g0d4 (h0 0.293 s); 2nd round g3r165 (c=1, other Pod) and S0 11.5 s\n\n")
    parts = [formula_text(section="1"), fill_text(section="2"), replay_text(g3c1, section="3"),
             replay_text(g3r165, section="4"), montecarlo_text(g3c1, runs=runs, seed=seed, section="5"),
             retry_text(g3c1, seed=seed, section="6")]
    return top + "\n".join(parts)


# ---------------------------------------------------------------------------------------------------------------- cli

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("formula", help="N, worst wait and Retry-After for target waits W")
    f.add_argument("--s", type=float, default=S_BUSY_C1, help="c=1 busy slot time S in seconds")
    f.add_argument("--mu", type=float, default=MU_C1, help="c=1 measured throughput, jobs/h")
    f.add_argument("--w", type=float, action="append", help="target worst wait W in seconds (repeatable)")
    f.add_argument("--mu2", type=float, default=MU_C2, help="c=2 measured throughput, jobs/h")
    f.add_argument("--t2", type=float, default=T_C2, help="c=2 mean processing in seconds")
    f.add_argument("--s-drift", type=float, default=S_DRIFT, help="a longer slot time to check the same N against")
    fl = sub.add_parser("fill", help="time to fill N from empty")
    fl.add_argument("--cap", type=int, action="append")
    fl.add_argument("--rate", type=float, action="append", help="realised arrivals, jobs/h (repeatable)")
    fl.add_argument("--capacity", type=float, default=MU_C1)
    for name, text in (("replay", "replay a run's arrivals through a cap"), ("montecarlo", "Poisson runs with a cap"),
                       ("retry", "retry traffic for Retry-After values")):
        p = sub.add_parser(name, help=text)
        p.add_argument("run", help="run folder with run.json and events.jsonl")
        p.add_argument("--cap", type=int, action="append")
        p.add_argument("--slots", type=int, default=1)
        p.add_argument("--h0", type=float, help="own /healthz before processing, s (default: half the handover gap)")
        p.add_argument("--seed", type=int, default=1)
        if name == "montecarlo":
            p.add_argument("--rate", type=float, action="append")
            p.add_argument("--minutes", type=float, action="append")
            p.add_argument("--runs", type=int, default=400)
            p.add_argument("--steady-hours", type=float, default=100.0)
            p.add_argument("--warmup-h", type=float, default=1.0)
        if name == "retry":
            p.add_argument("--rate", type=float, default=517.0)
            p.add_argument("--retry-after", type=float, action="append")
            p.add_argument("--max-tries", type=int, default=5)
            p.add_argument("--give-up-s", type=float, default=APP_MAX_WAIT_S)
            p.add_argument("--hours", type=float, default=20.0)
            p.add_argument("--warmup-h", type=float, default=1.0)
            p.add_argument("--upload-mb", type=float, default=UPLOAD_MB)
    a = sub.add_parser("all", help="every section, for docs/perf/load/data/2026-11-20_queue-cap/cap_model-output.txt")
    a.add_argument("--runs", type=int, default=400)
    a.add_argument("--seed", type=int, default=1)
    a.add_argument("--out", help="write to this file (UTF-8, LF) instead of stdout")
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    except (AttributeError, ValueError):
        pass
    try:
        if args.cmd == "formula":
            text = formula_text(args.s, args.mu, tuple(args.w or (120, 180, 300, 600)), args.mu2, args.t2, args.s_drift)
        elif args.cmd == "fill":
            text = fill_text(tuple(args.cap or (12, 19, 33, 68)), tuple(args.rate or (471.0, 517.0)), args.capacity)
        elif args.cmd == "replay":
            text = replay_text(load_run(args.run), tuple(args.cap or (12, 19)), args.slots, args.h0)
        elif args.cmd == "montecarlo":
            text = montecarlo_text(load_run(args.run), tuple(args.rate or (471.0, 517.0)), tuple(args.cap or (12, 19, 68)),
                                   tuple(args.minutes or (20, 40)), args.runs, args.seed, args.slots, args.h0,
                                   args.steady_hours, args.warmup_h)
        elif args.cmd == "retry":
            text = retry_text(load_run(args.run), args.rate, (args.cap or [19])[0], tuple(args.retry_after or (10.0, 60.0, 165.0)),
                              args.max_tries, args.give_up_s, args.hours, args.warmup_h, args.seed, args.slots, args.h0,
                              args.upload_mb)
        else:
            text = all_text(args.runs, args.seed)
    except (ValueError, FileNotFoundError) as exc:
        ap.error(str(exc))
    if getattr(args, "out", None):
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
