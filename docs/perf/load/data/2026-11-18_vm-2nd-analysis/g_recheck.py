"""Re-check the verifiers' new computed claims before they go into the round-2 results doc."""
import csv
import datetime as dt
import json
import random
import re
import statistics as st

BASE = "docs/perf/load/data/"
RUNS = {"g0r1": "2026-11-18_1851-g0r1", "g1r05": "2026-11-18_1908-g1r05", "g1r07": "2026-11-18_1934-g1r07",
        "g3r13": "2026-11-18_2006-g3r13", "g3r165": "2026-11-18_2033-g3r165"}


def p(x):
    return dt.datetime.fromisoformat(x.replace("Z", "+00:00"))


def q(v, f):
    v = sorted(v)
    k = (len(v) - 1) * f
    lo = int(k)
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def load(run):
    d = BASE + run
    m = json.load(open(f"{d}/k6-summary.json", encoding="utf-8"))["meta"]
    t0, t1 = p(m["startedAt"]), p(m["endedAt"])
    ev = []
    for line in open(f"{d}/vm/events.jsonl", encoding="utf-8"):
        if line.strip():
            e = json.loads(line)
            c = p(e["finishedAt"]) - dt.timedelta(milliseconds=e["totalMs"])
            if t0 <= c <= t1:
                e["_c"] = c
                ev.append(e)
    ev.sort(key=lambda e: e["_c"])
    return d, m, t0, t1, ev


print("== 1. connection reuse from pod access-log ports (job port = port of its GET /artifacts/<job>/base.glb)")
for k, run in RUNS.items():
    d, m, t0, t1, ev = load(run)
    port_of = {}
    for line in open(f"{d}/gpu/inference-ts.log", encoding="utf-8", errors="replace"):
        mm = re.search(r'127\.0\.0\.1:(\d+) - "GET /artifacts/([0-9a-f-]+)/base\.glb', line)
        if mm:
            port_of.setdefault(mm.group(2), int(mm.group(1)))
    seen, new, reused = set(), [], []
    order = sorted(ev, key=lambda e: e["_c"] + dt.timedelta(milliseconds=e["queuedMs"]))
    for e in order:
        pt = port_of.get(e["jobId"])
        xfer = e["processingMs"] - e["inferMs"] - e["convertMs"]
        if pt is None:
            continue
        (reused if pt in seen else new).append(xfer)
        seen.add(pt)
    print(f"  {k}: jobs {len(ev)} with port {len(new) + len(reused)}, distinct ports {len(seen)}, new {len(new)} reused {len(reused)}, "
          f"xfer mean new {st.fmean(new) / 1000 if new else float('nan'):.2f} s reused {st.fmean(reused) / 1000 if reused else float('nan'):.2f} s")

print("== 2. G1r rho0.7: P-K without the 14 slow jobs, replay with them normalised, conditioned Monte Carlo")
d, m, t0, t1, ev = load(RUNS["g1r07"])
H = 837.0
slow = [e for e in ev if e["inferMs"] > 6000 or e["convertMs"] > 2000]
fast = [e for e in ev if not (e["inferMs"] > 6000 or e["convertMs"] > 2000)]
lam = 250 / 3600
for name, js in (("all", ev), ("without slow", fast)):
    S = [(e["processingMs"] + H) / 1000 for e in js]
    ES, ES2 = st.fmean(S), st.fmean([s * s for s in S])
    rho = lam * ES
    print(f"  P-K {name}: mean slot {ES:.3f} cv {(ES2 - ES * ES) ** .5 / ES:.3f} rho {rho:.3f} Wq {lam * ES2 / (2 * (1 - rho)):.2f} s")


def replay(js, slot_fn):
    free, ws = 0.0, []
    for e in sorted(js, key=lambda e: e["_c"] + dt.timedelta(milliseconds=e.get("preprocessMs") or 0)):
        r = ((e["_c"] - t0).total_seconds() * 1000 + (e.get("preprocessMs") or 0)) / 1000
        s = max(r, free)
        ws.append(s - r)
        free = s + slot_fn(e)
    return st.fmean(ws)


