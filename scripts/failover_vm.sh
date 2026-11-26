#!/usr/bin/env bash
# PLANNED failover of the Beside main server to the other VM, and back (docs/ops/REDUNDANCY.md section 6, Phase 1,
# drills D-1 a->b and D-3 b->a). Run on the dev PC (owner gcloud login + the SSH key of the VMs).
#   bash scripts/failover_vm.sh --planned beside-vm beside-standby      # D-1: prod -> standby (zone b)
#   bash scripts/failover_vm.sh --planned beside-standby beside-vm      # D-3: back
#   DRY_RUN=1 bash scripts/failover_vm.sh --planned beside-vm beside-standby   # read-only checks + the commands
# Order (each line goes to the UTC timeline file, so the drill report can give the outage per step):
#   0 checks (no change): both VMs RUNNING, the static IP is on FROM, TO is a prepared copy (backend not running, same
#     repo commit, every compose image present, a ~/.beside-built marker for that commit: scripts/sync_standby.sh, or
#     step 6 of the failover that left it), TO logged in to the tailnet (else the GPU Pod is out of reach;
#     BESIDE_ALLOW_NO_TAILNET=1 goes on anyway), FROM answers healthz, FROM has no job waiting or running (they would
#     end as INTERNAL_ERROR; BESIDE_ALLOW_QUEUE=1 goes on; an unreadable count only warns)
#   1 FROM: maintenance flag on, backend stopped                                   <- users start seeing errors
#   2 copy FROM -> TO over SSH through this PC: the H2 DB, uploads/, results/, preprocessed/, events.jsonl and the
#     newest H2 zip of backup/ (numeric owners kept, about 80 MB on 2026-11-26) and deploy/.env (secrets, never
#     printed). RPO 0: backend stopped
#   3 TO: compose up (with the monitoring profile), wait for its own healthz
#   4 move the static IP: detach from FROM, drop TO's temporary IP if any, attach to TO
#   5 wait for the public healthz on the domain; then scripts/check_public_routes.sh
#   6 TO: role active, maintenance off, build marker removed (a later deploy there would leave it stale), watchdog +
#     off-VM backup timers on, daily snapshot schedule on its disk;
#     FROM: compose stopped, timers off, role standby, build marker = the commit its images ran (so the way back
#     passes step 0). It has no external IP now, so its Tailscale is offline
# If step 1, 2 (copy), 3 (start, healthz), 4 (IP move) or 5 (public healthz) fails: the static IP goes back to FROM and
# FROM's backend starts again (rollback, logged). A failed route check in step 5 is only logged.
# Not done here [계획]: unplanned failover (FROM dead), pulling backups on a timer (Phase 2).
# Overrides: BESIDE_GCP_PROJECT, BESIDE_STATIC_IP (beside-ip), BESIDE_REGION (asia-northeast3),
# BESIDE_DOMAIN (beside-team.duckdns.org), BESIDE_SSH_KEY (~/.ssh/beside-vm.key), BESIDE_TIMELINE_DIR (.).
set -euo pipefail

MODE="${1:-}"; FROM="${2:-}"; TO="${3:-}"
[[ "$MODE" == --planned && -n "$FROM" && -n "$TO" && "$FROM" != "$TO" ]] || { sed -n '2,8p' "$0" >&2; exit 2; }
PROJECT="${BESIDE_GCP_PROJECT:-$(gcloud config get-value project 2>/dev/null || true)}"
IP_NAME="${BESIDE_STATIC_IP:-beside-ip}"
REGION="${BESIDE_REGION:-asia-northeast3}"
DOMAIN="${BESIDE_DOMAIN:-beside-team.duckdns.org}"
KEY="${BESIDE_SSH_KEY:-$HOME/.ssh/beside-vm.key}"
VOL=/var/lib/docker/volumes/beside-storage/_data
DATA=(db uploads results preprocessed events.jsonl)
G=(gcloud --project="$PROJECT" --quiet)
DRY="${DRY_RUN:-0}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TL="${BESIDE_TIMELINE_DIR:-.}/failover-$STAMP-$FROM-to-$TO.tsv"
TRIES="${BESIDE_WAIT_TRIES:-40}"; NAP="${BESIDE_WAIT_SLEEP:-3}"   # healthz waits: 40 x 3 s = 120 s

