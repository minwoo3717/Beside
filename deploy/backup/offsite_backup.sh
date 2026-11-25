#!/usr/bin/env bash
# Copy the backend's backups off the VM into the backup bucket (R1). Runs on the VM host as root from
# beside-backup.timer (every hour at minute 20; the backend writes its H2 backup at minute 5), never in a container.
#   db/     each new H2 backup zip (volume beside-storage: backup/beside-<time>.zip) -> gs://$BUCKET/db/<name>  every run
#   files/  new files under uploads/ and results/ of the same volume -> gs://$BUCKET/files/<path>   first run of each day (KST)
# The VM's service account (beside-backup@, made by deploy/gcp/backup_bucket.sh) has only roles/storage.objectCreator on
# the bucket: it can create objects, not read, list, overwrite or delete them, so a broken or taken-over VM cannot erase
# its own backups. Uploads therefore go straight to the Cloud Storage JSON API with ifGenerationMatch=0: the object is
# created only if the name is free (the server-side no-clobber; 412 = already there, kept), and what was sent is
# remembered in $STATE_DIR. `gcloud storage cp` (with or without --no-clobber) does not work with that role: it reads
# the destination object first and accepts only 404 (gcloud 594: wildcard_iterator._try_getting_object_directly,
# copy_util.check_for_cloud_clobber), and the role answers 403.
# Install and check: deploy/README.md '운영 대응 설치'. Config: /etc/beside-backup.env with BUCKET=<bucket name>.
#   sudo /usr/local/sbin/beside-offsite-backup                  # what the timer runs
#   sudo FORCE_FILES=1 /usr/local/sbin/beside-offsite-backup    # photos and GLBs too, now, not only at the day's first run
# Exit status 1 when an upload failed (the next run tries it again) or the newest H2 zip is older than DB_STALE_MIN
# (150 min: the backend's own hourly backup stopped); a good run writes its time to $STATE_DIR/last.ok,
# which the VM watchdog reads to report a backup that stopped. The access token is never printed or put on a command
# line. Tests: scripts/test_offsite_backup.sh (fake docker/curl/metadata server/bucket).
set -euo pipefail
export LC_ALL=C   # byte order for the backup names and the manifest

CONFIG="${BESIDE_BACKUP_CONFIG:-/etc/beside-backup.env}"
# KEY=value lines only; nothing in the file is run as shell code. The environment wins (tests, one-off runs).
config_value() {
  local v
  v="$(sed -n "s/^$1=//p" "$CONFIG" 2>/dev/null | tail -n1 | tr -d '\r')"
  v="${v#[\"\']}"; v="${v%[\"\']}"
  printf '%s' "$v"
}
for key in BUCKET VOLUME; do
  if [[ -z "${!key:-}" && -r "$CONFIG" ]]; then
    printf -v "$key" '%s' "$(config_value "$key")"
  fi
done
BUCKET="${BUCKET:-}"
VOLUME="${VOLUME:-beside-storage}"
STATE_DIR="${STATE_DIRECTORY:-/var/lib/beside-backup}"       # systemd StateDirectory= sets STATE_DIRECTORY
MIN_AGE_MIN="${MIN_AGE_MIN:-2}"                               # newer files may still be being written
METADATA="${METADATA_URL:-http://169.254.169.254}"
GCS_UPLOAD="${GCS_UPLOAD_URL:-https://storage.googleapis.com/upload/storage/v1}"
FORCE_FILES="${FORCE_FILES:-0}"
DB_STALE_MIN="${DB_STALE_MIN:-150}"   # newest H2 zip older than this = the backend's hourly backup missed twice
MAINTENANCE_FILES="${BESIDE_MAINTENANCE_FILES:-/etc/beside-maintenance /home/beside/fault/MAINTENANCE}"

if [[ -n "${JOURNAL_STREAM:-}" ]]; then E='<3>'; else E='error: '; fi   # <3> = journald priority "err"
log() { printf '%s\n' "$*"; }
err() { printf '%s%s\n' "$E" "$*" >&2; }

if [[ ! "$BUCKET" =~ ^[a-z0-9][a-z0-9._-]{1,61}[a-z0-9]$ ]]; then
  err "BUCKET is not set or not a bucket name: put BUCKET=<bucket> in $CONFIG (deploy/gcp/backup_bucket.sh prints it)"
  exit 2
fi

mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"
exec 9>"$STATE_DIR/.lock"
if ! flock -n 9; then
  log "another run is still going: skipped"
  exit 0
fi
# What was sent belongs to one bucket: a new bucket starts from scratch (412 keeps it cheap if names are already there).
if [[ "$(cat "$STATE_DIR/bucket" 2>/dev/null || true)" != "$BUCKET" ]]; then
  rm -f "$STATE_DIR/db.last" "$STATE_DIR/files.done" "$STATE_DIR/files.day"
  printf '%s\n' "$BUCKET" > "$STATE_DIR/bucket"
fi

