#!/usr/bin/env bash
# Test of deploy/backup/offsite_backup.sh without a VM or GCP: fake docker (volume path), fake curl (metadata server and
# a bucket in a folder that answers like the JSON API: 200 created, 412 when the name is taken, 503 on demand).
# Checks: zips go oldest first and only once, an unreadable zip is reported and skipped, a failed upload stops the db
# order and is sent on the next run, photos/GLBs go once a day (or with FORCE_FILES=1), a file deleted after the run
# listed it is skipped without failing, only a good run writes last.ok, a lost state never overwrites what the bucket
# has, a VM without the write scope uploads nothing, and the token is never on a command line or printed.
#   bash scripts/test_offsite_backup.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../deploy/backup/offsite_backup.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
TOKEN_VALUE="tok-SECRET-0123456789"

mkdir -p "$WORK/bin" "$WORK/meta" "$WORK/bucket" "$WORK/vol/backup" "$WORK/vol/uploads" "$WORK/vol/results"
cat > "$WORK/bin/docker" <<'FAKE'
#!/usr/bin/env bash
# fake docker: "volume inspect -f {{.Mountpoint}} beside-storage" -> the test's volume folder
case "$*" in
  "volume inspect -f {{.Mountpoint}} beside-storage") echo "$FAKE/vol" ;;
  *) echo "fake docker: unexpected $*" >&2; exit 1 ;;
esac
FAKE
cat > "$WORK/bin/curl" <<'FAKE'
#!/usr/bin/env bash
# fake curl: the GCE metadata server and the Cloud Storage JSON API upload, with a folder as the bucket
printf '%s\n' "$*" >> "$FAKE/argv"
out=/dev/null; name=""; data=""; url=""; meta=0; config=""; query=""
while (($#)); do
  case "$1" in
    -o) out="$2"; shift ;;
    -K) [[ "$2" == - ]] && config="$(cat)"; shift ;;
    -w | --max-time | --retry | --retry-delay) shift ;;
    -H) [[ "$2" == "Metadata-Flavor: Google" ]] && meta=1; shift ;;
    --url-query) query+=" $2"; [[ "$2" == name=* ]] && name="${2#name=}"; shift ;;
    --data-binary) data="${2#@}"; shift ;;
    http*) url="$1" ;;
  esac
  shift
done
if ((meta)); then
  [[ -f "$FAKE/meta/${url##*/}" ]] || exit 22
  cat "$FAKE/meta/${url##*/}"
  exit 0
