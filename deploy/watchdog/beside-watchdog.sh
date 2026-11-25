#!/usr/bin/env bash
# VM watchdog for the backend (R4). Runs on the VM host as root every 30 s (beside-watchdog.timer).
#   - Health: Actuator /actuator/health on the management port 8081, asked inside the backend container (compose does
#     not publish 8081). It checks the database; prod leaves out Spring's disk-space check (application-prod.properties)
#     because a restart frees no disk (the disk warning below covers that). 3 failures in a row while the container
#     runs -> `docker restart` of the backend + a Discord message. Docker's own healthcheck only marks the container
#     unhealthy and never restarts it, so a frozen backend (FD2) stayed down until someone looked. At most one restart
#     try per 10 min, a failed one included (one 🔴 per try); a container that started less than 90 s ago is not judged (booting); a stopped or restarting
#     container is left to Docker and the operator (compose stop for a backup must not be undone here).
#   - Crash loop: a backend that exits right after starting (prod without its H2 file, failure drill FD5; a bad setting)
#     is restarted by Docker's policy, never runs 90 s and so is never judged above. When Docker's RestartCount has gone
#     up 3 times since the last good health check -> one Discord message (again hourly while it goes on), and one when
#     health is good again. The watchdog's own `docker restart` does not count there.
#   - Maintenance: while /etc/beside-maintenance or ~beside/fault/MAINTENANCE exists, no restarts (load tests, drills).
#     A reminder goes out when such a flag is older than 12 h.
#   - Disk: / at 80 % or more -> one Discord warning per hour.
#   - Off-VM backup (R1, deploy/backup): its last good run (/var/lib/beside-backup/last.ok) 3 h old, or its unit failed
#     and never succeeded -> a Discord message every 6 h until a good run, which is reported once. A failed backup
#     unit alone leaves nothing but the journal; this keeps a stopped backup from going unnoticed. Nothing while the
#     backup is not installed or a run is in progress.
# The webhook is NOTIFY_WEBHOOK_URL from ~beside/Beside/deploy/.env (the backend's own); it is never printed and never
# put on a command line. State: /var/lib/beside-watchdog. Logs (changes only): journalctl -u beside-watchdog.
# Tests: scripts/test_watchdog.sh (stub docker/curl/systemctl/df). Every setting below can be overridden by the
# environment.
set -uo pipefail
export LC_ALL=C

PROJECT="${BESIDE_COMPOSE_PROJECT:-beside}"
SERVICE="${BESIDE_BACKEND_SERVICE:-backend}"
ENV_FILE="${BESIDE_ENV_FILE:-/home/beside/Beside/deploy/.env}"
MAINTENANCE_FILES="${BESIDE_MAINTENANCE_FILES:-/etc/beside-maintenance /home/beside/fault/MAINTENANCE}"
STATE_DIR="${STATE_DIRECTORY:-/var/lib/beside-watchdog}"   # systemd StateDirectory= sets STATE_DIRECTORY
HEALTH_URL="${HEALTH_URL:-http://localhost:8081/actuator/health}"
FAILS_TO_RESTART="${FAILS_TO_RESTART:-3}"
CRASH_LOOP_RESTARTS="${CRASH_LOOP_RESTARTS:-3}"
CRASH_LOOP_EVERY_S="${CRASH_LOOP_ALERT_EVERY_S:-3600}"
COOLDOWN_S="${RESTART_COOLDOWN_S:-600}"
START_GRACE_S="${START_GRACE_S:-90}"
STALE_S="${STALE_S:-300}"   # failures older than this (timer stopped meanwhile) do not count
DISK_PATH="${DISK_PATH:-/}"
DISK_WARN_PCT="${DISK_WARN_PCT:-80}"
DISK_EVERY_S="${DISK_ALERT_EVERY_S:-3600}"
MAINT_REMIND_S="${MAINTENANCE_REMIND_S:-43200}"
BACKUP_UNIT="${BACKUP_UNIT:-beside-backup.service}"
BACKUP_OK_FILE="${BACKUP_OK_FILE:-/var/lib/beside-backup/last.ok}"   # written by offsite_backup.sh after a good run
BACKUP_STALE_S="${BACKUP_STALE_S:-10800}"        # hourly runs: 3 h = three missed or failed runs
BACKUP_EVERY_S="${BACKUP_ALERT_EVERY_S:-21600}"
NOW="${WATCHDOG_NOW:-$(date +%s)}"
HOST="$(hostname 2>/dev/null || echo vm)"

