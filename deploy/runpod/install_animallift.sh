#!/usr/bin/env bash
# Runpod Pod: install AnimalLift + the Beside inference deps under /workspace (survives stop/terminate on a network volume).
#   nohup bash install_animallift.sh > /workspace/beside-logs/install.log 2>&1 &     progress: beside-logs/install.status
# The checkout is moved to /workspace/AnimalLift only at the very end: start_pod.sh switches to model mode as soon as that
# folder exists. Re-running resumes (conda, env, clone and checkpoint are skipped when already there).
set -euo pipefail
WS="${WORKSPACE:-/workspace}"
REPO="${BESIDE_REPO:-$WS/Beside}"
CONDA_HOME="${CONDA_HOME:-$WS/miniconda3}"        # Miniforge (conda-forge, no Anaconda ToS prompt) at the default path
CONDA_ENV="${CONDA_ENV:-animallift}"
MF_VER=26.9.4-2
MF_SHA=bbaf16b3897da24c4be50315934ba74848f617b4495982841d6a0a60e0edbbdf
PYVER=3.10.20                                      # upstream AnimalLift README
CKPT_BYTES=2317900684                              # Chunyi99/AnimalLift latest.pt (Hugging Face API)
DEST="$WS/AnimalLift"
WORK="$WS/AnimalLift.installing"
STATUS="$WS/beside-logs/install.status"
export HF_HOME="${HF_HOME:-$WS/.cache/huggingface}" TORCH_HOME="${TORCH_HOME:-$WS/.cache/torch}"
export PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
mkdir -p "$WS/beside-logs"
step() { echo "$(date -u +%FT%TZ) +${SECONDS}s $*" | tee -a "$STATUS"; }
trap 'step "FAILED at line $LINENO (see install.log)"' ERR
[[ -d "$DEST" ]] && { step "already installed at $DEST"; exit 0; }
step "start"
if [[ ! -x "$CONDA_HOME/bin/conda" ]]; then
  step "miniforge $MF_VER"
  f="/tmp/Miniforge3-$MF_VER-Linux-x86_64.sh"
  curl -fsSL --retry 3 -o "$f" "https://github.com/conda-forge/miniforge/releases/download/$MF_VER/Miniforge3-$MF_VER-Linux-x86_64.sh"
  echo "$MF_SHA  $f" | sha256sum -c --quiet -
  bash "$f" -b -p "$CONDA_HOME"
  rm -f "$f"
fi
PY="$CONDA_HOME/envs/$CONDA_ENV/bin/python"
if [[ ! -x "$PY" ]]; then
  step "conda env $CONDA_ENV python=$PYVER"
  "$CONDA_HOME/bin/conda" create -y -q -n "$CONDA_ENV" "python=$PYVER" pip
fi
step "env $("$PY" --version 2>&1)"
[[ -d "$WORK/.git" ]] || { rm -rf "$WORK"; git clone -q https://github.com/Chuny1/AnimalLift.git "$WORK"; }
step "AnimalLift $(git -C "$WORK" rev-parse --short HEAD)"
step "pip: AnimalLift requirements (torch 2.4.0+cu124)"
"$PY" -m pip install -q -r "$WORK/requirements.txt"
step "pip: Beside inference service + tools"
"$PY" -m pip install -q -r "$REPO/generation/inference_service/requirements.txt" -r "$REPO/generation/tools/requirements.txt"
"$PY" -c 'import torch; assert torch.cuda.is_available(), "CUDA not available"; print("torch", torch.__version__, "cuda", torch.version.cuda, torch.cuda.get_device_name(0))' | tee -a "$STATUS"
if [[ "$(stat -c %s "$WORK/ckpt/latest.pt" 2>/dev/null || echo 0)" != "$CKPT_BYTES" ]]; then
  step "checkpoint Chunyi99/AnimalLift"
  (cd "$WORK" && "$CONDA_HOME/envs/$CONDA_ENV/bin/hf" download Chunyi99/AnimalLift --local-dir ckpt)
fi
[[ "$(stat -c %s "$WORK/ckpt/latest.pt")" == "$CKPT_BYTES" ]] || { step "ERROR checkpoint size"; exit 1; }
step "disk $(du -sh "$CONDA_HOME" "$WORK" 2>/dev/null | tr '\n\t' '  ')"
mv "$WORK" "$DEST"
step "done: $DEST (now start_pod.sh defaults to MODE=model)"