now_ms() { local ns; ns="$(date -u +%s%N)"; printf '%s' "${ns:0:13}"; }   # %3N is not portable (uutils date)
mark() {   # mark <step> <text>: one timeline line (also on screen)
  local line
  local iso; iso="$(date -u +%FT%T.%N)"
  line="${iso:0:23}Z	$(now_ms)	$1	$2"
  printf '%s\n' "$line"
  [[ "$DRY" == 1 ]] || printf '%s\n' "$line" >> "$TL"
}
run() {    # changing command
  if [[ "$DRY" == 1 ]]; then printf '+'; printf ' %q' "$@"; printf '\n'; else "$@"; fi
}
die() { mark error "$*"; echo "STOP: $*" >&2; exit 1; }

# ---- 0 checks (read-only, also in DRY_RUN)
vm_field() { "${G[@]}" compute instances list --filter="name=$1" --format="value($2)"; }
ZFROM="$(vm_field "$FROM" zone)"; ZFROM="${ZFROM##*/}"
ZTO="$(vm_field "$TO" zone)"; ZTO="${ZTO##*/}"
[[ -n "$ZFROM" && -n "$ZTO" ]] || die "VM not found: $FROM ($ZFROM) / $TO ($ZTO)"
for v in "$FROM" "$TO"; do
  [[ "$(vm_field "$v" status)" == RUNNING ]] || die "$v is not RUNNING"
done
IFROM="$(vm_field "$FROM" 'networkInterfaces[0].networkIP')"
ITO="$(vm_field "$TO" 'networkInterfaces[0].networkIP')"
STATIC="$("${G[@]}" compute addresses describe "$IP_NAME" --region="$REGION" --format='value(address)')"
USER_OF_IP="$("${G[@]}" compute addresses describe "$IP_NAME" --region="$REGION" --format='value(users[0])')"
[[ "${USER_OF_IP##*/}" == "$FROM" ]] || die "static IP $STATIC is used by '${USER_OF_IP##*/}', not $FROM"
TO_EXT="$(vm_field "$TO" 'networkInterfaces[0].accessConfigs[0].name')"
TO_DISK="$(vm_field "$TO" 'disks[0].source')"; TO_DISK="${TO_DISK##*/}"
IP_MOVED=0

# ssh to a VM: the one holding the static IP directly, the other through it to its internal IP
HOLDER="$FROM"
SSHBASE=(ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new)
on() {   # on <vm> <command...>
  local vm="$1"; shift
  if [[ "$vm" == "$HOLDER" ]]; then
    "${SSHBASE[@]}" -o HostKeyAlias="$vm" "beside@$STATIC" "$@"
  else
    local ip="$IFROM"; [[ "$vm" == "$TO" ]] && ip="$ITO"
    "${SSHBASE[@]}" -o HostKeyAlias="$vm" \
      -o ProxyCommand="ssh -i $KEY -o IdentitiesOnly=yes -o BatchMode=yes -o HostKeyAlias=$HOLDER beside@$STATIC -W %h:%p" \
      "beside@$ip" "$@"
  fi
}
COMPOSE='docker compose -f ~/Beside/deploy/docker-compose.yml --env-file ~/Beside/deploy/.env --profile monitoring'
HEALTH="curl -sk --max-time 5 --resolve $DOMAIN:443:127.0.0.1 https://$DOMAIN/api/v1/healthz"

mark check "from=$FROM($ZFROM,$IFROM) to=$TO($ZTO,$ITO) ip=$STATIC to_ext=${TO_EXT:-none}"
[[ "$(on "$FROM" "$HEALTH" || true)" == *'"status":"ok"'* ]] || die "$FROM does not answer healthz"
TO_RUNNING="$(on "$TO" "docker ps -q --filter name=beside-backend-1")" || die "docker ps on $TO failed"
[[ -z "$TO_RUNNING" ]] || die "$TO already runs a backend"
CF="$(on "$FROM" 'git -C ~/Beside rev-parse HEAD')" || die "ssh to $FROM failed"
CT="$(on "$TO" 'git -C ~/Beside rev-parse HEAD')" || die "ssh to $TO failed"
[[ "$CF" == "$CT" ]] || die "repo commits differ: $FROM $CF, $TO $CT (bash scripts/sync_standby.sh $FROM $TO)"
mark check "ok: same commit ${CF:0:7}"
# compose up in step 3 does not build: a pulled repo with the snapshot's images would start old code, and a missing
# image would be pulled during the outage. An empty or failed listing is an error, not "nothing missing".
MISSING="$(on "$TO" "set -o pipefail; imgs=\$($COMPOSE config --images | sort -u) && [ -n \"\$imgs\" ] && for i in \$imgs; do docker image inspect \"\$i\" >/dev/null 2>&1 || echo \"\$i\"; done")" \
  || die "could not list the images of $TO (compose config failed?)"