if [[ -n "${JOURNAL_STREAM:-}" ]]; then W='<4>'; E='<3>'; else W='warning: '; E='error: '; fi
log() { printf '%s\n' "$*"; }
warn() { printf '%s%s\n' "$W" "$*"; }

mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"
exec 9>"$STATE_DIR/.lock"
flock -n 9 || exit 0

get() { cat "$STATE_DIR/$1" 2>/dev/null || printf '%s' "${2:-}"; }
put() { printf '%s\n' "$2" > "$STATE_DIR/$1.tmp" && mv -f "$STATE_DIR/$1.tmp" "$STATE_DIR/$1"; }
del() { rm -f "$STATE_DIR/$1"; }

# A value from deploy/.env the way compose reads it (as scripts/deploy_vm.sh does): matching quotes are removed;
# otherwise ' #' starts a comment and surrounding spaces go.
env_value() {
  local v
  v="$(sed -n "s/^$1=//p" "$ENV_FILE" 2>/dev/null | tail -n1 | tr -d '\r')"
  v="${v#"${v%%[![:space:]]*}"}"
  case "$v" in
    \"*\"*) v="${v#\"}"; v="${v%%\"*}" ;;
    \'*\'*) v="${v#\'}"; v="${v%%\'*}" ;;
    *) v="${v%% #*}"; v="${v%"${v##*[![:space:]]}"}" ;;
  esac
  printf '%s' "$v"
}
json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '%s' "${s//$'\n'/\\n}"
}
# notify <text>: Discord message (the text also goes to the journal). The URL reaches curl as config on stdin.
notify() {
  local url
  url="$(env_value NOTIFY_WEBHOOK_URL)"
  if [[ -z "$url" ]]; then
    log "no NOTIFY_WEBHOOK_URL in $ENV_FILE, message only logged: $1"
    return 0
  fi
  if printf 'url = "%s"\n' "$url" |
    curl -fsS -m 10 -o /dev/null -K - -H 'Content-Type: application/json' \
      --data-binary "{\"content\": \"$(json_escape "$1")\"}" 2>/dev/null; then
    log "sent: $1"
  else
    warn "webhook failed, message: $1"
  fi
}

check_disk() {
  local pct last
  pct="$(df -P "$DISK_PATH" 2>/dev/null | awk 'NR == 2 { sub("%", "", $5); print $5 }')"
  [[ "$pct" =~ ^[0-9]+$ ]] || { warn "df $DISK_PATH gave no use%"; return 0; }
  ((pct >= DISK_WARN_PCT)) || return 0
  last="$(get disk.last 0)"
  if ((NOW - last >= DISK_EVERY_S)); then
    notify "💾 VM 디스크 $DISK_PATH 사용률 ${pct}% (기준 ${DISK_WARN_PCT}%, VM 감시 · $HOST) — docker image prune -f, 오래된 로그·부하 시험 볼륨을 확인해 주세요"
    put disk.last "$NOW"
  fi
}

