#!/usr/bin/env bash
# WSL2 Ubuntu: start the AnimalLift inference service.
#   Same PC as Spring (path mode, scripts/run_real.ps1 on Windows):
#     bash /mnt/c/Users/<you>/Desktop/Beside-main/scripts/start_inference_wsl.sh
#   Spring on a cloud VM over Tailscale (upload mode, Spring inference.transfer=upload):
#     BESIDE_HOST=<this PC's tailnet IP> BESIDE_INFERENCE_TOKEN=<secret> bash .../scripts/start_inference_wsl.sh
#     The VM's Spring needs the same secret in inference.token, and inference.base-url=http://<tailnet IP>:<PORT>.
# Override with env vars: ANIMALLIFT_DIR, CONDA_ENV, CONDA_HOME, WORK_DIR, PORT, RIG_TEMPLATE,
#   BESIDE_HOST (bind address, default 127.0.0.1), BESIDE_INFERENCE_TOKEN (X-Inference-Token secret, default none)
# Rigging: put the rigged template at ~/beside-rig/template-rigged.glb (or set RIG_TEMPLATE) -> animated results
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
CONDA_HOME="${CONDA_HOME:-$HOME/miniconda3}"
CONDA_ENV="${CONDA_ENV:-animallift}"
export BESIDE_ANIMALLIFT_DIR="${ANIMALLIFT_DIR:-$HOME/AnimalLift}"
export BESIDE_PATH_MAP="${BESIDE_PATH_MAP:-C:\\=/mnt/c/}"
# Job folders (photos, meshes, GLBs made from pets): inside the repo but ignored by git; under /mnt/c when the repo
# is on C:, so BESIDE_PATH_MAP still reaches them from Windows Spring in path mode.
export BESIDE_WORK_DIR="${WORK_DIR:-$REPO/generation/inference_service/work}"
PORT="${PORT:-8001}"
HOST="${BESIDE_HOST:-127.0.0.1}"
RIG_TEMPLATE="${RIG_TEMPLATE:-$HOME/beside-rig/template-rigged.glb}"
if [[ -s "$RIG_TEMPLATE" ]]; then export BESIDE_RIG_TEMPLATE="$RIG_TEMPLATE"; elif [[ -e "$RIG_TEMPLATE" ]]; then echo "WARNING: $RIG_TEMPLATE is empty (0 bytes) - rigging off"; fi
if [[ -n "${BESIDE_INFERENCE_TOKEN:-}" ]]; then
  export BESIDE_INFERENCE_TOKEN
  TOKEN_STATE="set (X-Inference-Token required on /infer, /infer/upload, /artifacts)"
else
  TOKEN_STATE="none"
  if [[ "$HOST" != "127.0.0.1" && "$HOST" != "localhost" ]]; then
    echo "WARNING: listening on $HOST without BESIDE_INFERENCE_TOKEN - anyone who can reach this address can run jobs"
  fi
fi

# shellcheck disable=SC1091
source "$CONDA_HOME/etc/profile.d/conda.sh"
conda activate "$CONDA_ENV"
cd "$REPO/generation/inference_service"
echo "AnimalLift: $BESIDE_ANIMALLIFT_DIR | work: $BESIDE_WORK_DIR | listen: $HOST:$PORT"
echo "token: $TOKEN_STATE"
echo "rigging: ${BESIDE_RIG_TEMPLATE:-off (no template at $RIG_TEMPLATE)}"
echo "wait for: 'model loaded ... on cuda'   (stop: Ctrl+C)"
exec python -m uvicorn app:app --host "$HOST" --port "$PORT"
