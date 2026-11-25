#!/usr/bin/env bash
# Test of deploy/watchdog/beside-watchdog.sh without a VM: stub docker (one backend container whose state, start time
# and health come from files), curl (the Discord webhook, recorded), systemctl (the off-VM backup unit) and df on PATH;
# the clock is WATCHDOG_NOW. Checks the decisions: 3 failures in a row -> one restart and one message; booting
# containers are not judged; at most one restart per 10 min with one "still failing" message; recovery is reported
# once; maintenance flags, stopped containers and stale failures never restart; a crash loop (Docker's RestartCount up
# 3 since the last good check) is reported once an hour, its end once; disk warnings at most hourly; an
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
  "inspect -f {{.State.Status}} {{.State.StartedAt}} {{.RestartCount}} c0ffee")
    echo "$(cat "$F/status") $(cat "$F/started") $(cat "$F/restartcount" 2>/dev/null || echo 0)" ;;
  "exec c0ffee curl -fsS -m 5 -o /dev/null http://localhost:8081/actuator/health") [[ -f "$F/healthy" ]] ;;
  "inspect -f {{.State.Status}} c0ffee") cat "$F/status" ;;
  "restart -t 20 c0ffee")
    echo "$WATCHDOG_NOW" >> "$F/restart-tries"
    if [[ -f "$F/restart-fails" ]]; then
      [[ -f "$F/restart-exits" ]] && echo exited > "$F/status"   # the stop worked, the start did not
      exit 1
    fi
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

# crash loop (FD5: prod without its H2 file exits at once; Docker restarts it, it never runs 90 s): RestartCount 3 above
# the last good check -> one message, again only after an hour; good health again -> one message. Never restarted here.
crash() { count '시작하자마자 계속 꺼져요'; }
touch "$WORK/fake/healthy"
echo 5 > "$WORK/fake/restartcount"
step 9600 || fail "healthy run before the crash loop"   # base 5
rm "$WORK/fake/healthy"
echo restarting > "$WORK/fake/status"
echo 6 > "$WORK/fake/restartcount"; step 9630
echo 7 > "$WORK/fake/restartcount"; step 9660
[[ "$(crash)" == 0 ]] || fail "crash loop alert after 2 Docker restarts"
echo running > "$WORK/fake/status"
date -u -d "@$((T0 + 9685))" +%Y-%m-%dT%H:%M:%S.000000001Z > "$WORK/fake/started"   # up again for a few seconds
echo 8 > "$WORK/fake/restartcount"; step 9690
[[ "$(crash)" == 1 ]] || fail "no crash loop alert after 3 Docker restarts"
grep -q 'Docker 가 3번 다시 띄웠어요' "$WORK/fake/messages" || fail "no restart count in the crash loop alert"
grep -q "RUNBOOK.md 'DB 복구'" "$WORK/fake/messages" || fail "no restore hint in the crash loop alert"
echo restarting > "$WORK/fake/status"
for n in 9 10 11; do echo "$n" > "$WORK/fake/restartcount"; step $((9690 + (n - 8) * 30)); done
[[ "$(crash)" == 1 && "$(restarts)" == 2 ]] || fail "crash loop alerted again within the hour or the watchdog restarted"
echo 30 > "$WORK/fake/restartcount"; step $((9690 + 3600))
[[ "$(crash)" == 2 ]] || fail "no crash loop reminder after an hour"
# the DB is restored: the backend runs and is healthy -> one recovery message, and the count starts over
echo running > "$WORK/fake/status"
date -u -d "@$((T0 + 13400))" +%Y-%m-%dT%H:%M:%S.000000001Z > "$WORK/fake/started"
touch "$WORK/fake/healthy"
step 13500; step 13530
[[ "$(count '다시 정상으로 떴어요')" == 1 ]] || fail "crash loop end reported $(count '다시 정상으로 떴어요') times"
# a stopped backend (the operator follows RUNBOOK 'DB 복구' step 1) gets no reminders, however long it stays stopped
echo 31 > "$WORK/fake/restartcount"; rm "$WORK/fake/healthy"; echo restarting > "$WORK/fake/status"
step 13600; step 13630; step 13660   # 31 is only 1 above the base 30 from the healthy runs: no alert yet
echo 34 > "$WORK/fake/restartcount"; step 13690
[[ "$(crash)" == 3 ]] || fail "no crash loop alert after 4 more Docker restarts"
echo exited > "$WORK/fake/status"
step $((13690 + 3700))
[[ "$(crash)" == 3 ]] || fail "crash loop reminder for a container the operator stopped"
# a deploy recreates the container on a lost DB: its count starts at 0 below the old base (34) and it never gets a good
# health check, so the base must follow it down for the next 3 restarts to alert (not only at 37)
echo restarting > "$WORK/fake/status"
for n in 0 1 2; do echo "$n" > "$WORK/fake/restartcount"; step $((17500 + n * 30)); done
[[ "$(crash)" == 3 ]] || fail "crash loop alert before 3 restarts of the recreated container"
echo 3 > "$WORK/fake/restartcount"; step 17590
[[ "$(crash)" == 4 ]] || fail "no crash loop alert for the recreated container at 3 restarts"
tail -n1 "$WORK/fake/messages" | grep -q 'Docker 가 3번 다시 띄웠어요' || fail "wrong count for the recreated container"
# restored: healthy again (reported once more), then failing health for the next part
echo running > "$WORK/fake/status"; echo 0 > "$WORK/fake/restartcount"
date -u -d "@$((T0 + 17600))" +%Y-%m-%dT%H:%M:%S.000000001Z > "$WORK/fake/started"
touch "$WORK/fake/healthy"; step 17700
[[ "$(count '다시 정상으로 떴어요')" == 2 ]] || fail "second crash loop end not reported"
rm "$WORK/fake/healthy"

