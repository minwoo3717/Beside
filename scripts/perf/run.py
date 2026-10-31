#!/usr/bin/env python3
"""Beside backend benchmark runner: builds each version's jar, runs the suites, writes the results as JSON.

    python scripts/perf/run.py --versions v0,v1,v2 --reps 3 [--versions-file docs/perf/versions.json]
        [--stub-python PATH] [--work-dir DIR] [--out DIR] [--quick] [--only scenario,...] [--server-cpus 2]

One suite = one version x one repetition (scenarios.py). The version order rotates per repetition (rep1 v0,v1,v2;
rep2 v1,v2,v0; rep3 v2,v0,v1) so drift of the machine does not always hit the same version. Output: one
<label>-r<rep>.json per suite and run.json (environment, versions with resolved commits, order, times, tool commit,
command line). Exit code 1 when any suite or scenario errored; whatever was measured is still written.
Standard library only (Python 3.10+).
"""
from __future__ import annotations

import argparse
import ctypes
import itertools
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import traceback
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import fixtures  # noqa: E402
import procs  # noqa: E402
import scenarios  # noqa: E402

REPO = HERE.parent.parent
SUITE_SCHEMA = "beside-perf-suite/1"
RUN_SCHEMA = "beside-perf-run/1"
JAR_NAME = re.compile(r"^mock-backend-.*\.jar$")
SUITE_KEYS = ("schema", "label", "rep", "position", "version", "quick", "startedAt", "finishedAt", "durationS", "jar",
              "settings", "fixtures", "servers", "scenarios", "status", "errors")
SCENARIO_KEYS = ("status", "samples", "values", "jobs")


def setup_output():
    """Progress lines must never crash the run: a pipe or file on Windows uses the ANSI code page (cp949 cannot
    encode every character), so redirected output is written as UTF-8 and anything unencodable is replaced."""
    for stream in (sys.stdout, sys.stderr):
        try:
            if stream.isatty():
                stream.reconfigure(errors="replace")
            else:
                stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):
            pass


def log(message: str):
    print(f"{datetime.now().strftime('%H:%M:%S')} {message}", flush=True)


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


# ----------------------------------------------------------------------------------------------- planning


def rotation(labels, reps: int) -> list:
    """Order of the versions in each repetition: repetition r starts at label (r-1) mod n."""
    labels = list(labels)
    if not labels:
        return []
    return [labels[r % len(labels):] + labels[:r % len(labels)] for r in range(reps)]


def load_versions(path) -> dict:
    path = Path(path)
    if not path.is_file():
        raise SystemExit(f"versions file not found: {path}\n"
                         "  pass --versions-file scripts/perf/versions.example.json (or create docs/perf/versions.json)")
    doc = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(doc.get("versions"), dict) or not doc["versions"]:
        raise SystemExit(f"{path}: no 'versions' object")
    for label, info in doc["versions"].items():
        if not isinstance(info, dict) or not info.get("ref"):
            raise SystemExit(f"{path}: version {label} has no ref")
    doc.setdefault("order", list(doc["versions"]))
    return doc


def git(*args, cwd=REPO, check=True) -> str:
    done = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, timeout=600)
    if check and done.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {done.stderr.decode('utf-8', 'replace').strip()}")
    return done.stdout.decode("utf-8", "replace").strip()


def resolve(doc: dict, labels) -> dict:
    """{label: {ref, sha, title, description, capabilities}} with each ref resolved to a full commit SHA."""
    out = {}
    for label in labels:
        if label not in doc["versions"]:
            raise SystemExit(f"unknown version {label!r}; known: {', '.join(doc['versions'])}")
        info = doc["versions"][label]
        sha = git("rev-parse", "--verify", "--quiet", f"{info['ref']}^{{commit}}", check=False)
        if not re.fullmatch(r"[0-9a-f]{40}", sha or ""):
            raise SystemExit(f"version {label}: ref {info['ref']!r} does not name a commit in {REPO}")
        out[label] = {"ref": info["ref"], "sha": sha, "title": info.get("title", ""),
                      "description": info.get("description", ""), "capabilities": list(info.get("capabilities", []))}
    return out


# ----------------------------------------------------------------------------------------------- jars


