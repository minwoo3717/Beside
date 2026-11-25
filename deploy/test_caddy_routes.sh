#!/usr/bin/env bash
# deploy/Caddyfile 의 경로 규칙 시험: 가짜 백엔드(모든 요청에 200) 앞에 같은 Caddyfile 을 HTTP 로 띄우고
# 경로마다 상태 코드를 본다. 404 = Caddy 가 막음, 401 = Basic 인증 요구, 200 = 백엔드까지 갔음, 413 = 본문 상한.
# /grafana* 도 같은 가짜 백엔드로 보낸다(GRAFANA_UPSTREAM). 가짜 백엔드는 /grafana 요청에 Authorization 헤더가
# 남아 있으면 400 을 준다 — Caddy 가 Basic 인증 헤더를 지우지 않으면 진짜 Grafana 가 401 을 주기 때문이다.
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
# 손님이 보낸 전달 헤더(Caddy 가 지워야 하는 것)가 도착하면 418 을 줘서 시험이 알아채게 한다.
# 단 /grafana 로 Authorization 헤더가 들어오면 400(Caddy 의 header_up -Authorization 이 빠졌다는 뜻).
cat > "$WORK/upstream.py" <<'PY'
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MUST_BE_STRIPPED = ("Forwarded", "X-Forwarded-Prefix", "X-Forwarded-Port", "X-Forwarded-Ssl")


class Upstream(BaseHTTPRequestHandler):
    def answer(self):
        left = int(self.headers.get("Content-Length") or 0)
        while left > 0:
            chunk = self.rfile.read(min(left, 1 << 20))
            if not chunk:
                break
            left -= len(chunk)
        leaked = any(self.headers.get(h) is not None for h in MUST_BE_STRIPPED)
        auth_leaked = self.path.startswith("/grafana") and self.headers.get("Authorization")
        self.send_response(418 if leaked else 400 if auth_leaked else 200)
        if self.command == "POST" and self.path.endswith("/shares"):
            self.send_header("Location", "/api/v1/shares/AbCdEf123")   # like the 201 of createJobShare
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
  -e GRAFANA_UPSTREAM="127.0.0.1:$UPSTREAM_PORT" \
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

echo "-- 404: v0 /api/jobs, H2 console, Swagger, OpenAPI, Actuator, ';' paths"
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
check 404 GET  /actuator
check 404 GET  /actuator/prometheus
check 404 GET  /actuator/health
check 404 GET  /ACTUATOR/prometheus
check 404 GET  /%61ctuator/prometheus
check 404 GET  /API/JOBS
check 404 GET  /%61pi/jobs
check 404 GET  //api//jobs
check 404 GET  /api/v1/../jobs
check 404 GET  "/api/jobs;x=1"
check 404 GET  "/api;x=1/jobs"
check 404 GET  "/api/v1/jobs;x=1"
check 404 GET  "/api;x=1/v1/jobs"
check 404 DELETE "/api/v1/jobs;x=1/$JOB"
check 404 GET  /webjars/swagger-ui/5.10.3/index.html
check 404 GET  /WEBJARS/x.js

echo "-- 401: web pages and admin/list/delete APIs without credentials"
check 401 GET  /
check 401 GET  /index.html
check 401 GET  /app.js
# 끝의 / 와 인코딩된 / (2026-11-13 감사: 예전 규칙에서 Spring 이 index.html·app.js 를 인증 없이 내줬다)
check 401 GET  /index.html/
check 401 GET  /index.html//
check 401 GET  /index.html%2F
check 401 GET  /INDEX.HTML/
check 401 GET  /app.js/
check 401 GET  /app.js/x
# method 철자·종류에 기대지 않기: 목록 경로는 POST 말고 전부, 작업 하나는 GET·HEAD·POST 말고 전부 인증
check 401 get  /api/v1/jobs
check 401 OPTIONS /api/v1/jobs
check 401 PUT  /api/v1/jobs
check 401 delete "/api/v1/jobs/$JOB"
check 401 PATCH "/api/v1/jobs/$JOB"
check 401 PUT  "/api/v1/jobs/$JOB/pet/"
check 401 GET  "/api/v1/jobs/$JOB/pet"
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
check 401 GET  /grafana
check 401 GET  /grafana/
check 401 GET  /grafana/api/health
check 401 GET  /grafana/d/beside-ops
check 401 GET  "/api/v1/jobs/$JOB/ratings"
check 401 PUT  "/api/v1/jobs/$JOB/ratings/minwoo"
check 401 GET  "/api/v1/jobs/$JOB/ratings/"
check 401 GET  "/api/v1/jobs/$JOB/ratings/minwoo"
# 공유 링크 만들기·모두 끊기는 웹 작업 상세만 쓴다(앱은 아직 공유하지 않음, 2026-11-21 v1.2 검토)
check 401 POST "/api/v1/jobs/$JOB/shares"
check 401 POST "/api/v1/jobs/$JOB/shares?ttlDays=30"
check 401 post "/api/v1/jobs/$JOB/shares"
check 401 DELETE "/api/v1/jobs/$JOB/shares"
check 401 POST "/api/v1/jobs/$JOB/shares/"
check 401 DELETE /api/v1/shares/AbCdEf123
check 401 delete /api/v1/shares/AbCdEf123
check 401 PATCH /api/v1/shares/AbCdEf123
check 401 GET  /api/v1/jobs -u "$AUTH_USER:wrong-password"

