#!/usr/bin/env bash
# Downloads the preprocessing models (photo check and cutout) into a folder and checks each one against
# backend/models/SHA256SUMS. Idempotent: a file that is already there with the right hash is left alone; a download
# with the wrong hash is not installed and the script fails. Sources and licences: backend/models/README.md.
#
#   scripts/fetch_models.sh                       # into $PREPROCESS_MODELSDIR, else backend/models
#   scripts/fetch_models.sh /opt/beside/models    # the folder the server reads (PREPROCESS_MODELSDIR)
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sums="$repo/backend/models/SHA256SUMS"
target="${1:-${PREPROCESS_MODELSDIR:-$repo/backend/models}}"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

fetch() {
  local name="$1" url="$2" want got part
  want="$(awk -v f="$name" '{ sub(/\r$/, "") } $2 == f { print $1 }' "$sums")"
  if [ -z "$want" ]; then
    echo "no SHA-256 for $name in $sums" >&2
    exit 1
  fi
  if [ -f "$target/$name" ] && [ "$(sha256 "$target/$name")" = "$want" ]; then
    echo "ok    $name"
    return
  fi
  part="$target/$name.part"
  curl -fL --retry 3 --connect-timeout 20 -o "$part" "$url"
  got="$(sha256 "$part")"
  if [ "$got" != "$want" ]; then
    rm -f "$part"
    echo "SHA-256 of $name is $got, expected $want: not installed" >&2
    exit 1
  fi
  mv -f "$part" "$target/$name"
  chmod 0644 "$target/$name"
  echo "got   $name"
}

mkdir -p "$target"
fetch yolox_s.onnx https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_s.onnx
fetch u2net.onnx https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net.onnx
echo "models in $target (point PREPROCESS_MODELSDIR at it unless it is backend/models)"
