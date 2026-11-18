"""Every computed number of the round-3 results doc (docs/perf/load/2026-11-19_vm-3rd.md), from the raw run folders.
Usage (repo root): python docs/perf/load/data/2026-11-19_vm-3rd-analysis/g3_analyze.py > docs/perf/load/data/2026-11-19_vm-3rd-analysis/g3_analyze-output.txt
Times are UTC. Pod log stamps come from `tail -s 0.2` (0..0.2 s late). Percentiles are linear between order statistics."""
import csv
import datetime as dt
import json
import math
import re
import statistics as st

BASE = "docs/perf/load/data/"
R3 = {"g0ka5": "2026-11-19_0134-g0ka5", "g0ka75": "2026-11-19_0152-g0ka75", "g0th8": "2026-11-19_0209-g0th8",
      "g2c2a": "2026-11-19_0226-g2c2a", "f2r1": "2026-11-19_0250-f2r1", "f1br1": "2026-11-19_0312-f1br1",
      "f1ar1": "2026-11-19_0324-f1ar1", "f1ar2": "2026-11-19_0341-f1ar2"}
R2 = {"g0r1": "2026-11-18_1851-g0r1", "g3r13": "2026-11-18_2006-g3r13", "g3r165": "2026-11-18_2033-g3r165"}
JOB_RE = re.compile(r"^(\S+) job ([0-9a-f-]{36}): (.*)$")
ACC_RE = re.compile(r'^(\S+) INFO:\s+127\.0\.0\.1:(\d+) - "(\w+) (\S+) HTTP/1\.1" (\d+)')


def p(x):
    return dt.datetime.fromisoformat(x.replace("Z", "+00:00"))


def q(v, f):
    v = sorted(v)
    if not v:
        return float("nan")
    k = (len(v) - 1) * f
    lo = int(k)
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def d(v, scale=1.0, nd=2):
    v = [x / scale for x in v]
    if not v:
        return "n 0"
    return f"n {len(v)} mean {st.fmean(v):.{nd}f} p50 {q(v, .5):.{nd}f} p95 {q(v, .95):.{nd}f} min {min(v):.{nd}f} max {max(v):.{nd}f}"


def wilson(k, n, z=1.96):
    if n == 0:
        return (float("nan"), float("nan"))
    ph = k / n
    den = 1 + z * z / n
    c = (ph + z * z / (2 * n)) / den
    h = z * math.sqrt(ph * (1 - ph) / n + z * z / (4 * n * n)) / den
    return (c - h, c + h)


def load(run):
    dd = BASE + run
    meta = json.load(open(f"{dd}/k6-summary.json", encoding="utf-8"))
    m = meta["meta"]
    t0, t1 = p(m["startedAt"]), p(m["endedAt"])
    att = []
    for line in open(f"{dd}/vm/events.jsonl", encoding="utf-8"):
        if line.strip():
            e = json.loads(line)
            e["_fin"] = p(e["finishedAt"])
            e["_c"] = e["_fin"] - dt.timedelta(milliseconds=e["totalMs"])
            att.append(e)
    first = {e["jobId"] for e in att if e["attempt"] == 1 and t0 <= e["_c"] <= t1}
    att = sorted([e for e in att if e["jobId"] in first], key=lambda e: e["_c"])
    return dd, meta, t0, t1, att


def final(att):
    last = {}
    for e in att:
        if e["jobId"] not in last or e["attempt"] > last[e["jobId"]]["attempt"]:
            last[e["jobId"]] = e
    return last


