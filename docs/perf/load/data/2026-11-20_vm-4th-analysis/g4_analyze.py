"""Every computed number of the round-4 load test (2026-11-19 UTC = 2026-11-20 KST), from the raw run folders.

Usage (any working directory):
  py -3 docs/perf/load/data/2026-11-20_vm-4th-analysis/g4_analyze.py > docs/perf/load/data/2026-11-20_vm-4th-analysis/g4_analyze-output.txt

Same methods as ../2026-11-19_vm-3rd-analysis/g3_analyze.py (clean jobs, h0, S0, connection reuse, sys.txt throttling,
slot queue, saturated stretch, MODEL_LOCK) and ../2026-11-18_vm-2nd-analysis/g_trace.py (S_busy), plus the Pod's
per-request timing lines (BESIDE_REQUEST_TIMING=1; generation/inference_service/README.md, section on request timing).

Clocks: VM stamps come from events.jsonl (created = finishedAt - totalMs; processing start ps = created + queuedMs).
Pod timing lines carry their own at= (Pod clock, request head read); the prefix of every inference-ts.log line is the
Pod reader's stamp (0.2 s polling, so 0..0.2 s late). VM and Pod both run NTP; their offset was not measured, so any
difference between a VM stamp and a Pod stamp (marked 'mixed clocks' below) carries that unknown offset.
Times are UTC. Percentiles are linear between order statistics. Standard library only."""
import bisect
import csv
import datetime as dt
import json
import math
import os
import re
import statistics as st
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
except (AttributeError, ValueError):
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.dirname(HERE) + os.sep          # docs/perf/load/data/
R4 = {"g0d4": "2026-11-20_0224-g0d4", "g3c1": "2026-11-20_0256-g3c1", "g2c2t": "2026-11-20_0321-g2c2t",
      "f1br4": "2026-11-20_0344-f1br4"}
R3 = {"g0ka5": "2026-11-19_0134-g0ka5", "g0ka75": "2026-11-19_0152-g0ka75", "g0th8": "2026-11-19_0209-g0th8",
      "g2c2a": "2026-11-19_0226-g2c2a", "f1br1": "2026-11-19_0312-f1br1"}
R2 = {"g0r1": "2026-11-18_1851-g0r1", "g3r165": "2026-11-18_2033-g3r165"}
RUN = {**R2, **R3, **R4}
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
JOB_RE = re.compile(r"^(\S+) job (" + UUID + r"): (.*)$")
ACC_RE = re.compile(r'^(\S+) INFO:\s+127\.0\.0\.1:(\d+) - "(\w+) (\S+) HTTP/1\.1" (\d+)')
TIM_RE = re.compile(r"^(\S+) timing (\w+) (\S+) (\d+) client=127\.0\.0\.1:(\d+) in=(\d+) body_ms=(\S+) start_ms=(\S+) "
                    r"end_ms=(\S+) gone_ms=(\S+) out=(\d+) err=(\S+) at=(\S+)\s*$")


def p(x):
    return dt.datetime.fromisoformat(x.replace("Z", "+00:00"))


def TD(ms):
    return dt.timedelta(milliseconds=ms)


def sec(a, b):
    """b - a in seconds."""
    return (b - a).total_seconds()


def q(v, f):
    v = sorted(v)
    if not v:
        return float("nan")
    k = (len(v) - 1) * f
    lo = int(k)
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def mean(v):
    return st.fmean(v) if v else float("nan")


def d(v, scale=1.0, nd=2):
    v = [x / scale for x in v]
    if not v:
        return "n 0"
    return f"n {len(v)} mean {st.fmean(v):.{nd}f} p50 {q(v, .5):.{nd}f} p95 {q(v, .95):.{nd}f} min {min(v):.{nd}f} max {max(v):.{nd}f}"


def d9(v, nd=3):
    """median / p90 first (section 2 asks for them), then mean, min, max."""
    if not v:
        return "n 0"
    return f"n {len(v)} p50 {q(v, .5):.{nd}f} p90 {q(v, .9):.{nd}f} mean {st.fmean(v):.{nd}f} min {min(v):.{nd}f} max {max(v):.{nd}f}"


def wilson(k, n, z=1.96):
    if n == 0:
        return (float("nan"), float("nan"))
    ph = k / n
    den = 1 + z * z / n
    c = (ph + z * z / (2 * n)) / den
    h = z * math.sqrt(ph * (1 - ph) / n + z * z / (4 * n * n)) / den
    return (max(0.0, c - h), min(1.0, c + h))


def count(items):
    out = {}
    for x in items:
        out[x] = out.get(x, 0) + 1
    return dict(sorted(out.items(), key=lambda kv: str(kv[0])))


# ---------------------------------------------------------------- loaders (same as g3_analyze.py)

def load(key):
    dd = BASE + RUN[key]
    meta = json.load(open(f"{dd}/k6-summary.json", encoding="utf-8"))
    m = meta["meta"]
    t0, t1 = p(m["startedAt"]), p(m["endedAt"])
    att = []
    for line in open(f"{dd}/vm/events.jsonl", encoding="utf-8"):
        if line.strip():
            e = json.loads(line)
            e["_fin"] = p(e["finishedAt"])
            e["_c"] = e["_fin"] - TD(e["totalMs"])
            e["_ps"] = e["_c"] + TD(e["queuedMs"])
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


def timing(dd):
    out = []
    num = lambda s: None if s == "-" else float(s)
    for line in open(f"{dd}/gpu/inference-ts.log", encoding="utf-8", errors="replace"):
        mm = TIM_RE.match(line.rstrip("\n"))
        if mm:
            out.append({"stamp": p(mm.group(1)), "meth": mm.group(2), "path": mm.group(3), "status": int(mm.group(4)),
                        "port": int(mm.group(5)), "in": int(mm.group(6)), "body": num(mm.group(7)), "start": num(mm.group(8)),
                        "end": num(mm.group(9)), "gone": num(mm.group(10)), "out": int(mm.group(11)),
                        "err": None if mm.group(12) == "-" else mm.group(12), "at": p(mm.group(13))})
    return out


def xfer(e):
    return e["processingMs"] - (e.get("inferMs") or 0) - (e.get("convertMs") or 0)


def reuse(dd, comp):
    """Connection of each job = client port of its GET /artifacts/<job>/base.glb in the pod access log."""
    _, acc = podlog(dd)
    port_of = {}
    for t, port, meth, path, code in acc:
        mm = re.match(r"/artifacts/(" + UUID + r")/base\.glb", path)
        if meth == "GET" and mm:
            port_of.setdefault(mm.group(1), port)
    seen, new, reused = set(), [], []
    for e in sorted(comp, key=lambda e: e["_ps"]):
        pt = port_of.get(e["jobId"])
        if pt is None:
            continue
        (reused if pt in seen else new).append(xfer(e))
        seen.add(pt)
    return len(new), len(reused), len(seen), new, reused


def series(dd, name, t0, t1):
    out = []
    path = f"{dd}/prometheus/{name}.csv"
    if not os.path.exists(path):
        return out
    for row in csv.DictReader(open(path, encoding="utf-8")):
        t = p(row["time"])
        if t0 < t <= t1:
            out.append(float(row["value"]))
    return out


def sysrows(dd, t0, t1):
    rows = []
    path = f"{dd}/gpu/sys.txt"
    if not os.path.exists(path):
        return rows
    for line in open(path, encoding="utf-8"):
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
    """[predict start, predict end] per job from the pod's 'infer N ms, convert M ms' line (stamped after convert, 0..0.2 s late)."""
    jobs, _ = podlog(dd)
    out = []
    for t, jid, msg in jobs:
        mm = re.match(r"infer (\d+) ms, convert (\d+) ms", msg)
        if mm and t0 <= t <= t1 + dt.timedelta(seconds=180):
            inf, conv = int(mm.group(1)), int(mm.group(2))
            end = t - TD(conv)
            out.append((end - TD(inf), end, jid))
    return sorted(out)


def dockerstats(dd, t0, t1):
    out = {}
    for line in open(f"{dd}/vm/docker-stats-series.jsonl", encoding="utf-8"):
        if not line.strip():
            continue
        e = json.loads(line)
        if "s" not in e or not (t0 <= p(e["ts"]) <= t1):
            continue
        out.setdefault(e["s"]["Name"], []).append(float(e["s"]["CPUPerc"].rstrip("%")))
    return out


def union(iv):
    out = []
    for a, b in sorted(iv):
        if b <= a:
            continue
        if out and a <= out[-1][1]:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return out


def in_union(x, U):
    i = bisect.bisect_right([u[0] for u in U], x) - 1
    return i >= 0 and U[i][0] <= x < U[i][1]


def ov(a0, a1, b0, b1):
    return max(0.0, (min(a1, b1) - max(a0, b0)).total_seconds())


# ---------------------------------------------------------------- Pod request join (round 4 only)

