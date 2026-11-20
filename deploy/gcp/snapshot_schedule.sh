#!/usr/bin/env bash
# Daily snapshot of the Beside VM's boot disk, kept 7 days (R2): if the VM or its zone is lost, a new VM is made from
# the newest snapshot in another zone (FD6). Run it where gcloud is signed in to the project (Cloud Shell, or the dev PC
# after `gcloud auth login`), like gcp/create_vm.sh. Re-running is safe: an existing schedule is kept as it is and a disk
# that already has it is left alone.
#   bash snapshot_schedule.sh                       # beside-vm in asia-northeast3-a
#   bash snapshot_schedule.sh asia-northeast3-b     # the VM's zone, if it was made elsewhere
#   DRY_RUN=1 bash snapshot_schedule.sh             # print the gcloud commands, change nothing
# Optional overrides (prefixed, like create_vm.sh): BESIDE_GCP_PROJECT, BESIDE_GCP_VM (beside-vm), BESIDE_GCP_DISK
#   (default: the VM's boot disk), BESIDE_SNAPSHOT_POLICY (beside-daily-snapshot), BESIDE_SNAPSHOT_START (19:00, in UTC =
#   04:00 KST), BESIDE_SNAPSHOT_DAYS (7), BESIDE_SNAPSHOT_LOCATION (the VM's region, so snapshots stay in Seoul).
# Snapshots are crash-consistent (as if the power went off). A restore therefore starts the backend from the newest H2
# backup zip in the volume's storage/backup (or the bucket) when the live H2 file does not open.
set -euo pipefail

DRY_RUN="${DRY_RUN:-0}"
ZONE="${1:-asia-northeast3-a}"
VM="${BESIDE_GCP_VM:-beside-vm}"
POLICY="${BESIDE_SNAPSHOT_POLICY:-beside-daily-snapshot}"
START="${BESIDE_SNAPSHOT_START:-19:00}"
DAYS="${BESIDE_SNAPSHOT_DAYS:-7}"

if [[ ! "$ZONE" =~ ^[a-z]+-[a-z]+[0-9]+-[a-z]$ ]]; then
  echo "not a zone name: $ZONE (expected e.g. asia-northeast3-a)" >&2
  exit 2
fi
REGION="${ZONE%-*}"
LOCATION="${BESIDE_SNAPSHOT_LOCATION:-$REGION}"
PROJECT="${BESIDE_GCP_PROJECT:-}"
if [[ -z "$PROJECT" ]] && command -v gcloud >/dev/null 2>&1; then
  PROJECT="$(gcloud config get-value project 2>/dev/null || true)"
fi
if [[ -z "$PROJECT" || "$PROJECT" == "(unset)" ]]; then
  if [[ "$DRY_RUN" != 1 ]]; then
    echo "no project selected: run  gcloud config set project <PROJECT_ID>  (or set BESIDE_GCP_PROJECT) and try again" >&2
    exit 2
  fi
  PROJECT=PROJECT_ID
fi
G=(gcloud --project="$PROJECT" --quiet)

# run: the command, or in DRY_RUN only its text. probe: a describe whose success means "already there"; DRY_RUN prints
# it and answers "not there", so every command that would change something is printed.
run() { if [[ "$DRY_RUN" == 1 ]]; then printf '+'; printf ' %q' "$@"; printf '\n'; else "$@"; fi; }
probe() { if [[ "$DRY_RUN" == 1 ]]; then printf '? %s\n' "$*"; return 1; fi; "$@" >/dev/null 2>&1; }

echo "[1/3] snapshot schedule $POLICY ($REGION): daily at $START UTC, kept $DAYS days, stored in $LOCATION"
if probe "${G[@]}" compute resource-policies describe "$POLICY" --region="$REGION"; then
  echo "      already exists: kept as it is (to change it: detach, delete and run this again)"
else
  run "${G[@]}" compute resource-policies create snapshot-schedule "$POLICY" --region="$REGION" \
    --description="Beside VM boot disk, daily, kept $DAYS days (deploy/gcp/snapshot_schedule.sh)" \
    --daily-schedule --start-time="$START" --max-retention-days="$DAYS" \
    --on-source-disk-delete=keep-auto-snapshots --storage-location="$LOCATION" --snapshot-labels=app=beside
fi

echo "[2/3] boot disk of $VM ($ZONE)"
DISK="${BESIDE_GCP_DISK:-}"
if [[ -z "$DISK" ]]; then
  if [[ "$DRY_RUN" == 1 ]]; then
    printf '? %s\n' "${G[*]} compute instances describe $VM --zone=$ZONE --format=value(disks[0].source)"
    DISK="$VM"   # create_vm.sh's boot disk takes the VM's name
  else
    SOURCE="$("${G[@]}" compute instances describe "$VM" --zone="$ZONE" --format='value(disks[0].source)')"
    DISK="${SOURCE##*/}"
  fi
fi
if [[ -z "$DISK" ]]; then
  echo "could not find the boot disk of $VM in $ZONE (set BESIDE_GCP_DISK)" >&2
  exit 1
fi
echo "      disk $DISK"

echo "[3/3] attach $POLICY to $DISK"
ATTACHED=""
if [[ "$DRY_RUN" == 1 ]]; then
  printf '? %s\n' "${G[*]} compute disks describe $DISK --zone=$ZONE --format=value(resourcePolicies)"
else
  ATTACHED="$("${G[@]}" compute disks describe "$DISK" --zone="$ZONE" --format='value(resourcePolicies)')"
fi
if [[ "$ATTACHED" == *"/resourcePolicies/$POLICY"* ]]; then
  echo "      already attached"
else
  run "${G[@]}" compute disks add-resource-policies "$DISK" --zone="$ZONE" --resource-policies="$POLICY"
fi

echo "done. The first snapshot comes at the next $START UTC. Check it the day after:"
echo "  gcloud compute snapshots list --project=$PROJECT --filter=\"sourceDisk~/disks/$DISK\$\" --sort-by=~creationTimestamp --limit=3"