# docker restart fails: exit 1 and a message
tries() { [[ -f "$WORK/fake/restart-tries" ]] && wc -l < "$WORK/fake/restart-tries" | tr -d ' ' || echo 0; }
touch "$WORK/fake/restart-fails"
tries_before="$(tries)"
step 20000; step 20030
if step 20060; then fail "a failed restart exited 0"; fi
[[ "$(count '재시작 실패')" == 1 ]] || fail "no restart-failed message"
# review finding 2026-11-25: a failed restart waits out the cooldown too (it used to try again and send 🔴 every run)
for t in 20090 20120 20150 20180 20210 20400 20640; do
  if step "$t"; then fail "run $t after a failed restart exited 0 while the backend is still down"; fi
done
[[ "$(tries)" == $((tries_before + 1)) ]] || fail "restart tried $(( $(tries) - tries_before )) times within 10 min"
[[ "$(count '재시작 실패')" == 1 ]] || fail "restart-failed message sent $(count '재시작 실패') times within 10 min"
step 20660 || true   # 20060 + 600: one more try, one more message
[[ "$(tries)" == $((tries_before + 2)) && "$(count '재시작 실패')" == 2 ]] || fail "no second try after the cooldown"
# the backend comes back by itself: one recovery message, no restart
touch "$WORK/fake/healthy"
step 20690 || fail "healthy run after a failed restart"
step 20720 || fail "second healthy run after a failed restart"
[[ "$(count '재시작이 실패했던 backend 가 다시 정상')" == 1 ]] || fail "recovery after a failed restart not reported once"
[[ "$(count '재시작 .*초 뒤 확인')" == 1 ]] || fail "a second ✅ (the old good restart's) after the failed one"
[[ "$(count '실행 중으로 남아 있어 10분 뒤 한 번 더')" == 2 ]] || fail "the 🔴 of a container left running does not say it is tried again"
# the stop worked but the start failed: the container is exited and left alone, so the 🔴 asks for compose up
rm "$WORK/fake/healthy"
touch "$WORK/fake/restart-exits"
step 21000; step 21030; step 21060 || true
[[ "$(count 'exited 상태로 남았어요')" == 1 ]] || fail "the 🔴 of an exited container does not say so"
echo running > "$WORK/fake/status"
rm "$WORK/fake/restart-exits" "$WORK/fake/restart-fails"
# a good restart, still failing, then a failed one, then it recovers by itself: one ✅ (the failed one's), not also
# the good restart's "N초 뒤 확인" that would read as if a restart brought it back
good_before="$(count '재시작 .*초 뒤 확인')"
step 22000; step 22030; step 22060 || fail "good restart run"
for t in 22200 22230 22260 22400; do step "$t" || fail "cooldown run $t"; done
touch "$WORK/fake/restart-fails"
step 22660 || true
[[ "$(count '재시작 실패')" == 4 ]] || fail "no failed restart after the good one: $(count '재시작 실패') 🔴"
rm "$WORK/fake/restart-fails"
touch "$WORK/fake/healthy"
step 22700 || fail "recovery after good + failed restart"
[[ "$(count '재시작이 실패했던 backend 가 다시 정상')" == 2 ]] || fail "no ✅ after good + failed restart"
[[ "$(count '재시작 .*초 뒤 확인')" == "$good_before" ]] || fail "two ✅ after good + failed restart"
rm "$WORK/fake/healthy"

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
echo "beside-watchdog.sh: restart after 3 failures, grace, cooldown, recovery, maintenance, crash loop, disk, backup and webhook handling ok"