def join(key):
    """Per completed job: its POST /infer/upload, GET /artifacts/<job>/base.glb and DELETE /artifacts/<job> timing lines.
    GET and DELETE carry the jobId in the path. POST does not: it is joined to the handler's 'job <id>: upload mode ...'
    line, which the handler prints just before it answers; that line's reader stamp T is 0..0.2 s after the answer moment
    A = POST.at + start_ms, so the POST with A in [T - 0.4 s, T + 0.1 s] is taken (if several: the one on the GET's port
    whose answer precedes the GET, then the nearest). Checked against a second, independent join: k-th POST by arrival
    time <-> k-th job by VM processing start."""
    dd, meta, t0, t1, att = load(key)
    comp = [e for e in att if e["result"] == "COMPLETED"]
    ids = {e["jobId"] for e in comp}
    tim = timing(dd)
    jobs, acc = podlog(dd)
    gets, dels = {}, {}
    for t in tim:
        mm = re.match(r"/artifacts/(" + UUID + r")(/base\.glb)?$", t["path"])
        if not mm:
            continue
        if t["meth"] == "GET" and mm.group(2):
            gets.setdefault(mm.group(1), t)
        elif t["meth"] == "DELETE":
            dels.setdefault(mm.group(1), []).append(t)
    posts = [t for t in tim if t["meth"] == "POST" and t["path"] == "/infer/upload" and t["start"] is not None]
    for t in posts:
        t["A"] = t["at"] + TD(t["start"])
    ups = [(s, j) for s, j, msg in jobs if msg.startswith("upload mode") and j in ids]
    used, rec, amb, lag = set(), {}, 0, []
    for s, j in ups:
        cands = [k for k, t in enumerate(posts) if k not in used and -0.10 <= sec(t["A"], s) <= 0.40]
        if not cands:
            continue
        if len(cands) > 1:
            amb += 1
            g = gets.get(j)
            same = [k for k in cands if g and posts[k]["port"] == g["port"] and posts[k]["A"] <= g["at"]]
            cands = same or cands
        k = min(cands, key=lambda k: abs(sec(posts[k]["A"], s) - 0.05))
        used.add(k)
        rec[j] = posts[k]
        lag.append(sec(posts[k]["A"], s))
    # independent order join
    comp_ps = sorted(comp, key=lambda e: e["_ps"])
    lo, hi = comp_ps[0]["_ps"] - dt.timedelta(seconds=5), max(e["_fin"] for e in comp)
    win_posts = sorted([t for t in posts if lo <= t["at"] <= hi], key=lambda t: t["at"])
    agree = sum(1 for e, t in zip(comp_ps, win_posts) if rec.get(e["jobId"]) is t)
    rows = []
    for e in comp:
        j = e["jobId"]
        po, g = rec.get(j), gets.get(j)
        de = (dels.get(j) or [None])[0]
        if not (po and g):
            continue
        A = po["A"]
        conv_end = A                                   # answer follows the convert by a few ms (file stat, JSON)
        pred_end = conv_end - TD(e["convertMs"])
        r = {"e": e, "jid": j, "ps": e["_ps"], "fin": e["_fin"], "post": po, "get": g, "del": de,
             "body": po["body"] / 1000, "work": (po["start"] - po["body"]) / 1000,
             "extra": (po["start"] - po["body"] - e["inferMs"] - e["convertMs"]) / 1000,
             "gap": sec(A, g["at"]), "get_start": g["start"] / 1000, "handoff": (g["end"] - g["start"]) / 1000,
             "leg": sec(g["at"] + TD(g["end"]), e["_fin"]), "front": sec(e["_ps"], po["at"]),
             "del_after": sec(e["_fin"], de["at"]) if de else None, "del_start": de["start"] / 1000 if de and de["start"] is not None else None,
             "same_port": po["port"] == g["port"],
             "up0": po["at"], "up1": po["at"] + TD(po["body"]), "pred0": pred_end - TD(e["inferMs"]), "pred1": pred_end,
             "conv1": conv_end, "dl0": g["at"], "leg0": g["at"] + TD(g["end"])}
        r["resid"] = e["processingMs"] / 1000 - (r["front"] + po["start"] / 1000 + r["gap"] + g["end"] / 1000 + r["leg"])
        rows.append(r)
    info = {"comp": len(comp), "posts_window": len(win_posts), "matched": len(rec), "rows": len(rows), "amb": amb,
            "lag": lag, "agree": agree, "unmatched_posts": len([t for t in win_posts if not any(t is v for v in rec.values())]),
            "acc": acc, "tim": tim, "meta": meta, "t0": t0, "t1": t1, "dd": dd, "att": att}
    return rows, info


def overlaps(rows, thr=0.05):
    """For each job's GLB leg window [GET.at + end_ms, VM finishedAt] (mixed clocks): seconds overlapped by other jobs'
    GLB legs, by other jobs' predict+convert (Pod clock, from the POST answer minus convertMs and inferMs) and by other
    jobs' upload receive [POST.at, POST.at + body_ms]."""
    for r in rows:
        W0, W1 = r["leg0"], r["fin"]
        oth = [x for x in rows if x is not r]
        r["o_glb"] = sum(ov(W0, W1, x["leg0"], x["fin"]) for x in oth)
        r["o_pc"] = sum(ov(W0, W1, x["pred0"], x["conv1"]) for x in oth)
        r["o_up"] = sum(ov(W0, W1, x["up0"], x["up1"]) for x in oth)
        r["cat"] = ("GLB+pred/conv" if r["o_glb"] > thr and r["o_pc"] > thr else "other GLB only" if r["o_glb"] > thr
                    else "other pred/conv only" if r["o_pc"] > thr else "alone")
        # the upload receive of this job: overlapped by another job's GLB leg?
        r["up_o_glb"] = sum(ov(r["up0"], r["up1"], x["leg0"], x["fin"]) for x in oth)
        r["up_o_up"] = sum(ov(r["up0"], r["up1"], x["up0"], x["up1"]) for x in oth)


# ---------------------------------------------------------------- net.txt (Pod: tailscaled / service CPU ticks, loopback queues)

def netrows(dd):
    rows = []
    for line in open(f"{dd}/gpu/net.txt", encoding="utf-8"):
        f = line.split()
        if len(f) < 3 or not f[0][0].isdigit():
            continue
        ent = []
        if len(f) > 3 and f[3].startswith("q="):
            for item in f[3][2:].split(","):
                if ":" not in item or ">" not in item:
                    continue
                pr, qq = item.split(":")
                lp, rp = pr.split(">")
                tx, rx = qq.split("/")
                ent.append((int(lp), int(rp), int(tx), int(rx)))
        rows.append((p(f[0]).timestamp(), int(f[1].split("=")[1]), int(f[2].split("=")[1]), ent))
    rows.sort(key=lambda r: r[0])
    return rows


class Cum:
    """Cumulative CPU ticks (100/s) of one process, linearly interpolated between the 0.25 s samples."""

    def __init__(self, rows, k):
        self.t = [r[0] for r in rows]
        self.v = [r[k] for r in rows]

    def at(self, ts):
        i = bisect.bisect_left(self.t, ts)
        if i <= 0:
            return self.v[0]
        if i >= len(self.t):
            return self.v[-1]
        a, b = self.t[i - 1], self.t[i]
        return self.v[i - 1] + (self.v[i] - self.v[i - 1]) * (ts - a) / (b - a) if b > a else self.v[i]

    def cores(self, a, b):
        return (self.at(b) - self.at(a)) / 100 / (b - a) if b > a else float("nan")

    def cores_in(self, U):
        tot = sum(b - a for a, b in U)
        return sum(self.at(b) - self.at(a) for a, b in U) / 100 / tot if tot > 0 else float("nan")


def windows1s(rows):
    """Non-overlapping windows of >= 1 s between samples: (start, end, tailscaled cores, service cores)."""
    out, i = [], 0
    while i < len(rows) - 1:
        j = i + 1
        while j < len(rows) and rows[j][0] - rows[i][0] < 1.0:
            j += 1
        if j >= len(rows):
            break
        w = rows[j][0] - rows[i][0]
        out.append((rows[i][0], rows[j][0], (rows[j][1] - rows[i][1]) / 100 / w, (rows[j][2] - rows[i][2]) / 100 / w))
        i = j
    return out


def clip(U, a, b):
    return [[max(x, a), min(y, b)] for x, y in U if min(y, b) > max(x, a)]


# ====================================================================================================================
print("# round-4 analysis (g4_analyze.py); all times UTC; [계산] = computed here from the raw files")

print("\n== 0. runs: jobs created in the k6 window, attempts, final results, k6 exit and failed thresholds")
for k in list(R4) + ["g0ka5", "g0ka75", "g0th8", "g2c2a", "f1br1", "g3r165"]:
    dd, meta, t0, t1, att = load(k)
    fin = final(att)
    ex = open(f"{dd}/k6-exit.txt", encoding="utf-8").read().strip() if os.path.exists(f"{dd}/k6-exit.txt") else "?"
    ft = [f"{x['metric']} {x['threshold']}" for x in meta["verdict"].get("failedThresholds") or []]
    print(f"  {k}: window {t0:%H:%M:%S}-{t1:%H:%M:%S} ({sec(t0, t1):.0f} s), jobs {len(fin)}, attempts {len(att)}, "
          f"final {count(e['result'] for e in fin.values())}, attempts {count(e['result'] for e in att)}, "
          f"k6 healthz p50 {meta['meta'].get('rttBaseMsMeasured')} ms, exit {ex}, failed thresholds {ft}")
dd, meta, t0, t1, att = load("g0d4")
ev_all = [json.loads(x) for x in open(f"{dd}/vm/events.jsonl", encoding="utf-8") if x.strip()]
pre = [e for e in ev_all if p(e["finishedAt"]) - TD(e["totalMs"]) < t0]
print(f"  g0d4: jobs in events.jsonl created before the k6 window (operator check job, not counted): "
      + ", ".join(f"{e['jobId'][:8]} created {p(e['finishedAt']) - TD(e['totalMs']):%H:%M:%S} infer {e['inferMs']} ms "
                  f"processing {e['processingMs']} ms queued {e['queuedMs']} ms" for e in pre))