def podlog(dd):
    jobs, acc = [], []
    for line in open(f"{dd}/gpu/inference-ts.log", encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        mm = JOB_RE.match(line)
        if mm:
            jobs.append((p(mm.group(1)), mm.group(2), mm.group(3)))
            continue
        mm = ACC_RE.match(line)
        if mm:
            acc.append((p(mm.group(1)), int(mm.group(2)), mm.group(3), mm.group(4), int(mm.group(5))))
    return jobs, acc


def xfer(e):
    return e["processingMs"] - (e.get("inferMs") or 0) - (e.get("convertMs") or 0)


def reuse(dd, comp):
    """Connection of each job = client port of its GET /artifacts/<job>/base.glb in the pod access log."""
    _, acc = podlog(dd)
    port_of = {}
    for t, port, meth, path, code in acc:
        mm = re.match(r"/artifacts/([0-9a-f-]{36})/base\.glb", path)
        if meth == "GET" and mm:
            port_of.setdefault(mm.group(1), port)
    seen, new, reused = set(), [], []
    for e in sorted(comp, key=lambda e: e["_c"] + dt.timedelta(milliseconds=e["queuedMs"])):
        pt = port_of.get(e["jobId"])
        if pt is None:
            continue
        (reused if pt in seen else new).append(xfer(e))
        seen.add(pt)
    return len(new), len(reused), len(seen), new, reused


def series(dd, name, t0, t1):
    out = []
    for row in csv.DictReader(open(f"{dd}/prometheus/{name}.csv", encoding="utf-8")):
        t = p(row["time"])
        if t0 < t <= t1:
            out.append(float(row["value"]))
    return out


def sysrows(dd, t0, t1):
    rows = []
    for line in open(f"{dd}/gpu/sys.txt", encoding="utf-8"):
        f = line.split()
        if len(f) >= 5 and f[0][0].isdigit():
            t = p(f[0])
            if t0 <= t <= t1:
                rows.append((t, float(f[1]), float(f[2]), int(f[3]), int(f[4])))
    return rows


def gpurows(dd, t0, t1):
    rows = []
    for line in open(f"{dd}/gpu/gpu.csv", encoding="utf-8").read().splitlines()[1:]:
        r = line.split(", ")
        if len(r) < 3:
            continue
        t = dt.datetime.strptime(r[0], "%Y/%m/%d %H:%M:%S.%f").replace(tzinfo=dt.timezone.utc)
        if t0 <= t <= t1:
            rows.append((t, float(r[1].split()[0]), float(r[2].split()[0])))
    return rows


def infer_intervals(dd, t0, t1):
    """[predict start, predict end] per completed job from the pod's 'infer N ms, convert M ms' line (stamped after convert)."""
    jobs, _ = podlog(dd)
    out = []
    for t, jid, msg in jobs:
        mm = re.match(r"infer (\d+) ms, convert (\d+) ms", msg)
        if mm and t0 <= t <= t1 + dt.timedelta(seconds=180):
            inf, conv = int(mm.group(1)), int(mm.group(2))
            end = t - dt.timedelta(milliseconds=conv)
            out.append((end - dt.timedelta(milliseconds=inf), end, jid))
    return sorted(out)


print("# round-3 analysis (g3_analyze.py)")
print("\n== 0. runs: jobs created in the k6 window, attempts, final results")
for k, run in R3.items():
    dd, meta, t0, t1, att = load(run)
    fin = final(att)
    res = {}
    for e in fin.values():
        res[e["result"]] = res.get(e["result"], 0) + 1
    ares = {}
    for e in att:
        ares[e["result"]] = ares.get(e["result"], 0) + 1
    print(f"  {k}: window {t0:%H:%M:%S}-{t1:%H:%M:%S} ({(t1 - t0).total_seconds():.0f} s), jobs {len(fin)}, attempts {len(att)}, "
          f"final {res}, attempts {ares}, k6 rtt healthz p50 {meta['meta'].get('rttBaseMsMeasured')} ms")

print("\n== 1. G0: server values (ms -> s). 'clean' = jobs not touched by an inference-service DOWN (queuedMs < 10 s and processingMs < 20 s)")
g0 = {}
for k, run in [("g0r1", R2["g0r1"]), ("g0ka5", R3["g0ka5"]), ("g0ka75", R3["g0ka75"]), ("g0th8", R3["g0th8"])]:
    dd, meta, t0, t1, att = load(run)
    comp = [e for e in att if e["result"] == "COMPLETED"]
    hit = [e for e in comp if e["queuedMs"] >= 10000 or e["processingMs"] >= 20000]
    clean = [e for e in comp if e not in hit]
    g0[k] = (dd, t0, t1, comp, clean)
    print(f"  {k}: completed {len(comp)}, hit by DOWN {len(hit)} " +
          ", ".join(f"{e['jobId'][:8]} created {e['_c']:%H:%M:%S} queued {e['queuedMs'] / 1000:.1f} s processing {e['processingMs'] / 1000:.1f} s" for e in hit))
    for name, js in (("all", comp), ("clean", clean)):
        print(f"    [{name}] processing {d([e['processingMs'] for e in js], 1000)}")
        print(f"    [{name}] infer      {d([e['inferMs'] for e in js], 1000)}")
        print(f"    [{name}] convert    {d([e['convertMs'] for e in js], 1000)}")
        print(f"    [{name}] transfer   {d([xfer(e) for e in js], 1000)}")
    h = [e["queuedMs"] - (e.get("preprocessMs") or 0) for e in clean]
    print(f"    [clean] queued {d([e['queuedMs'] for e in clean], 1000)}; h = queued - preprocess {d(h, 1000, 3)}")
    s0 = st.fmean([e["processingMs"] for e in clean]) / 1000 + 2 * q(h, .5) / 1000
    print(f"    [clean] S0 = mean processing + 2 x median h = {s0:.2f} s")
    n_new, n_re, n_ports, new, reused = reuse(dd, comp)
    print(f"    connections (port of GET base.glb), all jobs: new {n_new}, reused {n_re}, distinct ports {n_ports}")
    n_new, n_re, n_ports, new, reused = reuse(dd, clean)
    print(f"    connections, clean jobs: new {n_new}, reused {n_re}; transfer mean "
          f"new {st.fmean(new) / 1000 if new else float('nan'):.2f} s, reused {st.fmean(reused) / 1000 if reused else float('nan'):.2f} s")
    k6m = meta["k6"]["metrics"]
    for mname in ("load_waitMs", "load_flowMs"):
        v = k6m[mname]["values"]
        print(f"    k6 {mname}: med {v['med'] / 1000:.2f} s p95 {v['p(95)'] / 1000:.2f} s max {v['max'] / 1000:.2f} s (count {v['count']})")
a, b = g0["g0ka5"][4], g0["g0ka75"][4]
for f_, name in ((lambda e: e["processingMs"], "processing"), (xfer, "transfer"), (lambda e: e["inferMs"], "infer")):
    ma, mb = q([f_(e) for e in a], .5), q([f_(e) for e in b], .5)
    print(f"  ka5 -> ka75 clean median {name}: {ma / 1000:.3f} -> {mb / 1000:.3f} s (diff {(mb - ma) / 1000:+.3f} s, {(mb - ma) / ma * 100:+.1f} %)")
a, b = g0["g0ka75"][4], g0["g0th8"][4]
for f_, name in ((lambda e: e["inferMs"], "infer"), (lambda e: e["convertMs"], "convert"), (lambda e: e["processingMs"], "processing")):
    va, vb = [f_(e) for e in a], [f_(e) for e in b]
    print(f"  ka75 -> th8 clean {name}: median {q(va, .5) / 1000:.3f} -> {q(vb, .5) / 1000:.3f} s, mean {st.fmean(va) / 1000:.3f} -> {st.fmean(vb) / 1000:.3f} s")

print("\n== 2. pod CPU: cgroup v1 quota 7.65 cores per 100 ms period; sys.txt 5 s rows in each k6 window (throttle counters are deltas)")
print("   nr_throttled = 100 ms periods in which the group hit its quota; nr_throttled / (window / 0.1 s) is the wall-clock upper bound of throttling.")
print("   throttled_ms (cgroup v1 throttled_time) is summed over the per-CPU run queues, so it is NOT wall-clock time (one 5 s row can hold > 5,000 ms).")
for k in ("g0ka5", "g0ka75", "g0th8", "g2c2a", "f2r1", "f1br1"):
    dd, meta, t0, t1, att = load(R3[k])
    rows = sysrows(dd, t0, t1)
    ints = infer_intervals(dd, t0, t1)
    inside = [r for r in rows if any(s <= r[0] - dt.timedelta(seconds=2.5) <= e for s, e, _ in ints)]
    thr_rows = sum(1 for r in rows if r[3] > 0)
    span = (rows[-1][0] - rows[0][0]).total_seconds() + 5
    nthr = sum(r[3] for r in rows)
    print(f"  {k}: rows {len(rows)}, rows with throttling {thr_rows}, throttled periods {nthr} of {span / 0.1:.0f} ({nthr / (span / 0.1) * 100:.2f} %), "
          f"CPU-summed throttled_ms {sum(r[4] for r in rows)} ({sum(r[4] for r in rows) / max(nthr, 1) / 1000:.2f} s per throttled period), "
          f"container cores max {max(r[2] for r in rows):.2f}, mean in rows whose 5 s centre is inside a predict {st.fmean([r[2] for r in inside]) if inside else float('nan'):.2f} "
          f"(n {len(inside)}), host load1 {min(r[1] for r in rows):.1f}-{max(r[1] for r in rows):.1f}")

print("\n== 3. G2 (c=2) vs round-2 c=1 overload")
dd, meta, t0, t1, att = load(R3["g2c2a"])
comp = [e for e in att if e["result"] == "COMPLETED"]
arr_s = float(meta["meta"]["measuredArrivalWindowS"])   # k6 creates jobs for DURATION_S, then waits for the flows to end
print(f"  jobs {len(comp)}, k6 meta measuredJobsPerHour {meta['meta'].get('measuredJobsPerHour')} (arrival window {meta['meta'].get('measuredArrivalWindowS')} s), requested {meta['meta']['prediction']['jobsPerHour']}")
for name, f_ in (("processing", lambda e: e["processingMs"]), ("infer", lambda e: e["inferMs"]), ("convert", lambda e: e["convertMs"]),
                 ("transfer+lock wait", xfer), ("queued", lambda e: e["queuedMs"]), ("total", lambda e: e["totalMs"])):
    print(f"  {name}: {d([f_(e) for e in comp], 1000)}")
J = []
for e in comp:
    c = (e["_c"] - t0).total_seconds()
    J.append({"c": c, "req": c + (e.get("preprocessMs") or 0) / 1000, "ps": c + e["queuedMs"] / 1000,
              "pe": c + (e["queuedMs"] + e["processingMs"]) / 1000, "fin": (e["_fin"] - t0).total_seconds()})
# slot-queue (requested a slot, not yet processing) and jobs in system, 1 s grid
end = max(j["fin"] for j in J)
grid = [i * 1.0 for i in range(int(end) + 1)]
wq = [sum(1 for j in J if j["req"] <= t < j["ps"]) for t in grid]
ns = [sum(1 for j in J if j["c"] <= t < j["fin"]) for t in grid]
busy_slots = [sum(1 for j in J if j["ps"] <= t < j["pe"]) for t in grid]
ev_sys = sorted([(j["c"], 1) for j in J] + [(j["fin"], -1) for j in J], key=lambda x: (x[0], x[1]))
cur_n, max_n = 0, 0
for _, dlt in ev_sys:
    cur_n += dlt
    max_n = max(max_n, cur_n)
print(f"  max jobs in system {max(ns)} on the 1 s grid, {max_n} exact (event sweep); max waiting for a slot {max(wq)}, max processing at once {max(busy_slots)}")
# longest stretch with somebody waiting for a slot
best, cur = (0, 0), None
for t, w in zip(grid, wq):
    if w > 0 and cur is None:
        cur = t
    if w == 0 and cur is not None:
        if t - cur > best[1] - best[0]:
            best = (cur, t)
        cur = None
if cur is not None and grid[-1] - cur > best[1] - best[0]:
    best = (cur, grid[-1])
ta, tb = best
dep = [j for j in J if ta <= j["pe"] <= tb]
print(f"  saturated stretch (someone waits for a slot) {ta:.0f}-{tb:.0f} s after start ({tb - ta:.0f} s): {len(dep)} processing ends -> "
      f"{len(dep) / (tb - ta) * 3600:.0f} jobs/h; share of the stretch with 2 jobs processing {sum(1 for t, bsy in zip(grid, busy_slots) if ta <= t < tb and bsy >= 2) / (tb - ta) * 100:.1f} %")
starts = sorted(j["ps"] for j in J if ta <= j["ps"] <= tb)
gaps = [b_ - a_ for a_, b_ in zip(starts, starts[1:])]
print(f"  processing start-to-start inside the stretch: mean {st.fmean(gaps):.2f} s (x2 slots -> per-slot cycle {2 * st.fmean(gaps):.2f} s), p50 {q(gaps, .5):.2f} s")
# least squares slope of jobs in system over the arrival window
xs = [t for t in grid if t <= arr_s]
ys = ns[:len(xs)]
mx, my = st.fmean(xs), st.fmean(ys)
slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
print(f"  jobs in system slope over the arrival window: {slope * 60:.2f} /min; measured arrivals {len(J) / arr_s * 3600:.0f}/h -> capacity = arrivals - slope = {len(J) / arr_s * 3600 - slope * 3600:.0f}/h")
half = [t for t in xs if t >= arr_s / 2]
ys2 = ns[int(half[0]):int(half[0]) + len(half)]
mx2, my2 = st.fmean(half), st.fmean(ys2)
slope2 = sum((x - mx2) * (y - my2) for x, y in zip(half, ys2)) / sum((x - mx2) ** 2 for x in half)
arr2 = sum(1 for j in J if j["c"] >= arr_s / 2) / (arr_s / 2) * 3600
print(f"  second half only: slope {slope2 * 60:.2f} /min, arrivals {arr2:.0f}/h -> capacity {arr2 - slope2 * 3600:.0f}/h")
ints = infer_intervals(dd, t0, t1)
back = [(b_[0] - a_[1]).total_seconds() for a_, b_ in zip(ints, ints[1:])]
glued = sum(1 for g in back if abs(g) < 0.25)
print(f"  pod predicts {len(ints)}; next predict started within 0.25 s of the previous predict's end (waited for MODEL_LOCK; stamps 0..0.2 s late) "
      f"{glued} of {len(back)} ({glued / len(back) * 100:.0f} %); gaps < -0.25 s (would be real overlap) {sum(1 for g in back if g < -0.25)}, min gap {min(back):.2f} s")
lock_busy = sum((e_ - s_).total_seconds() for s_, e_, _ in ints if ta <= (s_ - t0).total_seconds() <= tb)
print(f"  MODEL_LOCK busy share in the stretch {lock_busy / (tb - ta) * 100:.1f} %")
gr = gpurows(dd, t0, t1)
print(f"  GPU util mean {st.fmean([r[1] for r in gr]):.1f} % (n {len(gr)}), memory max {max(r[2] for r in gr):.0f} MiB")
k6m = meta["k6"]["metrics"]
v = k6m["http_req_duration{endpoint:asset}"]["values"] if "http_req_duration{endpoint:asset}" in k6m else None
print(f"  k6 asset download: {v}")
for k in ("g3r13", "g3r165"):
    print(f"  round-2 {k} c=1 S_busy: see 2026-11-18_vm-2nd-analysis/trace-{k}.json busy_start_to_start_ms")
    tr = json.load(open(f"{BASE}2026-11-18_vm-2nd-analysis/trace-{k}.json", encoding="utf-8"))
    bs = tr["busy_start_to_start_ms"]["mean"] / 1000
    print(f"    mean {bs:.2f} s -> {3600 / bs:.0f} jobs/h")
dd2, _, t02, t12, att2 = load(R2["g3r165"])
c2 = [e for e in att2 if e["result"] == "COMPLETED"]
n_new, n_re, n_ports, _, _ = reuse(dd2, c2)
print(f"  round-2 g3r165 connections: new {n_new} reused {n_re}")
n_new, n_re, n_ports, _, _ = reuse(dd, comp)
print(f"  g2c2a connections: new {n_new} reused {n_re} ports {n_ports}")
sc, pc = series(dd, "system_cpu", t0, t1), series(dd, "process_cpu", t0, t1)
print(f"  VM system_cpu mean {st.fmean(sc) * 100:.1f} % max {max(sc) * 100:.1f} %, JVM process_cpu mean {st.fmean(pc) * 100:.1f} %; "
      f"VM core-s/job {st.fmean(sc) * 2 * (t1 - t0).total_seconds() / len(comp):.2f}")

print("\n== 4. F2-real: kill -9 of the inference service, restart after 90 s")
dd, meta, t0, t1, att = load(R3["f2r1"])
tl = open(f"{dd}/gpu/f2-timeline.txt", encoding="utf-8").read().splitlines()
kill = p(tl[1].split()[0]); restart = p(tl[3].split()[0]); healthy = p(tl[5].split()[0])
ev = open(f"{dd}/vm/inference-events.log", encoding="utf-8").read().splitlines()
def evt(pat):
    for line in ev:
        if pat in line:
            return p(line.split()[2])
down, loading, up = evt("UP -> DOWN"), evt("DOWN -> LOADING"), evt("LOADING -> UP")
print(f"  kill {kill:%H:%M:%S.%f}"[:-3] + f", backend DOWN +{(down - kill).total_seconds():.2f} s, restart +{(restart - kill).total_seconds():.1f} s, "
      f"LOADING +{(loading - kill).total_seconds():.1f} s, pod healthz ok +{(healthy - kill).total_seconds():.1f} s, backend UP +{(up - kill).total_seconds():.1f} s")
fin = final(att)
res = {}
for e in fin.values():
    res[e["result"]] = res.get(e["result"], 0) + 1
failed_att = [e for e in att if e["result"] != "COMPLETED"]
for e in failed_att:
    later = [x for x in att if x["jobId"] == e["jobId"] and x["attempt"] > e["attempt"]]
    print(f"  failed attempt {e['jobId'][:8]} #{e['attempt']} {e['result']} at {e['_fin']:%H:%M:%S.%f}"[:-3] +
          f" (+{(e['_fin'] - kill).total_seconds():.2f} s after kill); later attempts: " +
          ", ".join(f"#{x['attempt']} {x['result']} created +{(x['_c'] - kill).total_seconds():.1f} s queued {x['queuedMs'] / 1000:.1f} s" for x in later))
print(f"  final results {res}")
during = [e for e in att if e["_c"] <= up and e["_fin"] >= kill and e["result"] == "COMPLETED"]
print(f"  completed attempts that were in the system between kill and UP: {len(during)}, their queued {d([e['queuedMs'] for e in during], 1000, 1)}")
after = sorted([e for e in att if e["_fin"] > up and e["result"] == "COMPLETED"], key=lambda e: e["_fin"])
print(f"  first completion after UP: +{(after[0]['_fin'] - kill).total_seconds():.1f} s after kill ({(after[0]['_fin'] - up).total_seconds():.1f} s after UP)")
# backlog drained: first time after UP when nobody waits for the slot
J = [{"req": e["_c"] + dt.timedelta(milliseconds=e.get("preprocessMs") or 0), "ps": e["_c"] + dt.timedelta(milliseconds=e["queuedMs"])} for e in att if e["result"] == "COMPLETED"]
t = up
while any(j["req"] <= t < j["ps"] for j in J):
    t += dt.timedelta(seconds=1)
print(f"  slot queue empty again at +{(t - kill).total_seconds():.0f} s after kill ({(t - up).total_seconds():.0f} s after UP); "
      f"max queued {max(e['queuedMs'] for e in att if e['result'] == 'COMPLETED') / 1000:.1f} s")
pre = [e for e in att if e["result"] == "COMPLETED" and e["_fin"] < kill]
post = [e for e in att if e["result"] == "COMPLETED" and e["_c"] > up]
print(f"  processing before kill {d([e['processingMs'] for e in pre], 1000)}; after UP (created after) {d([e['processingMs'] for e in post], 1000)}")
jobs_, acc = podlog(dd)
ml = [l for l in open(f"{dd}/gpu/inference-ts.log", encoding="utf-8", errors="replace") if "model loaded in" in l]
print(f"  pod 'model loaded' lines in window: {[l.split(' ', 1)[1].strip()[:40] for l in ml]}")
k6m = meta["k6"]["metrics"]
print(f"  k6 flowOkRatio {k6m['load_flowOkRatio']['values']}, flowTimeoutRatio {k6m['load_flowTimeoutRatio']['values']}")

print("\n== 5. F1b-real: inference.timeout-ms = 5000 with the real model")
dd, meta, t0, t1, att = load(R3["f1br1"])
ares = {}
for e in att:
    ares[e["result"]] = ares.get(e["result"], 0) + 1
print(f"  jobs {len(final(att))}, attempts {len(att)} {ares}; processing {d([e['processingMs'] for e in att], 1000, 3)}; "
      f"queued {d([e['queuedMs'] for e in att], 1000)}; queued - preprocess {d([e['queuedMs'] - (e.get('preprocessMs') or 0) for e in att], 1000)}")
ids = {e["jobId"] for e in att}
jobs_, acc = podlog(dd)
done = [x for x in jobs_ if x[1] in ids and x[2].startswith("infer ")]
crash = [x for x in jobs_ if x[1] in ids and x[2] == "conversion crashed"]
dele = [x for x in acc if x[2] == "DELETE" and any(i in x[3] for i in ids)]
posts = [x for x in acc if x[2] == "POST" and x[3] == "/infer/upload"]
print(f"  pod: predict+convert finished {len(done)}, conversion crashed {len(crash)}, DELETE {len(dele)} (status {sorted({x[4] for x in dele})}), "
      f"POST /infer/upload answered {len([x for x in posts if t0 <= x[0] <= t1 + dt.timedelta(seconds=120)])}")
lines = open(f"{dd}/gpu/inference-ts.log", encoding="utf-8", errors="replace").read().splitlines()
crash_where = {}   # jobId -> [(time, missing path tail)]
for i, line in enumerate(lines):
    mm = JOB_RE.match(line)
    if mm and mm.group(3) == "conversion crashed" and mm.group(2) in ids:
        err = next((lines[k] for k in range(i + 1, min(i + 20, len(lines))) if "FileNotFoundError" in lines[k]), "")
        crash_where.setdefault(mm.group(2), []).append((p(mm.group(1)), err.rsplit(mm.group(2) + "/", 1)[-1].rstrip("'")))
per_job = {}
for j in ids:
    per_job[len(crash_where.get(j, []))] = per_job.get(len(crash_where.get(j, [])), 0) + 1
where = {}
for v in crash_where.values():
    for _, w in v:
        where[w] = where.get(w, 0) + 1
print(f"  jobs by number of crash lines {per_job}; missing file of the crashes {where}")
for x in dele:
    if x[4] != 204:
        jid = re.search(r"([0-9a-f-]{36})", x[3]).group(1)
        print(f"  DELETE {x[4]} at {x[0]:%H:%M:%S.%f}"[:-3] + f" for {jid[:8]}; its crashes {[(f'{t:%H:%M:%S.%f}'[:-3], w) for t, w in crash_where.get(jid, [])]}")
left = open(f"{dd}/gpu/f1b-leftovers.txt", encoding="utf-8").read()
for jid in dict.fromkeys(re.findall(r"== /workspace/beside-work/([0-9a-f-]{36})/", left)):
    dels = [x[0] for x in dele if jid in x[3]]
    glb = re.search(jid + r"/\n(\S+) 1326748 base\.glb", left)
    cr = crash_where.get(jid, [])
    after = [(f"+{(t - dels[-1]).total_seconds():.2f} s", w) for t, w in cr if t > dels[-1]]
    print(f"  leftover {jid[:8]}: DELETEs at {[f'{x:%H:%M:%S.%f}'[:-3] for x in dels]}, crashes after the last DELETE {after}, "
          f"base.glb mtime {glb.group(1)[11:19] if glb else '?'} (volume clock, whole seconds)")
gr = gpurows(dd, t0, t1 + dt.timedelta(seconds=60))
print(f"  GPU util mean {st.fmean([r[1] for r in gr]):.1f} %, memory {min(r[2] for r in gr):.0f}-{max(r[2] for r in gr):.0f} MiB; f1b-check: "
      + " | ".join(open(f"{dd}/gpu/f1b-check.txt", encoding="utf-8").read().split("\n")[:8]))

print("\n== 6. F1a: passthrough stub on the same pod")
for k in ("f1ar1", "f1ar2"):
    dd, meta, t0, t1, att = load(R3[k])
    ok = [e for e in att if e["result"] == "COMPLETED"]
    bad = [e for e in att if e["result"] != "COMPLETED"]
    fin = final(att)
    res = {}
    for e in fin.values():
        res[e["result"]] = res.get(e["result"], 0) + 1
    lo, hi = wilson(len(bad), len(att))
    print(f"  {k}: jobs {len(fin)}, attempts {len(att)}, failed attempts {len(bad)} ({len(bad) / len(att):.3f}, 95 % {lo:.3f}-{hi:.3f}) "
          f"{sorted({e['result'] for e in bad})}, final {res}")
    print(f"    completed: inferMs {sorted({e['inferMs'] for e in ok})}, glbBytes {sorted({e['glbBytes'] for e in ok})}, processing {d([e['processingMs'] for e in ok], 1000)}, "
          f"transfer {d([xfer(e) for e in ok], 1000)}")
    twice = [j for j, e in fin.items() if e["result"] != "COMPLETED"]
    for j in twice:
        print(f"    job {j[:8]} final {fin[j]['result']} after {fin[j]['attempt']} attempts")
    retried_ok = [e for e in ok if e["attempt"] > 1]
    print(f"    retried attempts completed {len(retried_ok)} of {len([e for e in att if e['attempt'] > 1])}")
    k6m = meta["k6"]["metrics"]
    print(f"    k6 flowOkRatio {k6m['load_flowOkRatio']['values']}")

print("\n== 7. inference service health on the VM (backend log), whole session")
seen = set()
for k in ("g0th8", "g2c2a", "f2r1", "f1br1", "f1ar2"):
    dd = BASE + R3[k]
    for line in open(f"{dd}/vm/inference-events.log", encoding="utf-8"):
        if "InferenceHealthMonitor" in line and line not in seen:
            seen.add(line)
rows = sorted(seen, key=lambda l: l.split()[2])
prev = None
for line in rows:
    t = p(line.split()[2])
    msg = line.split("inference service ")[1].strip()
    extra = f" (+{(t - prev).total_seconds():.1f} s)" if prev and ("-> UP" in msg or "-> LOADING" in msg) else ""
    print(f"  {t:%H:%M:%S.%f}"[:-3] + f" {msg}{extra}")
    if "-> DOWN" in msg:
        prev = t
    elif "-> UP" in msg:
        prev = None

print("\n== 8. VM CPU per job (Prometheus, window (startedAt, endedAt], 2 vCPU)")
for k in ("g0ka5", "g0ka75", "g0th8", "g2c2a", "f2r1"):
    dd, meta, t0, t1, att = load(R3[k])
    comp = [e for e in att if e["result"] == "COMPLETED"]
    sc, pc = series(dd, "system_cpu", t0, t1), series(dd, "process_cpu", t0, t1)
    w = (t1 - t0).total_seconds()
    print(f"  {k}: completed {len(comp)}, system_cpu mean {st.fmean(sc) * 100:.1f} % max {max(sc) * 100:.1f} % -> VM {st.fmean(sc) * 2 * w / len(comp):.2f} core-s/job; "
          f"JVM {st.fmean(pc) * 2 * w / len(comp):.2f} core-s/job")

print("\n== 9. pod volume write probe (1 MB write + fsync, 5 s) and GPU util per run")
for k in R3:
    dd, meta, t0, t1, att = load(R3[k])
    vol = []
    for line in open(f"{dd}/gpu/ioprobe.txt", encoding="utf-8"):
        mm = re.match(r"(\S+) volume_ms=(\d+) local_ms=(\d+)", line)
        if mm and t0 <= p(mm.group(1)) <= t1:
            vol.append(int(mm.group(2)))
    gr = gpurows(dd, t0, t1)
    print(f"  {k}: volume_ms {d(vol, 1, 0)}; GPU util mean {st.fmean([r[1] for r in gr]):.1f} % max {max(r[1] for r in gr):.0f} %, memory max {max(r[2] for r in gr):.0f} MiB")

print("\n== 10. where the slot time goes: VM processing start -> pod predict start (upload, + MODEL_LOCK wait) and pod predict end -> VM processing end")
print("   (VM stamp = created + queuedMs; pod predict start/end from the 'infer N ms, convert M ms' line, 0..0.2 s late; VM-pod clock offset not measured)")
for k in ("g0ka75", "g0th8", "g2c2a"):
    dd, meta, t0, t1, att = load(R3[k])
    comp = {e["jobId"]: e for e in att if e["result"] == "COMPLETED" and e["processingMs"] < 20000 and (k == "g2c2a" or e["queuedMs"] < 10000)}   # G0: not hit by a DOWN
    ints = infer_intervals(dd, t0, t1)
    prev_end = None
    rows = {"waited": [], "free": []}
    for s_, e_, jid in ints:
        waited = prev_end is not None and abs((s_ - prev_end).total_seconds()) < 0.25
        prev_end = e_
        e = comp.get(jid)
        if not e:
            continue
        ps = e["_c"] + dt.timedelta(milliseconds=e["queuedMs"])
        pe = ps + dt.timedelta(milliseconds=e["processingMs"])
        up = (s_ - ps).total_seconds()
        post = (pe - e_).total_seconds() - e["convertMs"] / 1000
        rows["waited" if waited else "free"].append((up, post))
    for name, v in rows.items():
        if v:
            print(f"  {k} [{name} for the lock]: n {len(v)}; to predict start {d([x[0] for x in v])} s; after convert to processing end {d([x[1] for x in v])} s")

print("\n== 11. API status codes on the VM (Prometheus http_rps_by_status series in the window) and k6 unexpected responses")
for k in R3:
    dd, meta, t0, t1, att = load(R3[k])
    stats = {}
    for row in csv.DictReader(open(f"{dd}/prometheus/http_rps_by_status.csv", encoding="utf-8")):
        if t0 < p(row["time"]) <= t1:
            stats.setdefault(row["series"], []).append(float(row["value"]))
    r5 = [float(row["value"]) for row in csv.DictReader(open(f"{dd}/prometheus/http_5xx_ratio.csv", encoding="utf-8")) if t0 < p(row["time"]) <= t1]
    print(f"  {k}: statuses {sorted(stats)}; 5xx ratio points {len(r5)} max {max(r5) if r5 else 0}; "
          f"k6 unexpected {meta['verdict']['unexpectedCount']}, checks failed {meta['verdict']['checksFailed']}, dropped {meta['verdict']['droppedIterations']}")

print("\n== 12. slow model jobs (round-2 rule: inferMs > 6000 or convertMs > 2000), real-model runs")
for k in ("g0ka5", "g0ka75", "g0th8", "g2c2a", "f2r1"):
    dd, meta, t0, t1, att = load(R3[k])
    comp = [e for e in att if e["result"] == "COMPLETED"]
    slow = [e for e in comp if e["inferMs"] > 6000 or e["convertMs"] > 2000]
    print(f"  {k}: {len(slow)} of {len(comp)} " + ", ".join(f"{e['jobId'][:8]} infer {e['inferMs']} convert {e['convertMs']}" for e in slow))

print("\n== 13. G2r: what the post-convert window (pod predict end + convert -> VM processing end) of each job overlaps")
dd, meta, t0, t1, att = load(R3["g2c2a"])
comp = {e["jobId"]: e for e in att if e["result"] == "COMPLETED"}
ints = infer_intervals(dd, t0, t1)
win = {}
for s_, e_, jid in ints:
    e = comp.get(jid)
    if e:
        pe = e["_c"] + dt.timedelta(milliseconds=e["queuedMs"] + e["processingMs"])
        win[jid] = (e_ + dt.timedelta(milliseconds=e["convertMs"]), pe, s_, e_)
def ov(a0, a1, b0, b1):
    return max(0.0, (min(a1, b1) - max(a0, b0)).total_seconds())
prev_end = None
rows = {"waited": [], "free": []}
for s_, e_, jid in ints:
    waited = prev_end is not None and abs((s_ - prev_end).total_seconds()) < 0.25
    prev_end = e_
    if jid not in win:
        continue
    w0, w1 = win[jid][0], win[jid][1]
    L = (w1 - w0).total_seconds()
    if L <= 0:
        continue
    pred = sum(ov(w0, w1, s2, e2) for s2, e2, j2 in ints if j2 != jid)
    conv = sum(ov(w0, w1, win[j2][3], win[j2][0]) for j2 in win if j2 != jid)
    other_post = sum(ov(w0, w1, win[j2][0], win[j2][1]) for j2 in win if j2 != jid)
    rows["waited" if waited else "free"].append((L, pred / L, conv / L, other_post / L))
for name, v in rows.items():
    print(f"  [{name} for the lock] n {len(v)}: window {d([x[0] for x in v])} s; share overlapping another job's predict p50 {q([x[1] for x in v], .5) * 100:.0f} %, "
          f"its convert p50 {q([x[2] for x in v], .5) * 100:.0f} %, its own post-convert window (concurrent response/download) p50 {q([x[3] for x in v], .5) * 100:.0f} %; "
          f"jobs with no overlap with another predict {sum(1 for x in v if x[1] == 0)}, max predict overlap {max(x[1] for x in v) * 100:.0f} %")
