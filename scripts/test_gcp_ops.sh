#!/usr/bin/env bash
# Test of deploy/gcp/{snapshot_schedule,backup_bucket,uptime_check}.sh without GCP. A fake gcloud keeps the project's
# resources as files and logs every call. Checks: DRY_RUN=1 prints the commands and never calls gcloud; a first run makes
# the schedule, bucket, account, uptime check and alert policy with the agreed settings; a second run changes nothing it
# should keep; pause/resume switch the alert policy only.
#   bash scripts/test_gcp_ops.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GCP="$HERE/../deploy/gcp"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/state"

cat > "$WORK/bin/gcloud" <<'FAKE'
#!/usr/bin/env bash
# fake gcloud: resources are files in $FAKE/state; every call (without --project/--quiet) goes to $FAKE/calls
args=(); for a in "$@"; do [[ "$a" == --project=* || "$a" == --quiet ]] || args+=("$a"); done
set -- "${args[@]}"
printf '%s\n' "$*" >> "$FAKE/calls"
S="$FAKE/state"
has() { [[ -e "$S/$1" ]]; }
case "$1 $2 $3" in
  "config get-value project") echo beside-test ;;
  "services enable "*) ;;
  "compute resource-policies describe") has policy ;;
  "compute resource-policies create") touch "$S/policy" ;;
  "compute instances describe") echo "https://www.googleapis.com/compute/v1/projects/beside-test/zones/asia-northeast3-a/disks/beside-vm-boot" ;;
  "compute disks describe") cat "$S/attached" 2>/dev/null || true ;;
  "compute disks add-resource-policies") echo "https://www.googleapis.com/compute/v1/projects/beside-test/regions/asia-northeast3/resourcePolicies/beside-daily-snapshot" > "$S/attached" ;;
  "storage buckets describe") has bucket ;;
  "storage buckets create") touch "$S/bucket" ;;
  "storage buckets update")
    for a in "$@"; do [[ "$a" != --lifecycle-file=* ]] || cp "${a#--lifecycle-file=}" "$S/lifecycle.json"; done ;;
  "storage buckets add-iam-policy-binding") echo "bindings: [...]" ;;
  "iam service-accounts describe") has sa ;;
  "iam service-accounts create") touch "$S/sa" ;;
  "monitoring uptime list-configs") cat "$S/check" 2>/dev/null || true ;;
  "monitoring uptime create") echo beside-healthz-a1b2c3 > "$S/check"; cat "$S/check" ;;
  "monitoring policies list") cat "$S/alert" 2>/dev/null || true ;;
  "monitoring policies create")
    for a in "$@"; do [[ "$a" == --policy-from-file=* ]] && cp "${a#--policy-from-file=}" "$S/policy.json"; done
    echo 9876543210 > "$S/alert" ;;
  "monitoring policies update") ;;
  *) echo "fake gcloud: unexpected $*" >&2; exit 3 ;;
esac
FAKE
chmod +x "$WORK/bin/gcloud"
export PATH="$WORK/bin:$PATH" FAKE="$WORK"
fail() { echo "FAIL: $*"; cat "$WORK/out.txt" 2>/dev/null; exit 1; }
calls() { cat "$WORK/calls" 2>/dev/null || true; }

# ---- DRY_RUN: every changing command is printed, gcloud is never called (not even for the project with it set)
for s in snapshot_schedule backup_bucket; do
  DRY_RUN=1 BESIDE_GCP_PROJECT=beside-test bash "$GCP/$s.sh" > "$WORK/out.txt" 2>&1 || fail "$s dry run"
  cp "$WORK/out.txt" "$WORK/dry-$s.txt"
done
DRY_RUN=1 BESIDE_GCP_PROJECT=beside-test bash "$GCP/uptime_check.sh" create 1234567890 > "$WORK/out.txt" 2>&1 \
  || fail "uptime dry run"
cp "$WORK/out.txt" "$WORK/dry-uptime.txt"
DRY_RUN=1 BESIDE_GCP_PROJECT=beside-test bash "$GCP/uptime_check.sh" pause > "$WORK/out.txt" 2>&1 || fail "pause dry run"
grep -q -- "--no-enabled" "$WORK/out.txt" || fail "pause dry run printed no update"
[[ -z "$(calls)" ]] || fail "DRY_RUN called gcloud: $(calls)"
grep -q "^+ gcloud .*resource-policies create snapshot-schedule beside-daily-snapshot .*--daily-schedule --start-time=19:00 --max-retention-days=7" \
  "$WORK/dry-snapshot_schedule.txt" || fail "dry run: no snapshot schedule command"
grep -q "^+ gcloud .*disks add-resource-policies beside-vm --zone=asia-northeast3-a --resource-policies=beside-daily-snapshot" \
  "$WORK/dry-snapshot_schedule.txt" || fail "dry run: no attach command"
grep -q "^+ gcloud .*storage buckets create gs://beside-test-beside-backup --location=asia-northeast3 .*--uniform-bucket-level-access --public-access-prevention" \
  "$WORK/dry-backup_bucket.txt" || fail "dry run: no bucket command"
