#!/usr/bin/env bash
# Bring the standby VM to exactly the commit the main server runs, with every image built or pulled, before a planned
# failover (docs/ops/REDUNDANCY.md section 6, 1-5; docs/ops/RUNBOOK.md '대기 서버로 넘기기'). Nothing is started on the
# standby and nothing changes on the main server. Run on the dev PC (owner gcloud login + the SSH key of the VMs).
#   bash scripts/sync_standby.sh                                  # main beside-vm -> standby beside-standby
#   bash scripts/sync_standby.sh beside-vm beside-standby
#   DRY_RUN=1 bash scripts/sync_standby.sh                        # checks + the commands (only git fetch runs)
# Why: scripts/failover_vm.sh compares the two repo commits, and its compose up does not build. A standby whose repo
# was only pulled would start the backend image of its snapshot, and pull missing images (tempo) during the outage.
# Order:
#   0 checks (no change): both VMs RUNNING, the static IP on MAIN, the standby has an external IP (egress for git,
#     images and models: deploy/gcp/clone_vm.sh ip standby; failover_vm.sh drops it again), role file 'standby', no
#     backend running there, the standby commit is an ancestor of the main server's
#   1 standby: git fetch; build marker set to 'pending' (failover_vm.sh refuses it until step 5); fast-forward to the
#     main server's commit (never to a newer main)
#   2 standby: preprocessing models (scripts/fetch_models.sh, only what is missing or wrong), in the folder the main
#     server's deploy/.env names (failover copies that .env)
#   3 standby: compose --profile monitoring build, then pull the images it does not build. No start
#   4 standby: every image of the compose file is present, still at that commit, no backend running
#   5 standby: ~/.beside-built = "<commit> <UTC time>"; Tailscale state printed (login is a person's step)
# Overrides: BESIDE_GCP_PROJECT, BESIDE_STATIC_IP (beside-ip), BESIDE_REGION (asia-northeast3),
# BESIDE_SSH_KEY (~/.ssh/beside-vm.key).
set -euo pipefail

MAIN="${1:-beside-vm}"; SB="${2:-beside-standby}"
[[ "$MAIN" != "$SB" ]] || { sed -n '2,7p' "$0" >&2; exit 2; }
PROJECT="${BESIDE_GCP_PROJECT:-$(gcloud config get-value project 2>/dev/null || true)}"
IP_NAME="${BESIDE_STATIC_IP:-beside-ip}"
REGION="${BESIDE_REGION:-asia-northeast3}"
KEY="${BESIDE_SSH_KEY:-$HOME/.ssh/beside-vm.key}"
G=(gcloud --project="$PROJECT" --quiet)
DRY="${DRY_RUN:-0}"

say() { printf '%s  %s\n' "$(date -u +%T)" "$*"; }
die() { echo "STOP: $*" >&2; exit 1; }
run() {   # changing command; DRY_RUN prints it (a remote one as the shell line the VM would run)
  if [[ "$DRY" != 1 ]]; then "$@"
  elif [[ "$1" == on ]]; then printf '+ on %s: %s\n' "$2" "${*:3}"
  else printf '+'; printf ' %q' "$@"; printf '\n'; fi
}

# ---- 0 checks (read-only, also in DRY_RUN)
vm_field() { "${G[@]}" compute instances list --filter="name=$1" --format="value($2)"; }
for v in "$MAIN" "$SB"; do
  [[ "$(vm_field "$v" status)" == RUNNING ]] || die "$v is not RUNNING"
done
ISB="$(vm_field "$SB" 'networkInterfaces[0].networkIP')"
STATIC="$("${G[@]}" compute addresses describe "$IP_NAME" --region="$REGION" --format='value(address)')"
USER_OF_IP="$("${G[@]}" compute addresses describe "$IP_NAME" --region="$REGION" --format='value(users[0])')"
[[ "${USER_OF_IP##*/}" == "$MAIN" ]] || die "static IP $STATIC is used by '${USER_OF_IP##*/}', not $MAIN"
SB_EXT="$(vm_field "$SB" 'networkInterfaces[0].accessConfigs[0].natIP')"
[[ -n "$SB_EXT" ]] || die "$SB has no external IP, so no egress for git/images: attach a temporary one (for beside-standby: bash deploy/gcp/clone_vm.sh ip standby)"

SSHBASE=(ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new)
on() {   # on <vm> <command...>: the main server directly on the static IP, the standby through it
  local vm="$1"; shift
  if [[ "$vm" == "$MAIN" ]]; then
    "${SSHBASE[@]}" -o HostKeyAlias="$MAIN" "beside@$STATIC" "$@"
  else
    "${SSHBASE[@]}" -o HostKeyAlias="$SB" \
      -o ProxyCommand="ssh -i $KEY -o IdentitiesOnly=yes -o BatchMode=yes -o HostKeyAlias=$MAIN beside@$STATIC -W %h:%p" \
      "beside@$ISB" "$@"
  fi
}
COMPOSE='docker compose -f ~/Beside/deploy/docker-compose.yml --env-file ~/Beside/deploy/.env --profile monitoring'
# every image compose would start; an empty or failed listing is an error, not "nothing missing"
IMAGES_MISSING="set -o pipefail; imgs=\$($COMPOSE config --images | sort -u) && [ -n \"\$imgs\" ] && for i in \$imgs; do docker image inspect \"\$i\" >/dev/null 2>&1 || echo \"\$i\"; done"

