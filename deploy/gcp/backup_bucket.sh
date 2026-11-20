#!/usr/bin/env bash
# Backup bucket outside the VM and the VM's create-only service account (R1). Run it where gcloud is signed in to the
# project (Cloud Shell, or the dev PC), like gcp/create_vm.sh. Re-running is safe: what exists is kept, the settings below
# are applied again.
#   bash backup_bucket.sh                                  # bucket <project>-beside-backup in asia-northeast3 (Seoul)
#   BESIDE_BACKUP_BUCKET=<name> bash backup_bucket.sh      # another (globally unique) bucket name
#   DRY_RUN=1 bash backup_bucket.sh                        # print the gcloud commands, change nothing
# Optional overrides: BESIDE_GCP_PROJECT, BESIDE_GCP_REGION (asia-northeast3), BESIDE_GCP_ZONE (asia-northeast3-a),
#   BESIDE_GCP_VM (beside-vm), BESIDE_BACKUP_SA (beside-backup), BESIDE_BACKUP_DB_DAYS (30).
# What it makes:
#   - bucket: Seoul, uniform bucket-level access, public access prevention, soft delete at the default 7 days, a
#     lifecycle rule that deletes only db/ objects (hourly H2 zips) older than 30 days. files/ (photos, GLBs) is kept.
#   - service account beside-backup@: roles/storage.objectCreator on this bucket only. It can create objects but not
#     read, list, replace or delete them, so a taken-over VM cannot wipe its backups. No key file is made: the VM gets
#     its token from the metadata server, and containers are kept away from that (deploy/backup/block-metadata.sh).
# It does not touch the VM: attaching the account needs the VM stopped (about a minute; containers come back by
# themselves), so the commands are printed at the end for a moment the team agrees on.
set -euo pipefail

DRY_RUN="${DRY_RUN:-0}"
REGION="${BESIDE_GCP_REGION:-asia-northeast3}"
ZONE="${BESIDE_GCP_ZONE:-asia-northeast3-a}"
VM="${BESIDE_GCP_VM:-beside-vm}"
SA_NAME="${BESIDE_BACKUP_SA:-beside-backup}"
DB_DAYS="${BESIDE_BACKUP_DB_DAYS:-30}"
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
BUCKET="${BESIDE_BACKUP_BUCKET:-$PROJECT-beside-backup}"
if [[ ! "$BUCKET" =~ ^[a-z0-9][a-z0-9._-]{1,61}[a-z0-9]$ ]]; then
  echo "not a bucket name: $BUCKET (lower-case letters, digits, - _ . ; set BESIDE_BACKUP_BUCKET)" >&2
  exit 2
fi
SA="$SA_NAME@$PROJECT.iam.gserviceaccount.com"
G=(gcloud --project="$PROJECT" --quiet)

run() { if [[ "$DRY_RUN" == 1 ]]; then printf '+'; printf ' %q' "$@"; printf '\n'; else "$@"; fi; }
probe() { if [[ "$DRY_RUN" == 1 ]]; then printf '? %s\n' "$*"; return 1; fi; "$@" >/dev/null 2>&1; }

echo "[1/5] project $PROJECT: Cloud Storage and IAM APIs"
run "${G[@]}" services enable storage.googleapis.com iam.googleapis.com

echo "[2/5] bucket gs://$BUCKET ($REGION)"
if probe "${G[@]}" storage buckets describe "gs://$BUCKET"; then
  echo "      already exists: access settings applied again"
  run "${G[@]}" storage buckets update "gs://$BUCKET" --uniform-bucket-level-access --public-access-prevention
else
  # Soft delete is left at its default (7 days): a deleted or lifecycle-expired object can still be restored that long.
  run "${G[@]}" storage buckets create "gs://$BUCKET" --location="$REGION" --default-storage-class=STANDARD \
    --uniform-bucket-level-access --public-access-prevention
fi

echo "[3/5] lifecycle: delete db/ objects older than $DB_DAYS days (files/ is kept)"
LIFECYCLE="$(mktemp)"
trap 'rm -f "$LIFECYCLE"' EXIT
cat > "$LIFECYCLE" <<JSON
{
  "rule": [
    {
      "action": {"type": "Delete"},
      "condition": {"age": $DB_DAYS, "matchesPrefix": ["db/"]}
    }
  ]
}
JSON
[[ "$DRY_RUN" == 1 ]] && sed 's/^/      /' "$LIFECYCLE"
run "${G[@]}" storage buckets update "gs://$BUCKET" --lifecycle-file="$LIFECYCLE"

echo "[4/5] service account $SA: roles/storage.objectCreator on gs://$BUCKET only, no key"
if probe "${G[@]}" iam service-accounts describe "$SA"; then
  echo "      already exists"
else
  run "${G[@]}" iam service-accounts create "$SA_NAME" --display-name="Beside VM backup (create-only)" \
    --description="Uploads H2 backups, photos and GLBs from beside-vm to gs://$BUCKET. No key file."
fi
BIND=("${G[@]}" storage buckets add-iam-policy-binding "gs://$BUCKET" --member="serviceAccount:$SA"
  --role=roles/storage.objectCreator)
if [[ "$DRY_RUN" == 1 ]]; then
  run "${BIND[@]}"
else
  # A new account can take a few seconds to be visible to IAM: try again for up to a minute. Adding a binding that is
  # already there changes nothing. The command prints the bucket's whole policy, which is not needed here.
  for attempt in 1 2 3 4 5 6; do
    if "${BIND[@]}" >/dev/null; then
      break
    fi
    if [[ "$attempt" == 6 ]]; then
      echo "      could not bind the role; run this script again in a minute" >&2
      exit 1
    fi
    sleep 10
  done
fi

echo "[5/5] next steps (not run here)"
cat <<NEXT
  1. Attach the account to the VM. The VM must be stopped (about a minute; containers start again by themselves).
     Announce it first; jobs that are PENDING or PROCESSING at that moment end as INTERNAL_ERROR.
       gcloud compute instances stop $VM --project=$PROJECT --zone=$ZONE
       gcloud compute instances set-service-account $VM --project=$PROJECT --zone=$ZONE \\
         --service-account=$SA --scopes=storage-rw,logging-write,monitoring-write
       gcloud compute instances start $VM --project=$PROJECT --zone=$ZONE
     The account has no logging or monitoring role; those two scopes only matter if an Ops Agent is added later.
  2. On the VM (deploy/README.md '운영 대응 설치'): block containers from the metadata server first, then
       echo 'BUCKET=$BUCKET' | sudo tee /etc/beside-backup.env
     and install deploy/backup (offsite_backup.sh, beside-backup.service/.timer).
  3. Check from here (your own account can read; the VM's cannot):
       gcloud storage ls --project=$PROJECT gs://$BUCKET/db/ | tail -n 3
NEXT