BOOT_CLASS = "BOOT-INF/classes/com/example/mockbackend/MockBackendApplication.class"
LOCK_WAIT_S = 45 * 60
BUILD_TIMEOUT_S = 30 * 60


def jar_problem(path: Path):
    """None when `path` is a Spring Boot jar of this backend; otherwise what is wrong with it."""
    import zipfile
    try:
        with zipfile.ZipFile(path) as jar:
            names = set(jar.namelist())
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        return f"not a readable jar ({error})"
    if BOOT_CLASS not in names or "Start-Class: com.example.mockbackend.MockBackendApplication" not in manifest:
        return "not the Beside backend boot jar"
    return None


def _sha256_file(path: Path) -> str:
    import hashlib
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


class _BuildLock:
    """<work>/jars/<sha>.lock created exclusively, so two runs sharing a work dir never build the same commit twice."""

    def __init__(self, path: Path):
        self.path = path
        self.held = False

    def acquire(self, timeout_s: float = LOCK_WAIT_S) -> bool:
        deadline = time.monotonic() + timeout_s
        while True:
            try:
                fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                os.write(fd, f"{os.getpid()} {now_iso()}\n".encode("ascii"))
                os.close(fd)
                self.held = True
                return True
            except FileExistsError:
                try:
                    if time.time() - self.path.stat().st_mtime > LOCK_WAIT_S:
                        self.path.unlink()  # stale: its builder died
                        continue
                except FileNotFoundError:
                    continue
                if time.monotonic() > deadline:
                    return False
                time.sleep(2.0)

    def release(self):
        if self.held:
            try:
                self.path.unlink()
            except FileNotFoundError:
                pass
            self.held = False


def _build_one(sha: str, jar: Path, work: Path) -> dict:
    """Build the boot jar of `sha` in a temporary detached worktree (removed afterwards) and store it as `jar`.

    Gradle runs with --no-daemon: `gradlew --stop` would stop every daemon of that Gradle version of this user,
    including daemons of the user's own builds, so no daemon is left behind instead. The build is started through
    procs.run_logged: a hung build is killed with its whole process tree (not only gradlew.bat's cmd.exe), so no
    Gradle JVM outlives the tool or keeps the temporary worktree locked.
    """
    src = work / "src" / f"{sha[:12]}-p{os.getpid()}"
    if src.exists():
        git("worktree", "remove", "--force", str(src), check=False)
        shutil.rmtree(src, ignore_errors=True)
        git("worktree", "prune", check=False)
    src.parent.mkdir(parents=True, exist_ok=True)
    git("worktree", "add", "--detach", str(src), sha)
    try:
        backend = src / "backend"
        gradlew = backend / ("gradlew.bat" if procs.IS_WINDOWS else "gradlew")
        cmd = ([str(gradlew)] if procs.IS_WINDOWS else ["sh", str(gradlew)]) + [
            "bootJar", "-x", "test", "--no-daemon", "--console=plain"]
        build_log = work / "logs" / f"build-{sha[:12]}-p{os.getpid()}.log"
        build_log.parent.mkdir(parents=True, exist_ok=True)
        if build_log.exists():
            build_log.unlink()
        log(f"jar {sha[:9]}: building with --no-daemon (log {build_log})")
        started = time.monotonic()
        try:
            code = procs.run_logged("gradle", cmd, cwd=backend, log_path=build_log, env=dict(os.environ),
                                    timeout_s=BUILD_TIMEOUT_S)
        except subprocess.TimeoutExpired:
            raise RuntimeError(f"build of {sha[:9]} did not finish within {BUILD_TIMEOUT_S // 60} min; its process "
                               f"tree was killed (log {build_log})") from None
        if code != 0:
            tail = "\n".join(build_log.read_text(encoding="utf-8", errors="replace").splitlines()[-20:])
            raise RuntimeError(f"build of {sha[:9]} failed (exit {code}):\n{tail}")
        built = [p for p in (backend / "build" / "libs").glob("*.jar")
                 if JAR_NAME.match(p.name) and not p.name.endswith("-plain.jar")]
        if len(built) != 1:
            raise RuntimeError(f"build of {sha[:9]}: expected one boot jar, found {[p.name for p in built]}")
        problem = jar_problem(built[0])
        if problem:
            raise RuntimeError(f"build of {sha[:9]}: {problem}")
        tmp = jar.with_name(f"{jar.name}.p{os.getpid()}.tmp")
        shutil.copyfile(built[0], tmp)
        os.replace(tmp, jar)
        record = {"sha": sha, "builtAt": now_iso(), "bytes": jar.stat().st_size, "sha256": _sha256_file(jar),
                  "builder": "scripts/perf/run.py", "gradle": "bootJar -x test --no-daemon",
                  "buildSeconds": round(time.monotonic() - started, 1)}
        write_json(jar.with_name(jar.name + ".json"), record)
        log(f"jar {sha[:9]}: built in {record['buildSeconds']:.0f} s ({record['bytes']:,} bytes)")
        return record
    finally:
        git("worktree", "remove", "--force", str(src), check=False)
        shutil.rmtree(src, ignore_errors=True)
        git("worktree", "prune", check=False)