# ---------------------------------------------------------------------------------------------------------------------
print("\n== 1. G0 one job every 30 s: g0d4 (round 4, new Pod, start_pod.sh defaults keep-alive 75 s + threads 8, JDK keep-alive 60 s)"
      "\n   vs round 3 g0ka5 (keep-alive 5 s) / g0ka75 (75 s) / g0th8 (75 s + threads 8) and round 2 g0r1. ms -> s."
      "\n   'clean' = not touched by an inference-service DOWN (queuedMs < 10 s and processingMs < 20 s); h = queued - preprocess;"
      "\n   S0 = mean processing + 2 x median h (clean); transfer etc = processing - infer - convert")
g0 = {}
for k in ("g0d4", "g0ka5", "g0ka75", "g0th8", "g0r1"):
    dd, meta, t0, t1, att = load(k)
    comp = [e for e in att if e["result"] == "COMPLETED"]
    hit = [e for e in comp if e["queuedMs"] >= 10000 or e["processingMs"] >= 20000]
    clean = [e for e in comp if e not in hit]
    h = [e["queuedMs"] - (e.get("preprocessMs") or 0) for e in clean]
    s0 = st.fmean([e["processingMs"] for e in clean]) / 1000 + 2 * q(h, .5) / 1000
    n_new, n_re, n_ports, new, reused = reuse(dd, comp)
    rows = sysrows(dd, t0, t1)
    if rows:
        span = sec(rows[0][0], rows[-1][0]) + 5
        nthr = sum(r[3] for r in rows)
        thr = f"{nthr} of {span / 0.1:.0f} periods ({nthr / (span / 0.1) * 100:.2f} %)"
    else:
        thr = "no sys.txt"
    k6m = meta["k6"]["metrics"]
    hz = k6m.get("load_healthzMs", {}).get("values", {}).get("med")
    g0[k] = {"comp": comp, "clean": clean, "h0": q(h, .5) / 1000, "S0": s0, "new": n_new, "re": n_re, "thr": thr, "hz": hz,
             "dd": dd, "t0": t0, "t1": t1, "meta": meta}
    print(f"  {k}: completed {len(comp)}, hit by DOWN {len(hit)}"
          + ("" if not hit else " (" + ", ".join(f"{e['jobId'][:8]} queued {e['queuedMs'] / 1000:.1f} s processing {e['processingMs'] / 1000:.1f} s" for e in hit) + ")"))
    for name, js in (("all", comp), ("clean", clean)):
        if name == "clean" and len(clean) == len(comp):
            print("    [clean] = [all]")
            continue
        print(f"    [{name}] processing {d([e['processingMs'] for e in js], 1000)}")
        print(f"    [{name}] infer      {d([e['inferMs'] for e in js], 1000)}")
        print(f"    [{name}] convert    {d([e['convertMs'] for e in js], 1000)}")
        print(f"    [{name}] transfer   {d([xfer(e) for e in js], 1000)}")
    print(f"    [clean] queued {d([e['queuedMs'] for e in clean], 1000)}; h {d(h, 1000, 3)}")
    print(f"    [clean] h0 = {q(h, .5) / 1000:.3f} s, S0 = {s0:.2f} s")
    print(f"    connections (port of GET base.glb, access log): new {n_new}, reused {n_re}, distinct ports {n_ports}; "
          f"transfer mean new {mean(new) / 1000:.2f} s, reused {mean(reused) / 1000:.2f} s")
    print(f"    pod cgroup throttling (sys.txt, window): {thr}; k6 healthz p50 {hz:.1f} ms (meta {meta['meta'].get('rttBaseMsMeasured')} ms)")
    for mname in ("load_waitMs", "load_flowMs"):
        v = k6m[mname]["values"]
        print(f"    k6 {mname}: med {v['med'] / 1000:.2f} s p95 {v['p(95)'] / 1000:.2f} s max {v['max'] / 1000:.2f} s (count {v['count']})")
# connection reuse in g0d4 from the timing lines (all three requests of a job)
rows4, info4 = join("g0d4")
tim = info4["tim"]
hz_ports = {t["port"] for t in tim if t["path"] == "/healthz"}
job_ports = {t["port"] for t in tim if t["path"] != "/healthz"}
seen, newj, sameall = set(), 0, 0
for r in sorted(rows4, key=lambda r: r["ps"]):
    pts = {r["post"]["port"], r["get"]["port"], r["del"]["port"] if r["del"] else None}
    if len(pts) == 1:
        sameall += 1
    if r["post"]["port"] not in seen:
        newj += 1
    seen |= pts
print(f"  g0d4 connections from the timing lines (k6 window, {len(rows4)} jobs): POST, GET and DELETE on one port in {sameall} jobs; "
      f"POST on a port not seen before {newj}; job ports {sorted(job_ports)}, /healthz ports {sorted(hz_ports - job_ports)} "
      f"(the backend's health monitor keeps its own connection)")
idle = []
prev = None
for r in sorted(rows4, key=lambda r: r["ps"]):
    if prev is not None:
        idle.append(sec(prev, r["post"]["at"]))
    prev = r["del"]["at"] + TD(r["del"]["end"]) if r["del"] else r["get"]["at"]
print(f"  g0d4 idle time of the job connection between a DELETE and the next POST: {d9(idle, 1)} s "
      f"(JDK keep-alive 60 s, Pod keep-alive 75 s: every gap below both, so no reconnect)")
print("  side by side (clean jobs, seconds; transfer etc = processing - infer - convert):")
print("    run     n  proc p50  proc mean  infer p50  conv p50  xfer p50  xfer mean   h0     S0    healthz p50  conn new/reused  throttled")
for k in ("g0d4", "g0ka5", "g0ka75", "g0th8", "g0r1"):
    c = g0[k]["clean"]
    pr = [e["processingMs"] / 1000 for e in c]
    print(f"    {k:7s} {len(c):2d}  {q(pr, .5):7.3f}  {st.fmean(pr):8.3f}  {q([e['inferMs'] / 1000 for e in c], .5):8.3f}  "
          f"{q([e['convertMs'] / 1000 for e in c], .5):8.3f}  {q([xfer(e) / 1000 for e in c], .5):7.3f}  {st.fmean([xfer(e) / 1000 for e in c]):8.3f}  "
          f"{g0[k]['h0']:5.3f}  {g0[k]['S0']:5.2f}  {g0[k]['hz']:10.1f}   {g0[k]['new']:3d}/{g0[k]['re']:<3d}        {g0[k]['thr']}")
a, b = g0["g0th8"]["clean"], g0["g0d4"]["clean"]
for f_, name in ((lambda e: e["processingMs"], "processing"), (lambda e: e["inferMs"], "infer"), (lambda e: e["convertMs"], "convert"), (xfer, "transfer etc")):
    ma, mb = q([f_(e) for e in a], .5), q([f_(e) for e in b], .5)
    print(f"  g0th8 -> g0d4 clean median {name}: {ma / 1000:.3f} -> {mb / 1000:.3f} s (diff {(mb - ma) / 1000:+.3f} s, {(mb - ma) / ma * 100:+.1f} %)")

# ---------------------------------------------------------------------------------------------------------------------
print("\n== 2. Pod request timing joined to the backend jobs (g0d4, g3c1, g2c2t)")
print("   clock assumption: VM and Pod on NTP, offset NOT measured. Pod-only differences (body, work, gap, handoff, GET start)")
print("   use the timing lines' own at= and ms fields and need no clock assumption. 'front' (VM processing start -> POST head")
print("   on the Pod) and 'GLB leg' (GET.at + end_ms -> VM finishedAt) are mixed-clock: an offset d moves front by +d and the")
print("   leg by -d; front + leg is offset-free. finishedAt is set after the download and the copy into storage, before the")
print("   DELETE (RealJobWorker / JobFinisher). end_ms is when uvicorn took the last chunk, not when the bytes left the Pod.")
print("   join: GET/DELETE by jobId in the path; POST via the handler's 'upload mode' line printed just before the answer")
print("   (see join() docstring), cross-checked by the order join (k-th POST by arrival <-> k-th job by VM processing start).")
rtt = []
for line in open(BASE + R4["g0d4"] + "/vm/derp-rtt.txt", encoding="utf-8"):
    f = line.split()
    if len(f) == 4 and f[3] == "200":
        rtt.append(float(f[0]))
print(f"   reference: VM -> Pod tailnet TCP connect time (derp-rtt.txt, 17:23, 20 x curl /healthz) p50 {q(rtt, .5) * 1000:.1f} ms "
      f"(one round trip), so one way is about {q(rtt, .5) * 500:.0f} ms")
