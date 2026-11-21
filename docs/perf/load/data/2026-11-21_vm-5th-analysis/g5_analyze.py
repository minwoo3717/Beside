"""Computed numbers of the round-5 load test and short drills (2026-11-21 UTC), from the raw folders.

Usage (any working directory):
  py -3 docs/perf/load/data/2026-11-21_vm-5th-analysis/g5_analyze.py > docs/perf/load/data/2026-11-21_vm-5th-analysis/g5_analyze-output.txt

Reads the run folders ../2026-11-21_1022-g0d5, ../2026-11-21_1033-g3cap0, ../2026-11-21_1126-g3cap19 (round 5) and,
for comparison, ../2026-11-20_0224-g0d4 and ../2026-11-20_0256-g3c1 (round 4), plus the drill folder
docs/ops/data/2026-11-21_drills. S_busy, N, Retry-After and the Monte Carlo come from scripts/load/cap_model.py; their
outputs are the cap_model-*.txt files inside the run folders and are not recomputed here.

Clocks: VM stamps come from events.jsonl (created = finishedAt - totalMs; processing start ps = created + queuedMs).
Pod timing lines (gpu/inference-ts.log, BESIDE_REQUEST_TIMING=1) carry the Pod clock (at=). VM and Pod both run NTP;
their offset was not measured, so 'GLB leg' (Pod GET answer end -> VM finishedAt) carries that unknown offset, as in
round 4. Percentiles are linear between order statistics. Times are UTC. Standard library only."""
import collections
import datetime as dt
import json
import os
import re
import statistics as st
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
except (AttributeError, ValueError):
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.dirname(HERE) + os.sep                                       # docs/perf/load/data/
DOCS = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(HERE))))   # docs/
OPS = os.path.join(DOCS, "ops", "data", "2026-11-21_drills") + os.sep
RUN = {"g0d4": "2026-11-20_0224-g0d4", "g3c1": "2026-11-20_0256-g3c1",
       "g0d5": "2026-11-21_1022-g0d5", "g3cap0": "2026-11-21_1033-g3cap0", "g3cap19": "2026-11-21_1126-g3cap19"}
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
TIM_RE = re.compile(r"^(\S+) timing (\w+) (\S+) (\d+) client=127\.0\.0\.1:(\d+) in=(\d+) body_ms=(\S+) start_ms=(\S+) "
                    r"end_ms=(\S+) gone_ms=(\S+) out=(\d+) err=(\S+) at=(\S+)\s*$")


def p(x):
    return dt.datetime.fromisoformat(x.replace("Z", "+00:00"))


def TD(ms):
    return dt.timedelta(milliseconds=ms)


def sec(a, b):
    return (b - a).total_seconds()


def q(v, f):
    v = sorted(v)
    if not v:
        return float("nan")
    k = (len(v) - 1) * f
    lo = int(k)
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def d(v, nd=3):
    if not v:
        return "n 0"
    return (f"n {len(v)} mean {st.fmean(v):.{nd}f} p50 {q(v, .5):.{nd}f} p90 {q(v, .9):.{nd}f} "
            f"p95 {q(v, .95):.{nd}f} min {min(v):.{nd}f} max {max(v):.{nd}f}")


def events(path):
    out = []
    for line in open(path, encoding="utf-8"):
        if line.strip():
            e = json.loads(line)
            e["_fin"] = p(e["finishedAt"])
            e["_c"] = e["_fin"] - TD(e["totalMs"])
            e["_ps"] = e["_c"] + TD(e["queuedMs"]) if e.get("queuedMs") is not None else None
            out.append(e)
    return out


def load(key):
    dd = BASE + RUN[key]
    meta = json.load(open(f"{dd}/k6-summary.json", encoding="utf-8"))
    m = meta["meta"]
    t0, t1 = p(m["startedAt"]), p(m["endedAt"])
    att = events(f"{dd}/vm/events.jsonl")
    first = {e["jobId"] for e in att if e["attempt"] == 1 and t0 <= e["_c"] <= t1}
    att = sorted([e for e in att if e["jobId"] in first], key=lambda e: e["_c"])
    return dd, meta, t0, t1, att


def timing(dd):
    out = []
    num = lambda s: None if s == "-" else float(s)
    path = f"{dd}/gpu/inference-ts.log"
    if not os.path.exists(path):
        return out
    seen = set()
    for line in open(path, encoding="utf-8", errors="replace"):
        mm = TIM_RE.match(line.rstrip("\n"))
        if not mm:
            continue
        key = mm.group(0)[mm.group(0).index(" timing "):]          # the reader may print a line twice
        if key in seen:
            continue
        seen.add(key)
        out.append({"meth": mm.group(2), "path": mm.group(3), "status": int(mm.group(4)), "port": int(mm.group(5)),
                    "body": num(mm.group(7)), "start": num(mm.group(8)), "end": num(mm.group(9)), "at": p(mm.group(13))})
    return out