check_backup() {
  local state ok age reason last
  state="$(systemctl show -p ActiveState --value "$BACKUP_UNIT" 2>/dev/null)"
  [[ "$state" != activating ]] || return 0   # a run is going (after boot the timer catches up): judge it afterwards
  ok="$(cat "$BACKUP_OK_FILE" 2>/dev/null || true)"
  if [[ "$ok" =~ ^[0-9]+$ ]]; then
    age=$((NOW - ok))
    if ((age < BACKUP_STALE_S)); then
      if [[ -n "$(get backup.alerted)" ]]; then
        notify "✅ VM 밖 백업이 다시 성공했어요 (VM 감시 · $HOST)"
        del backup.alerted
      fi
      return 0
    fi
    reason="마지막 성공이 $((age / 3600))시간 전이에요"
  elif [[ "$state" == failed ]]; then
    reason="설치한 뒤 한 번도 성공하지 않았어요"
  else
    return 0   # not installed (yet)
  fi
  last="$(get backup.alerted 0)"
  if ((NOW - last >= BACKUP_EVERY_S)); then
    notify "🗄️ VM 밖 백업(R1)이 멈췄어요 (VM 감시 · $HOST) — $reason. journalctl -u beside-backup -n 30 으로 원인(서비스 계정·범위, 버킷, backend 의 매시 DB 백업)을 확인해 주세요"
    put backup.alerted "$NOW"
  fi
}

check_backend() {
  local flag age cid status started started_s fails last_check last restarted count base failed_at
  for flag in $MAINTENANCE_FILES; do
    [[ -e "$flag" ]] || continue
    put fails 0
    age=$((NOW - $(stat -c %Y "$flag" 2>/dev/null || echo "$NOW")))
    if ((age >= MAINT_REMIND_S && NOW - $(get maint.reminded 0) >= MAINT_REMIND_S)); then
      notify "🛠️ 유지보수 표시($flag)가 $((age / 3600))시간째 있어요 (VM 감시 · $HOST) — 그동안 backend 자동 재시작이 꺼져 있어요. 끝났으면 지워 주세요"
      put maint.reminded "$NOW"
    fi
    return 0
  done

  if ! cid="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT" \
      --filter "label=com.docker.compose.service=$SERVICE" 2>/dev/null | head -n1)"; then
    warn "docker does not answer"
    return 0
  fi
  if [[ -z "$cid" ]]; then
    put fails 0
    return 0
  fi
  read -r status started count < <(docker inspect -f '{{.State.Status}} {{.State.StartedAt}} {{.RestartCount}}' "$cid" 2>/dev/null)
  [[ "${count:-}" =~ ^[0-9]+$ ]] || count=0
  if [[ "${status:-}" != running ]]; then
    # Only while Docker keeps restarting it: an exited container was stopped on purpose (DB restore, backup).
    [[ "${status:-}" != restarting ]] || check_crash_loop "$cid" "$count"
    [[ "$(get fails 0)" == 0 ]] || log "backend is ${status:-unknown}: left to Docker's restart policy and the operator"
    put fails 0
    return 0
  fi
  started_s="$(date -d "$started" +%s 2>/dev/null || echo 0)"
  if ((NOW - started_s < START_GRACE_S)); then
    put fails 0
    check_crash_loop "$cid" "$count"
    return 0
  fi

  if timeout 20 docker exec "$cid" curl -fsS -m 5 -o /dev/null "$HEALTH_URL" >/dev/null 2>&1; then
    fails="$(get fails 0)"
    ((fails == 0)) || log "health ok again after $fails failure(s)"
    put fails 0
    put restarts.base "$count"
    if [[ -n "$(get crashloop.alerted)" ]]; then
      notify "✅ backend 가 다시 정상으로 떴어요 (VM 감시 · $HOST) — 시작하자마자 꺼지던 것이 멈췄어요"
      del crashloop.alerted
    fi
    restarted="$(get restarted.at)"
    if [[ -n "$restarted" ]]; then
      notify "✅ backend health 정상 (VM 감시 · $HOST) — 재시작 $((NOW - restarted))초 뒤 확인"
      del restarted.at
    fi
    if [[ -n "$(get restart.failed)" ]]; then
      notify "✅ backend health 정상 (VM 감시 · $HOST) — 재시작이 실패했던 backend 가 다시 정상이에요"
      del restart.failed
    fi
    put last.check "$NOW"
    return 0
  fi

  fails="$(get fails 0)"
  last_check="$(get last.check 0)"
  ((NOW - last_check <= STALE_S)) || fails=0
  fails=$((fails + 1))
  put fails "$fails"
  put last.check "$NOW"
  warn "backend health failed ($fails/$FAILS_TO_RESTART): $HEALTH_URL in $cid"
  ((fails >= FAILS_TO_RESTART)) || return 0

  last="$(get restart.last 0)"
  if ((NOW - last < COOLDOWN_S)); then
    if [[ "$(get cooldown.warned)" != "$last" ]]; then
      notify "⚠️ backend health 가 재시작 뒤에도 실패해요 (VM 감시 · $HOST) — $((COOLDOWN_S / 60))분 안에는 다시 시작하지 않아요. 손으로 확인해 주세요"
      put cooldown.warned "$last"
    fi
    return 0
  fi
  # A restart that failed (the stop hit the 120 s timeout, dockerd refused) waits out the cooldown too: its 🔴 went out
  # with it. Without this, restart.last (written only on success) let the next run 30 s later try again and send
  # another 🔴, every run until someone looked (review finding, 2026-11-25).
  failed_at="$(get restart.failed 0)"
  if ((NOW - failed_at < COOLDOWN_S)); then
    warn "docker restart failed $((NOW - failed_at)) s ago: next try after the $((COOLDOWN_S / 60)) min cooldown"
    return 1
  fi
  if timeout 120 docker restart -t 20 "$cid" >/dev/null 2>&1; then
    put restart.last "$NOW"
    put restarted.at "$NOW"
    put fails 0
    del restart.failed
    notify "🔁 backend 를 다시 시작했어요 (VM 감시 · $HOST) — 관리 포트 health 가 ${FAILS_TO_RESTART}번 연속 실패. 그때 PENDING·PROCESSING 이던 작업은 INTERNAL_ERROR 로 끝나요(앱에서 다시 시도)"
  else
    put restart.failed "$NOW"
    printf '%s%s\n' "$E" "docker restart $cid failed"
    notify "🔴 backend 재시작 실패 (VM 감시 · $HOST) — docker restart 가 실패했어요. 손으로 확인해 주세요 ($((COOLDOWN_S / 60))분 뒤 한 번 더 시도해요)"
    return 1
  fi
}