mean_fast_slot = st.fmean([(e["processingMs"] + H) / 1000 for e in fast])
print(f"  replay as run {replay(ev, lambda e: (e['processingMs'] + H) / 1000):.2f} s; slow slots -> {mean_fast_slot:.2f} s: "
      f"{replay(ev, lambda e: mean_fast_slot if e in slow else (e['processingMs'] + H) / 1000):.2f} s")
meas = 8.087
S_all = [(e["processingMs"] + H) / 1000 for e in ev]
rng = random.Random(1)
means = []
for _ in range(2000):
    arr = sorted(rng.uniform(0, 1800) for _ in range(125))
    free, ws = 0.0, []
    for a in arr:
        s = max(a, free)
        ws.append(s - a)
        free = s + rng.choice(S_all)
    means.append(st.fmean(ws))
print(f"  conditioned on n=125: percentile of {meas} s = {sum(1 for x in means if x <= meas) / len(means) * 100:.1f}, "
      f"5-95 % {q(means, .05):.2f}-{q(means, .95):.2f} s")
# random order of the actual slot values on the actual arrival times
arr = sorted(((e["_c"] - t0).total_seconds() * 1000 + (e.get("preprocessMs") or 0)) / 1000 for e in ev)
rng = random.Random(2)
perm_means = []
for _ in range(2000):
    sl = S_all[:]
    rng.shuffle(sl)
    free, ws = 0.0, []
    for a, s_ in zip(arr, sl):
        s = max(a, free)
        ws.append(s - a)
        free = s + s_
    perm_means.append(st.fmean(ws))
actual = replay(ev, lambda e: (e["processingMs"] + H) / 1000)
print(f"  random slot order on actual arrivals: mean {st.fmean(perm_means):.2f} s, actual order percentile {sum(1 for x in perm_means if x <= actual) / len(perm_means) * 100:.1f}")

print("== 3. replay with mean handover instead of median")
for k, h_med, h_mean in (("g1r05", 833.7, 794.7), ("g1r07", 836.7, 762.1)):
    d, m, t0, t1, ev = load(RUNS[k])
    for name, hh in (("median", h_med), ("mean", h_mean)):
        print(f"  {k} {name} handover {hh}: replay mean {replay(ev, lambda e, hh=hh: (e['processingMs'] + hh) / 1000):.3f} s")

print("== 4. CPU per job from Prometheus (window (startedAt, endedAt])")
for k, run in RUNS.items():
    d, m, t0, t1, ev = load(run)
    def series(name):
        out = []
        for row in csv.DictReader(open(f"{d}/prometheus/{name}.csv", encoding="utf-8")):
            t = p(row["time"])
            if t0 < t <= t1:
                out.append(float(row["value"]))
        return out
    sc, pc = series("system_cpu"), series("process_cpu")
    w = (t1 - t0).total_seconds()
    print(f"  {k}: system_cpu {st.fmean(sc) * 100:.1f}% -> VM {st.fmean(sc) * 2 * w / len(ev):.2f} core-s/job; "
          f"process_cpu {st.fmean(pc) * 100:.1f}% -> JVM {st.fmean(pc) * 2 * w / len(ev):.2f} core-s/job")

