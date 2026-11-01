"""Collect one load-test run into its result folder (standard library only).

    RUN=docs/perf/load/data/2026-11-10_1400-a1r1      # one folder per k6 run: <YYYY-MM-DD_HHMM>-<RUN_ID>
    python scripts/load/collect.py $RUN --scenario A1 --tier T3 \
        --summary $RUN/k6-summary.json --k6-version "$(k6 version)" \
        --vm-provider <provider> --vm-type <type> --vm-vcpu 2 --vm-ram-gb 4 --uplink-mbps 94.1 --client-cpu-max-pct 41 \
        --server-sha <deployed commit> --photo scripts/load/out/fixtures/photo-400k.png \
        --prom-url http://localhost:9090 --attach $RUN/vm/events-lt.jsonl --attach $RUN/vm/docker-stats.txt

Run it once per run folder (scripts/load/README.md "결과 폴더"); the VM-side files of that run sit in its vm/
subfolder and reach run.json only through --attach.

Writes <run dir>/run.json (the conditions of docs/perf/load/TRAFFIC_MODEL.md §10: VM spec, commit SHAs, k6 version,
uplink, photo sizes, tier, scenario, window, the k6 verdict) and <run dir>/prometheus/<name>.csv: a fixed list of
query_range queries over the run window (start/end from --start/--end or the k6 summary, padded by --pad-s).
--prom-url is any URL that reaches the VM's Prometheus (SSH tunnel, Tailscale); it is not opened to the internet.
Files given with --attach are copied to <run dir>/attachments/. Exit code: 0 done, 1 written but some query failed, 2 nothing written (bad arguments).

run.json "outcome" says how the k6 run ended (k6_outcome: normal, ruleStop, aborted, unknown) from the summary's
verdict.aborted and verdict.guard, and "status"/"resultCandidate" say whether it may be cited as [실험 결과]: not a local
run, not one aborted for a reason other than capacity, not a summary without verdict.aborted. A run stopped by an error
rule stays a candidate, but its status says to rule out a server restart or a network drop first; a run whose guard
could not read Prometheus is named in the status. collect.py prints the status and how many of the 18 CSVs it wrote.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
DROP_LABELS = {"__name__", "env", "instance", "job"}


def selector(env: str, extra: str = "") -> str:
    parts = [f'env="{env}"'] if env else []
    if extra:
        parts.append(extra)
    return "{" + ",".join(parts) + "}" if parts else ""


def queries(env: str) -> list:
    """(name, PromQL) pairs, the same for every run so runs can be compared file by file. One CSV each (18)."""
    s = lambda extra="": selector(env, extra)  # noqa: E731
    req_count = f"http_server_requests_seconds_count{s()}"
    status_5xx = s('status=~"5.."')  # no backslashes inside f-string braces: Python 3.10 on the VM
    heap = s('area="heap"')
    # jvm_heap_after_gc is the "GC 뒤 힙 < 75 %" criterion (TRAFFIC_MODEL.md §8), a 0..1 ratio to compare with 0.75.
    # It is Micrometer's jvm_memory_usage_after_gc_percent (JvmHeapPressureMetrics, Spring Boot 3.2 / Micrometer 1.12):
    # the long-lived heap pool (G1 Old Gen; Tenured Gen under Serial/Parallel) used after the last GC / that pool's
    # max, set after every GC, young ones included, and 0..1 despite "_percent". Under G1 Old Gen's max is the whole
    # -Xmx, so it reads as "share of the heap still used after GC". Old Gen garbage that no mixed or full GC has
    # collected yet still counts, so it errs high (on the safe side). Not the other candidates:
    #   jvm_heap_used / jvm_heap_max  sum(jvm_memory_used_bytes{area="heap"}) is the heap at scrape time, G1 Eden
    #     included. It saw-tooths between young GCs (0.35..0.53 against 0.35 after GC in a k6 run on -Xmx128m) and the
    #     young generation may grow to 60 % of the heap (G1MaxNewSizePercent), so a reading above 0.75 proves nothing;
    #     it is kept for the "힙 사용·최대" CSVs and for comparison with earlier runs.
    #   jvm_gc_live_data_size_bytes / jvm_gc_max_data_size_bytes  (the Grafana JVM panel) Micrometer sets the live
    #     size only after a major GC or a GC that shrinks Old Gen, so on G1 it can stay 0 or stale for a whole run
    #     (it read 0.0 all through a k6 run on the mock jar with G1 and -Xmx1536m), which would read as a pass.
    # max(): one value per scrape (one backend; with several, the fullest). Checked names on /actuator/prometheus:
    # jvm_memory_usage_after_gc_percent{area="heap",env="loadtest",pool="long-lived"}.
    heap_long_lived = s('area="heap",pool="long-lived"')
    return [
        ("http_p95_by_uri", f"histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{s()}[1m])))"),
        ("http_p99_by_uri", f"histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket{s()}[1m])))"),
        ("http_rps_by_uri", f"sum by (uri) (rate({req_count}[1m]))"),
        ("http_rps_by_status", f"sum by (status) (rate({req_count}[1m]))"),
        ("http_5xx_ratio", f"sum(rate(http_server_requests_seconds_count{status_5xx}[1m])) / clamp_min(sum(rate({req_count}[1m])), 1e-9)"),
        ("jobs_queue", f"sum by (state) (beside_jobs_queue{s()})"),
        ("jobs_finished_rate", f"sum by (result, code) (rate(beside_jobs_finished_total{s()}[1m]))"),
        ("asset_downloads_rate", f"sum by (outcome) (rate(beside_asset_downloads_total{s()}[1m]))"),
        ("process_cpu", f"process_cpu_usage{s()}"),
        ("system_cpu", f"system_cpu_usage{s()}"),
        ("jvm_heap_used", f"sum(jvm_memory_used_bytes{heap})"),
        ("jvm_heap_max", f"sum(jvm_memory_max_bytes{heap})"),
        ("jvm_heap_after_gc", f"max(jvm_memory_usage_after_gc_percent{heap_long_lived})"),
        ("jvm_gc_pause_rate", f"sum(rate(jvm_gc_pause_seconds_sum{s()}[1m]))"),
        ("tomcat_busy", f"tomcat_threads_busy_threads{s()}"),
        ("hikari_pending", f"hikaricp_connections_pending{s()}"),
        ("process_open_files", f"process_files_open_files{s()}"),
        ("disk_free", f"disk_free_bytes{s()}"),
    ]


def series_label(metric: dict) -> str:
    items = sorted((k, v) for k, v in metric.items() if k not in DROP_LABELS)
    return ";".join(f"{k}={v}" for k, v in items) or "all"


def iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).isoformat(timespec="seconds")


def parse_time(text: str) -> float:
    value = text.strip()
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    dt = datetime.fromisoformat(value)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.timestamp()


def matrix_rows(doc: dict) -> list:
    """[unix, time, series, value] rows from a Prometheus query_range response."""
    if doc.get("status") != "success":
        raise ValueError(f"prometheus status {doc.get('status')}: {doc.get('error')}")
    data = doc.get("data") or {}
    if data.get("resultType") != "matrix":
        raise ValueError(f"expected a matrix, got {data.get('resultType')}")
    rows = []
    for series in data.get("result", []):
        label = series_label(series.get("metric", {}))
        for ts, value in series.get("values", []):
            rows.append([f"{float(ts):.3f}", iso(float(ts)), label, value])
    rows.sort(key=lambda r: (r[2], float(r[0])))
    return rows


def write_csv(path: Path, rows: list) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(["unix", "time", "series", "value"])
        writer.writerows(rows)


def http_fetch(url: str, timeout: float = 30.0) -> dict:
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def range_url(prom_url: str, query: str, start: float, end: float, step: float) -> str:
    params = urllib.parse.urlencode({"query": query, "start": f"{start:.3f}", "end": f"{end:.3f}", "step": f"{step:g}"})
    return f"{prom_url.rstrip('/')}/api/v1/query_range?{params}"


def pull(prom_url: str, env: str, start: float, end: float, step: float, out_dir: Path, fetch=http_fetch) -> list:
    results = []
    for name, query in queries(env):
        entry = {"name": name, "query": query, "file": f"prometheus/{name}.csv"}
        try:
            rows = matrix_rows(fetch(range_url(prom_url, query, start, end, step)))
            write_csv(out_dir / "prometheus" / f"{name}.csv", rows)
            entry.update(series=len({r[2] for r in rows}), points=len(rows))
        except (OSError, ValueError, urllib.error.URLError) as exc:
            entry.update(error=f"{type(exc).__name__}: {exc}", file=None)
        results.append(entry)
    return results


def redact_url(url: str) -> str:
    parts = urllib.parse.urlsplit(url)
    netloc = parts.hostname or ""
    if parts.port:
        netloc += f":{parts.port}"
    return urllib.parse.urlunsplit((parts.scheme, netloc, parts.path, "", ""))


def git_info(repo: Path = REPO) -> dict:
    def git(*args):
        try:
            return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=False).stdout.strip()
        except OSError:
            return ""
    return {"gitSha": git("rev-parse", "HEAD") or None, "scriptsDirty": bool(git("status", "--porcelain", "--", "scripts/load"))}


def file_info(path) -> dict:
    data = Path(path).read_bytes()
    return {"name": Path(path).name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def is_local(url) -> bool:
    host = (urllib.parse.urlsplit(url or "").hostname or "").lower()
    return host in ("localhost", "127.0.0.1", "::1")


def k6_outcome(summary: dict | None) -> dict:
    """How the k6 run ended, from its summary's verdict (lib/summary.js verdict.aborted, verdict.guard).

    ended: None without --summary (e.g. F4, window from --start/--end); "unknown" when the summary has no
    verdict.aborted (written before lib/summary.js reported aborts: an aborted run then read "all passed");
    "normal" when verdict.aborted is null; "ruleStop" when the abortOnFail error rule ("threshold") or the guard's 5xx
    rule (load_guardAbort 1) stopped the load. lib/summary.js still computes its stages and breaking point
    (buildReport's brokeAt), because that is how the A1 search ends on the VM when a level breaks; but the same rules
    fire when the backend restarts or is redeployed, Caddy goes down or the load PC loses its uplink (a real k6 v2.3.0
    run whose server was killed at about 0.5 req/s ended exactly so), so the name says which rule fired, not why;
    "aborted" for any other stop (setup, guard disk or unreadable Prometheus, Ctrl+C or REST stop, flows cut off in the
    drain), which says nothing about capacity.
    guardFailures: Prometheus answers the guard could not read (verdict.guard.failures; None without PROM_URL)."""
    if summary is None:
        return {"ended": None, "abortedBy": None, "reason": None, "guardFailures": None, "guardLastError": None}
    verdict = summary.get("verdict") or {}
    guard = verdict.get("guard") or {}
    out = {"ended": "normal", "abortedBy": None, "reason": None, "guardFailures": guard.get("failures"),
           "guardLastError": guard.get("lastError")}
    if "aborted" not in verdict:
        out["ended"] = "unknown"
        return out
    aborted = verdict.get("aborted")
    if not aborted:
        return out
    by = aborted.get("by")
    out.update(abortedBy=by, reason=aborted.get("reason"))
    metrics = (summary.get("k6") or {}).get("metrics") or {}
    code = ((metrics.get("load_guardAbort") or {}).get("values") or {}).get("value")
    guard_5xx = code == 1 or (code is None and str(aborted.get("reason") or "").startswith("server 5xx ratio"))
    out["ended"] = "ruleStop" if by == "threshold" or (by == "guard" and guard_5xx) else "aborted"
    return out


CITE = "인용할 때 vm·k6Version·server.sha·client.uplinkMbps·photos 를 함께 적는다"


def run_status(outcome: dict, local: bool) -> tuple:
    """(status text, resultCandidate) for run.json: only a run on the VM that ended normally, or was stopped by an error
    rule, is a [실험 결과] candidate. A rule stop names the rule, not a cause: it is a capacity result only once a
    server restart or network drop at that moment is ruled out, which collect.py cannot see, so the text says so. A
    run whose guard could not read Prometheus stays a candidate, with the gap named in the text."""
    ended, by = outcome["ended"], outcome["abortedBy"]
    failures = outcome.get("guardFailures") or 0
    caveat = (f"주의: 가드가 Prometheus 응답 {failures}개를 읽지 못했다(마지막: {outcome.get('guardLastError')}) — 그동안 "
              "중단 조건(5xx·디스크)을 지켜보지 못했고 prometheus/*.csv 에도 빈 구간이 있을 수 있다") if failures > 0 else ""
    if ended in ("normal", None, "ruleStop") and not local:
        # a candidate: the caveat goes before the citing rule, so the label never reads plain when the guard was blind
        body = ""
        if ended == "ruleStop":
            rule = "가드의 5xx 기준" if by == "guard" else "abortOnFail 오류 기준"
            body = (f"오류 기준으로 멈춘 실행({rule}, k6.verdict.aborted.by = {by}): 용량 한계일 때만 결과다 — 그 시각에 "
                    "서버 재시작·재배포·네트워크 끊김이 없었음을 VM 쪽 기록(vm/backend.log)과 k6 출력으로 확인한 뒤 "
                    "k6.verdict.stages·breakingPoint(·breakingPointNote)를 읽는다. thresholdsOk 는 늘 false 다. ")
        return f"[실험 결과] 원시 데이터 — {body}{caveat + '. ' if caveat else ''}{CITE}", True
    if local:
        text = "동작 확인용 로컬 실행 — 보고서에 인용하지 않는다"
        if ended in ("aborted", "ruleStop"):
            text += f" (중단된 실행, k6.verdict.aborted.by = {by})"
    elif ended == "unknown":
        text = ("인용하지 않는다 — k6 요약에 verdict.aborted 가 없다(중단을 알리기 전의 lib/summary.js): 중단된 실행도 "
                "'all passed' 로 적혔을 수 있으니 지금 스크립트로 다시 돌린다")
    else:  # aborted for a reason other than capacity
        reason = f": {outcome['reason']}" if outcome.get("reason") else ""
        text = (f"인용하지 않는다 — 중단된 실행(k6.verdict.aborted.by = {by}): 용량과 무관한 중단이라 [실험 결과] 가 "
                f"아니다. 원인을 고쳐 다시 돌린다{reason}")
    return text + (f" / {caveat}" if caveat else ""), False


def build_run_json(args, summary: dict | None, window: dict, tool: dict, photos: list, prom: dict, attachments: list) -> dict:
    meta = (summary or {}).get("meta", {})
    base_url = args.base_url or meta.get("baseUrl")
    local = is_local(base_url)
    outcome = k6_outcome(summary)
    status, candidate = run_status(outcome, local)
    return {
        "schema": "beside-load-run/1",
        "status": status,
        "resultCandidate": candidate,
        "outcome": outcome,
        "scenario": args.scenario or meta.get("scenario"),
        "tier": args.tier or meta.get("tier") or None,
        "runId": meta.get("runId"),
        "window": window,
        "target": {"baseUrl": base_url, "local": local, "serverSha": args.server_sha},
        "tool": dict(tool, k6Version=args.k6_version, script=meta.get("script")),
        "vm": {"provider": args.vm_provider, "type": args.vm_type, "vcpu": args.vm_vcpu, "ramGb": args.vm_ram_gb,
               "diskGb": args.vm_disk_gb, "region": args.vm_region},
        "client": {"uplinkMbps": args.uplink_mbps, "downlinkMbps": args.downlink_mbps, "cpuMaxPct": args.client_cpu_max_pct},
        "photos": photos,
        "k6": None if summary is None else {"file": args.summary_name, "meta": meta, "verdict": summary.get("verdict")},
        "prometheus": prom,
        "attachments": attachments,
        "note": args.note,
        "honesty": [
            "mock 결과는 '생성을 뺀 API 처리량', 스텁 결과는 '큐 동작 검증(생성 시간은 가정 T)'이다.",
            "k6 VU 수는 동시 사용자 수가 아니다. 사용자 수는 k6.meta.stages[].users(도착률 환산)로 적는다.",
            "'API 계층이 T3 를 통과했다'는 T3 10분 유지, dropped_iterations 0, 기준 충족, 클라이언트 CPU < 70 %, 업링크 비포화일 때만 쓴다.",
            "resultCandidate 가 false 인 실행(로컬, 용량과 무관하게 중단된 실행, 끝난 방식을 모르는 요약)은 [실험 결과] 로 인용하지 않는다.",
            "오류 기준으로 멈춘 실행(outcome.ended = ruleStop)은 후보지만, 그 시각에 서버 재시작·재배포·네트워크 끊김이 없었을 때만 용량 한계로 쓴다.",
            "GC 뒤 힙 < 75 % 는 prometheus/jvm_heap_after_gc.csv(0~1)로 본다. jvm_heap_used/max 는 Eden 을 포함한 순간값이다.",
        ],
        "collectedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("run_dir")
    ap.add_argument("--scenario", help="A0..A6, G1..G3, F1..F5")
    ap.add_argument("--tier", help="T1|T2|T3")
    ap.add_argument("--summary", help="k6 summary JSON written by handleSummary (SUMMARY_PATH)")
    ap.add_argument("--k6-version")
    ap.add_argument("--base-url", help="defaults to the summary's meta.baseUrl")
    ap.add_argument("--server-sha", help="commit deployed on the server under test")
    ap.add_argument("--vm-provider")
    ap.add_argument("--vm-type")
    ap.add_argument("--vm-vcpu", type=float)
    ap.add_argument("--vm-ram-gb", type=float)
    ap.add_argument("--vm-disk-gb", type=float)
    ap.add_argument("--vm-region")
    ap.add_argument("--uplink-mbps", type=float, help="load PC uplink measured before the run (iperf3/speedtest)")
    ap.add_argument("--downlink-mbps", type=float)
    ap.add_argument("--client-cpu-max-pct", type=float, help="highest k6 client CPU seen during the run")
    ap.add_argument("--photo", action="append", default=[], help="photo file used (repeatable): size and SHA-256 go in run.json")
    ap.add_argument("--prom-url", help="Prometheus base URL reachable from this PC (tunnel); omit to skip the CSVs")
    ap.add_argument("--prom-env", default="loadtest", help="value of the env label (METRICS_ENV); empty for none")
    ap.add_argument("--step", type=float, default=15.0, help="query_range step in seconds")
    ap.add_argument("--pad-s", type=float, default=60.0, help="seconds added before start and after end")
    ap.add_argument("--start", help="ISO 8601 start (default: summary meta.startedAt)")
    ap.add_argument("--end", help="ISO 8601 end (default: summary meta.endedAt)")
    ap.add_argument("--attach", action="append", default=[], help="file to copy into attachments/ (repeatable)")
    ap.add_argument("--note")
    return ap.parse_args(argv)


def prom_line(prom: dict, run_dir: Path) -> str:
    """One line on the CSVs: how many of the fixed queries were written, which failed and which came back empty."""
    if "queries" not in prom:
        return f"prometheus: {prom.get('skipped', 'skipped')}"
    results = prom["queries"]
    failed = [r["name"] for r in results if "error" in r]
    empty = [r["name"] for r in results if "error" not in r and not r.get("points")]
    line = f"prometheus: {len(results) - len(failed)} of {len(results)} CSV written to {run_dir / 'prometheus'}"
    if failed:
        line += f"; failed: {', '.join(failed)}"
    if empty:
        line += f"; no data: {', '.join(empty)}"
    return line


def main(argv=None, fetch=http_fetch, tool=None) -> int:
    args = parse_args(argv)
    run_dir = Path(args.run_dir)
    summary = None
    args.summary_name = None
    if args.summary:
        src = Path(args.summary)
        try:
            summary = json.loads(src.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            print(f"collect: cannot read --summary: {exc}", file=sys.stderr)
            return 2
        run_dir.mkdir(parents=True, exist_ok=True)
        dest = run_dir / "k6-summary.json"
        if src.resolve() != dest.resolve() and src.resolve().parent != run_dir.resolve():
            shutil.copyfile(src, dest)
            args.summary_name = dest.name
        else:
            args.summary_name = src.name
    meta = (summary or {}).get("meta", {})
    start_text, end_text = args.start or meta.get("startedAt"), args.end or meta.get("endedAt")
    if not start_text or not end_text:
        print("collect: the run window is unknown: pass --start/--end or a --summary with meta.startedAt/endedAt", file=sys.stderr)
        return 2
    start, end = parse_time(start_text), parse_time(end_text)
    if end <= start:
        print("collect: --end must be after --start", file=sys.stderr)
        return 2
    window = {"start": iso(start), "end": iso(end), "durationS": round(end - start, 1), "padS": args.pad_s}
    run_dir.mkdir(parents=True, exist_ok=True)

    photos = [file_info(p) for p in args.photo]
    prom = {"skipped": "no --prom-url"}
    failed = False
    if args.prom_url:
        results = pull(args.prom_url, args.prom_env, start - args.pad_s, end + args.pad_s, args.step, run_dir, fetch)
        failed = any("error" in r for r in results)
        prom = {"url": redact_url(args.prom_url), "env": args.prom_env, "step": args.step,
                "from": iso(start - args.pad_s), "to": iso(end + args.pad_s), "queries": results}
    attachments = []
    for path in args.attach:
        dest = run_dir / "attachments" / Path(path).name
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, dest)
        attachments.append(dict(file_info(dest), file=f"attachments/{dest.name}"))

    doc = build_run_json(args, summary, window, tool if tool is not None else git_info(), photos, prom, attachments)
    (run_dir / "run.json").write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    print(f"run.json written to {run_dir / 'run.json'}" + (" (some Prometheus queries failed)" if failed else ""))
    print(f"status: {doc['status']}")
    print(prom_line(prom, run_dir))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