# check_crash_loop <cid> <RestartCount>: Docker restarted the backend CRASH_LOOP_RESTARTS times or more since the last
# good health check (the count at that check is restarts.base; a recreated container starts again from 0). Asked only
# while Docker is restarting it or it is still booting: a healthy run resets the base instead, and a container the
# operator stopped (exited) gets no reminders.
check_crash_loop() {
  local cid="$1" count="$2" base last
  base="$(get restarts.base)"
  if [[ ! "$base" =~ ^[0-9]+$ ]] || ((count < base)); then
    put restarts.base "$count"
    return 0
  fi
  ((count - base >= CRASH_LOOP_RESTARTS)) || return 0
  last="$(get crashloop.alerted 0)"
  ((NOW - last >= CRASH_LOOP_EVERY_S)) || return 0
  notify "🧱 backend 가 시작하자마자 계속 꺼져요 (VM 감시 · $HOST) — 마지막 정상 확인 뒤 Docker 가 $((count - base))번 다시 띄웠어요. docker logs --tail 50 ${cid:0:12} 로 원인을 확인해 주세요. 'H2 database file ... does not exist' 면 DB 를 잃은 것이니 docs/ops/RUNBOOK.md 'DB 복구' 로 되돌립니다"
  put crashloop.alerted "$NOW"
}

rc=0
check_disk
check_backup
check_backend || rc=1
exit "$rc"
