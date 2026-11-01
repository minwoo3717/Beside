#!/usr/bin/env bash
# Update the Beside VM to the latest main: ssh -> git pull --ff-only -> docker compose up -d --build -> healthz.
# Runs from a dev PC (Git Bash / macOS / Linux) that can ssh into the VM. First-time setup: deploy/README.md.
#   scripts/deploy_vm.sh ubuntu@203.0.113.10                  # repo on the VM at ~/Beside
#   scripts/deploy_vm.sh ubuntu@203.0.113.10 /srv/Beside      # a path with ~ must be quoted ('~/x'), else it expands here
#   BESIDE_VM=ubuntu@203.0.113.10 BESIDE_VM_REPO=/srv/Beside scripts/deploy_vm.sh
# Restarting the backend fails jobs that are PENDING/PROCESSING at that moment (INTERNAL_ERROR, the app retries):
# deploy when nobody is generating a model.
set -euo pipefail

DEFAULT_REPO='~/Beside'   # expanded on the VM, not here
TARGET="${1:-${BESIDE_VM:-}}"
REPO="${2:-${BESIDE_VM_REPO:-$DEFAULT_REPO}}"
if [[ -z "$TARGET" ]]; then
  echo "usage: $0 <user@vm> [repo path on the VM, default ~/Beside]   (or env BESIDE_VM, BESIDE_VM_REPO)" >&2
  exit 2
fi

# The remote part gets the repo path as $1 (quoted with %q so spaces survive; a leading ~ is expanded there).
# bash reads this script from stdin, so commands inside get </dev/null and cannot swallow the rest of it.
ssh "$TARGET" "bash -s -- $(printf '%q' "$REPO")" <<'REMOTE'
set -euo pipefail
cd "${1/#\~/$HOME}"
COMPOSE=(docker compose -f deploy/docker-compose.yml --env-file deploy/.env)
[[ -f deploy/.env ]] || { echo "deploy/.env is missing on the VM (cp deploy/.env.example deploy/.env, see deploy/README.md)" >&2; exit 1; }

OLD="$(git rev-parse HEAD)"
echo "[1/3] git pull --ff-only   (was $(git rev-parse --short HEAD))"
git pull --ff-only </dev/null
git --no-pager log --oneline -1

echo "[2/3] docker compose up -d --build"
"${COMPOSE[@]}" up -d --build </dev/null
# Caddyfile is a single-file bind mount: git replaces the file, the running container keeps the old one until restart.
# Compare with what the running caddy reads, not with this run's pull: an earlier run may have pulled the change and
# then failed at the build, or someone pulled by hand, and the old rules (Basic auth routes) would stay live.
if ! "${COMPOSE[@]}" exec -T caddy cat /etc/caddy/Caddyfile </dev/null | cmp -s - deploy/Caddyfile; then
  echo "      caddy runs another deploy/Caddyfile than the checkout -> restart caddy"
  "${COMPOSE[@]}" restart caddy </dev/null
fi

# Through Caddy on this VM (TLS + proxy + backend), without depending on hairpin routing to the public IP.
DOMAIN="$(sed -n 's/^BESIDE_DOMAIN=//p' deploy/.env | tr -d "\"' \r")"
echo "[3/3] https://$DOMAIN/api/v1/healthz"
for _ in $(seq 1 60); do
  if out="$(curl -fsS --max-time 5 --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/api/v1/healthz" 2>/dev/null)"; then
    echo "$out"
    "${COMPOSE[@]}" ps </dev/null
    exit 0
  fi
  sleep 2
done
echo "no healthz answer within 120 s. Logs: docker compose -f deploy/docker-compose.yml logs --tail 100 backend caddy" >&2
"${COMPOSE[@]}" ps </dev/null
exit 1
REMOTE

echo "deployed. From outside: curl https://<domain>/api/v1/healthz  (checks: deploy/README.md '확인')"
