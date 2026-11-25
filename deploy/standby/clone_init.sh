#!/usr/bin/env bash
# First steps on a COPY of the main server made by deploy/gcp/clone_vm.sh (staging or standby), before it gets any
# external IP or service account. A snapshot copy carries prod's Tailscale node key, Discord webhooks, the VM watchdog
# and the off-VM backup timer. Left alone, the copy would fight prod for the tailnet node (and so for the GPU Pod),
# post alerts to the team channel and upload an older DB as the 'newest' backup (00-constraints.md 18/19).
# Run on the copy, as the beside user (uses sudo):
#   ssh -J beside-gcp -o HostKeyAlias=beside-staging beside@<internal IP> 'bash -s -- staging' < deploy/standby/clone_init.sh
#   ... 'bash -s -- standby' ...
#   CLONE_WEBHOOK_URL=http://drill-hook:8080/x  -> alerts of the copy go there (staging drills); default: none
#   DRY_RUN=1                                    -> print what would change
# It refuses to run on any host not named beside-staging or beside-standby (never on prod, beside-vm).
# What it does (each step is safe to repeat):
#   1. /etc/beside-role = <role>, maintenance flag on (the watchdog and backup scripts then stay quiet)
#   2. VM watchdog and off-VM backup timers disabled; the backup's last.ok removed (no false 'backup ok' later)
#   3. tailscaled stopped and disabled, its state wiped (never 'tailscale logout': that expires prod's node key)
#   4. deploy/.env: NOTIFY_WEBHOOK_URL and GRAFANA_ALERT_WEBHOOK_URL set to CLONE_WEBHOOK_URL (values not printed)
#   5. staging: compose (with the monitoring profile) up again so the backend picks up the new .env
#      standby: compose stopped and the out-of-compose beside-stub removed (it must not run jobs or timers)
set -euo pipefail

ROLE="${1:-}"
[[ "$ROLE" =~ ^(staging|standby)$ ]] || { echo "usage: clone_init.sh staging|standby" >&2; exit 2; }
HOST="${BESIDE_HOSTNAME:-$(hostname)}"
if [[ "$HOST" != "beside-$ROLE" ]]; then
  echo "refusing: this host is '$HOST', not beside-$ROLE (clone_init.sh only runs on a copy)" >&2
  exit 2
fi
ETC="${BESIDE_ETC:-/etc}"
TS_STATE="${BESIDE_TS_STATE:-/var/lib/tailscale}"
BACKUP_STATE="${BESIDE_BACKUP_STATE:-/var/lib/beside-backup}"
REPO="${BESIDE_REPO:-$HOME/Beside}"
ENV_FILE="$REPO/deploy/.env"
COMPOSE=(docker compose -f "$REPO/deploy/docker-compose.yml" --env-file "$ENV_FILE" --profile monitoring)

run() {
  if [[ "${DRY_RUN:-0}" == 1 ]]; then
    printf '+'; printf ' %q' "$@"; printf '\n'
  else
    "$@"
  fi
}

echo "[1/5] role $ROLE, maintenance flag"
run sudo sh -c "printf '%s\n' '$ROLE' > '$ETC/beside-role'"
run sudo touch "$ETC/beside-maintenance"

echo "[2/5] watchdog and off-VM backup timers off"
for unit in beside-watchdog.timer beside-backup.timer; do
  run sudo systemctl disable --now "$unit" || true
done
run sudo rm -f "$BACKUP_STATE/last.ok"

echo "[3/5] Tailscale: stop, disable, wipe prod's node state (no logout)"
run sudo systemctl disable --now tailscaled || true
run sudo find "$TS_STATE" -mindepth 1 -delete || true

echo "[4/5] webhooks in deploy/.env -> ${CLONE_WEBHOOK_URL:+the drill address}${CLONE_WEBHOOK_URL:-empty}"
if [[ -f "$ENV_FILE" ]]; then
  for key in NOTIFY_WEBHOOK_URL GRAFANA_ALERT_WEBHOOK_URL; do
    if grep -q "^$key=" "$ENV_FILE"; then
      run sed -i "s|^$key=.*|$key=${CLONE_WEBHOOK_URL:-}|" "$ENV_FILE"
    else
      run sh -c "printf '%s\n' '$key=${CLONE_WEBHOOK_URL:-}' >> '$ENV_FILE'"
    fi
  done
else
  echo "      no $ENV_FILE: nothing to change"
fi

echo "[5/5] containers"
if [[ "$ROLE" == staging ]]; then
  run "${COMPOSE[@]}" up -d
else
  run "${COMPOSE[@]}" stop
  run docker rm -f beside-stub || true
fi

echo "done: $HOST is now an isolated $ROLE copy. Check: systemctl is-enabled beside-watchdog.timer tailscaled; docker ps"