def pod_join(dd, comp):
    """c=1 runs only: per completed job, the first POST /infer/upload whose head arrived within [ps - 0.5 s, ps + 3 s]
    (mixed clocks; round 4 measured the VM processing start -> POST head at about 0.15 s) and the GET of its GLB."""
    tim = timing(dd)
    posts = sorted([t for t in tim if t["meth"] == "POST" and t["path"] == "/infer/upload" and t["start"] is not None],
                   key=lambda t: t["at"])
    gets = {}
    for t in tim:
        mm = re.match(r"/artifacts/(" + UUID + r")/base\.glb$", t["path"])
        if t["meth"] == "GET" and mm and t["end"] is not None:
            gets.setdefault(mm.group(1), t)
    used, rows = set(), []
    for e in sorted(comp, key=lambda e: e["_ps"]):
        po = next((k for k, t in enumerate(posts) if k not in used and -0.5 <= sec(e["_ps"], t["at"]) <= 3.0), None)
        g = gets.get(e["jobId"])
        if po is None or g is None:
            continue
        used.add(po)
        t = posts[po]
        rows.append({"front": sec(e["_ps"], t["at"]), "body": t["body"] / 1000, "work": (t["start"] - t["body"]) / 1000,
                     "extra": (t["start"] - t["body"] - e["inferMs"] - e["convertMs"]) / 1000,
                     "gap": sec(t["at"] + TD(t["start"]), g["at"]), "handoff": (g["end"] - g["start"]) / 1000,
                     "leg": sec(g["at"] + TD(g["end"]), e["_fin"]), "out": g.get("out")})
    return rows


def hr(title):
    print()
    print(f"== {title}")


# ====================================================================================================================
print(__doc__.splitlines()[0])

hr("1. per-run job times (first attempts created inside the k6 window, COMPLETED), round 5 vs round 4")
S = {}
for key in ("g0d4", "g0d5", "g3c1", "g3cap0", "g3cap19"):
    dd, meta, t0, t1, att = load(key)
    comp = [e for e in att if e["result"] == "COMPLETED"]
    proc = [e["processingMs"] / 1000 for e in comp]
    inf = [e["inferMs"] / 1000 for e in comp]
    conv = [e["convertMs"] / 1000 for e in comp]
    xf = [(e["processingMs"] - e["inferMs"] - e["convertMs"]) / 1000 for e in comp]
    h0 = q([(e["queuedMs"] - e["preprocessMs"]) / 1000 for e in comp], .5)
    glb = collections.Counter(e["glbBytes"] for e in comp)
    print(f"  {key} ({RUN[key]}): window {t0:%H:%M:%S}-{t1:%H:%M:%S}, first attempts {len(att)}, COMPLETED {len(comp)}, "
          f"results {dict(collections.Counter(e['result'] for e in att))}")
    print(f"    processingMs s: {d(proc)}")
    print(f"    inferMs p50 {q(inf, .5):.3f}  convertMs p50 {q(conv, .5):.3f}  rest (processing - infer - convert) mean "
          f"{st.fmean(xf):.3f} p50 {q(xf, .5):.3f}")
    if key.startswith("g0"):
        print(f"    h0 = median(queuedMs - preprocessMs) {h0:.3f} s;  S0 = mean processing + 2*h0 = {st.fmean(proc) + 2 * h0:.2f} s")
    print(f"    glbBytes {dict(glb)}")
    S[key] = st.fmean(proc)
    rows = pod_join(dd, comp)
    if rows:
        print(f"    Pod join: {len(rows)} of {len(comp)} jobs")
        for k, lab in (("front", "VM processing start -> POST head (mixed clocks)"),
                       ("body", "upload receive body_ms"), ("work", "Pod work start_ms - body_ms"),
                       ("extra", "  of which outside infer+convert"), ("gap", "answer -> GET head (Pod clock)"),
                       ("handoff", "GLB hand-off end_ms - start_ms"), ("leg", "GLB leg: hand-off end -> VM finishedAt (mixed)")):
            v = [r[k] for r in rows]
            print(f"      {lab:48s} p50 {q(v, .5):.3f} p90 {q(v, .9):.3f} mean {st.fmean(v):.3f} max {max(v):.3f}")
print(f"  mean processing g3cap0 - g3c1 = {S['g3cap0'] - S['g3c1']:+.3f} s, g0d5 - g0d4 = {S['g0d5'] - S['g0d4']:+.3f} s, "
      f"g3cap19 - g3cap0 = {S['g3cap19'] - S['g3cap0']:+.3f} s")