J2 = {}
for k in ("g0d4", "g3c1", "g2c2t"):
    rows, info = join(k)
    J2[k] = (rows, info)
    comp_ids = {e["jobId"] for e in info["att"] if e["result"] == "COMPLETED"}
    print(f"  {k}: completed {info['comp']}, POSTs (with an answer) in the window {info['posts_window']}, joined {info['matched']}, "
          f"usable rows {len(rows)}; joins with more than one candidate POST {info['amb']}; order join agrees for "
          f"{info['agree']} of {min(info['comp'], info['posts_window'])}; window POSTs left unjoined {info['unmatched_posts']}")
    print(f"    join error indicator: reader stamp of 'upload mode' - POST answer A: {d9(info['lag'], 3)} s; POST and GET on the same port "
          f"{sum(1 for r in rows if r['same_port'])} of {len(rows)}; identity residual (processing - sum of legs) max |{max(abs(r['resid']) for r in rows) * 1000:.1f}| ms")
    print(f"    front (VM processing start -> POST head on Pod, mixed clocks): {d9([r['front'] for r in rows])} s")
    print(f"    upload receive on the Pod (POST body_ms):                     {d9([r['body'] for r in rows])} s")
    print(f"    Pod work (POST start_ms - body_ms, incl. MODEL_LOCK wait):    {d9([r['work'] for r in rows])} s")
    print(f"      of which not infer/convert (work - inferMs - convertMs):    {d9([r['extra'] for r in rows])} s")
    print(f"    answer-to-GET gap (GET.at - (POST.at + start_ms), Pod clock): {d9([r['gap'] for r in rows])} s")
    print(f"    GET start_ms (head of the GLB answer):                         {d9([r['get_start'] * 1000 for r in rows], 1)} ms")
    print(f"    Pod handoff (GET end_ms - start_ms):                           {d9([r['handoff'] * 1000 for r in rows], 1)} ms")
    print(f"    GLB leg after the Pod handed it over (VM finishedAt - (GET.at + end_ms), mixed clocks): {d9([r['leg'] for r in rows])} s")
    print(f"    front + GLB leg (offset-free):                                 {d9([r['front'] + r['leg'] for r in rows])} s")
    print(f"    DELETE head on Pod - VM finishedAt (mixed clocks):             {d9([r['del_after'] for r in rows if r['del_after'] is not None])} s; "
          f"DELETE start_ms {d9([r['del_start'] * 1000 for r in rows if r['del_start'] is not None], 1)} ms")
    bl = {}
    for line in open(f"{info['dd']}/vm/backend.log", encoding="utf-8", errors="replace"):
        mm = re.search(r"\| (\S+)\s+INFO .*jobId=(" + UUID + r") stage=COMPLETED", line)
        if mm and mm.group(2) in comp_ids:
            bl[mm.group(2)] = p(mm.group(1))
    lagc = [sec(r["fin"], bl[r["jid"]]) * 1000 for r in rows if r["jid"] in bl]
    print(f"    backend.log stage=COMPLETED stamp - finishedAt: {d9(lagc, 1)} ms (so either can be the 'backend completion')")
    print(f"    processing (events) for these jobs: {d9([r['e']['processingMs'] / 1000 for r in rows])} s")
for k in ("g3c1", "g2c2t"):
    rows, info = J2[k]
    overlaps(rows)
print("  2b. GLB leg (s) by what overlapped it (another job's GLB leg / another job's predict+convert; overlap > 0.05 s counts)")
for k in ("g3c1", "g2c2t"):
    rows, info = J2[k]
    for cat in ("alone", "other GLB only", "other pred/conv only", "GLB+pred/conv"):
        v = [r for r in rows if r["cat"] == cat]
        if not v:
            print(f"    {k} [{cat}] n 0")
            continue
        print(f"    {k} [{cat}] GLB leg {d9([r['leg'] for r in v])}; front+leg p50 {q([r['front'] + r['leg'] for r in v], .5):.3f}; "
              f"gap p50 {q([r['gap'] for r in v], .5):.3f}; share of the leg overlapped: other GLB p50 "
              f"{q([r['o_glb'] / max(r['leg'], 1e-9) for r in v], .5) * 100:.0f} %, pred/conv p50 {q([r['o_pc'] / max(r['leg'], 1e-9) for r in v], .5) * 100:.0f} %, "
              f"other upload p50 {q([r['o_up'] / max(r['leg'], 1e-9) for r in v], .5) * 100:.0f} %")
rows, info = J2["g2c2t"]
for name, sel in (("upload overlapped by another job's GLB leg", lambda r: r["up_o_glb"] > 0.05),
                  ("upload overlapped by another upload only", lambda r: r["up_o_glb"] <= 0.05 and r["up_o_up"] > 0.05),
                  ("upload alone", lambda r: r["up_o_glb"] <= 0.05 and r["up_o_up"] <= 0.05)):
    v = [r for r in rows if sel(r)]
    print(f"    g2c2t upload receive body_ms [{name}]: {d9([r['body'] for r in v])} s")
base_extra = q([r["extra"] for r in J2["g3c1"][0]], .5)
lockw = [r for r in rows if r["extra"] > base_extra + 0.25]
print(f"    g2c2t Pod work beyond infer+convert: c=1 (g3c1) median {base_extra:.3f} s; g2c2t jobs more than 0.25 s above it "
      f"(waited for MODEL_LOCK) {len(lockw)} of {len(rows)}, their excess {d9([r['extra'] - base_extra for r in lockw])} s")
for name, sel in (("waited for the lock", lambda r: r in lockw), ("did not wait", lambda r: r not in lockw)):
    v = [r for r in rows if sel(r)]
    print(f"    g2c2t [{name}] processing {d9([r['e']['processingMs'] / 1000 for r in v])}; GLB leg p50 {q([r['leg'] for r in v], .5):.3f} s")
print("  2c. GLB leg by tailnet connection (client port of the GET on the Pod: one loopback connection per tailnet connection)"
      "\n      and by time since the k6 start (VM processing start), seconds")
QU = ((0, 300), (300, 600), (600, 900), (900, 1500))
for k in ("g3c1", "g2c2t"):
    rows, info = J2[k]
    t0 = info["t0"]
    for port in sorted({r["get"]["port"] for r in rows}):
        v = [r for r in rows if r["get"]["port"] == port]
        parts = []
        for lo, hi in QU:
            w = [r["leg"] for r in v if lo <= sec(t0, r["ps"]) < hi]
            parts.append(f"{lo}-{hi} s n {len(w)} p50 {q(w, .5):.2f} p90 {q(w, .9):.2f} min {min(w) if w else float('nan'):.2f}")
        print(f"    {k} GET port {port}: all {d9([r['leg'] for r in v], 2)}; legs < 2.2 s {sum(1 for r in v if r['leg'] < 2.2)}")
        print(f"      " + " | ".join(parts))
        print(f"      same jobs: upload body_ms p50 {q([r['body'] for r in v if r['post']['port'] == port], .5):.3f} s (POSTs on this port, VM -> Pod direction), "
              f"answer-to-GET gap p50 {q([r['gap'] for r in v], .5):.3f} s, handoff p50 {q([r['handoff'] * 1000 for r in v], .5):.1f} ms")
rows, info = J2["g2c2t"]
print("    g2c2t category x GET port (GLB leg p50 / n):")
for cat in ("alone", "other GLB only", "other pred/conv only", "GLB+pred/conv"):
    cells = []
    for port in sorted({r["get"]["port"] for r in rows}):
        w = [r["leg"] for r in rows if r["cat"] == cat and r["get"]["port"] == port]
        cells.append(f"port {port}: {q(w, .5):.2f} / {len(w)}")
    print(f"      [{cat}] " + ", ".join(cells))
slow_first = {}
for r in sorted(rows, key=lambda r: r["ps"]):
    if r["leg"] > 3.0 and r["get"]["port"] not in slow_first:
        slow_first[r["get"]["port"]] = r
for port, r in sorted(slow_first.items()):
    print(f"    g2c2t first GLB leg > 3 s on port {port}: job {r['jid'][:8]} processing start {r['ps']:%H:%M:%S} "
          f"(+{sec(info['t0'], r['ps']):.0f} s), leg {r['leg']:.2f} s, overlapped by another GLB leg {r['o_glb']:.2f} s, by pred/conv {r['o_pc']:.2f} s")
h0_4 = g0["g0d4"]["h0"]
for port in sorted({r["get"]["port"] for r in rows}):
    v = [r["e"]["processingMs"] / 1000 for r in rows if r["get"]["port"] == port]
    print(f"    g2c2t processing of the jobs whose GET used port {port}: {d9(v, 2)} s -> slot-time form if both slots ran like this: "
          f"2 x 3600 / (mean + 2 h0 {2 * h0_4:.2f}) = {2 * 3600 / (mean(v) + 2 * h0_4):.0f}/h")
print("    round 3 g2c2a (no timing lines): 'convert end -> VM processing end' (round-3 section 10 measure: reader stamp of the"
      "\n    'infer..convert' line, 0..0.2 s late; includes the answer, the GET and the GLB leg) by GET port from the access log")
for k in ("g2c2a", "g3r165"):
    dd, meta, t0, t1, att = load(k)
    compd = {e["jobId"]: e for e in att if e["result"] == "COMPLETED"}
    _, acc = podlog(dd)
    port_of = {}
    for t, port, meth, path, code in acc:
        mm = re.match(r"/artifacts/(" + UUID + r")/base\.glb", path)
        if meth == "GET" and mm:
            port_of.setdefault(mm.group(1), port)
    by = {}
    for s_, e_, jid in infer_intervals(dd, t0, t1):
        e = compd.get(jid)
        if not e:
            continue
        pe = e["_ps"] + TD(e["processingMs"])
        by.setdefault(port_of.get(jid), []).append((sec(t0, e["_ps"]), sec(e_, pe) - e["convertMs"] / 1000))
    for port, v in sorted(by.items(), key=lambda kv: str(kv[0])):
        parts = []
        for lo, hi in QU:
            w = [x for r_, x in v if lo <= r_ < hi]
            parts.append(f"{lo}-{hi} s n {len(w)} p50 {q(w, .5):.2f}")
        print(f"    {k} GET port {port}: all {d9([x for _, x in v], 2)} | " + " | ".join(parts))

# ---------------------------------------------------------------------------------------------------------------------
print("\n== 3. Pod CPU: tailscaled and the service from net.txt ticks (100/s, sampled every 0.25 s), loopback :8002 queues,"
      "\n   cgroup (sys.txt), GPU, VM. Windows (Pod clock unless noted): upload = [POST.at, +body_ms]; download = [GET.at, VM"
      "\n   finishedAt] (mixed clocks); predict = [answer - convert - infer, answer - convert]. 1-s windows are classified by"
      "\n   their midpoint (download > upload > predict > idle). 'cores' = share of one core.")
