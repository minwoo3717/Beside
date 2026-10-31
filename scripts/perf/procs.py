"""Start, watch and stop the processes a benchmark needs: the Spring jar and the inference stub (standard library only).

- start(): Popen with stdout+stderr to a log file. The server can be pinned to chosen logical CPUs (Windows:
  SetProcessAffinityMask right after Popen; Linux: sched_setaffinity in the child before exec). The load generator
  (this Python process) is never pinned. Every child is registered for cleanup (try/finally in the callers, atexit
  here) and, on Windows, put in a kill-on-close job object so it dies with this process even after a hard kill.
- wait_http_ok(): Popen -> first HTTP 200, polled every 25 ms with a 25 ms connect timeout (on Windows a refused
  loopback connect takes about a second, which would quantise the start-up time).
- memory(): working set / RSS and its peak (Windows psapi GetProcessMemoryInfo, Linux /proc/<pid>/status).
- stop(): graceful first (Windows: Ctrl+C delivered to the child's own console by a helper; POSIX: SIGTERM), then the
  whole tree is killed (taskkill /PID n /T /F, killpg SIGKILL). Only processes started here are ever touched, and a
  process stays registered for cleanup until it has exited.
- run_logged(): a build or tool run to completion with the same guarantees (a timeout kills the whole tree).
"""
from __future__ import annotations

import atexit
import ctypes
import http.client
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

IS_WINDOWS = os.name == "nt"
CREATE_NO_WINDOW = 0x08000000

# Environment variables that could change what is measured: JVM option injection and Spring relaxed binding of the
# properties this project reads (an exported INFERENCE_TOKEN or NOTIFY_WEBHOOK_URL must not leak into a run).
SCRUB_NAMES = {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "PYTHONPATH", "PYTHONHOME",
               "PYTHONSTARTUP"}
SCRUB_PREFIXES = ("SPRING_", "SERVER_", "STORAGE_", "INFERENCE_", "NOTIFY_", "MOCK_", "UPLOAD_", "SHARE_",
                  "LOGGING_", "MANAGEMENT_", "SPRINGDOC_", "BESIDE_")

STARTED_RE = re.compile(r"Started \S+ in ([0-9]+(?:\.[0-9]+)?) seconds")

_LIVE = {}  # pid -> Proc, every child still running


class ProcessError(RuntimeError):
    pass


def jvm_args(server_cpus: int) -> list:
    """The fixed JVM flags of every server; ActiveProcessorCount follows the number of pinned CPUs."""
    args = ["-Xms256m", "-Xmx1536m"]
    if server_cpus and server_cpus > 0:
        args.append(f"-XX:ActiveProcessorCount={server_cpus}")
    args.append("-XX:+UseG1GC")
    return args


def clean_env(extra: dict = None):
    """os.environ without the variables in SCRUB_NAMES / SCRUB_PREFIXES, plus `extra`. Returns (env, removed names)."""
    env, removed = {}, []
    for key, value in os.environ.items():
        upper = key.upper()
        if upper in SCRUB_NAMES or upper.startswith(SCRUB_PREFIXES):
            removed.append(key)
        else:
            env[key] = value
    env.update(extra or {})
    return env, sorted(removed)


def free_port(avoid=(8080,)) -> int:
    """A free TCP port on 127.0.0.1 chosen by the OS, never one in `avoid`."""
    for _ in range(100):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        if port not in avoid:
            return port
    raise ProcessError("no free port")


def pick_cpus(count: int):
    """The LAST `count` logical CPUs this process may use, or None (no pinning, or the OS cannot pin)."""
    if not count or count <= 0:
        return None
    if hasattr(os, "sched_getaffinity"):
        available = sorted(os.sched_getaffinity(0))
    elif IS_WINDOWS:
        available = list(range(min(os.cpu_count() or 1, 64)))  # one processor group
    else:
        return None  # macOS: no affinity API
    if count >= len(available):
        raise ProcessError(f"cannot pin to {count} CPUs: only {len(available)} available")
    return available[-count:]


def physical_cores(cpus):
    """How many physical cores the logical CPUs `cpus` belong to (2 SMT siblings of one core -> 1), or None when
    unknown. Best effort: Windows GetLogicalProcessorInformation, Linux sysfs topology."""
    if not cpus:
        return None
    wanted = set(cpus)
    try:
        if IS_WINDOWS:
            cores = [mask for mask in _core_masks_windows() if mask & wanted]
            covered = set().union(*cores) if cores else set()
            return len(cores) if wanted <= covered else None
        topology = Path("/sys/devices/system/cpu")
        if topology.is_dir():
            keys = set()
            for cpu in wanted:
                base = topology / f"cpu{cpu}" / "topology"
                keys.add(((base / "physical_package_id").read_text().strip(), (base / "core_id").read_text().strip()))
            return len(keys)
    except (OSError, ValueError, AttributeError):
        pass
    return None