print("== 5. GPU utilisation inside inference intervals (pod stamps lag 0..1 s)")
for k in ("g1r07", "g3r13", "g3r165"):
    d, m, t0, t1, ev = load(RUNS[k])
    ints = []
    for line in open(f"{d}/gpu/inference-ts.log", encoding="utf-8", errors="replace"):
        mm = re.match(r"(\S+) job \S+: infer (\d+) ms, convert (\d+) ms", line)
        if mm:
            end = p(mm.group(1))
            ints.append((end - dt.timedelta(milliseconds=int(mm.group(2)) + int(mm.group(3))), end - dt.timedelta(milliseconds=int(mm.group(3)))))
    rows = [l.split(", ") for l in open(f"{d}/gpu/gpu.csv", encoding="utf-8").read().splitlines()[1:] if l]
    samp = [(dt.datetime.strptime(r[0], "%Y/%m/%d %H:%M:%S.%f").replace(tzinfo=dt.timezone.utc), float(r[1].split()[0])) for r in rows]
    samp = [(t, u) for t, u in samp if t0 <= t <= t1]
    for lag in (0.0, 1.0):
        L = dt.timedelta(seconds=lag)
        inside = [u for t, u in samp if any(a - L <= t <= b - L for a, b in ints)]
        outside = [u for t, u in samp if not any(a - L <= t <= b - L for a, b in ints)]
        print(f"  {k} lag {lag}: inside n {len(inside)} mean {st.fmean(inside):.1f}%, outside n {len(outside)} mean {st.fmean(outside):.1f}%")

print("== 6. host loadavg (1 min) per run window")
for k, run in RUNS.items():
    d, m, t0, t1, ev = load(run)
    vals = [float(l.split()[1]) for l in open(f"{d}/gpu/loadavg.txt", encoding="utf-8") if l.strip() and t0 <= p(l.split()[0]) <= t1]
    print(f"  {k}: min {min(vals):.2f} max {max(vals):.2f} n {len(vals)}")

print("== 7. G0r upload estimate for all jobs (PROCESSING log -> pod inference start)")
d, m, t0, t1, ev = load(RUNS["g0r1"])
proc = {}
for line in open(f"{d}/vm/backend.log", encoding="utf-8", errors="replace"):
    mm = re.search(r"\| (\S+Z) .*jobId=([0-9a-f-]+) stage=PROCESSING", line)
    if mm:
        proc[mm.group(2)] = p(mm.group(1))
podstart = {}
for line in open(f"{d}/gpu/inference-ts.log", encoding="utf-8", errors="replace"):
    mm = re.match(r"(\S+) job ([0-9a-f-]+): infer (\d+) ms, convert (\d+) ms", line)
    if mm:
        podstart[mm.group(2)] = p(mm.group(1)) - dt.timedelta(milliseconds=int(mm.group(3)) + int(mm.group(4)))
ups = [(podstart[e["jobId"]] - proc[e["jobId"]]).total_seconds() for e in ev if e["jobId"] in proc and e["jobId"] in podstart]
print(f"  n {len(ups)} min {min(ups):.2f} median {q(ups, .5):.2f} max {max(ups):.2f} s (upper bound: pod stamps lag 0..1 s)")

print("== 8. g3r165 slope window details")
d, m, t0, t1, ev = load(RUNS["g3r165"])
c = [(e["_c"] - t0).total_seconds() for e in ev]
f = [(p(e["finishedAt"]) - t0).total_seconds() for e in ev]
a_in = sum(1 for x in c if 120 <= x <= 1200)
f_in = sum(1 for x in f if 120 <= x <= 1200)
n_at = lambda t: sum(1 for x in c if x <= t) - sum(1 for x in f if x <= t)
first_half = sum(1 for x in c if 120 <= x < 660)
second_half = sum(1 for x in c if 660 <= x <= 1200)
print(f"  arrivals 120-1200 s: {a_in} ({a_in / 1080 * 3600:.1f}/h), finishes {f_in} ({f_in / 1080 * 3600:.1f}/h), N(120)={n_at(120)} N(1200)={n_at(1200)}, "
      f"arrivals 120-660 {first_half} vs 660-1200 {second_half}")
# slot queue max (requested slot, not yet processing)
evs = [((e["_c"] - t0).total_seconds() + (e.get("preprocessMs") or 0) / 1000, (e["_c"] - t0).total_seconds() + e["queuedMs"] / 1000) for e in ev]
mx = 0
for t in [x for pair in evs for x in pair]:
    mx = max(mx, sum(1 for r, s in evs if r <= t < s))
print(f"  max waiting for the slot: {mx}")