def build_jars(resolved: dict, work: Path) -> dict:
    """{sha: {"path", "bytes", "sha256", "origin"}}. Write-once cache <work>/jars/<sha>.jar (never replaced once
    there: a running server may hold it open); a missing jar is built under a per-commit lock."""
    jars_dir = work / "jars"
    jars_dir.mkdir(parents=True, exist_ok=True)
    jars = {}
    for sha in dict.fromkeys(v["sha"] for v in resolved.values()):
        jar = jars_dir / f"{sha}.jar"
        lock = _BuildLock(jars_dir / f"{sha}.lock")
        origin = "cache"
        if not (jar.is_file() and jar_problem(jar) is None):
            if not lock.acquire():
                raise RuntimeError(f"another run has been building {sha[:9]} for over {LOCK_WAIT_S // 60} min "
                                   f"({lock.path}); delete the lock if no build is running")
            try:
                if jar.is_file() and jar_problem(jar) is None:
                    origin = "cache (built by a concurrent run)"
                else:
                    if jar.exists():
                        log(f"jar {sha[:9]}: cached file is unusable ({jar_problem(jar)}), rebuilding")
                    _build_one(sha, jar, work)
                    origin = "built"
            finally:
                lock.release()
        record_file = jar.with_name(jar.name + ".json")
        if origin == "cache":
            origin = "cache" if record_file.is_file() else "cache (no build record: not built by this tool)"
            log(f"jar {sha[:9]}: {origin} ({jar.stat().st_size:,} bytes)")
        jars[sha] = {"path": str(jar), "bytes": jar.stat().st_size, "sha256": _sha256_file(jar), "origin": origin}
    return jars


# ----------------------------------------------------------------------------------------------- environment


def cpu_model() -> str:
    try:
        if procs.IS_WINDOWS:
            import winreg
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as key:
                return str(winreg.QueryValueEx(key, "ProcessorNameString")[0]).strip()
        if Path("/proc/cpuinfo").exists():
            for line in Path("/proc/cpuinfo").read_text().splitlines():
                if line.lower().startswith("model name"):
                    return line.split(":", 1)[1].strip()
        if sys.platform == "darwin":
            return subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True, text=True,
                                  timeout=10).stdout.strip()
    except Exception:  # noqa: BLE001  environment facts are best effort
        pass
    return platform.processor() or "unknown"


def ram_gb():
    try:
        if procs.IS_WINDOWS:
            class MEMORYSTATUSEX(ctypes.Structure):
                _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                            ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]
            status = MEMORYSTATUSEX()
            status.dwLength = ctypes.sizeof(status)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status))
            return round(status.ullTotalPhys / 2 ** 30, 1)
        if Path("/proc/meminfo").exists():
            for line in Path("/proc/meminfo").read_text().splitlines():
                if line.startswith("MemTotal:"):
                    return round(int(line.split()[1]) * 1024 / 2 ** 30, 1)
        if sys.platform == "darwin":
            out = subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True, timeout=10)
            return round(int(out.stdout.strip()) / 2 ** 30, 1)
    except Exception:  # noqa: BLE001
        pass
    return None


def _decode(raw: bytes) -> str:
    for encoding in ("utf-8", "mbcs" if procs.IS_WINDOWS else "latin-1"):
        try:
            return raw.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", errors="replace")