# ----------------------------------------------------------------------------------------------- Windows helpers

if IS_WINDOWS:
    from ctypes import wintypes

    _k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _k32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    _k32.OpenProcess.restype = wintypes.HANDLE
    _k32.CloseHandle.argtypes = [wintypes.HANDLE]
    _k32.SetProcessAffinityMask.argtypes = [wintypes.HANDLE, ctypes.c_size_t]
    _k32.SetProcessAffinityMask.restype = wintypes.BOOL
    _k32.GetProcessAffinityMask.argtypes = [wintypes.HANDLE, ctypes.POINTER(ctypes.c_size_t),
                                            ctypes.POINTER(ctypes.c_size_t)]
    _k32.GetProcessAffinityMask.restype = wintypes.BOOL
    _k32.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
    _k32.CreateJobObjectW.restype = wintypes.HANDLE
    _k32.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    _k32.SetInformationJobObject.restype = wintypes.BOOL
    _k32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
    _k32.AssignProcessToJobObject.restype = wintypes.BOOL
    _k32.SetConsoleCtrlHandler.argtypes = [ctypes.c_void_p, wintypes.BOOL]
    _k32.SetConsoleCtrlHandler.restype = wintypes.BOOL

    PROCESS_SET_INFORMATION = 0x0200
    PROCESS_SET_QUOTA = 0x0100
    PROCESS_TERMINATE = 0x0001
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    PROCESS_VM_READ = 0x0010

    class _PROCESS_MEMORY_COUNTERS(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
                    ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                    ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                    ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]

    class _BASIC_LIMIT(ctypes.Structure):
        _fields_ = [("PerProcessUserTimeLimit", ctypes.c_int64), ("PerJobUserTimeLimit", ctypes.c_int64),
                    ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                    ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                    ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD),
                    ("SchedulingClass", wintypes.DWORD)]

    class _IO_COUNTERS(ctypes.Structure):
        _fields_ = [(name, ctypes.c_uint64) for name in (
            "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
            "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]

    class _EXTENDED_LIMIT(ctypes.Structure):
        _fields_ = [("BasicLimitInformation", _BASIC_LIMIT), ("IoInfo", _IO_COUNTERS),
                    ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                    ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]

    _job = None

    def _kill_on_close_job():
        """A job object that kills its processes when this process ends (its handle closes); None if unavailable."""
        global _job
        if _job is None:
            handle = _k32.CreateJobObjectW(None, None)
            if handle:
                info = _EXTENDED_LIMIT()
                info.BasicLimitInformation.LimitFlags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                if _k32.SetInformationJobObject(handle, 9, ctypes.byref(info), ctypes.sizeof(info)):  # extended
                    _job = handle
                else:
                    _k32.CloseHandle(handle)
                    _job = 0
            else:
                _job = 0
        return _job or None

    def _open(pid: int, access: int):
        handle = _k32.OpenProcess(access, False, pid)
        if not handle:
            raise ctypes.WinError(ctypes.get_last_error())
        return handle

    def _set_affinity(pid: int, cpus) -> list:
        handle = _open(pid, PROCESS_SET_INFORMATION | PROCESS_QUERY_LIMITED_INFORMATION)
        try:
            if not _k32.SetProcessAffinityMask(handle, sum(1 << c for c in cpus)):
                raise ctypes.WinError(ctypes.get_last_error())
            process_mask, system_mask = ctypes.c_size_t(), ctypes.c_size_t()
            if not _k32.GetProcessAffinityMask(handle, ctypes.byref(process_mask), ctypes.byref(system_mask)):
                raise ctypes.WinError(ctypes.get_last_error())
            return [c for c in range(64) if process_mask.value >> c & 1]
        finally:
            _k32.CloseHandle(handle)

    def _assign_job(pid: int) -> bool:
        job = _kill_on_close_job()
        if not job:
            return False
        try:
            handle = _open(pid, PROCESS_SET_QUOTA | PROCESS_TERMINATE)
        except OSError:
            return False
        try:
            return bool(_k32.AssignProcessToJobObject(job, handle))
        finally:
            _k32.CloseHandle(handle)

    class _SLPI(ctypes.Structure):  # SYSTEM_LOGICAL_PROCESSOR_INFORMATION (union as 16 reserved bytes)
        _fields_ = [("ProcessorMask", ctypes.c_size_t), ("Relationship", ctypes.c_int),
                    ("Union", ctypes.c_ulonglong * 2)]

    _k32.GetLogicalProcessorInformation.argtypes = [ctypes.c_void_p, ctypes.POINTER(wintypes.DWORD)]
    _k32.GetLogicalProcessorInformation.restype = wintypes.BOOL

    def _core_masks_windows() -> list:
        """One set of logical CPU numbers per physical core (RelationProcessorCore), processor group 0."""
        length = wintypes.DWORD(0)
        _k32.GetLogicalProcessorInformation(None, ctypes.byref(length))
        size = ctypes.sizeof(_SLPI)
        if length.value < size:
            return []
        items = (_SLPI * (length.value // size))()
        if not _k32.GetLogicalProcessorInformation(items, ctypes.byref(length)):
            return []
        return [{c for c in range(64) if item.ProcessorMask >> c & 1}
                for item in items[:length.value // size] if item.Relationship == 0]

    def _memory_windows(pid: int):
        psapi = ctypes.WinDLL("psapi", use_last_error=True)
        psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(_PROCESS_MEMORY_COUNTERS),
                                               wintypes.DWORD]
        psapi.GetProcessMemoryInfo.restype = wintypes.BOOL
        handle = _open(pid, PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ)
        try:
            counters = _PROCESS_MEMORY_COUNTERS()
            counters.cb = ctypes.sizeof(counters)
            if not psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
                raise ctypes.WinError(ctypes.get_last_error())
            return int(counters.WorkingSetSize), int(counters.PeakWorkingSetSize)
        finally:
            _k32.CloseHandle(handle)


# Runs in a separate short-lived Python: leaves its own console, attaches to the child's console (CREATE_NO_WINDOW
# gives every child its own windowless console), ignores Ctrl+C itself and sends Ctrl+C to that console only.
# The JVM answers Ctrl+C with its normal shutdown (shutdown hooks: Spring closes the context and the H2 file).
_CTRL_C_HELPER = (
    "import ctypes,sys\n"
    "k=ctypes.WinDLL('kernel32')\n"
    "k.FreeConsole()\n"
    "if not k.AttachConsole(int(sys.argv[1])): sys.exit(2)\n"
    "k.SetConsoleCtrlHandler(None,True)\n"
    "ok=k.GenerateConsoleCtrlEvent(0,0)\n"
    "k.FreeConsole()\n"
    "sys.exit(0 if ok else 3)\n"
)


# ----------------------------------------------------------------------------------------------- processes


class Proc:
    """A child process started by start(): Popen, log file, start time and pinning."""

    def __init__(self, name, popen, log_path, log_handle, started_ns, cmd, cwd, cpus, pinned, in_job):
        self.name = name
        self.popen = popen
        self.log_path = Path(log_path)
        self.log_handle = log_handle
        self.started_ns = started_ns
        self.cmd = cmd
        self.cwd = cwd
        self.cpus = cpus
        self.pinned = pinned
        self.in_job = in_job
        self.stop_method = None

    @property
    def pid(self) -> int:
        return self.popen.pid

    def alive(self) -> bool:
        return self.popen.poll() is None

    def log_text(self) -> str:
        try:
            return self.log_path.read_bytes().decode("utf-8", errors="replace")
        except OSError:
            return ""

    def log_tail(self, lines: int = 30) -> str:
        return "\n".join(self.log_text().splitlines()[-lines:])


def start(name: str, cmd: list, cwd, log_path, env: dict, cpus=None) -> Proc:
    """Start `cmd` in `cwd` with stdout+stderr appended to log_path; pin it to `cpus` (list) when given."""
    Path(log_path).parent.mkdir(parents=True, exist_ok=True)
    log_handle = open(log_path, "ab")
    kwargs = {"cwd": str(cwd), "env": env, "stdin": subprocess.DEVNULL, "stdout": log_handle,
              "stderr": subprocess.STDOUT}
    if IS_WINDOWS:
        kwargs["creationflags"] = CREATE_NO_WINDOW
        # "Ignore Ctrl+C" is a process attribute that children inherit; a parent started in a new process group (an
        # IDE, a CI agent, a tool runner) passes it on and the child would never see the graceful-stop Ctrl+C.
        # Restore normal Ctrl+C processing here so the child inherits that. stop() sends Ctrl+C to the child's own
        # (windowless) console only, so it never reaches this process.
        _k32.SetConsoleCtrlHandler(None, False)
    else:
        kwargs["start_new_session"] = True
        if cpus and hasattr(os, "sched_setaffinity"):
            chosen = set(cpus)
            kwargs["preexec_fn"] = lambda: os.sched_setaffinity(0, chosen)
    started = time.perf_counter_ns()
    try:
        popen = subprocess.Popen([str(c) for c in cmd], **kwargs)
    except BaseException:
        log_handle.close()
        raise
    pinned = None
    proc = Proc(name, popen, log_path, log_handle, started, [str(c) for c in cmd], str(cwd), cpus, None, False)
    _LIVE[popen.pid] = proc
    try:
        if cpus:
            if IS_WINDOWS:
                pinned = _set_affinity(popen.pid, cpus)
            elif hasattr(os, "sched_getaffinity"):
                pinned = sorted(os.sched_getaffinity(popen.pid))
        proc.pinned = pinned
        if IS_WINDOWS:
            proc.in_job = _assign_job(popen.pid)
    except BaseException:
        kill_tree(proc)
        raise
    return proc


def wait_http_ok(proc: Proc, port: int, path: str, timeout_s: float = 120.0, interval_s: float = 0.025,
                 host: str = "127.0.0.1") -> float:
    """Poll GET path until it answers 200; returns ms from Popen to that answer. Raises if the child exits first."""
    deadline = time.monotonic() + timeout_s
    while True:
        if proc.popen.poll() is not None:
            raise ProcessError(f"{proc.name} exited with code {proc.popen.returncode} before {path} answered 200:\n"
                               + proc.log_tail(25))
        if time.monotonic() > deadline:
            raise ProcessError(f"{proc.name}: {path} did not answer 200 within {timeout_s:.0f} s:\n" + proc.log_tail(25))
        try:
            sock = socket.create_connection((host, port), timeout=interval_s)
        except socket.timeout:
            continue  # the connect attempt already waited interval_s
        except OSError:
            time.sleep(interval_s)
            continue
        conn = http.client.HTTPConnection(host, port, timeout=10)
        try:
            sock.settimeout(10)
            conn.sock = sock
            conn.request("GET", path, headers={"Connection": "close"})
            answer = conn.getresponse()
            answer.read()
            done = time.perf_counter_ns()
            if answer.status == 200:
                return (done - proc.started_ns) / 1e6
        except (OSError, http.client.HTTPException):
            pass
        finally:
            conn.close()
        time.sleep(interval_s)


def spring_started_ms(proc: Proc, wait_s: float = 5.0):
    """Milliseconds from Spring's "Started <App> in X seconds" log line (waits briefly for it), or None."""
    deadline = time.monotonic() + wait_s
    while True:
        match = STARTED_RE.search(proc.log_text())
        if match:
            return round(float(match.group(1)) * 1000.0, 3)
        if time.monotonic() > deadline or not proc.alive():
            return None
        time.sleep(0.05)


def memory(pid: int):
    """(resident bytes, peak resident bytes) of a process; peak is None where the OS does not keep it."""
    if IS_WINDOWS:
        return _memory_windows(pid)
    status = Path(f"/proc/{pid}/status")
    if status.exists():
        values = {}
        for line in status.read_text().splitlines():
            key, _, rest = line.partition(":")
            if key in ("VmRSS", "VmHWM"):
                values[key] = int(rest.split()[0]) * 1024
        return values.get("VmRSS"), values.get("VmHWM")
    out = subprocess.run(["ps", "-o", "rss=", "-p", str(pid)], capture_output=True, text=True, timeout=10)
    return (int(out.stdout.strip()) * 1024 if out.stdout.strip() else None), None


def _ctrl_c(pid: int) -> bool:
    """Windows: deliver Ctrl+C to the console of `pid` only (see _CTRL_C_HELPER)."""
    try:
        done = subprocess.run([sys.executable, "-I", "-c", _CTRL_C_HELPER, str(pid)], capture_output=True,
                              timeout=15, creationflags=CREATE_NO_WINDOW)
        return done.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def kill_tree(proc: Proc):
    """Force-kill the process and its children (only the tree rooted at a process we started)."""
    if proc.popen.poll() is None:
        if IS_WINDOWS:
            subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True, timeout=30)
        else:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                proc.popen.kill()
    try:
        proc.popen.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.popen.kill()
        proc.popen.wait(timeout=15)


def _ask_to_stop(proc: Proc) -> bool:
    """Windows: Ctrl+C to the child's own console; POSIX: SIGTERM to its session. False when it could not be asked."""
    if IS_WINDOWS:
        return _ctrl_c(proc.pid)
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        proc.popen.terminate()
    return True


def stop(proc: Proc, timeout_s: float = 30.0) -> str:
    """Graceful stop, then kill the tree. Returns how it ended: exited | graceful | killed.

    The process stays registered (live_pids, stop_all at exit) until it has really exited: an interrupted stop (a
    second Ctrl+C during the graceful wait of up to timeout_s) kills the tree before the interrupt goes on, and when
    even the kill fails the process is left to stop_all() instead of being forgotten.
    """
    method = "exited"
    try:
        if proc.popen.poll() is None:
            method = "graceful"
            try:
                if not _ask_to_stop(proc):
                    raise subprocess.TimeoutExpired(proc.cmd, 0)
                proc.popen.wait(timeout=timeout_s)
            except subprocess.TimeoutExpired:
                method = "killed"
                kill_tree(proc)
            except BaseException:  # KeyboardInterrupt while asking or waiting: do not leave the tree running
                kill_tree(proc)
                raise
        if not IS_WINDOWS:
            try:
                os.killpg(proc.pid, signal.SIGKILL)  # children left in the session, if any
            except (ProcessLookupError, PermissionError):
                pass
    finally:
        try:
            proc.log_handle.close()
        except OSError:
            pass
        if proc.popen.poll() is not None:
            _LIVE.pop(proc.pid, None)
    proc.stop_method = method
    return method


def run_logged(name: str, cmd: list, cwd, log_path, env: dict, timeout_s: float) -> int:
    """Run `cmd` to the end with its output in log_path, like subprocess.run(timeout=...) but started by start().

    subprocess.run kills only the top process on a timeout: for gradlew.bat that is cmd.exe, and the Gradle JVM it
    started keeps running (holding the build folder open). Here the child is in the kill-on-close job (Windows) or
    its own session (POSIX), and a timeout or an interrupt kills the whole tree. Returns the exit code; raises
    subprocess.TimeoutExpired after the tree is killed.
    """
    proc = start(name, cmd, cwd=cwd, log_path=log_path, env=env)
    try:
        return proc.popen.wait(timeout=timeout_s)
    except BaseException:
        kill_tree(proc)
        raise
    finally:
        if proc.popen.poll() is not None:
            stop(proc)  # closes the log and unregisters; POSIX: kills whatever is left in the session


def stop_all():
    """Kill every child still registered (atexit and error paths)."""
    for proc in list(_LIVE.values()):
        try:
            kill_tree(proc)
        except Exception:  # noqa: BLE001  best effort at exit
            pass
        try:
            proc.log_handle.close()
        except OSError:
            pass
        _LIVE.pop(proc.pid, None)


def live_pids() -> list:
    return sorted(_LIVE)


atexit.register(stop_all)


# ----------------------------------------------------------------------------------------------- java


def resolve_java(java: str = None) -> dict:
    """The real java executable (not a launcher shim): {"path", "home", "version"}.

    Candidates: the given path, JAVA_HOME/bin/java, java on PATH. java.home from -XshowSettings:properties names the
    runtime, so PIDs, pinning and memory belong to the JVM itself.
    """
    exe_name = "java.exe" if IS_WINDOWS else "java"
    candidates = []
    if java:
        candidates.append(java)
    if os.environ.get("JAVA_HOME"):
        candidates.append(str(Path(os.environ["JAVA_HOME"]) / "bin" / exe_name))
    found = shutil.which("java")
    if found:
        candidates.append(found)
    for candidate in candidates:
        if not Path(candidate).is_file():
            continue
        try:
            out = subprocess.run([candidate, "-XshowSettings:properties", "-version"], capture_output=True,
                                 timeout=60)
        except (OSError, subprocess.SubprocessError):
            continue
        text = out.stderr.decode("utf-8", errors="replace")
        match = re.search(r"^\s*java\.home = (.+?)\s*$", text, re.MULTILINE)
        home = match.group(1) if match else str(Path(candidate).parent.parent)
        real = Path(home) / "bin" / exe_name
        if not real.is_file():
            real = Path(candidate)
        version = subprocess.run([str(real), "-version"], capture_output=True, timeout=60)
        return {"path": str(real), "home": home,
                "version": version.stderr.decode("utf-8", errors="replace").strip()}
    raise ProcessError("java not found: pass --java, set JAVA_HOME or put java on PATH")