if [[ -z "${STORAGE_DIR:-}" ]]; then
  if ! STORAGE_DIR="$(docker volume inspect -f '{{.Mountpoint}}' "$VOLUME" 2>/dev/null)"; then
    err "docker volume $VOLUME not found (is this the VM that runs deploy/docker-compose.yml?)"
    exit 1
  fi
fi
if [[ ! -d "$STORAGE_DIR" ]]; then
  err "storage directory $STORAGE_DIR does not exist"
  exit 1
fi

meta() { curl -fsS --max-time 5 -H 'Metadata-Flavor: Google' "$METADATA/computeMetadata/v1/instance/service-accounts/default/$1"; }
SA="$(meta email 2>/dev/null || true)"
SCOPES="$(meta scopes 2>/dev/null || true)"
if [[ -z "$SA" ]]; then
  err "the VM has no service account (metadata server): attach beside-backup@ as deploy/gcp/backup_bucket.sh prints"
  exit 1
fi
if ! grep -qE 'devstorage\.(read_write|full_control)|auth/cloud-platform' <<<"$SCOPES"; then
  err "service account $SA has no Cloud Storage write scope: attach it with --scopes=storage-rw,logging-write,monitoring-write"
  exit 1
fi

TOKEN=""
TOKEN_AT=0
token() {
  local now json
  now="$(date +%s)"
  if [[ -z "$TOKEN" || $((now - TOKEN_AT)) -ge 1800 ]]; then   # tokens live about an hour; refresh after 30 min
    if ! json="$(meta token 2>/dev/null)"; then
      err "no access token from the metadata server"
      return 1
    fi
    TOKEN="$(sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' <<<"$json")"
    if [[ -z "$TOKEN" ]]; then
      err "the metadata server answered without an access token"
      return 1
    fi
    TOKEN_AT="$now"
  fi
}

RESP="$(mktemp)"
LIST="$(mktemp)"
NEW="$(mktemp)"
trap 'rm -f "$RESP" "$LIST" "$NEW"' EXIT

# upload <local file> <object name>: 0 = created now or already in the bucket, 1 = failed.
upload() {
  local file="$1" name="$2" type code message attempt
  case "${file,,}" in
    *.zip) type=application/zip ;;
    *.jpg | *.jpeg) type=image/jpeg ;;
    *.png) type=image/png ;;
    *.webp) type=image/webp ;;
    *.glb) type=model/gltf-binary ;;
    *) type=application/octet-stream ;;
  esac
  for attempt in 1 2; do
    token || return 1
    : > "$RESP"
    # The Authorization header reaches curl as config on stdin, never on its command line (ps would show it).
    # --url-query (curl 7.87+, Ubuntu 24.04 has 8.5) percent-encodes the object name.
    code="$(printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" |
      curl -sS -K - -o "$RESP" -w '%{http_code}' --max-time 600 --retry 3 --retry-delay 10 \
        -H "Content-Type: $type" --data-binary "@$file" \
        --url-query uploadType=media --url-query "name=$name" --url-query ifGenerationMatch=0 \
        "$GCS_UPLOAD/b/$BUCKET/o")" || true
    message="$(sed -n 's/.*"message"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$RESP" 2>/dev/null | head -n1)"
    case "$code" in
      200 | 201) return 0 ;;
      412) log "already in the bucket, kept as it is: $name"; return 0 ;;
      401) TOKEN="" ;;   # expired token: fetch a new one and try once more
      403)
        # Replacing an object needs storage.objects.delete, which the role does not have: the name is already taken.
        if [[ "$message" == *storage.objects.delete* ]]; then
          log "already in the bucket (no permission to replace), kept as it is: $name"
          return 0
        fi
        break ;;
      *) break ;;
    esac
  done
  err "upload failed (HTTP ${code:-none}): $name${message:+: $message}"
  return 1
}

# A zip that does not open is reported, not sent (H2 died while writing it); python3 is on every Ubuntu cloud image.
zip_ok() {
  if command -v python3 >/dev/null 2>&1; then
    python3 -I -m zipfile -t "$1" >/dev/null 2>&1
  fi
}

failed=0

# ---- db/: every new H2 backup, oldest first. db.last is the newest name sent; names sort by their time stamp.
BACKUP_DIR="$STORAGE_DIR/backup"
last="$(cat "$STATE_DIR/db.last" 2>/dev/null || true)"
sent=0
if [[ -d "$BACKUP_DIR" ]]; then
  # find exits 1 when a file vanishes under it (a zip pruned meanwhile): what it listed is still good.
  { find "$BACKUP_DIR" -maxdepth 1 -type f -name 'beside-*.zip' -mmin "+$MIN_AGE_MIN" -printf '%f\n' || true; } | sort > "$LIST"
  while IFS= read -r name <&3; do
    [[ -z "$last" || "$name" > "$last" ]] || continue
    if [[ ! -f "$BACKUP_DIR/$name" ]]; then
      err "pruned before it was sent, skipped: $name (was the VM off for two days?)"
    elif ! zip_ok "$BACKUP_DIR/$name"; then
      err "not a readable zip, skipped: $name"
      failed=1
    elif upload "$BACKUP_DIR/$name" "db/$name"; then
      sent=$((sent + 1))
    else
      failed=1
      break   # keep the order: the next run starts again from this one
    fi
    last="$name"
    printf '%s\n' "$last" > "$STATE_DIR/db.last.tmp" && mv -f "$STATE_DIR/db.last.tmp" "$STATE_DIR/db.last"
  done 3< "$LIST"