def power_info() -> dict:
    info = {}
    try:
        if procs.IS_WINDOWS:
            class SYSTEM_POWER_STATUS(ctypes.Structure):
                _fields_ = [("ACLineStatus", ctypes.c_ubyte), ("BatteryFlag", ctypes.c_ubyte),
                            ("BatteryLifePercent", ctypes.c_ubyte), ("SystemStatusFlag", ctypes.c_ubyte),
                            ("BatteryLifeTime", ctypes.c_ulong), ("BatteryFullLifeTime", ctypes.c_ulong)]
            status = SYSTEM_POWER_STATUS()
            if ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(status)):
                info["source"] = {0: "battery", 1: "ac"}.get(status.ACLineStatus, "unknown")
                info["battery"] = "none" if status.BatteryFlag & 128 else f"{status.BatteryLifePercent}%"
            out = subprocess.run(["powercfg", "/getactivescheme"], capture_output=True, timeout=20)
            text = _decode(out.stdout).strip()
            match = re.search(r"([0-9a-fA-F-]{36})\s*\((.*)\)", text)
            if match:
                info["plan"] = f"{match.group(2)} ({match.group(1)})"
        else:
            supplies = Path("/sys/class/power_supply")
            if supplies.is_dir():
                for supply in supplies.iterdir():
                    kind = (supply / "type").read_text().strip() if (supply / "type").exists() else ""
                    if kind == "Mains" and (supply / "online").exists():
                        info["source"] = "ac" if (supply / "online").read_text().strip() == "1" else "battery"
    except Exception:  # noqa: BLE001
        pass
    return info


def collect_env(java: dict, jvm_args, cpus, server_cpus: int, removed_env) -> dict:
    return {
        "os": platform.platform(), "system": platform.system(), "release": platform.release(),
        "machine": platform.machine(), "cpuModel": cpu_model(), "logicalCpus": os.cpu_count(), "ramGB": ram_gb(),
        "python": platform.python_version(), "pythonImplementation": platform.python_implementation(),
        "java": java["version"], "javaPath": java["path"], "jvmArgs": list(jvm_args), "serverCpus": server_cpus,
        "pinnedCpus": cpus, "loadGeneratorPinned": False, "power": power_info(),
        "envRemovedForServers": list(removed_env),
    }


def tool_commit() -> dict:
    commit = git("rev-parse", "HEAD", check=False)
    dirty = bool(git("status", "--porcelain", "--", "scripts/perf", check=False))
    return {"commit": commit, "dirty": dirty}


# ----------------------------------------------------------------------------------------------- results


WRITE_ATTEMPTS = 8
WRITE_RETRY_S = 0.25  # pause before retry n is n x this (about 7 s over all attempts)
_TMP_IDS = itertools.count(1)


def write_json(path: Path, data, attempts: int = None, retry_s: float = None):
    """Write JSON atomically: a fresh temporary file, then os.replace onto `path`.

    On Windows os.replace fails with PermissionError while another process has the target open: OneDrive uploading
    the previous run.json (the default output folder is in the synced repo), a virus scanner, an editor. That is
    retried a few times with growing pauses; the last failure is raised and the temporary file removed.
    """
    attempts = WRITE_ATTEMPTS if attempts is None else attempts
    retry_s = WRITE_RETRY_S if retry_s is None else retry_s
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.p{os.getpid()}-{next(_TMP_IDS)}.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for attempt in range(1, attempts + 1):
        try:
            os.replace(tmp, path)
            return
        except PermissionError:
            if attempt >= attempts:
                try:
                    tmp.unlink()
                except OSError:
                    pass
                raise
            time.sleep(retry_s * attempt)


def save_json(path: Path, data, pending: dict) -> bool:
    """write_json for progress files during a run: a failure that outlasts the retries is logged and remembered in
    `pending` (path -> data) to be written again at the end, so a sync lock never stops the measurement."""
    try:
        write_json(path, data)
    except OSError as error:
        pending[path] = data
        log(f"could not write {path} ({type(error).__name__}: {error}); measuring goes on, retried at the end")
        return False
    pending.pop(path, None)
    return True


