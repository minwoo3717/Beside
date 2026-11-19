#!/usr/bin/env bash
# Runpod Pod: join the team tailnet (Tailscale userspace mode) and start the inference service on 127.0.0.1.
#
# Why this shape: a Pod is a container without /dev/net/tun, so tailscaled runs with --tun=userspace-networking.
# In that mode tailscaled hands inbound tailnet TCP for its own 100.x address to 127.0.0.1:<same port>
# (wgengine/netstack acceptTCP), so the VM reaches http://<pod 100.x>:8002 while the service listens only on
# loopback: nothing on the Pod's public TCP ports and nothing behind the Runpod HTTP proxy, which cuts requests at
# 100 s (Spring waits up to 600 s on /infer/upload). Port 8002, not 8001: the nginx in Runpod's official images
# already listens on 8001 (also 3001, 7270, 7861, 8081, 9091; Jupyter 8888, sshd 22).
#
# First time (Pod web terminal or SSH; an exec tool can run every line except the token one, which needs a keyboard:
# an exec tool has no stdin, and a token passed in its command or env ends up in the tool-call record):
#   git clone -b deploy/gcp https://github.com/minwoo3717/Beside.git /workspace/Beside   # main once merged
#   (umask 077; cat > /workspace/beside-token)    # paste only the value after INFERENCE_TOKEN= in the VM deploy/.env,
#                                                 # then Enter, Ctrl-D
# Every Pod start (the container disk is wiped on stop; /workspace survives):
#   bash /workspace/Beside/deploy/runpod/start_pod.sh
#   or, when nobody watches the terminal (an exec tool, a browser tab that may close):
#   mkdir -p /workspace/beside-logs && nohup bash /workspace/Beside/deploy/runpod/start_pod.sh > /workspace/beside-logs/start.log 2>&1 &
# The first run prints a Tailscale login link (also in /workspace/beside-logs/tailscale-up.log): approve it in the
# team tailnet. Later runs reuse /workspace/tailscale, so the node keeps its 100.x address and the VM .env stays.
# /workspace on a network volume survives Terminate too (a new Pod in the same data center gets the same node);
# a Pod volume disk does not: then log in again, remove the old node in the admin console, update the VM .env.
#
# MODE=passthrough  fixed sample GLB, no model: tests the VM <-> Pod path before AnimalLift is installed
# MODE=model        AnimalLift through scripts/start_inference_wsl.sh; conda, AnimalLift and checkpoints under
#                   /workspace (generation/inference_service/ANIMALLIFT_SETUP.md). Refuses to start without a GPU.
# default: model when $ANIMALLIFT_DIR exists, otherwise passthrough.
# Overrides: WORKSPACE (/workspace), PORT (8002), TS_HOSTNAME (beside-gpu), RUN_DIR (/var/run/beside: socket, pids),
#   TOKEN_FILE (/workspace/beside-token), ANIMALLIFT_DIR, CONDA_HOME, CONDA_ENV, WORK_DIR, RIG_TEMPLATE, SAMPLE_GLB,
#   HF_HOME, TORCH_HOME (model caches, default under /workspace/.cache), ALLOW_NO_GPU=1, TS_VERSION + TS_SHA256,
#   UVICORN_TIMEOUT_KEEP_ALIVE (75 s), THREADS (8: OMP/MKL/OPENBLAS_NUM_THREADS; "default" leaves them unset) - both
#   apply when the service starts
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WS="${WORKSPACE:-/workspace}"
PORT="${PORT:-8002}"
TS_HOSTNAME="${TS_HOSTNAME:-beside-gpu}"
TS_VERSION="${TS_VERSION:-1.104.6}"
TS_SHA256="${TS_SHA256:-82a85429f733e52fd1a95dedb2c8dd8cd46d6603504e566a56b1779c9a7a4809}"
TS_DIR="$WS/tailscale"              # binaries + node state: persistent, keeps the same 100.x after a Pod restart
TS_BIN="$TS_DIR/bin"
RUN_DIR="${RUN_DIR:-/var/run/beside}"   # runtime only; a unix socket path must stay short and off network volumes
SOCK="$RUN_DIR/tailscaled.sock"
LOG_DIR="$WS/beside-logs"
TOKEN_FILE="${TOKEN_FILE:-$WS/beside-token}"
ANIMALLIFT_DIR="${ANIMALLIFT_DIR:-$WS/AnimalLift}"
MODE="${MODE:-}"
SERVE="uvicorn app:app --host 127.0.0.1 --port $PORT"   # also how a running service is recognised
# Service defaults measured on the A40 Pod (2026-11-19 load test round 3, docs/perf/load/2026-11-19_vm-3rd.md 2절):
# - uvicorn closes an idle connection after 5 s by default, so with jobs more than 5 s apart Spring opened a new
#   VM <-> Pod connection for every job; 75 s keeps it (processing median 9.09 -> 8.22 s at one job per 30 s).
#   The backend's HttpClient drops idle connections after 60 s (backend/Dockerfile), before this side does.
# - With the libraries' default thread pools (count not measured; the report's hypothesis: sized to the 96 host CPUs
#   the container sees) 0.5 % of the 100 ms CPU-quota periods were throttled (quota 7.65 cores on that Pod);
#   8 threads made it 0 (inference median -2.6 %). On another Pod type set THREADS to its cgroup CPU quota
#   (cpu.cfs_quota_us / cpu.cfs_period_us, or cpu.max), rounded - not the vCPU count the console shows.
#   THREADS=default starts with the libraries' own choice (the round-3 G2r/F baseline).
KEEP_ALIVE_S="${UVICORN_TIMEOUT_KEEP_ALIVE:-75}"
THREADS="${THREADS:-8}"

