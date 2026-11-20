#!/usr/bin/env bash
# Test of deploy/watchdog/beside-watchdog.sh without a VM: stub docker (one backend container whose state, start time
# and health come from files), curl (the Discord webhook, recorded), systemctl (the off-VM backup unit) and df on PATH;
# the clock is WATCHDOG_NOW. Checks the decisions: 3 failures in a row -> one restart and one message; booting
# containers are not judged; at most one restart per 10 min with one "still failing" message; recovery is reported
# once; maintenance flags, stopped containers and stale failures never restart; disk warnings at most hourly; an
# off-VM backup without a good run for 3 h (or failing since it was installed) is reported every 6 h, its recovery
# once; the webhook URL is never on a command line or in the log.
#   bash scripts/test_watchdog.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../deploy/watchdog/beside-watchdog.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/fake"
HOOK="https://discord.test/api/webhooks/123/HOOK-SECRET-abc"

cat > "$WORK/bin/docker" <<'FAKE'
#!/usr/bin/env bash
F="$FAKE/fake"
case "$*" in
  "ps -aq --filter label=com.docker.compose.project=beside --filter label=com.docker.compose.service=backend")
    cat "$F/cid" 2>/dev/null || true ;;
  "inspect -f {{.State.Status}} {{.State.StartedAt}} c0ffee") echo "$(cat "$F/status") $(cat "$F/started")" ;;
  "exec c0ffee curl -fsS -m 5 -o /dev/null http://localhost:8081/actuator/health") [[ -f "$F/healthy" ]] ;;
  "restart -t 20 c0ffee")
    [[ -f "$F/restart-fails" ]] && exit 1
    echo "$WATCHDOG_NOW" >> "$F/restarts"
    date -u -d "@$WATCHDOG_NOW" +%Y-%m-%dT%H:%M:%S.123456789Z > "$F/started" ;;
  *) echo "fake docker: unexpected $*" >&2; exit 1 ;;
