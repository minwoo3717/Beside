#!/usr/bin/env bash
# WSL2 Ubuntu: start the AnimalLift inference service for a Spring real server running on Windows (same PC).
#   bash /mnt/c/Users/<you>/Desktop/Beside-main/scripts/start_inference_wsl.sh
# Override with env vars: ANIMALLIFT_DIR, CONDA_ENV, CONDA_HOME, WORK_DIR, PORT, RIG_TEMPLATE
# Rigging: put the rigged template at ~/beside-rig/template-rigged.glb (or set RIG_TEMPLATE) -> animated results
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
CONDA_HOME="${CONDA_HOME:-$HOME/miniconda3}"
CONDA_ENV="${CONDA_ENV:-animallift}"
export BESIDE_ANIMALLIFT_DIR="${ANIMALLIFT_DIR:-$HOME/AnimalLift}"
export BESIDE_PATH_MAP="${BESIDE_PATH_MAP:-C:\\=/mnt/c/}"
export BESIDE_WORK_DIR="${WORK_DIR:-/mnt/c/beside/work}"
PORT="${PORT:-8001}"
RIG_TEMPLATE="${RIG_TEMPLATE:-$HOME/beside-rig/template-rigged.glb}"
if [[ -s "$RIG_TEMPLATE" ]]; then export BESIDE_RIG_TEMPLATE="$RIG_TEMPLATE"; elif [[ -e "$RIG_TEMPLATE" ]]; then echo "WARNING: $RIG_TEMPLATE is empty (0 bytes) - rigging off"; fi

# shellcheck disable=SC1091
source "$CONDA_HOME/etc/profile.d/conda.sh"
conda activate "$CONDA_ENV"
cd "$REPO/generation/inference_service"
echo "AnimalLift: $BESIDE_ANIMALLIFT_DIR | work: $BESIDE_WORK_DIR | port: $PORT"
echo "rigging: ${BESIDE_RIG_TEMPLATE:-off (no template at $RIG_TEMPLATE)}"
echo "wait for: 'model loaded ... on cuda'   (stop: Ctrl+C)"
exec python -m uvicorn app:app --host 127.0.0.1 --port "$PORT"