[[ -z "$MISSING" ]] || die "images missing on $TO: $(tr '\n' ' ' <<<"$MISSING")(bash scripts/sync_standby.sh $FROM $TO)"
BUILT="$(on "$TO" 'cat ~/.beside-built 2>/dev/null || true')" || die "ssh to $TO failed"
[[ "${BUILT%% *}" == "$CF" ]] \
  || die "$TO has no build marker for ${CF:0:7} ('${BUILT:-none}'): bash scripts/sync_standby.sh $FROM $TO"
mark check "ok: images on $TO built for ${CF:0:7} at ${BUILT#* }"
TS_TO="$(on "$TO" 'tailscale status --self=true --peers=false 2>&1 | head -1' || true)"
mark check "tailnet on $TO: ${TS_TO:-?}"
if [[ ! "$TS_TO" =~ ^100\. ]]; then
  if [[ "${BESIDE_ALLOW_NO_TAILNET:-0}" == 1 ]]; then
    mark check "BESIDE_ALLOW_NO_TAILNET=1: going on; GPU jobs wait (PENDING) until $TO joins the tailnet"
  elif [[ "$DRY" == 1 ]]; then
    echo "DRY_RUN: a real run would STOP here: $TO is not logged in to the tailnet (docs/ops/RUNBOOK.md '대기 서버로 넘기기')"
    DRY_BLOCKED=1
  else
    die "$TO is not logged in to the tailnet, so it could not reach the GPU Pod (BESIDE_ALLOW_NO_TAILNET=1 to go on)"
  fi
fi
# Jobs waiting or running on FROM end as INTERNAL_ERROR when its backend stops (the target's InterruptedJobRecovery;
# 2026-11-26 drill finding 3), so a planned switch waits for an empty queue. The count is the backend's own gauge
# beside_jobs_queue{state=pending|processing}, read through the prometheus container (management port 8081). An
# unreadable count (monitoring profile down) only warns.
QUEUE="$(on "$FROM" "docker exec beside-prometheus-1 wget -qO- http://backend:8081/actuator/prometheus 2>/dev/null | awk '/^beside_jobs_queue\\{/ {s+=\$2; n++} END {if (n) printf \"%d\", s}'" || true)"
if [[ ! "$QUEUE" =~ ^[0-9]+$ ]]; then
  mark check "queue on $FROM: unknown (backend metrics not readable); jobs waiting there would end as INTERNAL_ERROR"
elif [[ "$QUEUE" == 0 ]]; then
  mark check "ok: queue on $FROM empty"
elif [[ "${BESIDE_ALLOW_QUEUE:-0}" == 1 ]]; then
  mark check "BESIDE_ALLOW_QUEUE=1: going on with $QUEUE job(s) on $FROM; they end as INTERNAL_ERROR (retry in the app)"
elif [[ "$DRY" == 1 ]]; then
  echo "DRY_RUN: a real run would STOP here: $QUEUE job(s) waiting or running on $FROM (wait, or BESIDE_ALLOW_QUEUE=1)"
  DRY_BLOCKED=1
else
  die "$QUEUE job(s) waiting or running on $FROM would end as INTERNAL_ERROR: wait until the queue is empty (BESIDE_ALLOW_QUEUE=1 to go on)"
fi
if [[ "$DRY" == 1 && "${DRY_BLOCKED:-0}" == 1 ]]; then
  echo "DRY_RUN: the other checks passed. Once that is fixed, the changing steps would be:"
elif [[ "$DRY" == 1 ]]; then
  echo "DRY_RUN: checks passed. The changing steps would be:"
fi

rollback() {
  mark rollback "putting $IP_NAME back on $FROM (if it moved), stopping $TO, starting the backend of $FROM"
  if [[ "$IP_MOVED" == 1 ]]; then
    run "${G[@]}" compute instances delete-access-config "$TO" --zone="$ZTO" --access-config-name=external-nat || true
  fi
  if [[ -z "$(vm_field "$FROM" 'networkInterfaces[0].accessConfigs[0].natIP')" ]]; then
    run "${G[@]}" compute instances add-access-config "$FROM" --zone="$ZFROM" --access-config-name=external-nat \
      --address="$STATIC" || true
  fi
  HOLDER="$FROM"
  run on "$TO" "$COMPOSE stop" || true
  run on "$FROM" "$COMPOSE up -d backend && sudo rm -f /etc/beside-maintenance" || true
  mark rollback "done; check https://$DOMAIN by hand"
  exit 1
}

# ---- 1 stop the source
mark 1-stop "maintenance on, backend stop on $FROM"
run on "$FROM" "sudo touch /etc/beside-maintenance && $COMPOSE stop backend" || rollback
mark 1-stop "backend stopped"

