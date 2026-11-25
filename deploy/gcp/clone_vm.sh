#!/usr/bin/env bash
# Copy the Beside main server (beside-vm) from a disk snapshot, either as
#   - staging: a server you may break (load tests to failure, memory/lag drills) without taking prod down, or
#   - standby: the warm standby for a planned failover (docs/ops/REDUNDANCY.md section 6, Phase 1).
# The copy boots ISOLATED, as in drill FD6 (docs/ops/2026-11-20_failure-drills.md): no external IP, no service account,
# no API scopes. So its copies of prod's Tailscale node key, Discord webhooks and backup timers cannot reach anything
# until deploy/standby/clone_init.sh has wiped or switched them off (00-constraints.md 18/19). Reach it with SSH through
# the prod VM (internal IP). It gets prod's SSH keys (instance metadata) so the same key works.
#   bash deploy/gcp/clone_vm.sh create staging [latest|now|<snapshot>]   # default latest (the daily 19:14 UTC one)
#   bash deploy/gcp/clone_vm.sh create standby [latest|now|<snapshot>]
#   bash deploy/gcp/clone_vm.sh status staging|standby
#   bash deploy/gcp/clone_vm.sh ip standby          # temporary external IP, ONLY after clone_init.sh (Tailscale login)
#   bash deploy/gcp/clone_vm.sh delete staging|standby --yes
#   DRY_RUN=1 bash deploy/gcp/clone_vm.sh ...                             # print the changing commands only
# 'now' takes a crash-consistent snapshot of the running prod disk first (no downtime), named beside-vm-<role>-<UTC time>.
# Overrides: BESIDE_GCP_PROJECT, BESIDE_GCP_VM (beside-vm), BESIDE_GCP_ZONE (asia-northeast3-a, where prod runs),
# BESIDE_GCP_CLONE_ZONE (asia-northeast3-b), BESIDE_GCP_MACHINE (e2-standard-2).
# Costs (third-party prices, REDUNDANCY 6.4): about $0.086/h for the VM + about $0.22/day for the 50 GB disk.
# Delete staging the same day; keep standby until the cleanup at the end of the term (deploy/README '비용과 끝내기').
set -euo pipefail

CMD="${1:-}"
ROLE="${2:-}"
PROJECT="${BESIDE_GCP_PROJECT:-$(gcloud config get-value project 2>/dev/null || true)}"
SRC_VM="${BESIDE_GCP_VM:-beside-vm}"
SRC_ZONE="${BESIDE_GCP_ZONE:-asia-northeast3-a}"
ZONE="${BESIDE_GCP_CLONE_ZONE:-asia-northeast3-b}"
MACHINE="${BESIDE_GCP_MACHINE:-e2-standard-2}"

usage() {
  sed -n '2,17p' "$0" >&2
  exit 2
}
[[ "$CMD" =~ ^(create|status|delete|ip)$ ]] || usage
[[ "$ROLE" =~ ^(staging|standby)$ ]] || { echo "role must be staging or standby, got '$ROLE'" >&2; usage; }
if [[ -z "$PROJECT" || "$PROJECT" == "(unset)" ]]; then
  echo "no project selected: run  gcloud config set project <PROJECT_ID>  and try again" >&2
  exit 2
fi
VM="beside-$ROLE"
REGION="${ZONE%-*}"
G=(gcloud --project="$PROJECT" --quiet)

run() {   # changing command: printed with DRY_RUN=1, run otherwise
  if [[ "${DRY_RUN:-0}" == 1 ]]; then
    printf '+'; printf ' %q' "$@"; printf '\n'
  else
    "$@"
  fi
}

case "$CMD" in
status)
  "${G[@]}" compute instances describe "$VM" --zone="$ZONE" \
    --format='table(name,status,networkInterfaces[0].networkIP:label=INTERNAL_IP,networkInterfaces[0].accessConfigs[0].natIP:label=EXTERNAL_IP,labels.role,creationTimestamp)'
  exit 0
  ;;