res3 = {}
for k in ("g0d4", "g3c1", "g2c2t"):
    rows, info = J2[k]
    dd, t0, t1 = info["dd"], info["t0"], info["t1"]
    a, b = t0.timestamp(), t1.timestamp()
    nr = [r for r in netrows(dd) if a - 2 <= r[0] <= b + 2]
    tsd, svc = Cum(nr, 1), Cum(nr, 2)
    D = clip(union([[r["dl0"].timestamp(), r["fin"].timestamp()] for r in rows]), a, b)
    U = clip(union([[r["up0"].timestamp(), r["up1"].timestamp()] for r in rows]), a, b)
    P = clip(union([[r["pred0"].timestamp(), r["pred1"].timestamp()] for r in rows]), a, b)
    W = [w for w in windows1s(nr) if a <= w[0] and w[1] <= b]
    cls = {"download": [], "upload": [], "predict": [], "idle": []}
    for w in W:
        m_ = (w[0] + w[1]) / 2
        cls["download" if in_union(m_, D) else "upload" if in_union(m_, U) else "predict" if in_union(m_, P) else "idle"].append(w)
    allw = [w[2] * 100 for w in W]
    print(f"  {k}: net.txt samples {len(nr)}, 1-s windows {len(W)}; download union {sum(y - x for x, y in D):.0f} s, upload union {sum(y - x for x, y in U):.0f} s, "
          f"predict union {sum(y - x for x, y in P):.0f} s of {b - a:.0f} s")
    print(f"    tailscaled % of one core: whole window mean {tsd.cores(a, b) * 100:.2f} %; 1-s windows p50 {q(allw, .5):.1f} p95 {q(allw, .95):.1f} max {max(allw):.1f} %")
    for c_, ws in cls.items():
        v = [w[2] * 100 for w in ws]
        U_ = {"download": D, "upload": U, "predict": P}.get(c_)
        exact = f"; exact mean over the union {tsd.cores_in(U_) * 100:.2f} %" if U_ else ""
        print(f"      [{c_}] 1-s windows n {len(v)} mean {mean(v):.1f} p50 {q(v, .5):.1f} p95 {q(v, .95):.1f} max {max(v) if v else float('nan'):.1f} %{exact}")
    per = [tsd.cores(r["dl0"].timestamp(), r["fin"].timestamp()) * 100 for r in rows if a <= r["dl0"].timestamp() and r["fin"].timestamp() <= b]
    print(f"    tailscaled over each job's own download window: {d9(per, 1)} %")
    vs = [w[3] for w in W]
    print(f"    service process cores: whole window mean {svc.cores(a, b):.2f}; [download] exact {svc.cores_in(D):.2f}, [upload] {svc.cores_in(U):.2f}, "
          f"[predict] {svc.cores_in(P):.2f}; 1-s windows max {max(vs):.2f}")
    tim = info["tim"]
    hzp = {t["port"] for t in tim if t["path"] == "/healthz"} - {t["port"] for t in tim if t["path"] != "/healthz"}
    ts_rx, sv_tx, sv_rx, ts_tx = [], [], [], []
    for r in nr:
        if in_union(r[0], D):
            ts_rx.append(sum(e[3] for e in r[3] if e[1] == 8002 and e[0] not in hzp))
            sv_tx.append(sum(e[2] for e in r[3] if e[0] == 8002 and e[1] not in hzp and e[1] != 0))
        elif in_union(r[0], U):
            sv_rx.append(sum(e[3] for e in r[3] if e[0] == 8002 and e[1] not in hzp and e[1] != 0))
            ts_tx.append(sum(e[2] for e in r[3] if e[1] == 8002 and e[0] not in hzp))
    fmt = lambda v: f"n {len(v)} nonzero {sum(1 for x in v if x)} p50 {q(v, .5):.0f} p90 {q(v, .9):.0f} max {max(v) if v else 0} B"
    print(f"    loopback queues in download windows: tailscaled-side rx_queue (GLB bytes not yet read by tailscaled) {fmt(ts_rx)}; "
          f"service-side tx_queue {fmt(sv_tx)}")
    print(f"    loopback queues in upload windows: service-side rx_queue (upload bytes not yet read by the service) {fmt(sv_rx)}; "
          f"tailscaled-side tx_queue {fmt(ts_tx)}")
    sr = sysrows(dd, t0, t1)
    span = sec(sr[0][0], sr[-1][0]) + 5
    nthr = sum(r[3] for r in sr)
    inside = [r for r in sr if in_union((r[0] - dt.timedelta(seconds=2.5)).timestamp(), P)]
    print(f"    pod container (sys.txt 5 s rows {len(sr)}): cores mean {mean([r[2] for r in sr]):.2f} max {max(r[2] for r in sr):.2f}, mean in rows whose 5 s centre "
          f"is inside a predict {mean([r[2] for r in inside]):.2f} (n {len(inside)}); throttled periods {nthr} of {span / 0.1:.0f} ({nthr / (span / 0.1) * 100:.2f} %), "
          f"rows with throttling {sum(1 for r in sr if r[3] > 0)}; host load1 {min(r[1] for r in sr):.1f}-{max(r[1] for r in sr):.1f}")
    gr = gpurows(dd, t0, t1)
    gin = [r for r in gr if in_union(r[0].timestamp(), P)]
    spans = sorted((r["pred0"].timestamp(), r["pred1"].timestamp()) for r in rows)
    phase = []
    for g_ in gin:
        x = g_[0].timestamp()
        for s_, e_ in spans:
            if s_ <= x < e_:
                phase.append((x - s_) / (e_ - s_))
                break
    print(f"    GPU util mean {mean([r[1] for r in gr]):.1f} % (n {len(gr)}), samples inside a predict n {len(gin)} mean {mean([r[1] for r in gin]):.1f} %, "
          f"outside {mean([r[1] for r in gr if r not in gin]):.1f} %; memory max {max(r[2] for r in gr):.0f} MiB; position of those samples inside "
          f"their predict (0 = start, 1 = end) p10 {q(phase, .1):.2f} p50 {q(phase, .5):.2f} p90 {q(phase, .9):.2f}"
          + (" (jobs every 30 s and samples every 5 s: the samples hit the same part of each predict, so this mean is aliased)" if k == "g0d4" else ""))
    comp = [e for e in info["att"] if e["result"] == "COMPLETED"]
    sc, pc = series(dd, "system_cpu", t0, t1), series(dd, "process_cpu", t0, t1)
    w = sec(t0, t1)
    ds = dockerstats(dd, t0, t1)
    print(f"    VM (2 vCPU): system_cpu mean {mean(sc) * 100:.1f} % max {max(sc) * 100:.1f} %, JVM process_cpu mean {mean(pc) * 100:.1f} %; "
          f"VM {mean(sc) * 2 * w / len(comp):.2f} core-s/job, JVM {mean(pc) * 2 * w / len(comp):.2f} core-s/job; docker stats CPU % of one core (15 s) "
          + ", ".join(f"{n_} mean {mean(v):.1f} max {max(v):.1f}" for n_, v in sorted(ds.items())))
    res3[k] = {"dl_tsd": tsd.cores_in(D) * 100, "all_tsd": tsd.cores(a, b) * 100}
dd = BASE + R4["f1br4"]
_, _, t0, t1, _ = load("f1br4")
sr = sysrows(dd, t0, t1)
span = sec(sr[0][0], sr[-1][0]) + 5
nthr = sum(r[3] for r in sr)
gr = gpurows(dd, t0, t1)
nr = [r for r in netrows(dd) if t0.timestamp() <= r[0] <= t1.timestamp()]
print(f"  f1br4: pod cores mean {mean([r[2] for r in sr]):.2f} max {max(r[2] for r in sr):.2f}, throttled periods {nthr} of {span / 0.1:.0f} "
      f"({nthr / (span / 0.1) * 100:.2f} %); GPU util mean {mean([r[1] for r in gr]):.1f} %; tailscaled mean "
      f"{Cum(nr, 1).cores(nr[0][0], nr[-1][0]) * 100:.2f} % of one core")

# ---------------------------------------------------------------------------------------------------------------------
print("\n== 4. same lambda (517/h requested), c=1 (g3c1) vs c=2 (g2c2t) on the same Pod; round 3 g2c2a (c=2, other Pod, default"
      "\n   threads, JDK keep-alive 1200 s) and round 2 g3r165 (c=1, other Pod). request = created + preprocessMs (round-3 rule); ask = backend.log"
      "\n   stamp of the cutout line (the real slot request, after any wait for the cutout semaphore); ps = created + queued.")