def flush_pending(pending: dict, fallback_dir: Path) -> list:
    """Write every pending file once more; what still fails is copied to fallback_dir (outside the synced folder).
    Returns the paths that could not be written where they belong."""
    for path, data in list(pending.items()):
        save_json(path, data, pending)
    failed = list(pending)
    for path in failed:
        try:
            write_json(fallback_dir / path.name, pending[path])
            log(f"{path.name} written to {fallback_dir} instead")
        except OSError as error:
            log(f"{path.name} could not be written anywhere: {error}")
    return failed


def validate_suite(doc: dict) -> list:
    """Problems with a suite document (empty list = matches the schema the report reads)."""
    problems = []
    for key in SUITE_KEYS:
        if key not in doc:
            problems.append(f"missing {key}")
    if doc.get("schema") != SUITE_SCHEMA:
        problems.append(f"schema is {doc.get('schema')!r}")
    for name, result in (doc.get("scenarios") or {}).items():
        if name not in scenarios.ALL_SCENARIOS:
            problems.append(f"unknown scenario {name}")
        for key in SCENARIO_KEYS:
            if key not in result:
                problems.append(f"{name}: missing {key}")
        if result.get("status") not in ("ok", "skipped", "error"):
            problems.append(f"{name}: bad status {result.get('status')!r}")
        if result.get("status") != "ok" and not result.get("reason"):
            problems.append(f"{name}: {result.get('status')} without a reason")
        for sample, values in (result.get("samples") or {}).items():
            if not isinstance(values, list) or not all(isinstance(v, (int, float)) for v in values):
                problems.append(f"{name}.{sample}: samples must be a list of numbers")
        for value_name, value in (result.get("values") or {}).items():
            if value is not None and (not isinstance(value, (int, float)) or isinstance(value, bool)):
                problems.append(f"{name}.{value_name}: value must be a number or null")
    return problems


def suite_record(label, rep, position, version, quick, started, finished, duration_s, jar, settings, fixture_info,
                 servers, scenario_results, errors) -> dict:
    status = "error" if errors or any(r.get("status") == "error" for r in scenario_results.values()) else "ok"
    return {"schema": SUITE_SCHEMA, "label": label, "rep": rep, "position": position, "version": version,
            "quick": quick, "startedAt": started, "finishedAt": finished, "durationS": round(duration_s, 3),
            "jar": jar, "settings": settings, "fixtures": fixture_info, "servers": servers,
            "scenarios": scenario_results, "status": status, "errors": errors}


def default_out(quick: bool) -> Path:
    stamp = datetime.now().strftime("%Y-%m-%d_%H%M")
    base = REPO / ("scripts/perf/out/quick" if quick else "docs/perf/data")
    out = base / stamp
    n = 2
    while out.exists():
        out = base / f"{stamp}-{n}"
        n += 1
    return out


def command_line(argv) -> str:
    args = ["python", "scripts/perf/run.py"] + list(argv)
    return subprocess.list2cmdline(args) if procs.IS_WINDOWS else shlex.join(args)


def fmt_s(seconds: float) -> str:
    seconds = max(0, int(seconds))
    return f"{seconds // 3600}:{seconds // 60 % 60:02d}:{seconds % 60:02d}"


# ----------------------------------------------------------------------------------------------- main


def parse_args(argv):
    parser = argparse.ArgumentParser(description="Beside backend benchmark runner (standard library only).")
    parser.add_argument("--versions", required=True, help="comma-separated labels from the versions file, e.g. v0,v1,v2")
    parser.add_argument("--reps", type=int, default=3, help="repetitions per version (default 3)")
    parser.add_argument("--versions-file", default=str(REPO / "docs" / "perf" / "versions.json"))
    parser.add_argument("--stub-python", help="python with fastapi/uvicorn/python-multipart for the inference stub")
    parser.add_argument("--work-dir", default=str(Path(tempfile.gettempdir()) / "beside-perf"),
                        help="jars, fixtures, run folders and logs (default <temp>/beside-perf, outside OneDrive)")
    parser.add_argument("--out", help="results folder (default docs/perf/data/<YYYY-MM-DD_HHMM>, "
                                      "with --quick scripts/perf/out/quick/<...>)")
    parser.add_argument("--quick", action="store_true", help="every count / 10 (min 2), N = 50/100/200")
    parser.add_argument("--only", help="comma-separated scenarios to run (the others are recorded as skipped)")
    parser.add_argument("--server-cpus", type=int, default=2, help="pin each server to the last N logical CPUs (0 = no)")
    parser.add_argument("--java", help="java executable for the servers (default JAVA_HOME, then PATH)")
    parser.add_argument("--keep-storage", action="store_true", help="keep server storage (uploads, DB) for debugging")
    parser.add_argument("--cooldown", type=float, default=3.0, help="seconds of rest between suites (default 3)")
    args = parser.parse_args(argv)
    if args.reps < 1:
        parser.error("--reps must be at least 1")
    return args