ip)
  # A temporary (ephemeral) external IP so the standby can log in to Tailscale as its own node and reach the GPU Pod.
  # Only after deploy/standby/clone_init.sh wiped prod's node state. scripts/failover_vm.sh drops it before the
  # static IP is attached. Staging never gets one (it stays internal).
  [[ "$ROLE" == standby ]] || { echo "only the standby gets an external IP" >&2; exit 2; }
  run "${G[@]}" compute instances add-access-config "$VM" --zone="$ZONE" --access-config-name=external-nat
  exit 0
  ;;
delete)
  if [[ "${3:-}" != "--yes" ]]; then
    echo "delete removes $VM and its boot disk for good. Run again with --yes." >&2
    exit 2
  fi
  if [[ "$ROLE" == standby ]]; then
    run "${G[@]}" compute instances update "$VM" --zone="$ZONE" --no-deletion-protection
  fi
  run "${G[@]}" compute instances delete "$VM" --zone="$ZONE" --delete-disks=all
  exit 0
  ;;
esac

# ---- create
if [[ "${DRY_RUN:-0}" != 1 ]] && "${G[@]}" compute instances describe "$VM" --zone="$ZONE" >/dev/null 2>&1; then
  echo "$VM already exists in $ZONE: delete it first (clone_vm.sh delete $ROLE --yes) or use it as it is" >&2
  exit 1
fi

SNAP="${3:-latest}"
if [[ "$SNAP" == latest ]]; then
  SNAP="$("${G[@]}" compute snapshots list --filter="sourceDisk~/disks/${SRC_VM}" \
    --sort-by=~creationTimestamp --limit=1 --format='value(name)')"
  [[ -n "$SNAP" ]] || { echo "no snapshot of $SRC_VM found (gcloud compute snapshots list)" >&2; exit 1; }
  echo "[1/3] snapshot: newest of $SRC_VM = $SNAP"
elif [[ "$SNAP" == now ]]; then
  DISK="$("${G[@]}" compute instances describe "$SRC_VM" --zone="$SRC_ZONE" --format='value(disks[0].source)')"
  DISK="${DISK##*/}"
  SNAP="${SRC_VM}-${ROLE}-$(date -u +%Y%m%d-%H%M%S)"
  echo "[1/3] snapshot: taking $SNAP of $DISK now (prod keeps running)"
  run "${G[@]}" compute disks snapshot "$DISK" --zone="$SRC_ZONE" --snapshot-names="$SNAP" \
    --storage-location="$REGION"
else
  echo "[1/3] snapshot: $SNAP"
fi

# prod's SSH keys, so the same key reaches the copy (without them the guest agent may drop the keys it manages)
KEYS="$(mktemp)"
trap 'rm -f "$KEYS"' EXIT
"${G[@]}" compute instances describe "$SRC_VM" --zone="$SRC_ZONE" \
  --format='value(metadata.items.ssh-keys)' > "$KEYS" || true
[[ -s "$KEYS" ]] || { echo "could not read the ssh-keys metadata of $SRC_VM" >&2; exit 1; }

EXTRA=()
TAGS="beside-$ROLE"
if [[ "$ROLE" == standby ]]; then
  # beside-web opens 80/443 once the static IP moves here in a failover; deletion protection like prod
  TAGS="$TAGS,beside-web"
  EXTRA+=(--deletion-protection)
fi
echo "[2/3] VM $VM ($ZONE, $MACHINE) from $SNAP: no external IP, no service account"
run "${G[@]}" compute instances create "$VM" --zone="$ZONE" --machine-type="$MACHINE" \
  --create-disk="boot=yes,auto-delete=yes,name=${VM}-boot,source-snapshot=${SNAP},type=pd-balanced,size=50GB" \
  --no-address --no-service-account --no-scopes \
  --tags="$TAGS" --labels="app=beside,role=$ROLE" \
  --metadata-from-file=ssh-keys="$KEYS" "${EXTRA[@]}"

IP="(DRY_RUN)"
if [[ "${DRY_RUN:-0}" != 1 ]]; then
  IP="$("${G[@]}" compute instances describe "$VM" --zone="$ZONE" --format='value(networkInterfaces[0].networkIP)')"
fi
echo "[3/3] next, BEFORE it gets any external IP or service account: isolate it"
echo "      ssh -J beside-gcp -o HostKeyAlias=$VM beside@$IP 'bash -s -- $ROLE' < deploy/standby/clone_init.sh"