def slot_stats(key, c, h0):
    """h0 = the job's own healthz time before processing, taken from the G0 run on the same Pod and settings (the median
    of queued - preprocess over all jobs of an overloaded run would include the slot wait)."""
    dd, meta, t0, t1, att = load(key)
    comp = [e for e in att if e["result"] == "COMPLETED"]
    arr_s = float(meta["meta"].get("measuredArrivalWindowS") or meta["meta"].get("durationS"))
    J = []
    for e in comp:
        c_ = sec(t0, e["_c"])
        J.append({"c": c_, "req": c_ + (e.get("preprocessMs") or 0) / 1000, "ps": c_ + e["queuedMs"] / 1000,
                  "pe": c_ + (e["queuedMs"] + e["processingMs"]) / 1000, "fin": sec(t0, e["_fin"]), "e": e})
    out = {"key": key, "n": len(comp), "arr_h": len(J) / arr_s * 3600, "meta_h": meta["meta"].get("measuredJobsPerHour"), "J": J,
           "proc": [e["processingMs"] / 1000 for e in comp], "queued": [e["queuedMs"] / 1000 for e in comp],
           "infer": [e["inferMs"] / 1000 for e in comp], "conv": [e["convertMs"] / 1000 for e in comp], "xfer": [xfer(e) / 1000 for e in comp],
           "h": [(e["queuedMs"] - (e.get("preprocessMs") or 0)) / 1000 for e in comp]}
    end = max(j["fin"] for j in J)
    grid = [i * 1.0 for i in range(int(end) + 1)]
    wq = [sum(1 for j in J if j["req"] <= t < j["ps"]) for t in grid]
    ns = [sum(1 for j in J if j["c"] <= t < j["fin"]) for t in grid]
    busy = [sum(1 for j in J if j["ps"] <= t < j["pe"]) for t in grid]
    ev = sorted([(j["c"], 1) for j in J] + [(j["fin"], -1) for j in J], key=lambda x: (x[0], x[1]))
    cur_n = max_n = 0
    for _, dl in ev:
        cur_n += dl
        max_n = max(max_n, cur_n)
    out.update(max_ns_grid=max(ns), max_ns=max_n, max_wq=max(wq), max_busy=max(busy))
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
    out.update(ta=ta, tb=tb, stretch_h=len(dep) / (tb - ta) * 3600 if tb > ta else float("nan"),
               both_busy=sum(1 for t, b_ in zip(grid, busy) if ta <= t < tb and b_ >= c) / (tb - ta) * 100 if tb > ta else float("nan"))
    starts = sorted(j["ps"] for j in J if ta <= j["ps"] <= tb)
    gaps = [y - x for x, y in zip(starts, starts[1:])]
    out["s2s"] = mean(gaps)
    out["stretch_dep"] = len(dep)
    # Saturated time over the whole run: someone has asked for a slot and waits (wait interval [ask, ps - h0]) while all c slots
    # are held (hold interval [ps - h0, pe + h0]: own healthz, processing, DELETE). 'ask' = the backend.log stamp of the
    # job's cutout line (the slot is requested right after the cutout); created + preprocessMs would miss the wait for the
    # cutout semaphore (preprocess.cutout.max-concurrency=1, preprocessMs counts only the cutout work).
    cut = {}
    for line in open(f"{dd}/vm/backend.log", encoding="utf-8", errors="replace"):
        mm = re.search(r"\| (\S+)\s+INFO .*jobId=(" + UUID + r") cutout ", line)
        if mm:
            cut[mm.group(2)] = sec(t0, p(mm.group(1)))
    for j in J:
        j["ask"] = cut.get(j["e"]["jobId"], j["req"])
    out["ask_from_log"] = sum(1 for j in J if j["e"]["jobId"] in cut)
    out["cut_wait"] = [j["ask"] - j["req"] for j in J if j["e"]["jobId"] in cut]
    bounds = sorted({x for j in J for x in (j["ask"], j["ps"] - h0, j["pe"] + h0)})
    sat = []
    for x, y in zip(bounds, bounds[1:]):
        m_ = (x + y) / 2
        w = sum(1 for j in J if j["ask"] <= m_ < j["ps"] - h0)
        hld = sum(1 for j in J if j["ps"] - h0 <= m_ < j["pe"] + h0)
        if w >= 1 and hld >= c:
            sat.append([x, y])
    SU = union(sat)
    out.update(h0=h0, sat_s=sum(y - x for x, y in SU), sat_n=len(SU), arr_s=arr_s, win_s=sec(t0, t1))
    # Slot-time form: a slot is held for own healthz + processing + DELETE ~ processing + 2 h0, so with every slot busy the
    # capacity is c x 3600 / (mean processing + 2 h0). Processing depends on the load at c=2 (MODEL_LOCK wait, transfer), so it
    # is taken (a) over all jobs and (b) over the jobs that waited for a slot (ps - ask > h0 + 0.5 s: they ran while the
    # slots were full). Check: c=1 gives S_busy, and g2c2a (saturated whole run) gives its stretch value.
    waited = [j for j in J if j["ps"] - j["ask"] > h0 + 0.5]
    out.update(form_all=c * 3600 / (mean(out["proc"]) + 2 * h0),
               form_wait=c * 3600 / (mean([j["e"]["processingMs"] / 1000 for j in waited]) + 2 * h0) if waited else float("nan"),
               proc_wait=[j["e"]["processingMs"] / 1000 for j in waited])
    # S_busy (round-2 g_trace.py): processing start-to-start where the later job requested before the previous processing end
    Js = sorted(J, key=lambda j: j["ps"])
    bs = [cur["ps"] - prv["ps"] for prv, cur in zip(Js, Js[1:]) if cur["req"] < prv["pe"]]
    hg = [cur["ps"] - prv["pe"] for prv, cur in zip(Js, Js[1:]) if cur["req"] < prv["pe"] and cur["ps"] >= prv["pe"]]
    out.update(S_busy=mean(bs), S_busy_n=len(bs), handover=q(hg, .5) if hg else float("nan"))
    # start-to-start where the starting job waited for a slot (pstart - request > h0 + 0.5 s): any c
    wj = sorted(J, key=lambda j: j["ps"])
    ww = [cur["ps"] - prv["ps"] for prv, cur in zip(wj, wj[1:]) if cur["ps"] - cur["req"] > h0 + 0.5]
    out.update(wait_s2s=mean(ww), wait_s2s_n=len(ww))
    # least squares slope of jobs in system over the arrival window
    xs = [t for t in grid if t <= arr_s]
    ys = ns[:len(xs)]
    mx, my = st.fmean(xs), st.fmean(ys)
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
    out.update(slope_min=slope * 60, slope_cap=out["arr_h"] - slope * 3600)
    half = [t for t in xs if t >= arr_s / 2]
    ys2 = ns[int(half[0]):int(half[0]) + len(half)]
    mx2, my2 = st.fmean(half), st.fmean(ys2)
    slope2 = sum((x - mx2) * (y - my2) for x, y in zip(half, ys2)) / sum((x - mx2) ** 2 for x in half)
    arr2 = sum(1 for j in J if j["c"] >= arr_s / 2) / (arr_s / 2) * 3600
    out.update(slope2_min=slope2 * 60, arr2_h=arr2, slope2_cap=arr2 - slope2 * 3600)
    # fixed steady window: 300 s after the start to the end of arrivals
    stw = [j for j in J if 300 <= j["pe"] <= arr_s]
    out["steady_h"] = len(stw) / (arr_s - 300) * 3600
    # pod MODEL_LOCK from the reader stamps (same as round 3)
    ints = infer_intervals(dd, t0, t1)
    back = [sec(a_[1], b_[0]) for a_, b_ in zip(ints, ints[1:])]
    out.update(pred=len(ints), glued=sum(1 for g in back if abs(g) < 0.25), overlap=sum(1 for g in back if g < -0.25),
               lock_share=sum(sec(s_, e_) for s_, e_, _ in ints if ta <= sec(t0, s_) <= tb) / (tb - ta) * 100 if tb > ta else float("nan"))
    gr = gpurows(dd, t0, t1)
    out["gpu"] = mean([r[1] for r in gr])
    n_new, n_re, n_ports, _, _ = reuse(dd, comp)
    out.update(conn=f"new {n_new} reused {n_re} ports {n_ports}")
    sc = series(dd, "system_cpu", t0, t1)
    out["vm_cps"] = mean(sc) * 2 * sec(t0, t1) / len(comp) if sc else float("nan")
    out["vm_cpu"] = mean(sc) * 100 if sc else float("nan")
    k6m = meta["k6"]["metrics"]
    out["asset"] = k6m.get("http_req_duration{endpoint:asset}", {}).get("values", {})
    return out