grep -q "roles/storage.objectCreator" "$WORK/dry-backup_bucket.txt" || fail "dry run: no role binding"
grep -q -- "--scopes=storage-rw,logging-write,monitoring-write" "$WORK/dry-backup_bucket.txt" || fail "dry run: no attach hint"
grep -q "^+ gcloud .*monitoring uptime create beside-healthz .*--path=/api/v1/healthz .*--period=1 .*--matcher-content=" \
  "$WORK/dry-uptime.txt" || fail "dry run: no uptime check command"
grep -q "^+ gcloud .*monitoring policies create --policy-from-file=" "$WORK/dry-uptime.txt" || fail "dry run: no policy command"
grep -q '"notificationChannels": \["projects/beside-test/notificationChannels/1234567890"\]' "$WORK/dry-uptime.txt" \
  || fail "dry run: channel id not expanded"

# ---- wrong input stops before any call
if bash "$GCP/snapshot_schedule.sh" seoul > "$WORK/out.txt" 2>&1; then fail "snapshot_schedule took a bad zone"; fi
if bash "$GCP/uptime_check.sh" create > "$WORK/out.txt" 2>&1; then fail "uptime_check create without a channel"; fi
grep -q "channels list" "$WORK/out.txt" || fail "no hint how to find the channel"
if bash "$GCP/uptime_check.sh" > "$WORK/out.txt" 2>&1; then fail "uptime_check without a command"; fi
grep -q "uptime_check.sh pause" "$WORK/out.txt" || fail "usage does not list pause"
# reading the local gcloud config (which project) is fine; nothing may reach the project
[[ -z "$(calls | grep -v '^config get-value project$')" ]] || fail "bad input reached gcloud: $(calls)"

# ---- first real run against the fake project
bash "$GCP/snapshot_schedule.sh" > "$WORK/out.txt" 2>&1 || fail "snapshot_schedule"
calls | grep -q "^compute disks add-resource-policies beside-vm-boot --zone=asia-northeast3-a --resource-policies=beside-daily-snapshot$" \
  || fail "the boot disk from the VM was not used: $(calls)"
bash "$GCP/backup_bucket.sh" > "$WORK/out.txt" 2>&1 || fail "backup_bucket"
python3 -I - "$WORK/state/lifecycle.json" <<'PY' || fail "lifecycle rule"
import json, sys
rules = json.load(open(sys.argv[1]))["rule"]
assert rules == [{"action": {"type": "Delete"}, "condition": {"age": 30, "matchesPrefix": ["db/"]}}], rules
PY
calls | grep -q "^storage buckets add-iam-policy-binding gs://beside-test-beside-backup --member=serviceAccount:beside-backup@beside-test.iam.gserviceaccount.com --role=roles/storage.objectCreator$" \
  || fail "role binding: $(calls)"
calls | grep -q "keys create" && fail "a key file was made"
grep -q "set-service-account beside-vm" "$WORK/out.txt" || fail "no attach commands printed"
calls | grep -q "set-service-account" && fail "the VM was changed by backup_bucket.sh"
bash "$GCP/uptime_check.sh" create 1234567890 > "$WORK/out.txt" 2>&1 || fail "uptime_check create"
python3 -I - "$WORK/state/policy.json" <<'PY' || fail "alert policy"
import json, sys
p = json.load(open(sys.argv[1]))
c = p["conditions"][0]["conditionThreshold"]
assert 'metric.label.check_id="beside-healthz-a1b2c3"' in c["filter"], c["filter"]
assert "uptime_check/check_passed" in c["filter"], c["filter"]
agg = c["aggregations"][0]
assert agg["crossSeriesReducer"] == "REDUCE_COUNT_FALSE" and agg["alignmentPeriod"] == "60s", agg
assert c["comparison"] == "COMPARISON_GT" and c["thresholdValue"] == 1 and c["duration"] == "120s", c
assert p["notificationChannels"] == ["projects/beside-test/notificationChannels/1234567890"], p
PY

# ---- second run: everything is there, nothing is made again
cp "$WORK/calls" "$WORK/calls-first"
bash "$GCP/snapshot_schedule.sh" > "$WORK/out.txt" 2>&1 || fail "snapshot_schedule again"
bash "$GCP/backup_bucket.sh" > "$WORK/out.txt" 2>&1 || fail "backup_bucket again"
bash "$GCP/uptime_check.sh" create projects/beside-test/notificationChannels/1234567890 > "$WORK/out.txt" 2>&1 \
  || fail "uptime_check create again"
again="$(tail -n +"$(($(wc -l < "$WORK/calls-first") + 1))" "$WORK/calls")"
grep -qE " create|add-resource-policies" <<<"$again" && fail "second run made something again: $again"

# ---- pause/resume switch the policy, nothing else
: > "$WORK/calls"
bash "$GCP/uptime_check.sh" pause > "$WORK/out.txt" 2>&1 || fail "pause"
bash "$GCP/uptime_check.sh" resume > "$WORK/out.txt" 2>&1 || fail "resume"
[[ "$(grep -c '^monitoring policies update' "$WORK/calls")" == 2 ]] || fail "pause/resume calls: $(calls)"
grep -q "^monitoring policies update 9876543210 --no-enabled$" "$WORK/calls" || fail "pause: $(calls)"
grep -q "^monitoring policies update 9876543210 --enabled$" "$WORK/calls" || fail "resume: $(calls)"
echo "gcp scripts: dry runs, settings, second runs and pause/resume ok"