hr("2. k6 flows of the G3 runs (k6-summary.json): what a client saw")
for key in ("g3c1", "g3cap0", "g3cap19"):
    dd, meta, t0, t1, att = load(key)
    m, v = meta["meta"], meta["verdict"]
    km = meta["k6"]["metrics"]
    val = lambda n: (km.get(n) or {}).get("values", km.get(n) or {})
    fo, ft, fm, wm = val("load_flowOkRatio"), val("load_flowTimeoutRatio"), val("load_flowMs"), val("load_waitMs")
    ad = val("http_req_duration{endpoint:asset}")
    posts = m.get("measuredCreatePosts") or m.get("measuredCreates")
    print(f"  {key}: creates POST {posts} ({posts * 3600 / m['measuredArrivalWindowS']:.1f}/h)"
          f", admitted {m.get('measuredCreates')} ({m.get('measuredJobsPerHour')}/h), refused {m.get('measuredRefused')}"
          f", refused retries {m.get('measuredRefusedRetries')}, Retry-After checks {m.get('measuredRetryAfterChecks')}"
          f", queueCap {m.get('queueCap')} R {m.get('queueRetryAfterS')}")
    print(f"    flows ok {fo.get('passes')} / {fo.get('passes', 0) + fo.get('fails', 0)}; flows that hit the 600 s client"
          f" timeout {ft.get('passes')}; flowMs p50 {fm.get('med', 0) / 1000:.1f} p95 {fm.get('p(95)', 0) / 1000:.1f}"
          f" max {fm.get('max', 0) / 1000:.1f} s; waitMs (completed flows) p95 {wm.get('p(95)', 0) / 1000:.1f}"
          f" max {wm.get('max', 0) / 1000:.1f} s")
    print(f"    http 5xx {v['httpStatus']['5xxByCode']}, 4xx {v['httpStatus']['4xx']}, status0 {v['httpStatus']['0']},"
          f" dropped {v['droppedIterations']}, unfinishedFlows {v['unfinishedFlows']}, failed thresholds"
          f" {[f['metric'] + ' ' + f['threshold'] for f in v['failedThresholds']]}")
    print(f"    asset (k6 Pod <-> VM) n {ad.get('count')} p50 {ad.get('med', 0) / 1000:.2f} p90 {ad.get('p(90)', 0) / 1000:.2f}"
          f" p95 {ad.get('p(95)', 0) / 1000:.2f} p99 {ad.get('p(99)', 0) / 1000:.2f} max {ad.get('max', 0) / 1000:.2f} s")

hr("3. the cap-0 run's jobs after the run (events of the next backend, which carries the earlier finishes too)")
dd0, meta0, t0, t1, _ = load("g3cap0")
later = events(BASE + RUN["g3cap19"] + "/vm/events.jsonl")
mine = [e for e in later if e["attempt"] == 1 and t0 <= e["_c"] <= t1]
res = collections.Counter(e["result"] for e in mine)
print(f"  created in the g3cap0 window {len(mine)}: {dict(res)}")
ie = [e for e in mine if e["result"] == "FAILED:INTERNAL_ERROR"]
print(f"  INTERNAL_ERROR (closed by the W7 backend restart): PENDING {sum(1 for e in ie if e['processingMs'] is None)}, "
      f"PROCESSING {sum(1 for e in ie if e['processingMs'] is not None)}; closed at {min(e['_fin'] for e in ie):%H:%M:%S}"
      f"-{max(e['_fin'] for e in ie):%H:%M:%S}; their wait at closing (totalMs) min {min(e['totalMs'] for e in ie) / 1000:.1f}"
      f" max {max(e['totalMs'] for e in ie) / 1000:.1f} s")
done = [e for e in mine if e["result"] == "COMPLETED"]
print(f"  COMPLETED wait created -> COMPLETED: {d([e['totalMs'] / 1000 for e in done], 1)}")
print(f"  COMPLETED later than the run end ({t1:%H:%M:%S}): {sum(1 for e in done if e['_fin'] > t1)}")