die() { echo "ERROR: $*" >&2; exit 1; }
stamp() { printf '\n=== %s start_pod.sh ===\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$1"; }   # logs are appended
port_busy() { (exec 3<> "/dev/tcp/127.0.0.1/$1") 2>/dev/null; }   # something accepts connections there

[[ -d "$WS" ]] || die "$WS not found - run this inside the Runpod Pod (or set WORKSPACE)"
[[ "$PORT" =~ ^[0-9]{2,5}$ ]] || die "PORT must be a number (got: $PORT)"
[[ "$KEEP_ALIVE_S" =~ ^[1-9][0-9]{0,3}$ ]] || die "UVICORN_TIMEOUT_KEEP_ALIVE must be seconds, 1-9999 (got: $KEEP_ALIVE_S)"
[[ "$THREADS" =~ ^[1-9][0-9]{0,2}$ || "$THREADS" == default ]] ||
  die "THREADS must be a number of threads, 1-999, or default (got: $THREADS)"
if [[ -z "${BESIDE_INFERENCE_TOKEN:-}" && -r "$TOKEN_FILE" ]]; then
  BESIDE_INFERENCE_TOKEN="$(cat "$TOKEN_FILE")"
  perms="$(stat -c %a "$TOKEN_FILE" 2>/dev/null || echo unknown)"
  [[ "$perms" == 600 || "$perms" == 400 ]] || echo "WARNING: $TOKEN_FILE has mode $perms - chmod 600 it"
fi
trimmed="${BESIDE_INFERENCE_TOKEN:-}"
trimmed="${trimmed//[[:space:]]/}"
[[ -n "$trimmed" ]] || die "no inference token: put the value of INFERENCE_TOKEN from the VM's deploy/.env in $TOKEN_FILE (chmod 600) or BESIDE_INFERENCE_TOKEN"
[[ "$trimmed" == *INFERENCE_TOKEN=* || "$trimmed" == *[\"\']* ]] &&
  die "the inference token must be only the value after INFERENCE_TOKEN= (no name, no quotes) - fix $TOKEN_FILE or BESIDE_INFERENCE_TOKEN"
export BESIDE_INFERENCE_TOKEN
command -v curl >/dev/null || die "curl not found (apt-get update && apt-get install -y curl)"
if [[ -z "$MODE" ]]; then
  if [[ -d "$ANIMALLIFT_DIR" ]]; then MODE=model; else MODE=passthrough; fi
fi
case "$MODE" in model|passthrough) ;; *) die "MODE must be model or passthrough (got: $MODE)" ;; esac
if [[ "$MODE" == model && "${ALLOW_NO_GPU:-0}" != 1 ]]; then
  # A stopped Pod resumes only on its old machine; if that GPU was rented out it starts with none, and the model
  # would load on CPU without an error and every job would hit Spring's 600 s timeout.
  gpus="$(nvidia-smi -L 2>/dev/null || true)"
  [[ "$gpus" == *"GPU "* ]] || die "no GPU visible (nvidia-smi -L). Terminate this Pod and create a new one on the same network volume (ALLOW_NO_GPU=1 starts anyway)"
fi

# PID from a pid file, only if that process is still the one we started (pid files may outlive the process).
running_pid() {
  local pid cmd
  pid="$(cat "$1" 2>/dev/null)" || return 1
  [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/cmdline" ]] || return 1
  cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)" || return 1
  [[ "$cmd" == *"$2 "* ]] || return 1   # every argument ends with a space here, so --port 800 does not match 8002
  echo "$pid"
}

ts() { "$TS_BIN/tailscale" --socket="$SOCK" "$@"; }

# Keep-alive and thread count the service process really got (its environment), for the [4/4] line.
THREADS_LABEL="$THREADS"; [[ "$THREADS" == default ]] && THREADS_LABEL="library default (not set)"
WANTED_SETTINGS="keep-alive $KEEP_ALIVE_S s, threads $THREADS_LABEL"
service_settings() {
  local env ka th
  env="$(tr '\0' '\n' 2>/dev/null < "/proc/$1/environ")" || { echo "settings unknown (cannot read /proc/$1/environ)"; return 0; }
  ka="$(sed -n 's/^UVICORN_TIMEOUT_KEEP_ALIVE=//p' <<< "$env")"
  th="$(sed -n 's/^OMP_NUM_THREADS=//p' <<< "$env")"
  if [[ -n "$ka" ]]; then ka="$ka s"; else ka="5 s (uvicorn default)"; fi
  echo "keep-alive $ka, threads ${th:-library default (not set)}"
}

mkdir -p "$TS_DIR/state" "$RUN_DIR" "$LOG_DIR"
# One run at a time: an exec tool that stops waiting does not stop the command, so a retry could overlap it.
# Background children close fd 9 (9>&-) so they do not keep the lock after this script exits.
exec 9> "$RUN_DIR/start.lock"
flock -n 9 || die "another start_pod.sh is still running (see $LOG_DIR/start.log)"
chmod 700 "$TS_DIR" "$TS_DIR/state" 2>/dev/null || echo "WARNING: could not chmod 700 $TS_DIR (the state holds the node key)"
if [[ -n "${RUNPOD_POD_ID:-}" ]]; then
  last="$(cat "$TS_DIR/last-pod" 2>/dev/null || true)"
  if [[ -n "$last" && "$last" != "$RUNPOD_POD_ID" ]]; then
    echo "NOTE: this Tailscale state was last used by Pod $last - make sure that Pod is terminated or stopped;"
    echo "      two Pods running with one node key keep knocking each other off the tailnet."
  fi
  echo "$RUNPOD_POD_ID" > "$TS_DIR/last-pod"
fi

# 1. Tailscale binaries (static build, checksum pinned) on /workspace so a restart does not download again.
if [[ -x "$TS_BIN/tailscaled" && -x "$TS_BIN/tailscale" && "$(cat "$TS_BIN/VERSION" 2>/dev/null)" == "$TS_VERSION" ]]; then
  echo "[1/4] Tailscale $TS_VERSION already in $TS_BIN"
else
  [[ "$(uname -m)" == x86_64 ]] || die "this downloads the amd64 build; this machine is $(uname -m)"
  tgz="tailscale_${TS_VERSION}_amd64.tgz"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  echo "[1/4] downloading Tailscale $TS_VERSION -> $TS_BIN"
  curl -fsSL --retry 3 -o "$tmp/$tgz" "https://pkgs.tailscale.com/stable/$tgz"
  echo "$TS_SHA256  $tmp/$tgz" | sha256sum -c --quiet - || die "sha256 mismatch for $tgz - not installing it"
  tar -xzf "$tmp/$tgz" -C "$tmp"
  mkdir -p "$TS_BIN"
  install -m 755 "$tmp/tailscale_${TS_VERSION}_amd64/tailscale" "$tmp/tailscale_${TS_VERSION}_amd64/tailscaled" "$TS_BIN/"
  echo "$TS_VERSION" > "$TS_BIN/VERSION"
fi

# 2. tailscaled in userspace mode (no TUN device, no root network changes).
if pid="$(running_pid "$RUN_DIR/tailscaled.pid" "$TS_BIN/tailscaled")"; then
  echo "[2/4] tailscaled already running (pid $pid)"
else
  rm -f "$SOCK"
  stamp "$LOG_DIR/tailscaled.log"
  nohup "$TS_BIN/tailscaled" --tun=userspace-networking --statedir="$TS_DIR/state" --socket="$SOCK" \
    >> "$LOG_DIR/tailscaled.log" 2>&1 9>&- &
  tspid=$!
  echo "$tspid" > "$RUN_DIR/tailscaled.pid"
  for _ in $(seq 1 30); do [[ -S "$SOCK" ]] && break; kill -0 "$tspid" 2>/dev/null || break; sleep 1; done
  [[ -S "$SOCK" ]] || { tail -n 20 "$LOG_DIR/tailscaled.log" >&2; die "tailscaled did not start ($LOG_DIR/tailscaled.log)"; }
  echo "[2/4] tailscaled started (userspace networking, state $TS_DIR/state)"
fi

# 3. Join the tailnet. Known node: returns at once. New node: prints a login link and waits for approval.
echo "[3/4] tailnet login as '$TS_HOSTNAME' (a link appears only on the first run: approve it in the team tailnet;"
echo "      it is also written to $LOG_DIR/tailscale-up.log)"
stamp "$LOG_DIR/tailscale-up.log"
ts up --reset --hostname="$TS_HOSTNAME" --timeout=15m 2>&1 | tee -a "$LOG_DIR/tailscale-up.log"
TS_IP="$(ts ip -4)"
[[ "$TS_IP" =~ ^100\. ]] || die "unexpected tailnet address: $TS_IP"

# 4. The inference service, loopback only (tailscaled forwards tailnet connections to it).
export HF_HOME="${HF_HOME:-$WS/.cache/huggingface}" TORCH_HOME="${TORCH_HOME:-$WS/.cache/torch}"  # SD VAE, DINOv2
export UVICORN_TIMEOUT_KEEP_ALIVE="$KEEP_ALIVE_S"   # uvicorn reads its UVICORN_* settings (both modes run python -m uvicorn)
if [[ "$THREADS" == default ]]; then
  unset OMP_NUM_THREADS MKL_NUM_THREADS OPENBLAS_NUM_THREADS
else
  export OMP_NUM_THREADS="$THREADS" MKL_NUM_THREADS="$THREADS" OPENBLAS_NUM_THREADS="$THREADS"
fi
if pid="$(running_pid "$RUN_DIR/inference.pid" "$SERVE")"; then
  # The same uvicorn command line serves both modes: after installing AnimalLift on a Pod that still runs the
  # passthrough test, the old service must not be taken for the model.
  running_mode="$(cat "$RUN_DIR/inference.mode" 2>/dev/null || echo unknown)"
  [[ "$running_mode" == "$MODE" ]] ||
    die "the inference service (pid $pid) runs in $running_mode mode, MODE is $MODE - kill $pid and run this again"
  curl -fsS --max-time 3 -o /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null ||
    die "the inference service (pid $pid) does not answer /healthz - see $LOG_DIR/inference.log, kill $pid and run this again"
  settings="$(service_settings "$pid")"
  echo "[4/4] inference service already running ($MODE, pid $pid, $settings, stop: kill $pid)"
  if [[ "$settings" != "$WANTED_SETTINGS" && "$settings" != "settings unknown"* ]]; then
    echo "NOTE: it was started with other settings; kill $pid and run this again for $WANTED_SETTINGS"
  fi
else
  port_busy "$PORT" && die "127.0.0.1:$PORT is already in use by something this script did not start - stop it or set PORT (and the VM's INFERENCE_BASE_URL)"
  if [[ "$MODE" == passthrough ]]; then
    venv="$WS/beside-venv"
    if ! "$venv/bin/python" -c 'import sys' 2>/dev/null; then
      rm -rf "$venv"
      python3 -m venv "$venv" || die "python3 -m venv failed (apt-get update && apt-get install -y python3-venv)"
    fi
    "$venv/bin/python" -m pip install -q --no-cache-dir --disable-pip-version-check \
      -r "$REPO/generation/inference_service/requirements.txt"
    glb="${SAMPLE_GLB:-$WS/beside-sample.glb}"
    if [[ ! -s "$glb" ]]; then
      # One-triangle glTF 2.0 binary: enough for the wiring test. Put a real GLB at this path to show a pet instead.
      "$venv/bin/python" - "$glb" <<'PY'
import json, struct, sys
pos = struct.pack("<9f", 0, 0, 0, 0.1, 0, 0, 0, 0.1, 0)
idx = struct.pack("<3H", 0, 1, 2) + b"\0\0"
blob = pos + idx
doc = {
    "asset": {"version": "2.0", "generator": "beside deploy/runpod/start_pod.sh (wiring test)"},
    "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
    "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
    "buffers": [{"byteLength": len(blob)}],
    "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36, "target": 34962},
                    {"buffer": 0, "byteOffset": 36, "byteLength": 6, "target": 34963}],
    "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3",
                   "min": [0, 0, 0], "max": [0.1, 0.1, 0]},
                  {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}],
}
js = json.dumps(doc, separators=(",", ":")).encode()
js += b" " * (-len(js) % 4)
out = struct.pack("<4sII", b"glTF", 2, 12 + 8 + len(js) + 8 + len(blob))
out += struct.pack("<I4s", len(js), b"JSON") + js + struct.pack("<I4s", len(blob), b"BIN\0") + blob
open(sys.argv[1], "wb").write(out)
PY
      echo "      wrote a one-triangle sample GLB: $glb"
    fi
    stamp "$LOG_DIR/inference.log"
    # "&" on the command alone (not on a "cd && ..." list) so $! is the server's own pid
    (cd "$REPO/generation/inference_service" || exit 1
     BESIDE_SAMPLE_GLB="$glb" BESIDE_WORK_DIR="${WORK_DIR:-$WS/beside-work}" \
       nohup "$venv/bin/python" -m $SERVE >> "$LOG_DIR/inference.log" 2>&1 9>&- &
     echo $! > "$RUN_DIR/inference.pid") || die "cannot cd to $REPO/generation/inference_service"
  else
    stamp "$LOG_DIR/inference.log"
    CONDA_HOME="${CONDA_HOME:-$WS/miniconda3}" CONDA_ENV="${CONDA_ENV:-animallift}" ANIMALLIFT_DIR="$ANIMALLIFT_DIR" \
      WORK_DIR="${WORK_DIR:-$WS/beside-work}" RIG_TEMPLATE="${RIG_TEMPLATE:-$WS/beside-rig/template-rigged.glb}" \
      BESIDE_HOST=127.0.0.1 PORT="$PORT" \
      nohup bash "$REPO/scripts/start_inference_wsl.sh" >> "$LOG_DIR/inference.log" 2>&1 9>&- &
    echo $! > "$RUN_DIR/inference.pid"
  fi
  echo "$MODE" > "$RUN_DIR/inference.mode"
  svpid="$(cat "$RUN_DIR/inference.pid")"
  up=0
  for _ in $(seq 1 120); do   # a fuse /workspace makes the first torch import slow
    if curl -fsS --max-time 3 -o /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null; then up=1; break; fi
    kill -0 "$svpid" 2>/dev/null || break   # exited (port taken, conda env missing ...): stop waiting
    sleep 1
  done
  [[ $up == 1 ]] && kill -0 "$svpid" 2>/dev/null ||
    { tail -n 30 "$LOG_DIR/inference.log" >&2; die "inference service did not answer /healthz ($LOG_DIR/inference.log)"; }
  echo "[4/4] inference service up ($MODE, 127.0.0.1:$PORT, X-Inference-Token required, $(service_settings "$svpid"))"
fi

echo
echo "Pod tailnet address : $TS_IP"
echo "VM deploy/.env      : INFERENCE_BASE_URL=http://$TS_IP:$PORT"
echo "check from the VM   : curl -sS http://$TS_IP:$PORT/healthz"
echo "service log         : tail -f $LOG_DIR/inference.log"
if [[ "$MODE" == model ]]; then echo "model               : wait for 'model loaded ... on cuda' in the log (healthz modelLoaded)"; fi