def main(argv=None) -> int:
    setup_output()
    argv = list(sys.argv[1:] if argv is None else argv)
    args = parse_args(argv)
    labels = [v.strip() for v in args.versions.split(",") if v.strip()]
    if not labels or len(set(labels)) != len(labels):
        raise SystemExit("--versions needs distinct labels")
    selected = scenarios.ALL_SCENARIOS
    if args.only:
        selected = [s.strip() for s in args.only.split(",") if s.strip()]
        unknown = [s for s in selected if s not in scenarios.ALL_SCENARIOS]
        if unknown:
            raise SystemExit(f"unknown scenarios {unknown}; known: {', '.join(scenarios.ALL_SCENARIOS)}")
    doc = load_versions(args.versions_file)
    resolved = resolve(doc, labels)
    work = Path(args.work_dir).resolve()
    work.mkdir(parents=True, exist_ok=True)
    out = Path(args.out).resolve() if args.out else default_out(args.quick)
    out.mkdir(parents=True, exist_ok=True)
    stamp = f"{out.name}-p{os.getpid()}"  # run folder name; unique even when two runs share the work dir
    started_at = now_iso()
    log(f"results -> {out}")
    log(f"work dir -> {work}")

    java = procs.resolve_java(args.java)
    cpus = procs.pick_cpus(args.server_cpus)
    jvm_args = procs.jvm_args(args.server_cpus if cpus else 0)
    _env, removed_env = procs.clean_env()
    stub_reason = scenarios.stub_check(args.stub_python)
    app_dir = REPO / "generation" / "inference_service"
    if not stub_reason and not (app_dir / "app.py").is_file():
        stub_reason = f"no inference stub at {app_dir}"
    log(f"java {java['path']}; server CPUs {cpus or 'not pinned'}; stub: {stub_reason or 'available'}")

    fx = fixtures.ensure_standard(work)
    for info in fx.values():
        info["data"] = Path(info["path"]).read_bytes()
    fixture_info = {k: {key: v[key] for key in ("bytes", "sha256", "generator", "args")} for k, v in fx.items()}
    counts = scenarios.effective_counts(args.quick)

    orders = rotation(labels, args.reps)
    run_doc = {
        "schema": RUN_SCHEMA, "status": "running", "startedAt": started_at, "finishedAt": None,
        "quick": args.quick, "reps": args.reps, "labels": labels, "order": orders,
        "baseline": doc.get("baseline"), "versionOrder": doc.get("order"),
        "versionsFile": str(Path(args.versions_file).resolve()), "versions": resolved,
        "selected": list(selected), "tool": tool_commit(), "command": command_line(argv), "argv": argv,
        "workDir": str(work), "runDir": str(work / "runs" / stamp), "fixtures": fixture_info, "counts": counts,
        "stub": {"python": args.stub_python, "available": stub_reason is None, "reason": stub_reason},
        "env": collect_env(java, jvm_args, cpus, args.server_cpus, removed_env), "suites": [], "jars": {},
    }
    write_json(out / "run.json", run_doc)

    exit_code = 0
    pending = {}  # result files whose write failed during the run (e.g. a OneDrive lock): written again at the end
    try:
        jars = build_jars(resolved, work)
        run_doc["jars"] = jars
        save_json(out / "run.json", run_doc, pending)
        total = len(orders) * len(labels)
        done = 0
        durations = []
        for rep_index, order in enumerate(orders, 1):
            for position, label in enumerate(order, 1):
                done += 1
                version = resolved[label]
                remaining = total - done + 1
                eta = (sum(durations) / len(durations) * remaining) if durations else None
                log(f"[{done}/{total}] {label} rep {rep_index} ({version['sha'][:9]})"
                    + (f", ETA {fmt_s(eta)}" if eta is not None else ""))
                record = run_one(label, rep_index, position, version, jars[version["sha"]], java, jvm_args, cpus,
                                 args, fx, fixture_info, counts, selected, stub_reason, app_dir, work, stamp)
                if procs.live_pids():
                    raise RuntimeError(f"processes left after a suite: {procs.live_pids()}")
                durations.append(record["durationS"])
                save_json(out / f"{label}-r{rep_index}.json", record, pending)
                run_doc["suites"].append({"file": f"{label}-r{rep_index}.json", "label": label, "rep": rep_index,
                                          "position": position, "status": record["status"],
                                          "durationS": record["durationS"]})
                save_json(out / "run.json", run_doc, pending)
                summary = ", ".join(f"{name} {res['status']}" for name, res in record["scenarios"].items()
                                    if res["status"] != "ok")
                log(f"[{done}/{total}] {label} rep {rep_index}: {record['status']} in {record['durationS']:.0f} s"
                    + (f" ({summary})" if summary else ""))
                if record["status"] != "ok":
                    exit_code = 1
                if done < total and args.cooldown > 0:
                    time.sleep(args.cooldown)
        run_doc["status"] = "error" if exit_code else "ok"
    except KeyboardInterrupt:
        run_doc["status"] = "interrupted"
        exit_code = 130
        log("interrupted")
    except Exception as error:  # noqa: BLE001  record and exit non-zero
        run_doc["status"] = "error"
        run_doc["error"] = f"{type(error).__name__}: {error}"
        exit_code = 1
        log(f"run failed: {type(error).__name__}: {error}")
        traceback.print_exc()
    finally:
        procs.stop_all()
        run_doc["finishedAt"] = now_iso()
        pending[out / "run.json"] = run_doc  # the final write, together with every write that failed on the way
        unwritten = flush_pending(pending, Path(run_doc["runDir"]))
        if unwritten and not exit_code:
            exit_code = 1
    log(f"done: {run_doc['status']} -> {out}")
    return exit_code