hr("4. Retry-After R = round(N * S_busy) with N 19 (W 180 s rule), half up")
for lab, s in (("round 4 g3c1 (decision)", 8.67), ("round 2 g3r165 (other Pod)", 8.81), ("round 5 g3cap0", 8.8083),
               ("round 5 g3cap19", 8.8077)):
    n = int(180 // s) - 1
    print(f"  {lab:28s} S_busy {s:.4f} s: N {n}, worst (N+1)*S {(n + 1) * s:.1f} s, R {19 * s:.2f} -> {int(19 * s + 0.5)}")
for r in (165, 167):
    print(f"  R {r} <=> S_busy in [{(r - 0.5) / 19:.3f}, {(r + 0.5) / 19:.3f}) s (N 19)")
print(f"  N stays 19 while S_busy <= {180 / 20:.2f} s")

hr("5. drills (docs/ops/data/2026-11-21_drills)")


def flows(path):
    return [json.loads(x) for x in open(path, encoding="utf-8") if x.strip()]


def probe(path):
    rows = flows(path)
    codes = collections.Counter(r["code"] for r in rows)
    return len(rows), dict(codes), max(r["ms"] for r in rows if r["ms"] is not None)


for run in ("fd16b", "fd9"):
    fl = flows(f"{OPS}client/{run}/flows.jsonl")
    fin = collections.Counter((f["final"], f.get("errorCode")) for f in fl)
    tot = [f["timings"]["totalMs"] / 1000 for f in fl if f.get("timings") and f["timings"].get("totalMs") is not None]
    pr = [f["timings"]["processingMs"] / 1000 for f in fl if f.get("timings") and f["timings"].get("processingMs")]
    n, codes, mx = probe(f"{OPS}client/{run}/probe.jsonl")
    print(f"  {run}: flows {len(fl)} {dict(fin)}; totalMs s {d(tot, 1)}; processingMs p50 {q(pr, .5):.3f} max {max(pr):.3f} s")
    print(f"    probe (public healthz, 1 s) {n}: codes {codes}, slowest {mx:.0f} ms")
    if run == "fd16b":
        slow = [(f["postAt"][11:19], f["jobId"][:8], f["final"], f.get("errorCode"), f["timings"]["totalMs"] / 1000)
                for f in fl if f.get("timings") and f["timings"].get("totalMs") and f["timings"]["totalMs"] > 20000]
        for s_ in slow:
            print(f"    total > 20 s: post {s_[0]} {s_[1]} {s_[2]} {s_[3]} {s_[4]:.1f} s")
ev = {e["jobId"]: e for e in events(f"{OPS}vm/f1b/events.jsonl")}
for j in open(f"{OPS}vm/f1b-ids.txt", encoding="utf-8").read().split():
    e = ev.get(j)
    if e:
        print(f"  f1b {j[:8]}: {e['result']} queuedMs {e['queuedMs']} processingMs {e['processingMs']} totalMs {e['totalMs']}")
hook = [x for x in open(f"{OPS}vm/drill-hook-all.log", encoding="utf-8") if x[:4] == "2026"]
fd9 = [x for x in hook if "2026-11-21T03:34:30" <= x[:19] <= "2026-11-21T03:43:51"]
kinds = collections.Counter()
for x in fd9:
    for k in ("\U0001F534", "\U0001F7E2", "\U0001F4ED", "\U0001F4E6", "✅", "⚠"):
        if '"content":"' + k in x.replace('": "', '":"'):
            kinds[k] += 1
print(f"  drill-hook during FD9 (03:34:30-03:43:51): {len(fd9)} posts; by first emoji "
      + ", ".join(f"U+{ord(k[0]):04X} {v}" for k, v in kinds.items()))
ids = collections.Counter(m for x in fd9 for m in re.findall(r"✅ 3D \S+ \S+ · \S+ `([0-9a-f]{8})`", x))
print(f"  job ids posted more than once as single messages during FD9: {sum(1 for v in ids.values() if v > 1)}")
for name in ("dr5a", "dr5b"):
    print(f"  fd5 {name} check: " + ", ".join(l.split()[3] + " " + (l.split()[4] if len(l.split()) > 4 else "-")
                                          for l in open(f"{OPS}client/fd5/check.txt", encoding="utf-8")
                                          if l[:4] == "2026" and l.split()[1][:8] in
                                          open(f"{OPS}client/fd5/marked-{name}.txt", encoding="utf-8").read()))

hr("6. downtime and Pod cost (operation records: the state log of this night, outside the repo; prices read at create)")
W1, PROD = p("2026-11-21T01:19:04Z"), p("2026-11-21T03:55:22.74Z")
print(f"  production backend down {W1:%H:%M:%S} -> {PROD:%H:%M:%S.%f}: {sec(W1, PROD):.0f} s = {sec(W1, PROD) / 3600:.3f} h"
      f" (limit 4 h, end of limit 05:19:04)")
pods = (("GPU A40 jvytgcpcgahd1t", "2026-11-21T01:15:18Z", "2026-11-21T03:28:02Z", 0.59),
        ("k6 cpu3g ho9wgig3hz2a9q", "2026-11-21T01:12:40Z", "2026-11-21T03:54:59Z", 0.16))
tot = 0.0
for name, a, b, price in pods:
    h = sec(p(a), p(b)) / 3600
    tot += h * price
    print(f"  {name}: {a[11:19]} -> {b[11:19]} = {h:.3f} h x ${price}/h = ${h * price:.2f}")
print(f"  total ${tot:.2f} (network volume billed monthly, not included)")
