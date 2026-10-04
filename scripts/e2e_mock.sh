#!/usr/bin/env bash
# Beside Mock 서버의 API v1 전체 흐름(healthz -> 업로드 -> 폴링 -> GLB 다운로드)을 curl 로 실행한다.
# 사용법: scripts/e2e_mock.sh [BASE_URL] [photo ...]
#   scripts/e2e_mock.sh                                       # http://localhost:8080, 1x1 PNG 2장 자동 생성
#   scripts/e2e_mock.sh http://192.168.0.10:8080 a.jpg b.jpg
#   scripts/e2e_mock.sh http://localhost:8080 fail-dog.jpg    # 파일명에 fail -> FAILED (종료 코드 1)
# 종료 코드: 0 = COMPLETED + 다운로드 성공, 1 = FAILED / 타임아웃 / HTTP 오류. jq 없이 동작한다.
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
[ $# -gt 0 ] && shift
BASE_URL="${BASE_URL%/}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-60}"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)/out"
mkdir -p "$OUT_DIR"

PHOTOS=("$@")
if [ ${#PHOTOS[@]} -eq 0 ]; then
  PNG_B64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="
  for i in 1 2; do
    if ! printf '%s' "$PNG_B64" | base64 -d > "$OUT_DIR/probe-$i.png" 2>/dev/null; then
      printf '%s' "$PNG_B64" | base64 -D > "$OUT_DIR/probe-$i.png"   # macOS 구버전
    fi
    PHOTOS+=("$OUT_DIR/probe-$i.png")
  done
fi
for p in "${PHOTOS[@]}"; do
  [ -f "$p" ] || { echo "photo not found: $p" >&2; exit 1; }
done

# 평면 문자열/숫자 필드만 뽑는다 (중첩 객체 안의 첫 번째 일치도 허용).
json_field() {
  printf '%s' "$1" | sed -n "s/.*\"$2\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -n1
}
now_ms() {
  date +%s%3N 2>/dev/null || python3 -c 'import time; print(int(time.time() * 1000))'
}

# 1. healthz
HEALTH="$(curl -sS "$BASE_URL/api/v1/healthz")"
echo "[1/4] healthz  status=$(json_field "$HEALTH" status) profile=$(json_field "$HEALTH" profile) workerType=$(json_field "$HEALTH" workerType)"

# 2. 업로드 (202 + Location + { jobId })
FORM=()
for p in "${PHOTOS[@]}"; do FORM+=(-F "photos=@$p"); done
T0=$(now_ms)
BODY="$(curl -sS -D "$OUT_DIR/upload-headers.txt" -H "Idempotency-Key: e2e-$(date +%s)" "${FORM[@]}" "$BASE_URL/api/v1/jobs")"
UPLOAD_MS=$(( $(now_ms) - T0 ))
STATUS_LINE="$(head -n1 "$OUT_DIR/upload-headers.txt" | tr -d '\r')"
case "$STATUS_LINE" in
  *" 202 "*) ;;
  *) echo "upload failed: $STATUS_LINE"; echo "$BODY"; exit 1 ;;
esac
JOB_ID="$(json_field "$BODY" jobId)"
LOCATION="$(grep -i '^Location:' "$OUT_DIR/upload-headers.txt" | head -n1 | sed 's/^[Ll]ocation: *//' | tr -d '\r')"
echo "[2/4] upload   202 jobId=$JOB_ID location=$LOCATION uploadMs=$UPLOAD_MS"

# 3. 폴링 (1초 간격, 최대 TIMEOUT_SECONDS)
STATES=""
STATUS=""
JOB=""
T0=$(now_ms)
DEADLINE=$(( $(date +%s) + TIMEOUT_SECONDS ))
while :; do
  JOB="$(curl -sS "$BASE_URL/api/v1/jobs/$JOB_ID")"
  S="$(json_field "$JOB" status)"
  if [ "$S" != "$STATUS" ]; then STATUS="$S"; STATES="${STATES:+$STATES>}$S"; fi
  if [ "$STATUS" = "COMPLETED" ] || [ "$STATUS" = "FAILED" ]; then break; fi
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then break; fi
  sleep 1
done
WAIT_MS=$(( $(now_ms) - T0 ))
echo "[3/4] poll     states=$STATES waitMs=$WAIT_MS"
echo "        job=$JOB"
if [ "$STATUS" = "FAILED" ]; then echo "        FAILED error.code=$(json_field "$JOB" code)"; exit 1; fi
if [ "$STATUS" != "COMPLETED" ]; then echo "        timeout after ${TIMEOUT_SECONDS}s (last status: $STATUS)"; exit 1; fi

# 4. 다운로드 (asset.url 은 서버 루트 기준 상대 경로)
ASSET_URL="$(json_field "$JOB" url)"
GLB="$OUT_DIR/$JOB_ID-base.glb"
T0=$(now_ms)
curl -sS -f -D "$OUT_DIR/asset-headers.txt" -o "$GLB" "$BASE_URL$ASSET_URL"
DOWNLOAD_MS=$(( $(now_ms) - T0 ))
BYTES=$(wc -c < "$GLB" | tr -d ' ')
echo "[4/4] download 200 bytes=$BYTES downloadMs=$DOWNLOAD_MS file=$GLB"
if [ "$BYTES" -eq 0 ]; then
  echo "WARNING: GLB is 0 bytes. Replace backend/storage/results/sample-dog.glb with a valid GLB (docs/asset/GLB_SPEC.md)." >&2
fi

printf '{"jobId":"%s","uploadMs":%s,"waitMs":%s,"downloadMs":%s,"e2eMs":%s,"bytes":%s,"states":"%s"}\n' \
  "$JOB_ID" "$UPLOAD_MS" "$WAIT_MS" "$DOWNLOAD_MS" $(( UPLOAD_MS + WAIT_MS + DOWNLOAD_MS )) "$BYTES" "$STATES"