CM="$(on "$MAIN" 'git -C ~/Beside rev-parse HEAD')" || die "ssh to $MAIN failed"
[[ "$CM" =~ ^[0-9a-f]{40}$ ]] || die "no commit from $MAIN: '$CM'"
ROLE="$(on "$SB" 'cat /etc/beside-role 2>/dev/null || true')" || die "ssh to $SB failed"
[[ "$ROLE" == standby ]] || die "$SB role is '${ROLE:-none}', not standby (it may be the active server after a failover)"
RUNNING="$(on "$SB" 'docker ps -q --filter name=beside-backend-1')" || die "docker ps on $SB failed"
[[ -z "$RUNNING" ]] || die "$SB runs a backend: not a standby now"
CS="$(on "$SB" 'git -C ~/Beside rev-parse HEAD')" || die "ssh to $SB failed"
say "check: $MAIN ${CM:0:7}, $SB ${CS:0:7} (external IP on $SB for egress)"

# ---- 1 code (from here the marker says 'pending' until step 5, so a half-done sync is refused by failover_vm.sh)
on "$SB" "git -C ~/Beside fetch -q origin" || die "git fetch on $SB failed (egress?)"
on "$SB" "git -C ~/Beside merge-base --is-ancestor HEAD $CM" \
  || die "$SB ${CS:0:7} is not an ancestor of $MAIN ${CM:0:7}: look at both by hand"
run on "$SB" "printf 'pending %s\n' \$(date -u +%FT%TZ) > ~/.beside-built"
if [[ "$CS" == "$CM" ]]; then say "1 code: already at ${CM:0:7}"; else
  run on "$SB" "git -C ~/Beside merge --ff-only -q $CM" || die "fast-forward on $SB failed (the marker stays 'pending')"
  say "1 code: ${CS:0:7} -> ${CM:0:7}"
fi

# ---- 2 models (the server refuses to start without them when preprocessing is on). The main server's .env decides
# where: failover copies it to the standby.
MODELS="$(on "$MAIN" "sed -n 's/^BESIDE_MODELS_DIR=//p' ~/Beside/deploy/.env | tail -n1 | tr -d '\r'")" || die "ssh to $MAIN failed"
MODELS="${MODELS%% #*}"; MODELS="${MODELS//[\"\']/}"
MODELS="${MODELS#"${MODELS%%[![:space:]]*}"}"; MODELS="${MODELS%"${MODELS##*[![:space:]]}"}"
MODELS="${MODELS:-backend/models}"
run on "$SB" "cd ~/Beside && bash scripts/fetch_models.sh $(printf '%q' "$MODELS") </dev/null" \
  || die "models on $SB failed (the marker stays 'pending')"
say "2 models: $MODELS"

# ---- 3 images, without starting anything
run on "$SB" "$COMPOSE build -q </dev/null && $COMPOSE pull -q --ignore-buildable </dev/null" \
  || die "build or pull on $SB failed (the marker stays 'pending', so failover_vm.sh refuses $SB)"

if [[ "$DRY" == 1 ]]; then
  say "DRY_RUN: steps 3-5 printed only; nothing built, checked or marked"
  exit 0
fi
say "3 images: built and pulled"

# ---- 4 verify
MISSING="$(on "$SB" "$IMAGES_MISSING")" || die "could not list the images of $SB (compose config failed?)"
[[ -z "$MISSING" ]] || die "images still missing on $SB: $(tr '\n' ' ' <<<"$MISSING")"
[[ "$(on "$SB" 'git -C ~/Beside rev-parse HEAD')" == "$CM" ]] || die "$SB is not at ${CM:0:7} after the sync"
RUNNING="$(on "$SB" 'docker ps -q --filter name=beside-backend-1')" || die "docker ps on $SB failed"
[[ -z "$RUNNING" ]] || die "a backend started on $SB"
say "4 ok: $SB at ${CM:0:7}, all images present, nothing started"

# ---- 5 build marker
on "$SB" "printf '%s %s\n' $CM \$(date -u +%FT%TZ) > ~/.beside-built"
say "5 marker: ~/.beside-built = ${CM:0:7}"
say "tailnet on $SB: $(on "$SB" 'tailscale status --self=true --peers=false 2>&1 | head -1' || echo '?')"
echo "next: DRY_RUN=1 bash scripts/failover_vm.sh --planned $MAIN $SB"
