#!/usr/bin/env bash
# Live check of the public routes of a running server, without credentials: internal paths answer 404, team pages and
# admin/library/Grafana 401 (Basic auth), the app's public endpoints reach Spring, security headers are set.
# Mirrors deploy/test_caddy_routes.sh (which runs in CI against a local Caddy) where it can. Used after every VM deploy
# ("공개 경로 61/61"), after a failover to the standby VM, and on a staging copy.
#   bash scripts/check_public_routes.sh                                 # https://beside-team.duckdns.org
#   bash scripts/check_public_routes.sh https://beside-team.duckdns.org 10.178.0.9   # same name, sent to that IP
# The second argument pins the domain to an IP with curl --resolve (staging copy via its internal IP, or the standby on
# its temporary IP before the static IP moves), so the certificate of the domain still validates.
# Until 2026-11-26 this script lived outside the repo (scripts/load/out/round2/live_routes.sh, gitignored).
set -uo pipefail
D="${1:-https://beside-team.duckdns.org}"
PIN=()
if [[ -n "${2:-}" ]]; then
  host="${D#*://}"; host="${host%%/*}"
  PIN=(--resolve "$host:443:$2")
fi
JOB=00000000-0000-0000-0000-000000000000
pass=0; fail=0
check() { # check <want> <method> <path> [curl args...]
  local want="$1" method="$2" path="$3"; shift 3
  local got
  got="$(curl -s "${PIN[@]}" -o /dev/null -w '%{http_code}' --path-as-is --max-time 15 -X "$method" "$@" "$D$path" || true)"
  [[ "$method" == HEAD ]] && got="$(curl -s "${PIN[@]}" -o /dev/null -w '%{http_code}' --path-as-is --max-time 15 -I "$@" "$D$path" || true)"
  if [[ "$got" == "$want" ]]; then pass=$((pass + 1)); else fail=$((fail + 1)); printf 'FAIL %s %-7s %s (expected %s)\n' "$got" "$method" "$path" "$want"; fi
}
for p in /api/jobs "/api/jobs/$JOB" /h2-console /h2-console/login.do /swagger-ui.html /swagger-ui/index.html /v3/api-docs \
         /v3/api-docs/swagger-config /actuator /actuator/prometheus /actuator/health /ACTUATOR/prometheus /%61ctuator/prometheus \
         /API/JOBS /%61pi/jobs //api//jobs /api/v1/../jobs "/api/jobs;x=1" "/api;x=1/jobs" "/api/v1/jobs;x=1" "/api;x=1/v1/jobs" \
         /webjars/swagger-ui/5.10.3/index.html /WEBJARS/x.js; do check 404 GET "$p"; done
check 404 POST /api/jobs
check 404 DELETE "/api/v1/jobs;x=1/$JOB"
for p in / /index.html /app.js /index.html/ /index.html%2F /INDEX.HTML/ /app.js/x /admin/ /library/ "/job/?id=$JOB" \
         /api/v1/jobs "/api/v1/jobs?limit=5" /api/v1/jobs/ "/api/v1/jobs/$JOB/pet" /api/v1/admin/overview \
         "/api/v1/admin/runs?format=csv" /api/v1/pets /grafana /grafana/ /grafana/api/health /grafana/d/beside-ops; do check 401 GET "$p"; done
check 401 get /api/v1/jobs
check 401 OPTIONS /api/v1/jobs
check 401 PUT /api/v1/jobs
check 401 HEAD /api/v1/jobs
check 401 DELETE "/api/v1/jobs/$JOB"
check 401 PATCH "/api/v1/jobs/$JOB"
check 401 PUT "/api/v1/jobs/$JOB/pet"
check 401 POST /api/v1/pets
check 401 GET /api/v1/jobs -u "team:wrong-password"
# Public: app endpoints reach Spring.
check 200 GET /api/v1/healthz
check 404 GET "/api/v1/jobs/$JOB"
check 415 POST /api/v1/jobs   # no multipart body: Spring answers 415 (public upload path reaches Spring)
hdr="$(curl -s "${PIN[@]}" -o /dev/null -D - --max-time 15 "$D/index.html" | tr -d '\r')"
for h in '^strict-transport-security: max-age=15552000' '^x-content-type-options: nosniff' '^x-frame-options: DENY'; do
  if grep -qiE "$h" <<<"$hdr"; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "FAIL header $h"; fi
done
echo "healthz: $(curl -s "${PIN[@]}" --max-time 15 "$D/api/v1/healthz")"
echo "== $pass passed, $fail failed"
[[ $fail -eq 0 ]]