def run_one(label, rep, position, version, jar, java, jvm_args, cpus, args, fx, fixture_info, counts, selected,
            stub_reason, app_dir, work, stamp) -> dict:
    run_dir = work / "runs" / stamp / f"{label}-r{rep}"
    if run_dir.exists():
        shutil.rmtree(run_dir, ignore_errors=True)
    run_dir.mkdir(parents=True, exist_ok=True)
    started_at = now_iso()
    started = time.monotonic()
    errors = []
    results = {}

    def suite_log(message):
        log(f"  {label} r{rep} {message}")

    ctx = scenarios.SuiteContext(label, rep, jar["path"], java["path"], jvm_args, cpus, fx, counts, run_dir,
                                 suite_log, keep_storage=args.keep_storage)
    try:
        results = scenarios.run_suite(ctx, selected, version["capabilities"], args.stub_python, stub_reason, app_dir)
    except Exception as error:  # noqa: BLE001  one broken suite must not stop the run
        errors.append(f"{type(error).__name__}: {error}")
        suite_log("suite failed: " + errors[-1])
        traceback.print_exc()
    finally:
        leftovers = procs.live_pids()
        if leftovers:
            errors.append(f"processes still running after the suite were killed: {leftovers}")
        procs.stop_all()
    settings = {"jvmArgs": list(jvm_args), "serverCpus": args.server_cpus, "pinnedCpus": cpus, "counts": counts,
                "selected": list(selected), "runDir": str(run_dir)}
    jar_info = {"sha": version["sha"], "bytes": jar["bytes"], "sha256": jar["sha256"], "file": Path(jar["path"]).name,
                "origin": jar["origin"]}
    return suite_record(label, rep, position, version, args.quick, started_at, now_iso(), time.monotonic() - started,
                        jar_info, settings, fixture_info, ctx.servers, results, errors)


if __name__ == "__main__":
    sys.exit(main())