esac
FAKE
cat > "$WORK/bin/curl" <<'FAKE'
#!/usr/bin/env bash
# the webhook: argv and the config from stdin are recorded; the message is the --data-binary JSON
printf '%s\n' "$*" >> "$FAKE/fake/curl-argv"
cat >> "$FAKE/fake/curl-config"
while (($#)); do [[ "$1" == --data-binary ]] && printf '%s\n' "$2" >> "$FAKE/fake/messages"; shift; done
FAKE
cat > "$WORK/bin/systemctl" <<'FAKE'
#!/usr/bin/env bash
# fake systemctl: the off-VM backup unit's state comes from a file (a unit that is not installed reads "inactive")
[[ "$*" == "show -p ActiveState --value beside-backup.service" ]] || { echo "fake systemctl: unexpected $*" >&2; exit 1; }
cat "$FAKE/fake/backup-state" 2>/dev/null || echo inactive
FAKE
cat > "$WORK/bin/df" <<'FAKE'
#!/usr/bin/env bash
echo "Filesystem 1024-blocks Used Available Capacity Mounted on"
echo "/dev/root 50620216 1 1 $(cat "$FAKE/fake/disk")% /"
FAKE
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH" FAKE="$WORK"
printf 'BESIDE_DOMAIN=example.test\nNOTIFY_WEBHOOK_URL=%s   # team channel\n' "$HOOK" > "$WORK/env"

T0=1795132800   # 2026-11-20T00:00:00Z; the container started then
echo c0ffee > "$WORK/fake/cid"
echo running > "$WORK/fake/status"
date -u -d "@$T0" +%Y-%m-%dT%H:%M:%S.000000001Z > "$WORK/fake/started"
echo 41 > "$WORK/fake/disk"
touch "$WORK/fake/healthy"

step() {   # step <seconds after T0>: one timer run
  local rc=0
  env STATE_DIRECTORY="$WORK/state" BESIDE_ENV_FILE="$WORK/env" WATCHDOG_NOW=$((T0 + $1)) \
    BACKUP_OK_FILE="$WORK/backup/last.ok" BESIDE_MAINTENANCE_FILES="$WORK/maintenance $WORK/fault/MAINTENANCE" bash "$SCRIPT" >> "$WORK/log" 2>&1 || rc=$?
  return "$rc"
}
fail() { echo "FAIL: $*"; echo "--- log"; cat "$WORK/log"; echo "--- messages"; cat "$WORK/fake/messages" 2>/dev/null; exit 1; }
restarts() { [[ -f "$WORK/fake/restarts" ]] && wc -l < "$WORK/fake/restarts" | tr -d ' ' || echo 0; }
messages() { [[ -f "$WORK/fake/messages" ]] && wc -l < "$WORK/fake/messages" | tr -d ' ' || echo 0; }
count() { grep -c -- "$1" "$WORK/fake/messages" 2>/dev/null || true; }

# healthy: nothing happens, nothing is logged (the timer runs 2,880 times a day)
step 600 || fail "healthy run"
[[ ! -s "$WORK/log" && "$(restarts)" == 0 && "$(messages)" == 0 ]] || fail "a healthy run did something"

# a booting container (started < 90 s ago) is not judged
rm "$WORK/fake/healthy"
date -u -d "@$((T0 + 600))" +%Y-%m-%dT%H:%M:%S.000000001Z > "$WORK/fake/started"
for t in 630 660 680; do step "$t" || fail "grace run $t"; done
[[ "$(restarts)" == 0 ]] || fail "restarted a booting container"

# three failures in a row -> one restart and one message
step 700; step 730
[[ "$(restarts)" == 0 ]] || fail "restarted after 2 failures"
step 760 || fail "restart run"
[[ "$(restarts)" == 1 && "$(count '다시 시작했어요')" == 1 ]] || fail "no restart/message after 3 failures"

# still failing after the restart: not judged while it boots, then one warning, no restart within 10 min
for t in 790 820 850 880 910 940 970 1000 1030 1060 1090 1120 1150 1180 1210 1240 1270 1300 1330; do
  step "$t" || fail "cooldown run $t"
done
[[ "$(restarts)" == 1 ]] || fail "restarted again within 10 min: $(xargs < "$WORK/fake/restarts")"
[[ "$(count '재시작 뒤에도 실패')" == 1 ]] || fail "expected one still-failing message, got $(count '재시작 뒤에도 실패')"
# 10 min after the restart (760 + 600) it may restart again
step 1360; step 1390
[[ "$(restarts)" == 2 ]] || fail "no second restart after the cooldown"

# recovery after the restart is reported once
touch "$WORK/fake/healthy"
step 1420                       # booting: not judged
step 1480 || fail "recovery run"
step 1510 || fail "healthy run after recovery"
[[ "$(count 'health 정상')" == 1 ]] || fail "recovery reported $(count 'health 정상') times"

# failures from long ago do not add up with a new one (the timer was stopped meanwhile)
rm "$WORK/fake/healthy"
step 2000; step 2030
step 9000; step 9030
[[ "$(restarts)" == 2 ]] || fail "stale failures led to a restart"

# maintenance flag: failures never restart; an old flag is reminded once per 12 h
mkdir -p "$WORK/fault" && touch "$WORK/fault/MAINTENANCE"
for t in 9060 9090 9120 9150; do step "$t"; done
[[ "$(restarts)" == 2 ]] || fail "restarted during maintenance"
touch -d "@$((T0 + 9200 - 13 * 3600))" "$WORK/fault/MAINTENANCE"
before="$(messages)"
step 9200; step 9230
[[ "$(count '유지보수 표시')" == 1 && "$(messages)" == $((before + 1)) ]] || fail "maintenance reminder not sent once"
rm "$WORK/fault/MAINTENANCE"

# stopped (compose stop for a backup) or restarting containers are left alone
echo exited > "$WORK/fake/status"
for t in 9300 9330 9360 9390; do step "$t"; done
echo restarting > "$WORK/fake/status"
for t in 9420 9450 9480; do step "$t"; done
[[ "$(restarts)" == 2 ]] || fail "restarted a stopped container"
echo running > "$WORK/fake/status"

# docker restart fails: exit 1 and a message
touch "$WORK/fake/restart-fails"
step 20000; step 20030
if step 20060; then fail "a failed restart exited 0"; fi
[[ "$(count '재시작 실패')" == 1 ]] || fail "no restart-failed message"
rm "$WORK/fake/restart-fails"

# disk: 85 % -> one warning, again only after an hour; 79 % -> none
touch "$WORK/fake/healthy"
echo 85 > "$WORK/fake/disk"
step 30000; step 30030; step 31000
[[ "$(count '디스크')" == 1 ]] || fail "disk warned $(count '디스크') times within an hour"
step 33601
[[ "$(count '디스크')" == 2 ]] || fail "no disk warning after an hour"
echo 79 > "$WORK/fake/disk"
step 40000
[[ "$(count '디스크')" == 2 ]] || fail "disk warning below 80 %"

# off-VM backup (R1). Not installed (no last good run, unit inactive): nothing, as in every run above.
backup() { count 'VM 밖 백업(R1)이 멈췄어요'; }
[[ "$(backup)" == 0 ]] || fail "backup alert while the backup is not installed"
# installed but failing from its first run: one message, again only after 6 h
mkdir -p "$WORK/backup"
echo failed > "$WORK/fake/backup-state"
step 41000; step 41030; step 45000
[[ "$(backup)" == 1 ]] || fail "a backup that never succeeded alerted $(backup) times within 6 h"
grep -q '한 번도 성공하지 않았어요' "$WORK/fake/messages" || fail "no never-succeeded reason"
step $((41000 + 21600))
[[ "$(backup)" == 2 ]] || fail "no backup reminder after 6 h"
# the first good run after an alert is reported once
echo inactive > "$WORK/fake/backup-state"
echo $((T0 + 63000)) > "$WORK/backup/last.ok"
step 63030; step 63060
[[ "$(count 'VM 밖 백업이 다시 성공')" == 1 ]] || fail "backup recovery reported $(count 'VM 밖 백업이 다시 성공') times"
# one or two failed hourly runs (a good run less than 3 h ago) are not reported, even with the unit failed
echo failed > "$WORK/fake/backup-state"
step $((63000 + 10800 - 30))
[[ "$(backup)" == 2 ]] || fail "alerted before the last good run was 3 h old"
# a run in progress is not judged (after a VM that was off, the timer catches up at boot)
echo activating > "$WORK/fake/backup-state"
step $((63000 + 10800 + 60))
[[ "$(backup)" == 2 ]] || fail "alerted while a backup run was in progress"
# no good run for 3 h (failing runs, or the timer stopped): alert, whatever the unit state
echo inactive > "$WORK/fake/backup-state"
step $((63000 + 10800 + 90))
[[ "$(backup)" == 3 ]] || fail "no alert when the last good run was 3 h old"
grep -q '마지막 성공이 3시간 전' "$WORK/fake/messages" || fail "no age in the stale-backup alert"
echo $((T0 + 80000)) > "$WORK/backup/last.ok"

# no webhook configured: messages only in the log, curl never called
echo 'BESIDE_DOMAIN=example.test' > "$WORK/env"
calls="$(wc -l < "$WORK/fake/curl-argv")"
echo 90 > "$WORK/fake/disk"
step 80030
[[ "$(wc -l < "$WORK/fake/curl-argv")" == "$calls" ]] || fail "curl called without a webhook"
grep -q "message only logged" "$WORK/log" || fail "no log line without a webhook"

# the webhook URL went only through curl's config on stdin, comment stripped; never on a command line or in the log
grep -qF "$HOOK" "$WORK/fake/curl-argv" && fail "webhook URL on a curl command line"
grep -qF "$HOOK" "$WORK/log" && fail "webhook URL in the log"
grep -qxF "url = \"$HOOK\"" "$WORK/fake/curl-config" || fail "webhook URL not passed as curl config: $(head -n1 "$WORK/fake/curl-config")"
python3 -I -c 'import json, sys; [json.loads(line) for line in open(sys.argv[1], encoding="utf-8")]' "$WORK/fake/messages" \
  || fail "a message is not valid JSON"
[[ "$(stat -c %a "$WORK/state")" == 700 ]] || fail "state folder is not 700"
echo "beside-watchdog.sh: restart after 3 failures, grace, cooldown, recovery, maintenance, disk, backup and webhook handling ok"