fi
[[ -f "$data" ]] || { printf '000'; exit 26; }   # curl: (26) cannot read the file to send
answer() { printf '%s' "$2" > "$out"; printf '%s' "$1"; }
[[ "$url" == https://gcs.test/upload/storage/v1/b/beside-test-backup/o ]] || { answer 404 '{"error":{"message":"no such bucket"}}'; exit 0; }
grep -qxF "header = \"Authorization: Bearer $(cat "$FAKE/token-value")\"" <<<"$config" \
  || { answer 401 '{"error":{"message":"Invalid Credentials"}}'; exit 0; }
# a create-only upload: simple media upload with the precondition "no live object of this name"
[[ " $query " == *" uploadType=media "* && " $query " == *" ifGenerationMatch=0 "* ]] \
  || { answer 400 '{"error":{"message":"not a create-only media upload"}}'; exit 0; }
if grep -qxF "$name" "$FAKE/fail" 2>/dev/null; then answer 503 '{"error":{"message":"Backend Error"}}'; exit 0; fi
if [[ -e "$FAKE/bucket/$name" ]]; then
  answer 412 '{"error":{"message":"At least one of the pre-conditions you specified did not hold."}}'
  exit 0
fi
mkdir -p "$(dirname "$FAKE/bucket/$name")"
cp "$data" "$FAKE/bucket/$name"
echo "$name" >> "$FAKE/writes"
# delete-after: "<object> <path in the volume>" lines; once <object> is written, that file is gone (a job deleted or a
# zip pruned while the run is still going, after it made its list)
if [[ -f "$FAKE/delete-after" ]]; then
  while read -r trigger victim; do [[ "$trigger" != "$name" ]] || rm -f "$FAKE/vol/$victim"; done < "$FAKE/delete-after"
fi
answer 200 "{\"name\":\"$name\"}"
FAKE
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH" FAKE="$WORK"
printf '%s' "$TOKEN_VALUE" > "$WORK/token-value"
echo "beside-backup@beside-test.iam.gserviceaccount.com" > "$WORK/meta/email"
echo "https://www.googleapis.com/auth/devstorage.read_write" > "$WORK/meta/scopes"
printf '{"access_token":"%s","expires_in":3599,"token_type":"Bearer"}' "$TOKEN_VALUE" > "$WORK/meta/token"
echo "BUCKET=beside-test-backup" > "$WORK/backup.env"

zip_at() {   # zip_at <name> <minutes ago>: a real zip (H2's BACKUP TO writes one) with an old enough mtime
  python3 -I -c 'import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], "w"); z.writestr("beside-real.mv.db", "h2"); z.close()' \
    "$WORK/vol/backup/$1"
  touch -d "$2 minutes ago" "$WORK/vol/backup/$1"
}
file_at() {  # file_at <relative path> <minutes ago>
  mkdir -p "$(dirname "$WORK/vol/$1")"
  echo "$1" > "$WORK/vol/$1"
  touch -d "$2 minutes ago" "$WORK/vol/$1"
}
run() {
  local rc=0
  env STATE_DIRECTORY="$WORK/state" BESIDE_BACKUP_CONFIG="$WORK/backup.env" METADATA_URL=http://meta.test \
    GCS_UPLOAD_URL=https://gcs.test/upload/storage/v1 "$@" bash "$SCRIPT" > "$WORK/out.txt" 2>&1 || rc=$?
  cat "$WORK/out.txt" >> "$WORK/all.txt"
  return "$rc"
}
fail() { echo "FAIL: $*"; cat "$WORK/out.txt"; exit 1; }
writes() { [[ -f "$WORK/writes" ]] && wc -l < "$WORK/writes" | tr -d ' ' || echo 0; }
in_bucket() { [[ -f "$WORK/bucket/$1" ]]; }

# no bucket configured: refuses before touching anything
if env STATE_DIRECTORY="$WORK/state" BESIDE_BACKUP_CONFIG="$WORK/none.env" bash "$SCRIPT" > "$WORK/out.txt" 2>&1; then
  fail "ran without BUCKET"
fi
grep -q "BUCKET is not set" "$WORK/out.txt" || fail "no BUCKET hint"

zip_at beside-20261120-090500-001.zip 120
zip_at beside-20261120-100500-001.zip 60
printf 'not a zip' > "$WORK/vol/backup/beside-20261120-103000-000.zip"   # H2 died while writing it
touch -d "30 minutes ago" "$WORK/vol/backup/beside-20261120-103000-000.zip"
zip_at beside-20261120-110500-001.zip 15
zip_at beside-20261120-112000-001.zip 0          # still being written: waits for the next run
file_at "uploads/0a1b_dog 1.jpg" 300
file_at results/0a1b/base.glb 200
file_at results/9f9f/base.glb 0                   # GLB being copied right now

# the VM still has the read-only default service account: nothing is sent, the error says how to fix it
echo "https://www.googleapis.com/auth/devstorage.read_only" > "$WORK/meta/scopes"
if run; then fail "uploaded with a read-only scope"; fi
grep -q "no Cloud Storage write scope" "$WORK/out.txt" || fail "no scope hint"
[[ "$(writes)" == 0 ]] || fail "wrote with a read-only scope"
echo "https://www.googleapis.com/auth/devstorage.read_write" > "$WORK/meta/scopes"

# first run: three readable zips in order (the broken one is reported, skipped and makes the run fail), and the files
if run; then fail "the unreadable zip did not fail the run"; fi
grep -q "not a readable zip, skipped: beside-20261120-103000-000.zip" "$WORK/out.txt" || fail "broken zip not reported"
[[ "$(sed -n '1,3p' "$WORK/writes" | xargs)" == "db/beside-20261120-090500-001.zip db/beside-20261120-100500-001.zip db/beside-20261120-110500-001.zip" ]] \
  || fail "zips not sent oldest first: $(xargs < "$WORK/writes")"
in_bucket "files/uploads/0a1b_dog 1.jpg" || fail "photo not sent"
in_bucket files/results/0a1b/base.glb || fail "GLB not sent"
in_bucket db/beside-20261120-112000-001.zip && fail "a zip younger than MIN_AGE_MIN was sent"
in_bucket files/results/9f9f/base.glb && fail "a file younger than MIN_AGE_MIN was sent"
in_bucket db/beside-20261120-103000-000.zip && fail "the unreadable zip was sent"
[[ "$(writes)" == 5 ]] || fail "expected 5 objects, got $(writes)"
[[ ! -e "$WORK/state/last.ok" ]] || fail "a failed run wrote last.ok"

# the same hour again: nothing new, success; last.ok (read by the VM watchdog) holds the time of this good run
before_run="$(date +%s)"
run || fail "second run"
[[ "$(writes)" == 5 ]] || fail "second run wrote again"
ok="$(cat "$WORK/state/last.ok" 2>/dev/null || true)"
[[ "$ok" =~ ^[0-9]+$ ]] && ((ok >= before_run && ok <= $(date +%s))) || fail "last.ok after a good run: '$ok'"

# next hour: the waiting zip and a new one go; the day's files are done, so the new photo waits for tomorrow
touch -d "70 minutes ago" "$WORK/vol/backup/beside-20261120-112000-001.zip"
zip_at beside-20261120-120500-001.zip 15
file_at uploads/77aa_cat.png 30
run || fail "third run"
in_bucket db/beside-20261120-112000-001.zip && in_bucket db/beside-20261120-120500-001.zip || fail "new zips not sent"
in_bucket files/uploads/77aa_cat.png && fail "files sent twice the same day"
run FORCE_FILES=1 || fail "FORCE_FILES run"
in_bucket files/uploads/77aa_cat.png || fail "FORCE_FILES did not send the new photo"
in_bucket files/results/9f9f/base.glb && fail "FORCE_FILES sent a file younger than MIN_AGE_MIN"
[[ "$(writes)" == 8 ]] || fail "expected 8 objects, got $(writes)"

# a failed upload stops the zips there and is sent by the next run
zip_at beside-20261120-130500-001.zip 15
zip_at beside-20261120-140500-001.zip 10
echo db/beside-20261120-130500-001.zip > "$WORK/fail"
echo 1 > "$WORK/state/last.ok"   # an old good run
if run; then fail "a 503 did not fail the run"; fi
grep -q "upload failed (HTTP 503): db/beside-20261120-130500-001.zip: Backend Error" "$WORK/out.txt" || fail "503 not reported"
in_bucket db/beside-20261120-140500-001.zip && fail "a later zip was sent past the failed one"
[[ "$(cat "$WORK/state/last.ok")" == 1 ]] || fail "a failed run moved last.ok"
rm "$WORK/fail"
run || fail "run after the 503"
in_bucket db/beside-20261120-130500-001.zip && in_bucket db/beside-20261120-140500-001.zip || fail "zips not sent after the 503"
(($(cat "$WORK/state/last.ok") > 1)) || fail "the good run after the 503 did not move last.ok"

# a zip or a job's file that vanishes after the run listed it is skipped: no write, no failure, the rest still goes
zip_at beside-20261120-150500-001.zip 20
zip_at beside-20261120-160500-001.zip 15
file_at results/aaaa/base.glb 30
file_at uploads/bbbb_dog.jpg 30
printf '%s %s\n' db/beside-20261120-150500-001.zip backup/beside-20261120-160500-001.zip \
  files/results/aaaa/base.glb uploads/bbbb_dog.jpg > "$WORK/delete-after"
run FORCE_FILES=1 || fail "a file deleted after it was listed failed the run"
rm "$WORK/delete-after"
grep -q "pruned before it was sent, skipped: beside-20261120-160500-001.zip" "$WORK/out.txt" || fail "vanished zip not reported"
grep -q "deleted before it was sent, skipped: uploads/bbbb_dog.jpg" "$WORK/out.txt" || fail "vanished file not reported"
in_bucket db/beside-20261120-150500-001.zip && in_bucket files/results/aaaa/base.glb || fail "listed files that stayed were not sent"
in_bucket db/beside-20261120-160500-001.zip && fail "a vanished zip was written"
in_bucket files/uploads/bbbb_dog.jpg && fail "a vanished file was written"
grep -q "files: .* 0 failed" "$WORK/out.txt" || fail "a vanished file counted as failed"

# state lost (VM rebuilt from a snapshot): everything is offered again, the bucket answers 412 and nothing is replaced
rm -rf "$WORK/state" "$WORK/vol/backup/beside-20261120-103000-000.zip"   # the broken zip was pruned meanwhile
before="$(writes)"
run FORCE_FILES=1 || fail "run with lost state"
[[ "$(writes)" == "$before" ]] || fail "objects written again after the state was lost"
grep -q "already in the bucket, kept as it is: db/beside-20261120-090500-001.zip" "$WORK/out.txt" || fail "412 not treated as sent"

# the token is never on a command line and never printed
grep -qF "$TOKEN_VALUE" "$WORK/argv" && fail "token on a curl command line"
grep -qF "$TOKEN_VALUE" "$WORK/all.txt" && fail "token printed"
[[ "$(stat -c %a "$WORK/state")" == 700 ]] || fail "state folder is not 700"
echo "offsite_backup.sh: order, once-only, daily files, vanished files, last.ok, 412 keep, scope check and token handling ok"
