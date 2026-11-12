#!/usr/bin/env bash
# Runpod Pod: join the team tailnet (Tailscale userspace mode) and start the inference service on 127.0.0.1.
#
# Why this shape: a Pod is a container without /dev/net/tun, so tailscaled runs with --tun=userspace-networking.
# In that mode tailscaled hands inbound tailnet TCP for its own 100.x address to 127.0.0.1:<same port>
# (wgengine/netstack acceptTCP), so the VM reaches http://<pod 100.x>:8001 while the service listens only on
# loopback: nothing on the Pod's public TCP ports and nothing behind the Runpod HTTP proxy, which cuts requests at
# 100 s (Spring waits up to 600 s on /infer/upload).
#
# Pod web terminal or SSH, after every Pod start (stop wipes the container disk, /workspace survives):
#   git clone https://github.com/minwoo3717/Beside.git /workspace/Beside        # first time only
#   BESIDE_INFERENCE_TOKEN='<INFERENCE_TOKEN from the VM deploy/.env>' bash /workspace/Beside/deploy/runpod/start_pod.sh
# The first run prints a Tailscale login link: approve it in the team tailnet. Later runs reuse /workspace/tailscale,
# so the Pod keeps its node and 100.x address (no new login, no .env change on the VM). Terminating the Pod deletes
# /workspace: log in again, remove the old node in the Tailscale admin console and update INFERENCE_BASE_URL.
#
# MODE=passthrough  fixed sample GLB, no model: tests the VM <-> Pod path (a CPU Pod is enough)
# MODE=model        AnimalLift through scripts/start_inference_wsl.sh; conda and checkpoints must live under /workspace
#                   (generation/inference_service/ANIMALLIFT_SETUP.md), the container disk is wiped on stop
# default: model when $ANIMALLIFT_DIR exists, otherwise passthrough.
# Overrides: WORKSPACE (/workspace), PORT (8001), TS_HOSTNAME (beside-gpu), RUN_DIR (/var/run/beside: socket, pids),
#   ANIMALLIFT_DIR, CONDA_HOME, CONDA_ENV, WORK_DIR, RIG_TEMPLATE, SAMPLE_GLB, TS_VERSION + TS_SHA256 (Tailscale tarball)
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WS="${WORKSPACE:-/workspace}"
PORT="${PORT:-8001}"
TS_HOSTNAME="${TS_HOSTNAME:-beside-gpu}"
TS_VERSION="${TS_VERSION:-1.104.6}"
TS_SHA256="${TS_SHA256:-82a85429f733e52fd1a95dedb2c8dd8cd46d6603504e566a56b1779c9a7a4809}"
TS_DIR="$WS/tailscale"              # binaries + node state: persistent, keeps the same 100.x after a Pod restart
TS_BIN="$TS_DIR/bin"
RUN_DIR="${RUN_DIR:-/var/run/beside}"   # runtime only; a unix socket path must stay short and off network volumes
SOCK="$RUN_DIR/tailscaled.sock"
LOG_DIR="$WS/beside-logs"
ANIMALLIFT_DIR="${ANIMALLIFT_DIR:-$WS/AnimalLift}"
MODE="${MODE:-}"
SERVE="uvicorn app:app --host 127.0.0.1 --port $PORT"   # also how a running service is recognised

die() { echo "ERROR: $*" >&2; exit 1; }

[[ -d "$WS" ]] || die "$WS not found - run this inside the Runpod Pod (or set WORKSPACE)"
[[ "$PORT" =~ ^[0-9]{2,5}$ ]] || die "PORT must be a number (got: $PORT)"
trimmed="${BESIDE_INFERENCE_TOKEN:-}"
trimmed="${trimmed//[[:space:]]/}"
[[ -n "$trimmed" ]] || die "set BESIDE_INFERENCE_TOKEN to INFERENCE_TOKEN from the VM's deploy/.env"
export BESIDE_INFERENCE_TOKEN
command -v curl >/dev/null || die "curl not found (apt-get update && apt-get install -y curl)"
if [[ -z "$MODE" ]]; then
  if [[ -d "$ANIMALLIFT_DIR" ]]; then MODE=model; else MODE=passthrough; fi
fi
case "$MODE" in model|passthrough) ;; *) die "MODE must be model or passthrough (got: $MODE)" ;; esac