H0 = {"g3c1": ("g0d4", 1), "g2c2t": ("g0d4", 2), "g2c2a": ("g0ka75", 2), "g3r165": ("g0r1", 1)}
S4 = {k: slot_stats(k, c, g0[src]["h0"]) for k, (src, c) in H0.items()}
for k, s in S4.items():
    c = H0[k][1]
    print(f"  {k} (c={c}): jobs {s['n']} ({s['arr_h']:.0f}/h measured, k6 meta {s['meta_h']}/h)")
    print(f"    processing {d(s['proc'])}")
    print(f"    infer p50 {q(s['infer'], .5):.2f} convert p50 {q(s['conv'], .5):.2f}; transfer + lock wait {d(s['xfer'])}")
    print(f"    queued {d(s['queued'])}; queued - preprocess p50 {q(s['h'], .5):.2f} s; h0 used {s['h0']:.3f} s (clean median of G0 run {H0[k][0]})")
    print(f"    jobs in system max {s['max_ns']} (exact; {s['max_ns_grid']} on the 1 s grid), waiting for a slot max {s['max_wq']}, processing at once max {s['max_busy']}")
    print(f"    saturated stretch (someone waits, round-3 rule) {s['ta']:.0f}-{s['tb']:.0f} s ({s['tb'] - s['ta']:.0f} s): {s['stretch_dep']} processing ends -> {s['stretch_h']:.0f}/h; "
          f"share with every slot processing {s['both_busy']:.1f} %; processing start-to-start {s['s2s']:.2f} s (x c = {s['s2s'] * c:.2f} s per slot)")
    print(f"    saturated time over the run (someone waits for a slot while all {c} slots are held; ask = cutout log stamp for "
          f"{s['ask_from_log']} of {s['n']} jobs) {s['sat_s']:.0f} s in {s['sat_n']} episodes ({s['sat_s'] / s['win_s'] * 100:.0f} % of the k6 window {s['win_s']:.0f} s); "
          f"wait for the cutout semaphore (ask - created - preprocessMs) {d(s['cut_wait'])} s")
    print(f"    slot-time form c x 3600 / (mean processing + 2 h0): all jobs {s['form_all']:.0f}/h; jobs that waited for a slot "
          f"(n {len(s['proc_wait'])}, processing mean {mean(s['proc_wait']):.2f} s) {s['form_wait']:.0f}/h")
    if c == 1:
        print(f"    S_busy (round-2 rule: start-to-start when the later job asked before the previous end) {s['S_busy']:.2f} s (n {s['S_busy_n']}) -> "
              f"{3600 / s['S_busy']:.0f}/h; handover gap (previous processing end -> next start, = DELETE + healthz) p50 {s['handover']:.3f} s; "
              f"start-to-start of jobs that waited > h0 + 0.5 s {s['wait_s2s']:.2f} s (n {s['wait_s2s_n']}) -> {3600 / s['wait_s2s']:.0f}/h")
    print(f"    slope of jobs in system over the arrival window {s['slope_min']:.2f}/min -> arrivals - slope = {s['slope_cap']:.0f}/h; "
          f"second half {s['slope2_min']:.2f}/min, arrivals {s['arr2_h']:.0f}/h -> {s['slope2_cap']:.0f}/h; completions in [300 s, end of arrivals] {s['steady_h']:.0f}/h")
    print(f"    pod predicts {s['pred']}, started within 0.25 s of the previous predict's end {s['glued']} ({s['glued'] / max(s['pred'] - 1, 1) * 100:.0f} %), "
          f"real overlaps {s['overlap']}; MODEL_LOCK busy share in the stretch {s['lock_share']:.1f} %; GPU util mean {s['gpu']:.1f} %")
    print(f"    connections {s['conn']}; VM system_cpu mean {s['vm_cpu']:.1f} %, {s['vm_cps']:.2f} core-s/job; k6 asset (client <- VM) "
          f"med {s['asset'].get('med', float('nan')) / 1000:.2f} p95 {s['asset'].get('p(95)', float('nan')) / 1000:.2f} p99 {s['asset'].get('p(99)', float('nan')) / 1000:.2f} max {s['asset'].get('max', float('nan')) / 1000:.2f} s")
c1, c2 = S4["g3c1"], S4["g2c2t"]
cap1 = 3600 / c1["S_busy"]
print("  capacity estimates (jobs/h) [계산]:")
print(f"    c=1 g3c1: S_busy {cap1:.0f}, slot-time form {c1['form_all']:.0f}, longest stretch {c1['stretch_h']:.0f}, slope {c1['slope_cap']:.0f} (second half {c1['slope2_cap']:.0f}); "
      f"measured arrivals {c1['arr_h']:.0f}/h were only {c1['arr_h'] / cap1:.2f} x capacity (requested 517/h assumed T_SEC 11.5 s), so the queue emptied at times")
lo2 = min(c2["slope_cap"], c2["slope2_cap"])
print(f"    c=2 g2c2t: not overloaded (slope {c2['slope_min']:.2f}/min, saturated {c2['sat_s'] / c2['win_s'] * 100:.0f} % of the k6 window), so the capacity is not observed"
      f" directly: lower bound = arrivals - slope {lo2:.0f}-{max(c2['slope_cap'], c2['slope2_cap']):.0f}; slot-time form with the processing of the jobs that"
      f" waited {c2['form_wait']:.0f} (n {len(c2['proc_wait'])}), with all jobs {c2['form_all']:.0f} (load-dependent: processing grows towards saturation);"
      f" longest stretch {c2['stretch_h']:.0f} ({c2['stretch_dep']} ends in {c2['tb'] - c2['ta']:.0f} s, few ends, first one biased up)")
r3, r2 = S4["g2c2a"], S4["g3r165"]
cap2 = 3600 / r2["S_busy"]
print(f"    check of the slot-time form on saturated runs: g2c2a form {r3['form_all']:.0f} (waited-only {r3['form_wait']:.0f}) vs its stretch {r3['stretch_h']:.0f} and slope "
      f"{r3['slope_cap']:.0f}/{r3['slope2_cap']:.0f}; g3r165 form {r2['form_all']:.0f} vs S_busy {cap2:.0f}; g3c1 form {c1['form_all']:.0f} vs S_busy {cap1:.0f}")
print(f"    ratio c=2 / c=1 on the same Pod (g2c2t / g3c1 S_busy): lower bound {lo2 / cap1:.2f}, slot-time form (waited jobs) {c2['form_wait'] / cap1:.2f}, "
      f"(all jobs) {c2['form_all'] / cap1:.2f}, longest stretch {c2['stretch_h'] / cap1:.2f}")
print(f"    round 3/2 (different Pods): g2c2a stretch {r3['stretch_h']:.0f}/h, slope {r3['slope_cap']:.0f}/{r3['slope2_cap']:.0f}/h vs g3r165 S_busy {cap2:.0f}/h -> "
      f"{r3['stretch_h'] / cap2:.2f} / {r3['slope_cap'] / cap2:.2f}-{r3['slope2_cap'] / cap2:.2f} (round 3 doc: ~1.3)")
print(f"    mean processing c=2 / c=1: round 4 {mean(c2['proc']) / mean(c1['proc']):.2f} ({mean(c2['proc']):.2f} / {mean(c1['proc']):.2f} s), "
      f"round 3/2 {mean(r3['proc']) / mean(r2['proc']):.2f} ({mean(r3['proc']):.2f} / {mean(r2['proc']):.2f} s)")

# ---------------------------------------------------------------------------------------------------------------------
print("\n== 5. F1b-real: inference.timeout-ms = 5000 with the real model; f1br4 (round 4, PR #11 cancel-on-DELETE) vs f1br1 (round 3)")
for k in ("f1br4", "f1br1"):
    dd, meta, t0, t1, att = load(k)
    fin = final(att)
    ids = {e["jobId"] for e in att}
    print(f"  {k}: jobs {len(fin)}, attempts {len(att)} {count(e['result'] for e in att)}, retries (attempt > 1) {sum(1 for e in att if e['attempt'] > 1)}, "
          f"final {count(e['result'] for e in fin.values())}")
    print(f"    processing {d([e['processingMs'] for e in att], 1000, 3)}; queued {d([e['queuedMs'] for e in att], 1000)}; "
          f"preprocess on retries {count(e.get('preprocessResult') for e in att if e['attempt'] > 1)}")
    k6m = meta["k6"]["metrics"]
    print(f"    k6 flowOkRatio {k6m['load_flowOkRatio']['values']} (failToken {meta['meta'].get('failToken')}, failRatio {meta['meta'].get('failRatio')}: "
          f"flows with the token are expected to fail, so they count as ok)")
    jobs, acc = podlog(dd)
    lo, hi = t0, t1 + dt.timedelta(seconds=120)
    jl = [x for x in jobs if x[1] in ids and lo <= x[0] <= hi]
    lines = open(f"{dd}/gpu/inference-ts.log", encoding="utf-8", errors="replace").read().splitlines()
    tb_n = sum(1 for l in lines if "Traceback" in l)
    crash = [x for x in jl if x[2] == "conversion crashed"]
    canc = [x for x in jl if x[2].startswith("work folder deleted, cancelled")]
    before_conv = [x for x in jl if "stopping before conversion" in x[2]]
    after_err = [x for x in jl if "stopping after a conversion error" in x[2]]
    before_model = [x for x in jl if "stopping before the model" in x[2]]
    done = [x for x in jl if x[2].startswith("infer ")]
    dele_acc = [x for x in acc if x[2] == "DELETE" and any(i in x[3] for i in ids)]
    print(f"    pod job lines: cancelled-in-progress {len(canc)}, stopping before conversion {len(before_conv)}, after a conversion error {len(after_err)}, "
          f"before the model {len(before_model)}, conversion crashed {len(crash)}, predict+convert finished {len(done)}; Traceback lines in the log {tb_n}")
    tim = timing(dd)
    if tim:
        tposts = [t for t in tim if t["meth"] == "POST" and lo <= t["at"] <= hi]
        tdels = [t for t in tim if t["meth"] == "DELETE" and any(i in t["path"] for i in ids)]
        acc_post = [x for x in acc if x[2] == "POST" and lo <= x[0] <= hi]
        print(f"    timing lines: POST /infer/upload {len(tposts)} statuses {count(t['status'] for t in tposts)} err {count(t['err'] for t in tposts)}, "
              f"uvicorn access lines for POST {len(acc_post)} (an answer to a client that already left has no access line); "
              f"DELETE {len(tdels)} statuses {count(t['status'] for t in tdels)}")
        alld = [t for t in tim if t["meth"] == "DELETE"]
        print(f"    DELETE timing lines in this log file: {len(alld)} statuses {count(t['status'] for t in alld)}")
        every = [t for r_ in R4.values() for t in timing(BASE + r_) if t["meth"] == "DELETE"]
        print(f"    DELETE timing lines over the four round-4 Pod logs (one service run, incl. the g0d4 check job): {len(every)} statuses "
              f"{count(t['status'] for t in every)}; POST statuses {count(t['status'] for r_ in R4.values() for t in timing(BASE + r_) if t['meth'] == 'POST')}")
        work = [(t["start"] - t["body"]) / 1000 for t in tposts if t["start"] is not None and t["body"] is not None]
        print(f"    Pod work of the abandoned attempts (POST start_ms - body_ms, predict ran to the end in each, then stop or a failed conversion): "
              f"{d9(work)} s, sum {sum(work):.0f} s; upload body_ms {d9([t['body'] / 1000 for t in tposts if t['body'] is not None])} s")
        # DELETE arrival vs that attempt's POST arrival (same job, nearest preceding POST is unknown by id: use the VM timeline)
        da = []
        for e in att:
            ds_ = [t for t in tdels if e["jobId"] in t["path"] and e["_fin"] - dt.timedelta(seconds=2) <= t["at"] <= e["_fin"] + dt.timedelta(seconds=10)]
            if ds_:
                da.append(sec(e["_fin"], ds_[0]["at"]))
        print(f"    DELETE head on the Pod - VM finishedAt of the attempt (mixed clocks): {d9(da)} s (n {len(da)} of {len(att)} attempts)")
        seq = sorted([("POST", t["at"], t["port"]) for t in tposts] + [("DELETE", t["at"], t["port"]) for t in tdels], key=lambda x: x[1])
        post_on_prev_del = sum(1 for a_, b_ in zip(seq, seq[1:]) if a_[0] == "DELETE" and b_[0] == "POST" and a_[2] == b_[2])
        del_new = sum(1 for i, x in enumerate(seq) if x[0] == "DELETE" and all(y[2] != x[2] for y in seq[:i]))
        print(f"    connections: POST/DELETE ports distinct {len({x[2] for x in seq})} for {len(seq)} requests; DELETE on a port not used before "
              f"{del_new} of {len(tdels)} (the timed-out POST's connection is dropped, so each DELETE opens a new one: one extra round trip); "
              f"next POST on the previous DELETE's port {post_on_prev_del}")
        g3 = J2["g3c1"][0]
        inf_med = q([r["e"]["inferMs"] / 1000 for r in g3], .5)
        started = len(att) - len(before_model)
        print(f"    predicts started = attempts - 'stopping before the model' = {started}; abandoned GPU time estimate "
              f"{started} x g3c1 median inferMs {inf_med:.2f} s = {started * inf_med:.0f} s (none of it produced a GLB)")
        wa = open(f"{dd}/gpu/workdir-after.txt", encoding="utf-8").read()
        folders = re.findall(UUID, wa)
        mm = re.search(r"job folders: (\d+)", wa)
        nf = int(mm.group(1)) if mm else len(folders)
        lo_, hi_ = wilson(nf, len(att))
        print(f"    job folders left after the run (workdir-after.txt): {nf}; per attempt {nf}/{len(att)}, Wilson 95 % {lo_:.3f}-{hi_:.3f}")
        for name, v in (("f1br4", alld), ("all four runs", every)):
            n500 = sum(1 for t in v if t["status"] >= 500)
            lo_, hi_ = wilson(n500, len(v))
            print(f"    DELETE 5xx ({name}) {n500} of {len(v)} (Wilson 95 % {lo_:.4f}-{hi_:.4f})")
    else:
        print(f"    (no timing lines in round 3) access log: DELETE {len(dele_acc)} statuses {count(x[4] for x in dele_acc)}; POST /infer/upload answered "
              f"{len([x for x in acc if x[2] == 'POST' and lo <= x[0] <= hi])}")
        left = open(f"{dd}/gpu/f1b-leftovers.txt", encoding="utf-8").read()
        lf = list(dict.fromkeys(re.findall(r"== /workspace/beside-work/(" + UUID + r")/", left)))
        lo_, hi_ = wilson(len(lf), len(att))
        print(f"    job folders left (f1b-leftovers.txt) {len(lf)}; per attempt {len(lf)}/{len(att)}, Wilson 95 % {lo_:.3f}-{hi_:.3f}")
        n500 = sum(1 for x in dele_acc if x[4] >= 500)
        lo_, hi_ = wilson(n500, len(dele_acc))
        print(f"    DELETE 5xx {n500} of {len(dele_acc)} (Wilson 95 % {lo_:.3f}-{hi_:.3f})")
    gr = gpurows(dd, t0, t1 + dt.timedelta(seconds=60))
    print(f"    GPU util mean {mean([r[1] for r in gr]):.1f} %, memory {min(r[2] for r in gr):.0f}-{max(r[2] for r in gr):.0f} MiB")
    print(f"    VM inference health events in the window: {[l.split('inference service ')[1].strip() for l in open(f'{dd}/vm/inference-events.log', encoding='utf-8') if 'InferenceHealthMonitor' in l]}")