echo "-- 200 with credentials"
check 200 GET  / "${AUTH[@]}"
check 200 GET  /api/v1/jobs "${AUTH[@]}"
check 200 DELETE "/api/v1/jobs/$JOB" "${AUTH[@]}"
check 200 GET  /api/v1/admin/overview "${AUTH[@]}"
check 200 GET  /grafana/ "${AUTH[@]}"
check 200 GET  /grafana/api/health "${AUTH[@]}"
check 200 DELETE /api/v1/shares/AbCdEf123 "${AUTH[@]}"
check 200 POST "/api/v1/jobs/$JOB/shares" "${AUTH[@]}"
check 200 DELETE "/api/v1/jobs/$JOB/shares" "${AUTH[@]}"
check 200 PUT  "/api/v1/jobs/$JOB/ratings/minwoo" "${AUTH[@]}"

echo "-- 200 public: app API, healthz, share links, static files"
check 200 GET  /api/v1/healthz
check 200 POST /api/v1/jobs
check 200 GET  "/api/v1/jobs/$JOB"
check 200 GET  "/api/v1/jobs/$JOB/asset?variant=base"
check 200 POST "/api/v1/jobs/$JOB/retry"
check 200 POST "/api/v1/jobs/$JOB/cancel"
check 200 GET  /styles.css
check 200 GET  /s/AbCdEf123
check 200 GET  /share.html
check 200 GET  /share.js
check 200 GET  /api/v1/shares/AbCdEf123
check 200 GET  /api/v1/shares/AbCdEf123/asset
check 200 HEAD "/api/v1/jobs/$JOB"

echo "-- forwarding headers a client sends are stripped before Spring (fake backend answers 418 if one arrives)"
check 200 GET  /api/v1/healthz -H 'Forwarded: for=127.0.0.1;host=localhost;proto=http'
check 200 GET  /api/v1/healthz -H 'X-Forwarded-Prefix: /zz'
check 200 GET  /api/v1/healthz -H 'X-Forwarded-Port: 80' -H 'X-Forwarded-Ssl: off'
check 200 GET  /api/v1/jobs "${AUTH[@]}" -H 'Forwarded: host=evil.example'

echo "-- response headers"
check_header() { # check_header <path> <header line regex>
  local got
  got="$(curl -s -o /dev/null -D - --max-time 20 "$BASE$1" | tr -d '\r' | grep -iE "$2" || true)"
  if [[ -n "$got" ]]; then printf 'ok   %s: %s\n' "$1" "$got"; else printf 'FAIL %s: no header matching %s\n' "$1" "$2"; failures=$((failures + 1)); fi
}
check_header /api/v1/healthz '^strict-transport-security: max-age=[0-9]+'
check_header /api/v1/healthz '^x-content-type-options: nosniff'
check_header /index.html '^strict-transport-security: '   # 401 응답에도

echo "-- 413: body over request_body max_size (25MB)"
head -c 26000000 /dev/zero > "$WORK/big.bin"
check 413 POST /api/v1/jobs --http1.1 -H 'Content-Type: application/octet-stream' --data-binary "@$WORK/big.bin"

echo "-- 502 with the backend down: the error log (default logger, not the access log) for a share path"
kill "$UPSTREAM_PID" 2>/dev/null || true
wait "$UPSTREAM_PID" 2>/dev/null || true
UPSTREAM_PID=""
check 502 GET /s/AbCdEf123

echo "-- Caddy logs keep only the first 6 characters of a share token (the checks above sent AbCdEf123)"
caddy_log="$(docker logs "$NAME" 2>&1 || true)"
log_check() { # log_check <description> <grep -E pattern that must match one line>
  if grep -qE "$2" <<<"$caddy_log"; then
    printf 'ok   %s\n' "$1"
  else
    printf 'FAIL %s\n' "$1"
    failures=$((failures + 1))
  fi
}
if grep -q 'AbCdEf123' <<<"$caddy_log"; then
  echo 'FAIL a Caddy log line has a whole share token:'
  grep 'AbCdEf123' <<<"$caddy_log" | head -3
  failures=$((failures + 1))
else
  echo 'ok   no whole share token in the Caddy logs'
fi
log_check "access log: cut viewer path" '"uri":"/s/AbCdEf\.\.\."'
log_check "access log: cut asset path" '"uri":"/api/v1/shares/AbCdEf\.\.\./asset"'
log_check "access log: cut Location of a new share link" '"Location":\["/api/v1/shares/AbCdEf\.\.\."\]'
log_check "error log of the 502: cut path" '"level":"error".*"uri":"/s/AbCdEf\.\.\."'

if (( failures > 0 )); then
  echo "$failures check(s) failed. Caddy log:"
  docker logs --tail 50 "$NAME" 2>&1 || true
  exit 1
fi
echo "all route checks passed"
