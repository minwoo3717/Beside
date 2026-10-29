#!/usr/bin/env bash
# deploy/Caddyfile 의 경로 규칙 시험: 가짜 백엔드(모든 요청에 200) 앞에 같은 Caddyfile 을 HTTP 로 띄우고
# 경로마다 상태 코드를 본다. 404 = Caddy 가 막음, 401 = Basic 인증 요구, 200 = 백엔드까지 갔음, 413 = 본문 상한.
# Spring·도메인·인증서는 필요 없다. 필요: Linux, docker, python3, curl. CI(.github/workflows/backend-ci.yml)가 돌린다.
#   bash deploy/test_caddy_routes.sh
# 포트: CADDY_PORT(기본 18088), UPSTREAM_PORT(기본 18089). Caddy 이미지는 docker-compose.yml 의 태그를 쓴다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CADDY_IMAGE="${CADDY_IMAGE:-$(grep -m1 -oE 'caddy:[0-9][0-9.]*' "$ROOT/deploy/docker-compose.yml")}"
CADDY_PORT="${CADDY_PORT:-18088}"
UPSTREAM_PORT="${UPSTREAM_PORT:-18089}"
BASE="http://127.0.0.1:$CADDY_PORT"
NAME="beside-caddy-route-test-$$"
AUTH_USER=ci
AUTH_PASSWORD=ci-route-test
JOB=00000000-0000-4000-8000-000000000000
WORK="$(mktemp -d)"
UPSTREAM_PID=""

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  if [[ -n "$UPSTREAM_PID" ]]; then kill "$UPSTREAM_PID" 2>/dev/null || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# 가짜 백엔드: 본문을 끝까지 읽고(그래야 큰 업로드가 Caddy 의 상한에 걸린다) 200 을 준다.
cat > "$WORK/upstream.py" <<'PY'
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Upstream(BaseHTTPRequestHandler):
    def answer(self):
        left = int(self.headers.get("Content-Length") or 0)
        while left > 0:
            chunk = self.rfile.read(min(left, 1 << 20))
            if not chunk:
                break
            left -= len(chunk)
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    do_GET = do_HEAD = do_POST = do_PUT = do_PATCH = do_DELETE = do_OPTIONS = answer

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Upstream).serve_forever()
PY
python3 "$WORK/upstream.py" "$UPSTREAM_PORT" 2> "$WORK/upstream.log" &
UPSTREAM_PID=$!

echo "image: $CADDY_IMAGE"
HASH="$(docker run --rm "$CADDY_IMAGE" caddy hash-password --plaintext "$AUTH_PASSWORD")"
# 사이트 주소 :포트 = 호스트 이름 없는 HTTP 사이트(인증서 없음). 나머지 규칙은 VM 과 같은 파일이다.
docker run -d --name "$NAME" --network host \
  -e BESIDE_DOMAIN=":$CADDY_PORT" \
  -e BASIC_AUTH_USER="$AUTH_USER" \
  -e BASIC_AUTH_HASH="$HASH" \
  -e BACKEND_UPSTREAM="127.0.0.1:$UPSTREAM_PORT" \
  -v "$ROOT/deploy/Caddyfile:/etc/caddy/Caddyfile:ro" \
  "$CADDY_IMAGE" >/dev/null

for _ in $(seq 1 30); do
  curl -s -o /dev/null --max-time 2 "$BASE/" && break
  sleep 1
done

failures=0
check() { # check <기대 코드> <메서드> <경로> [curl 추가 인자...]
  local want="$1" method="$2" path="$3" got
  shift 3
  local args=(-s -o /dev/null -w '%{http_code}' --max-time 20 --path-as-is)
  if [[ "$method" == HEAD ]]; then args+=(-I); else args+=(-X "$method"); fi
  got="$(curl "${args[@]}" "$@" "$BASE$path" || true)"
  if [[ "$got" == "$want" ]]; then
    printf 'ok   %s %-7s %s\n' "$got" "$method" "$path"
  else
    printf 'FAIL %s %-7s %s (expected %s)\n' "$got" "$method" "$path" "$want"
    failures=$((failures + 1))
  fi
}
AUTH=(-u "$AUTH_USER:$AUTH_PASSWORD")

echo "-- 404: v0 /api/jobs, H2 console, Swagger, OpenAPI, ';' paths"
check 404 GET  /api/jobs
check 404 POST /api/jobs
check 404 GET  "/api/jobs/$JOB"
check 404 GET  "/api/jobs/$JOB/result"
check 404 GET  /h2-console
check 404 GET  /h2-console/login.do
check 404 GET  /swagger-ui.html
check 404 GET  /swagger-ui/index.html
check 404 GET  /v3/api-docs
check 404 GET  /v3/api-docs/swagger-config
check 404 GET  /API/JOBS
check 404 GET  /%61pi/jobs
check 404 GET  //api//jobs
check 404 GET  /api/v1/../jobs
check 404 GET  "/api/jobs;x=1"
check 404 GET  "/api;x=1/jobs"
check 404 GET  "/api/v1/jobs;x=1"
check 404 GET  "/api;x=1/v1/jobs"
check 404 DELETE "/api/v1/jobs;x=1/$JOB"

echo "-- 401: web pages and admin/list/delete APIs without credentials"
check 401 GET  /
check 401 GET  /index.html
check 401 GET  /app.js
check 401 GET  /admin/
check 401 GET  /admin/index.html
check 401 GET  /library/
check 401 GET  "/job/?id=$JOB"
check 401 GET  /api/v1/jobs
check 401 GET  "/api/v1/jobs?limit=5"
check 401 HEAD /api/v1/jobs
check 401 GET  /api/v1/jobs/
check 401 DELETE "/api/v1/jobs/$JOB"
check 401 PUT  "/api/v1/jobs/$JOB/pet"
check 401 GET  /api/v1/admin/overview
check 401 GET  "/api/v1/admin/runs?format=csv"
check 401 GET  /api/v1/pets
check 401 POST /api/v1/pets
check 401 GET  /api/v1/pets/abc
check 401 GET  /api/v1/jobs -u "$AUTH_USER:wrong-password"

echo "-- 200 with credentials"
check 200 GET  / "${AUTH[@]}"
check 200 GET  /api/v1/jobs "${AUTH[@]}"
check 200 DELETE "/api/v1/jobs/$JOB" "${AUTH[@]}"
check 200 GET  /api/v1/admin/overview "${AUTH[@]}"

echo "-- 200 public: app API, healthz, share links, static files"
check 200 GET  /api/v1/healthz
check 200 POST /api/v1/jobs
check 200 GET  "/api/v1/jobs/$JOB"
check 200 GET  "/api/v1/jobs/$JOB/asset?variant=base"
check 200 POST "/api/v1/jobs/$JOB/retry"
check 200 GET  /styles.css
check 200 GET  /s/AbCdEf123
check 200 GET  /share.html
check 200 GET  /share.js
check 200 GET  /api/v1/shares/AbCdEf123
check 200 GET  /api/v1/shares/AbCdEf123/asset

echo "-- 413: body over request_body max_size (25MB)"
head -c 26000000 /dev/zero > "$WORK/big.bin"
check 413 POST /api/v1/jobs --http1.1 -H 'Content-Type: application/octet-stream' --data-binary "@$WORK/big.bin"

if (( failures > 0 )); then
  echo "$failures check(s) failed. Caddy log:"
  docker logs --tail 50 "$NAME" 2>&1 || true
  exit 1
fi
echo "all route checks passed"