# ---------------------------------------------------------------------------------------------------------------------
print("\n== 6. anything surprising: health flaps, 5xx, retries, k6 errors, Pod errors, path")
for k in R4:
    dd, meta, t0, t1, att = load(k)
    v = meta["verdict"]
    hs = [l.split("inference service ")[1].strip() for l in open(f"{dd}/vm/inference-events.log", encoding="utf-8") if "InferenceHealthMonitor" in l]
    flaps = open(f"{dd}/vm/inference-flaps.txt", encoding="utf-8").read().strip()
    bl = open(f"{dd}/vm/backend.log", encoding="utf-8", errors="replace").read().splitlines()
    warn = count(re.sub(UUID, "J", re.sub(r"^.*?(WARN|ERROR) \d+ --- \[[^\]]*\] (\S+)\s+: (.{0,60}).*$", r"\1 \2 \3", l))
                 for l in bl if re.search(r" (WARN|ERROR) \d+ --- ", l) and t0 - dt.timedelta(seconds=60) <= p(l.split()[2]) <= t1 + dt.timedelta(seconds=60))
    stats = {}
    for row in csv.DictReader(open(f"{dd}/prometheus/http_rps_by_status.csv", encoding="utf-8")):
        if t0 < p(row["time"]) <= t1:
            stats.setdefault(row["series"], []).append(float(row["value"]))
    r5 = series(dd, "http_5xx_ratio", t0, t1)
    tim = timing(dd)
    tw = [t for t in tim if t0 - dt.timedelta(seconds=30) <= t["at"] <= t1 + dt.timedelta(seconds=120)]
    odd = count(f"{t['meth']} {re.sub(UUID, 'J', t['path'])} {t['status']}" for t in tw if t["status"] >= 400)
    gone = sum(1 for t in tw if t["gone"] is not None)
    errs = count(t["err"] for t in tw if t["err"])
    stamp_lag = [sec(t["at"] + TD(t["end"]), t["stamp"]) for t in tw if t["end"] is not None]
    lines = open(f"{dd}/gpu/inference-ts.log", encoding="utf-8", errors="replace").read().splitlines()
    bad = sum(1 for l in lines if re.search(r"Traceback|ERROR|crashed|Exception", l))
    ts_line = [l for l in open(f"{dd}/vm/tailscale-status.txt", encoding="utf-8") if "beside-gpu" in l]
    comp = [e for e in att if e["result"] == "COMPLETED"]
    slow = [e for e in comp if (e.get("inferMs") or 0) > 6000 or (e.get("convertMs") or 0) > 2000]
    print(f"  {k}: health events {hs} (inference-flaps.txt '{flaps}'); backend WARN/ERROR in the window {warn or 'none'}")
    print(f"    VM statuses {sorted(stats)}, 5xx ratio points {len(r5)}; k6 unexpected {v['unexpectedCount']}, checks failed {v['checksFailed']}, "
          f"dropped {v['droppedIterations']}, http status 0/4xx/5xx {v['httpStatus']['0']}/{v['httpStatus']['4xx']}/{v['httpStatus']['5xx']}; "
          f"retries (attempt > 1) {sum(1 for e in att if e['attempt'] > 1)}")
    print(f"    Pod timing lines in the window {len(tw)}: status >= 400 {odd or 'none'}, client gone before the end {gone}, err {errs or 'none'}; "
          f"Traceback/ERROR/crashed/Exception lines {bad}; reader stamp - (at + end_ms) {d9(stamp_lag, 3)} s")
    print(f"    slow model jobs (inferMs > 6000 or convertMs > 2000): {len(slow)} of {len(comp)} "
          + ", ".join(f"{e['jobId'][:8]} infer {e['inferMs']} convert {e['convertMs']}" for e in slow))
    k6m = meta["k6"]["metrics"]
    parts = []
    for ep in ("create", "poll", "asset"):
        vv = k6m.get(f"http_req_duration{{endpoint:{ep}}}", {}).get("values")
        if vv and vv.get("count"):
            parts.append(f"{ep} med {vv['med']:.0f} p95 {vv['p(95)']:.0f} p99 {vv.get('p(99)', float('nan')):.0f} max {vv['max']:.0f} ms (n {vv['count']})")
    print(f"    k6 client (US-CA) <-> VM durations: " + ("; ".join(parts) or f"no endpoint-tagged durations ({meta['meta'].get('script')})"))
    print(f"    tailnet path at the end: {ts_line[0].split('linux', 1)[1].strip() if ts_line else '?'}")
prev = None
print("  VM -> Pod tailnet bytes per run (tailscale status counters at the end of each run; differences include the gaps between runs):")
for k in R4:
    dd, meta, t0, t1, att = load(k)
    l = [x for x in open(f"{dd}/vm/tailscale-status.txt", encoding="utf-8") if "beside-gpu" in x][0]
    tx, rx = int(re.search(r"tx (\d+)", l).group(1)), int(re.search(r"rx (\d+)", l).group(1))
    if prev:
        print(f"    {k}: tx +{(tx - prev[0]) / 1e6:.1f} MB, rx +{(rx - prev[1]) / 1e6:.1f} MB for {len(att)} attempts "
              f"({(tx - prev[0]) / len(att) / 1e6:.2f} / {(rx - prev[1]) / len(att) / 1e6:.2f} MB per attempt)")
    else:
        print(f"    {k}: tx {tx / 1e6:.1f} MB, rx {rx / 1e6:.1f} MB since the tailscale session started (includes the operator check job)")
    prev = (tx, rx)
