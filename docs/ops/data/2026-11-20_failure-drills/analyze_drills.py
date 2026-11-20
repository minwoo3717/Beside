#!/usr/bin/env python3
"""Per-scenario summary of the 2026-11-20 failure drills from the raw client data (stdlib only).

  python analyze_drills.py            # run from this folder; prints the table used in docs/ops/2026-11-20_failure-drills.md

For each scenario window (UTC, taken from vm/timeline.txt) it reports:
  probe   outside GET /api/v1/healthz once per second (client/*/probe.jsonl): non-200 answers and the longest run of them
          (first non-200 -> first 200 after it), with the kinds of failure seen
  jobs    jobs whose POST fell inside the window (client/*/flows.jsonl): final status and error code
The windows include the time each scenario was observed after the injection. Backend recreation for a new configuration
('up ...' lines in the timeline) happens between windows, so its short outages are not counted against a scenario.
"""
import json
import os
import sys
from collections import Counter
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))

WINDOWS = [   # id, start, end (UTC, 2026-11-20), client run
    ("FD1 backend kill -9", "03:13:37.1", "03:14:03.6", "b1"),
    ("FD2 backend SIGSTOP 180 s", "03:14:03.6", "03:17:30.0", "b1"),
    ("FD7 caddy kill -9", "03:17:33.2", "03:17:43.0", "b1"),
    ("FD17 wrong inference token", "03:18:02.9", "03:20:02.9", "b1"),
    ("FD13 inference stuck, healthz UP", "03:20:37.0", "03:25:07.1", "b1"),
    ("FD12 inference paused, offline wait 180 s", "03:26:26.9", "03:31:56.9", "b1"),
    ("FD5 DB file deleted -> restore", "03:34:22.9", "03:35:18.8", "b1"),
    ("FD4 disk full", "03:35:57.9", "03:41:40.0", "b1"),
    ("FD10 tailnet block 120 s", "03:44:17.3", "03:48:22.6", "b2"),
    ("FD11 netem 300 ms / 5 % 180 s", "03:48:31.0", "03:53:31.1", "b2"),
    ("FD14 uvicorn SIGSTOP 120 s", "03:53:40.6", "03:57:47.3", "b2"),
    ("FD18 model load failure", "03:57:58.6", "04:02:49.3", "b2"),
    ("FD15 GPU memory exhausted", "04:03:13.0", "04:07:04.7", "b2"),
    ("FD16b Pod stop under load", "04:07:14.1", "04:24:07.0", "b2"),
]


def ts(s):
    s = s.rstrip("Z")
    if "T" not in s:
        s = "2026-11-20T" + s
    return datetime.fromisoformat(s)


def load(run, name):
    path = os.path.join(HERE, "client", run, name)
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def probe_summary(rows, a, b):
    inside = [r for r in rows if a <= ts(r["t"]) <= b]
    bad = [r for r in inside if r["code"] != 200]
    longest, cur_start, last_bad = 0.0, None, None
    for r in inside:
        t = ts(r["t"])
        if r["code"] != 200:
            cur_start = cur_start or t
            last_bad = t
        elif cur_start is not None:
            longest = max(longest, (t - cur_start).total_seconds())
            cur_start = None
    if cur_start is not None and last_bad is not None:
        longest = max(longest, (last_bad - cur_start).total_seconds())
    kinds = Counter((r["code"], (r.get("err") or "").split(":")[0][:22]) for r in bad)
    return len(inside), len(bad), longest, kinds


def job_summary(rows, a, b):
    inside = [r for r in rows if a <= ts(r["postAt"]) <= b]
    c = Counter()
    worst = 0
    for r in inside:
        if r.get("final") == "NOT_CREATED":
            c[f"POST {r.get('postStatus')} {r.get('postCode') or ''}".strip()] += 1
        else:
            c[f"{r.get('final')} {r.get('errorCode') or ''}".strip()] += 1
            if r.get("final") == "COMPLETED":
                worst = max(worst, ((r.get("timings") or {}).get("totalMs") or 0))
    return len(inside), c, worst


def main():
    sys.stdout.reconfigure(encoding="utf-8")   # Korean headers; -I ignores PYTHONIOENCODING
    cache = {}
    print("| 시나리오 | 탐침 수 | 200 아님 | 가장 긴 연속 끊김(s) | 끊김 종류 | 그 사이 만든 작업 | 작업 결과 | 완료 작업 최대 totalMs(s) |")
    print("|---|---|---|---|---|---|---|---|")
    for name, s, e, run in WINDOWS:
        a, b = ts(s), ts(e)
        if run not in cache:
            cache[run] = (load(run, "probe.jsonl"), load(run, "flows.jsonl"))
        probes, flows = cache[run]
        n, nb, longest, kinds = probe_summary(probes, a, b)
        nj, jc, worst = job_summary(flows, a, b)
        kind_txt = ", ".join(f"{k[0]}{(' ' + k[1]) if k[1] else ''}×{v}" for k, v in kinds.most_common(3)) or "-"
        job_txt = ", ".join(f"{k}×{v}" for k, v in jc.most_common()) or "-"
        print(f"| {name} | {n} | {nb} | {longest:.1f} | {kind_txt} | {nj} | {job_txt} | {worst / 1000:.1f} |")


if __name__ == "__main__":
    main()
