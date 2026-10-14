#!/usr/bin/env bash
# Ubuntu: Beside real profile on one machine — inference service (AnimalLift) + Spring real, both in the background.
#   ./scripts/run_real.sh /path/to/AnimalLift [/path/to/conda/envs/animallift/bin/python]
# Logs: logs/inference.log, logs/spring.log.  Stop: kill the PIDs printed at the end (or ./scripts/run_real.sh stop).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/logs"

if [[ "${1:-}" == "stop" ]]; then
  for f in "$ROOT/logs/"*.pid; do [[ -f "$f" ]] && kill "$(cat "$f")" 2>/dev/null && rm -f "$f"; done
  echo "stopped"; exit 0
fi

ANIMALLIFT_DIR="${1:?usage: run_real.sh <AnimalLift dir> [python]}"
PY="${2:-$(command -v python)}"
PORT="${PORT:-8080}"; INFER_PORT="${INFER_PORT:-8001}"
[[ -f "$ANIMALLIFT_DIR/animallift/inference.py" ]] || { echo "not an AnimalLift checkout: $ANIMALLIFT_DIR"; exit 1; }

# 1. inference service (same machine as Spring -> 127.0.0.1 only)
( cd "$ROOT/generation/inference_service" && \
  BESIDE_ANIMALLIFT_DIR="$ANIMALLIFT_DIR" nohup "$PY" -m uvicorn app:app --host 127.0.0.1 --port "$INFER_PORT" \
  > "$ROOT/logs/inference.log" 2>&1 & echo $! > "$ROOT/logs/inference.pid" )

# 2. Spring real (working dir backend/ for storage/ paths)
( cd "$ROOT/backend" && \
  nohup ./gradlew bootRun --args="--spring.profiles.active=real --server.port=$PORT --inference.base-url=http://127.0.0.1:$INFER_PORT" \
  > "$ROOT/logs/spring.log" 2>&1 & echo $! > "$ROOT/logs/spring.pid" )

wait_for() { for _ in $(seq 1 120); do curl -sf "$1" && return 0; sleep 2; done; echo; echo "no answer from $1 (see logs/)"; return 1; }
echo -n "[1/2] inference "; wait_for "http://127.0.0.1:$INFER_PORT/healthz"; echo
echo -n "[2/2] spring    "; wait_for "http://127.0.0.1:$PORT/api/v1/healthz"; echo
echo "model loads in the background: wait for 'model loaded' in logs/inference.log (tail -f logs/inference.log)"
echo "phone / other PCs: http://$(hostname -I | awk '{print $1}'):$PORT   (sudo ufw allow $PORT/tcp if the firewall is on)"
echo "stop: ./scripts/run_real.sh stop"