# ---- 2 copy data and .env
mark 2-copy "start"
# The newest H2 zip of backup/ (the backend's 5-minute copy) goes along, not the whole folder: without it the target's
# first off-VM backup runs judge its snapshot-time zips as stale and the watchdog reports the backup as stopped
# (2026-11-26 drill finding 1). Zip names sort by time (beside-YYYYMMDD-HHMMSS-NNN.zip).
COPY_OUT="sudo sh -c 'cd $VOL && tar --numeric-owner -cf - ${DATA[*]} \$(ls -1 backup/beside-*.zip 2>/dev/null | sort | tail -n 1)'"
if [[ "$DRY" == 1 ]]; then
  echo "+ on $FROM $COPY_OUT | on $TO sudo tar -C $VOL --numeric-owner -xf -"
  echo "+ on $FROM cat ~/Beside/deploy/.env | on $TO (keep .env.standby, write .env 600)"
else
  on "$TO" "sudo sh -c 'cd $VOL && rm -rf ${DATA[*]}'" || rollback
  on "$FROM" "$COPY_OUT" | on "$TO" "sudo tar -C $VOL --numeric-owner -xf -" \
    || rollback
  on "$FROM" 'cat ~/Beside/deploy/.env' | on "$TO" \
    'cp -p ~/Beside/deploy/.env ~/Beside/deploy/.env.standby 2>/dev/null; umask 077; cat > ~/Beside/deploy/.env.new && mv -f ~/Beside/deploy/.env.new ~/Beside/deploy/.env' \
    || rollback
fi
mark 2-copy "done"

# ---- 3 start the target
mark 3-start "compose up on $TO"
run on "$TO" "$COMPOSE up -d" || rollback
if [[ "$DRY" != 1 ]]; then
  ok=0
  for _ in $(seq 1 "$TRIES"); do
    if [[ "$(on "$TO" "$HEALTH" || true)" == *'"status":"ok"'* ]]; then ok=1; break; fi
    sleep "$NAP"
  done
  [[ "$ok" == 1 ]] || { mark 3-start "no healthz on $TO in 120 s"; rollback; }
fi
mark 3-start "healthz ok on $TO"

# ---- 4 move the static IP
mark 4-ip "detach from $FROM"
run "${G[@]}" compute instances delete-access-config "$FROM" --zone="$ZFROM" --access-config-name=external-nat || rollback
if [[ -n "$TO_EXT" ]]; then
  run "${G[@]}" compute instances delete-access-config "$TO" --zone="$ZTO" --access-config-name="$TO_EXT" || rollback
fi
mark 4-ip "attach to $TO"
IP_MOVED=1
run "${G[@]}" compute instances add-access-config "$TO" --zone="$ZTO" --access-config-name=external-nat \
  --address="$STATIC" || rollback
HOLDER="$TO"
mark 4-ip "attached"

# ---- 5 public check
if [[ "$DRY" != 1 ]]; then
  ok=0
  for _ in $(seq 1 "$TRIES"); do
    if [[ "$(curl -s --max-time 5 "https://$DOMAIN/api/v1/healthz" || true)" == *'"status":"ok"'* ]]; then ok=1; break; fi
    sleep "$NAP"
  done
  [[ "$ok" == 1 ]] || { mark 5-public "no public healthz in 120 s"; rollback; }
fi
mark 5-public "first public 200"
if [[ "$DRY" != 1 ]]; then
  routes="$(bash "$(dirname "$0")/check_public_routes.sh" "https://$DOMAIN" | tail -1 || true)"
  mark 5-public "routes: $routes"   # a failed route check is logged, the roles step still runs
fi

# ---- 6 roles
mark 6-roles "start"
run on "$TO" "printf 'active\n' | sudo tee /etc/beside-role >/dev/null && sudo rm -f /etc/beside-maintenance && rm -f ~/.beside-built && sudo systemctl enable --now beside-watchdog.timer beside-backup.timer"
run "${G[@]}" compute disks add-resource-policies "$TO_DISK" --zone="$ZTO" --resource-policies=beside-daily-snapshot || true
run on "$FROM" "$COMPOSE stop; sudo systemctl disable --now beside-watchdog.timer beside-backup.timer; printf 'standby\n' | sudo tee /etc/beside-role >/dev/null; printf '%s %s\n' $CF \$(date -u +%FT%TZ) > ~/.beside-built"
mark 6-roles "done: $TO is active with $STATIC, $FROM is a stopped standby"
[[ "$DRY" == 1 ]] || echo "timeline: $TL"
echo "SSH: the alias beside-gcp now reaches $TO; use  ssh -o HostKeyAlias=$TO beside-gcp  (its host key differs from $FROM)."