else
  err "no backup folder $BACKUP_DIR (storage.backup.enabled in the prod profile?)"
  failed=1
fi
log "db: $sent new backup(s) sent, newest handled: ${last:-none}"

# A backend whose hourly H2 backup stopped (a folder it cannot write, a full disk) left runs of "0 new" that counted as
# good, and its own failure is only an ERROR line in the backend log: nothing reached Discord while the bucket's newest
# DB copy grew old (review finding, 2026-11-25). Now a newest zip older than DB_STALE_MIN fails the run, so last.ok
# stops moving and the VM watchdog reports it. Not checked under a maintenance flag (load tests and drills; the
# load-test overlay turns the backend's backup off).
if [[ -d "$BACKUP_DIR" ]]; then
  maintenance=""
  for flag in $MAINTENANCE_FILES; do
    if [[ -e "$flag" ]]; then maintenance="$flag"; break; fi
  done
  newest="$({ find "$BACKUP_DIR" -maxdepth 1 -type f -name 'beside-*.zip' -printf '%T@ %f\n' || true; } | sort -n | tail -n1)"
  # A VM that was off for hours (or rebuilt from the daily snapshot) runs this before the backend's next xx:05 backup:
  # its newest zip is old for a good reason, so the age is judged only once the VM has been up DB_STALE_MIN.
  uptime_s="${UPTIME_S:-$(cut -d. -f1 /proc/uptime 2>/dev/null || echo 999999)}"
  if [[ -n "$maintenance" ]]; then
    log "db: maintenance flag $maintenance: the age of the newest backup is not checked"
  elif ((uptime_s < DB_STALE_MIN * 60)); then
    log "db: the VM has been up $((uptime_s / 60)) min (< $DB_STALE_MIN): the age of the newest backup is not checked yet"
  elif [[ -z "$newest" ]]; then
    err "no H2 backup zip in $BACKUP_DIR: is the backend's hourly backup (storage.backup.*) running?"
    failed=1
  else
    newest_s="${newest%% *}"
    newest_age_min=$(( ($(date +%s) - ${newest_s%%.*}) / 60 ))
    if ((newest_age_min > DB_STALE_MIN)); then
      err "the newest H2 backup ${newest#* } is $newest_age_min min old (limit $DB_STALE_MIN): the backend's hourly backup has stopped (backend log: 'H2 backup failed')"
      failed=1
    fi
  fi
fi

# ---- files/: photos and GLBs, once a day. files.done lists every relative path already sent (objects never change:
# uploads/<job>_<name> and results/<job>/base.glb are written once per job).
today="$(TZ=Asia/Seoul date +%F)"
if [[ "$FORCE_FILES" == 1 || "$(cat "$STATE_DIR/files.day" 2>/dev/null || true)" != "$today" ]]; then
  manifest="$STATE_DIR/files.done"
  touch "$manifest"
  sort -u -o "$manifest" "$manifest"
  dirs=()
  for dir in uploads results; do
    [[ -d "$STORAGE_DIR/$dir" ]] && dirs+=("$dir")
  done
  : > "$LIST"
  if ((${#dirs[@]})); then
    # a job deleted while find runs makes it exit 1; the files it did list are still to be sent
    (cd "$STORAGE_DIR" && { find "${dirs[@]}" -type f ! -name $'*\n*' -mmin "+$MIN_AGE_MIN" -print || true; }) | sort -u > "$LIST"
  fi
  comm -13 "$manifest" "$LIST" > "$NEW"
  files_sent=0
  files_failed=0
  while IFS= read -r rel <&3; do
    if [[ ! -f "$STORAGE_DIR/$rel" ]]; then
      log "deleted before it was sent, skipped: $rel"   # its job was deleted meanwhile; nothing to retry
    elif upload "$STORAGE_DIR/$rel" "files/$rel"; then
      printf '%s\n' "$rel" >> "$manifest"
      files_sent=$((files_sent + 1))
    else
      files_failed=$((files_failed + 1))
    fi
  done 3< "$NEW"
  if ((files_failed == 0)); then
    printf '%s\n' "$today" > "$STATE_DIR/files.day"
  else
    failed=1   # files.day stays: the next hourly run sends what is still missing
  fi
  log "files: $files_sent new file(s) sent, $files_failed failed, $(wc -l < "$manifest") in the bucket"
fi

# The time of the last good run, for the VM watchdog (deploy/watchdog): it posts to Discord when this is 3 h old, so a
# backup that keeps failing (service account or scope gone, uploads refused) does not end the protection unnoticed.
if ((failed == 0)); then
  date +%s > "$STATE_DIR/last.ok.tmp" && mv -f "$STATE_DIR/last.ok.tmp" "$STATE_DIR/last.ok"
fi
exit "$failed"