# PID from a pid file, only if that process is still the one we started (pid files outlive a Pod restart).
running_pid() {
  local pid
  pid="$(cat "$1" 2>/dev/null)" || return 1
  [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/cmdline" ]] || return 1
  tr '\0' ' ' < "/proc/$pid/cmdline" | grep -qF -- "$2" || return 1
  echo "$pid"
}

ts() { "$TS_BIN/tailscale" --socket="$SOCK" "$@"; }

mkdir -p "$TS_DIR/state" "$RUN_DIR" "$LOG_DIR"
chmod 700 "$TS_DIR" "$TS_DIR/state"   # the state file holds the node key

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
  nohup "$TS_BIN/tailscaled" --tun=userspace-networking --statedir="$TS_DIR/state" --socket="$SOCK" \
    > "$LOG_DIR/tailscaled.log" 2>&1 &
  tspid=$!
  echo "$tspid" > "$RUN_DIR/tailscaled.pid"
  for _ in $(seq 1 30); do [[ -S "$SOCK" ]] && break; kill -0 "$tspid" 2>/dev/null || break; sleep 1; done
  [[ -S "$SOCK" ]] || { tail -n 20 "$LOG_DIR/tailscaled.log" >&2; die "tailscaled did not start ($LOG_DIR/tailscaled.log)"; }
  echo "[2/4] tailscaled started (userspace networking, state $TS_DIR/state)"
fi

# 3. Join the tailnet. Known node: returns at once. New node: prints a login link and waits for approval.
echo "[3/4] tailnet login as '$TS_HOSTNAME' (a link appears only on the first run: approve it in the team tailnet)"
ts up --reset --hostname="$TS_HOSTNAME" --timeout=15m
TS_IP="$(ts ip -4)"
[[ "$TS_IP" =~ ^100\. ]] || die "unexpected tailnet address: $TS_IP"

# 4. The inference service, loopback only (tailscaled forwards tailnet connections to it).
if pid="$(running_pid "$RUN_DIR/inference.pid" "$SERVE")"; then
  echo "[4/4] inference service already running (pid $pid, stop: kill $pid)"
else
  if curl -fsS --max-time 3 -o /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null; then
    die "something not started by this script already answers on 127.0.0.1:$PORT - stop it or set PORT"
  fi
  if [[ "$MODE" == passthrough ]]; then
    venv="$WS/beside-venv"
    if ! "$venv/bin/python" -c 'import sys' 2>/dev/null; then
      rm -rf "$venv"
      python3 -m venv "$venv" || die "python3 -m venv failed (apt-get update && apt-get install -y python3-venv)"
    fi
    "$venv/bin/python" -m pip install -q --disable-pip-version-check -r "$REPO/generation/inference_service/requirements.txt"
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
    # "&" on the command alone (not on a "cd && ..." list) so $! is the server's own pid
    (cd "$REPO/generation/inference_service" || exit 1
     BESIDE_SAMPLE_GLB="$glb" BESIDE_WORK_DIR="${WORK_DIR:-$WS/beside-work}" \
       nohup "$venv/bin/python" -m $SERVE > "$LOG_DIR/inference.log" 2>&1 &
     echo $! > "$RUN_DIR/inference.pid") || die "cannot cd to $REPO/generation/inference_service"
  else
    CONDA_HOME="${CONDA_HOME:-$WS/miniconda3}" CONDA_ENV="${CONDA_ENV:-animallift}" ANIMALLIFT_DIR="$ANIMALLIFT_DIR" \
      WORK_DIR="${WORK_DIR:-$WS/beside-work}" RIG_TEMPLATE="${RIG_TEMPLATE:-$WS/beside-rig/template-rigged.glb}" \
      BESIDE_HOST=127.0.0.1 PORT="$PORT" \
      nohup bash "$REPO/scripts/start_inference_wsl.sh" > "$LOG_DIR/inference.log" 2>&1 &
    echo $! > "$RUN_DIR/inference.pid"
  fi
  svpid="$(cat "$RUN_DIR/inference.pid")"
  up=0
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 3 -o /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null; then up=1; break; fi
    kill -0 "$svpid" 2>/dev/null || break   # exited (port taken, conda env missing ...): stop waiting
    sleep 1
  done
  [[ $up == 1 ]] && kill -0 "$svpid" 2>/dev/null ||
    { tail -n 30 "$LOG_DIR/inference.log" >&2; die "inference service did not answer /healthz ($LOG_DIR/inference.log)"; }
  echo "[4/4] inference service up ($MODE, 127.0.0.1:$PORT, X-Inference-Token required)"
fi

echo
echo "Pod tailnet address : $TS_IP"
echo "VM deploy/.env      : INFERENCE_BASE_URL=http://$TS_IP:$PORT"
echo "check from the VM   : curl -sS http://$TS_IP:$PORT/healthz"
echo "service log         : tail -f $LOG_DIR/inference.log"
if [[ "$MODE" == model ]]; then echo "model               : wait for 'model loaded ... on cuda' in the log (healthz modelLoaded)"; fi
