#!/usr/bin/env bash
# Test of deploy/runpod/supervise_inference.sh and of start_pod.sh running the service under it, without a Pod:
# a fake service (python3; answers GET /healthz when given --port), fake tailscale/tailscaled and a fake venv python,
# all under a temp dir. Nothing goes to the network except 127.0.0.1. Short delays (RESTART_DELAY_S=1,
# RESTART_MAX_DELAY_S=4, RESTART_STABLE_S=3) keep it at about a minute. Needs Linux bash, python3, curl, flock
# (WSL works: tr -d '\r' < deploy/runpod/test_start_pod_supervisor.sh | wsl.exe bash -s -- <repo path in WSL>).
#   bash deploy/runpod/test_start_pod_supervisor.sh [repo]
set -uo pipefail   # no -e: a failed check is counted, not fatal
if [[ -n "${1:-}" ]]; then REPO="$(cd "$1" && pwd)"; else REPO="$(cd "$(dirname "$0")/../.." && pwd)"; fi
SUP="$REPO/deploy/runpod/supervise_inference.sh"
START="$REPO/deploy/runpod/start_pod.sh"
T="$(mktemp -d)"
unset THREADS UVICORN_TIMEOUT_KEEP_ALIVE MODE PORT WORKSPACE RUN_DIR LOG_DIR NORESTART_FILE BESIDE_INFERENCE_TOKEN \
  RESTART_DELAY_S RESTART_MAX_DELAY_S RESTART_STABLE_S RUNPOD_POD_ID ALLOW_NO_GPU SAMPLE_GLB TOKEN_FILE

cleanup() {
  # every process this test started has $T in its command line; supervisors first, so none starts a service after
  pkill -9 -f -- "supervise_inference\.sh .*$T/" 2>/dev/null
  sleep 0.2
  pkill -9 -f -- "$T/" 2>/dev/null
  rm -rf "$T"
}
trap cleanup EXIT

checks=0
fails=0
ok() { checks=$((checks + 1)); echo "ok   $*"; }
bad() { checks=$((checks + 1)); fails=$((fails + 1)); echo "FAIL $*"; }
check() { local what="$1"; shift; if "$@"; then ok "$what"; else bad "$what"; return 1; fi; }
wait_for() {   # wait_for SECONDS COMMAND...: poll every 0.1 s
  local n=$(( $1 * 10 )); shift
  for _ in $(seq 1 "$n"); do "$@" && return 0; sleep 0.1; done
  "$@"
}
alive() { [[ "${1:-}" =~ ^[0-9]+$ ]] && kill -0 "$1" 2>/dev/null; }
dead() { ! alive "$1"; }
end_sup() {   # end_sup PID (a supervisor started here): rc = its exit status; still running after 5 s: killed, rc=running
  wait_for 5 dead "$1"
  if alive "$1"; then
    kill -9 "$1"; wait "$1" 2>/dev/null; pkill -9 -f -- "$FAKE"; rc=running   # so the next part starts clean
  else
    wait "$1"; rc=$?
  fi
}
has() { grep -qF -- "$2" "$1" 2>/dev/null; }     # has FILE TEXT
lines() { if [[ -f "$1" ]]; then wc -l < "$1" | tr -d ' '; else echo 0; fi; }
lock_free() { flock -n "$1/supervisor.lock" true; }
env_of() { tr '\0' '\n' < "/proc/$1/environ" | grep -qx -- "$2"; }   # env_of PID NAME=VALUE

# The fake service: records "pid" in FAKE_STARTS, exits 3 at once while FAKE_CRASH exists, ends with status 0 on SIGTERM
# like uvicorn, and with --port serves GET /healthz on 127.0.0.1.
FAKE="$T/fake_service.py"
cat > "$FAKE" <<'PY'
import http.server, os, signal, sys, time
signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))   # before the start record: a recorded start ends with 0
with open(os.environ["FAKE_STARTS"], "a") as f:
    f.write(f"{os.getpid()}\n")
if os.path.exists(os.environ["FAKE_CRASH"]):
    sys.exit(3)
port = int(sys.argv[sys.argv.index("--port") + 1]) if "--port" in sys.argv else None
if port is None:
    while True:
        time.sleep(1)
class Health(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'{"status":"ok"}' if self.path == "/healthz" else b"{}"
        self.send_response(200 if self.path == "/healthz" else 404)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *_):
        pass
http.server.ThreadingHTTPServer(("127.0.0.1", port), Health).serve_forever()
PY

echo "== A. supervise_inference.sh alone (fake service without a port)"
A="$T/a"
mkdir -p "$A"
asup=(env RUN_DIR="$A/run" LOG_DIR="$A/logs" NORESTART_FILE="$A/norestart" RESTART_DELAY_S=1 RESTART_MAX_DELAY_S=4
      RESTART_STABLE_S=3 FAKE_STARTS="$A/starts" FAKE_CRASH="$A/crash" bash "$SUP")
apid() { cat "$A/run/inference.pid" 2>/dev/null || true; }
alog="$A/logs/supervisor.log"
# a_started [OLD]: the pid file names a live service other than OLD, and that service has recorded its start
a_started() { local p; p="$(apid)"; [[ "$p" != "${1:-}" ]] && alive "$p" && [[ "$(tail -n 1 "$A/starts" 2>/dev/null)" == "$p" ]]; }

out="$("${asup[@]}" 2>&1)"; rc=$?
check "no command: exit 2 with usage" test "$rc" = 2
check "no command: says usage" grep -q "usage: supervise_inference.sh COMMAND" <<< "$out"
out="$(env RESTART_DELAY_S=0 RUN_DIR="$A/run0" LOG_DIR="$A/logs0" bash "$SUP" true 2>&1)"; rc=$?
check "RESTART_DELAY_S=0: refused (exit 2)" test "$rc" = 2
check "RESTART_DELAY_S=0: says why" grep -q "RESTART_DELAY_S must be whole seconds" <<< "$out"
out="$(env RESTART_DELAY_S=9 RESTART_MAX_DELAY_S=4 RUN_DIR="$A/run0" LOG_DIR="$A/logs0" bash "$SUP" true 2>&1)"; rc=$?
check "max delay below first delay: refused (exit 2)" test "$rc" = 2

"${asup[@]}" python3 "$FAKE" > "$A/out.log" 2>&1 &
sup=$!
wait_for 5 a_started || true
p1="$(apid)"
check "pid file names the running service" alive "$p1"
check "pid file is the service the supervisor started (its own record)" test "$p1" = "$(tail -n 1 "$A/starts" 2>/dev/null)"
check "supervisor.pid is the supervisor" test "$(cat "$A/run/supervisor.pid" 2>/dev/null)" = "$sup"
check "supervisor.log: start line with the pid" has "$alog" "start: service pid $p1, supervisor pid $sup"
check "supervisor.log lines carry a UTC time" grep -qE '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z start:' "$alog"

out="$("${asup[@]}" python3 "$FAKE" 2>&1)"; rc=$?
check "second supervisor refused (exit 2)" test "$rc" = 2
check "second supervisor names the running one" grep -q "another supervisor is running (pid $sup" <<< "$out"
check "second supervisor started nothing" test "$(lines "$A/starts")" = 1
check "second supervisor left the pid file alone" test "$(apid)" = "$p1"

kill -STOP "$p1"
sleep 2
check "SIGSTOP: no restart (same pid, one start)" test "$(apid):$(lines "$A/starts")" = "$p1:1"
kill -CONT "$p1"

kill -9 "$p1"
wait_for 5 a_started "$p1" || true
p2="$(apid)"
check "kill -9: restarted with a new pid" a_started "$p1"
check "kill -9: pid file names the new service" test "$p2" = "$(tail -n 1 "$A/starts")"
check "supervisor.log: exit status 137 / signal 9" has "$alog" "service pid $p1 exited (status 137, signal 9)"
check "supervisor.log: restart 1 after the first delay (1 s)" has "$alog" "restart 1 in 1 s"
check "supervisor.log: restart line with the new pid" has "$alog" "restart 1: service pid $p2"
check "stdout (inference.log) marks the restart" grep -qE '^=== [0-9T:-]+Z supervise_inference.sh restart 1 ===$' "$A/out.log"

# crash loop: the delay doubles (2, 4) and stops at RESTART_MAX_DELAY_S (4)
touch "$A/crash"
kill -9 "$p2"
wait_for 12 has "$alog" "restart 4 in" || true
rm -f "$A/crash"
delays="$(sed -n 's/.*; restart \([0-9]*\) in \([0-9]*\) s$/\1:\2/p' "$alog" | xargs)"
check "crash loop: delays 1, 2, 4, 4 (doubling, capped) - got: $delays" test "$delays" = "1:1 2:2 3:4 4:4"
check "crash loop: exit status 3 logged" has "$alog" "exited (status 3)"
wait_for 6 a_started "$p2" || true
p3="$(apid)"
check "after the crash loop the service runs again" alive "$p3"

sleep 3.5   # longer than RESTART_STABLE_S: the next delay is the first one again
kill -9 "$p3"
wait_for 4 a_started "$p3" || true
p4="$(apid)"
check "a run longer than RESTART_STABLE_S resets the delay to 1 s" has "$alog" "restart 5 in 1 s"
check "restarted after the reset" a_started "$p3"

# the flag file appears during the delay: no restart, the supervisor ends
starts="$(lines "$A/starts")"
kill -9 "$p4"
wait_for 2 has "$alog" "restart 6 in 2 s" || true
touch "$A/norestart"
wait_for 4 dead "$sup" || true
check "flag during the delay: supervisor ended" dead "$sup"
check "flag during the delay: logged" has "$alog" "$A/norestart appeared during the restart delay - not restarting"
check "flag during the delay: nothing started" test "$(lines "$A/starts")" = "$starts"
check "supervisor ended: pid files removed" test ! -e "$A/run/inference.pid" -a ! -e "$A/run/supervisor.pid"
check "supervisor ended: lock free" lock_free "$A/run"
end_sup "$sup"
check "flag during the delay: supervisor exit status 0 (got $rc)" test "$rc" = 0

# the flag file exists when the service exits (stop on purpose): no restart
"${asup[@]}" python3 "$FAKE" >> "$A/out.log" 2>&1 &
sup=$!
wait_for 5 a_started || true
q1="$(apid)"
starts="$(lines "$A/starts")"
kill -9 "$q1"
wait_for 3 dead "$sup" || true
check "flag + kill -9: supervisor ended" dead "$sup"
check "flag + kill -9: logged as not restarting" has "$alog" "service pid $q1 exited (status 137, signal 9) after"
check "flag + kill -9: says why" has "$alog" "$A/norestart exists - not restarting, supervisor ends"
sleep 1.5
check "flag + kill -9: nothing started" test "$(lines "$A/starts")" = "$starts"
end_sup "$sup"
rm -f "$A/norestart"

# SIGTERM to the supervisor stops the service (even a stopped one) and ends the loop
"${asup[@]}" python3 "$FAKE" >> "$A/out.log" 2>&1 &
sup=$!
wait_for 5 a_started || true
r1="$(apid)"
kill -STOP "$r1"
kill -TERM "$sup"
wait_for 5 dead "$r1" || true
check "SIGTERM to the supervisor: the (stopped) service ended" dead "$r1"
end_sup "$sup"
check "SIGTERM to the supervisor: it ended with status 0 (got $rc)" test "$rc" = 0
check "SIGTERM to the supervisor: logged" has "$alog" "service pid $r1 exited (status 0) after SIGTERM to the supervisor - supervisor ends"
check "SIGTERM to the supervisor: pid files removed" test ! -e "$A/run/inference.pid" -a ! -e "$A/run/supervisor.pid"

# kill -9 of the supervisor: the service keeps running and does not hold the lock
"${asup[@]}" python3 "$FAKE" >> "$A/out.log" 2>&1 &
sup=$!
wait_for 5 a_started || true
s1="$(apid)"
kill -9 "$sup"
wait "$sup" 2>/dev/null || true
check "supervisor killed with -9: the service still runs" alive "$s1"
check "supervisor killed with -9: the lock is free (the service did not inherit it)" lock_free "$A/run"
kill -9 "$s1"

echo "== B. start_pod.sh with the supervisor (MODE=passthrough, fake Tailscale and venv)"
B="$T/b"
WS="$B/ws"
RUN="$B/run"
mkdir -p "$WS/tailscale/bin" "$WS/beside-venv/bin" "$RUN"
echo 1.104.6 > "$WS/tailscale/bin/VERSION"
cat > "$WS/tailscale/bin/tailscaled" <<'SH'
#!/usr/bin/env bash
# fake tailscaled: holds the unix socket until this process goes
for a in "$@"; do case "$a" in --socket=*) sock="${a#--socket=}" ;; esac; done
python3 - "$sock" "$$" <<'PY' &
import os, socket, sys, time
s = socket.socket(socket.AF_UNIX)
s.bind(sys.argv[1])
s.listen(1)
while os.getppid() == int(sys.argv[2]):
    time.sleep(0.5)
PY
wait
SH
cat > "$WS/tailscale/bin/tailscale" <<'SH'
#!/usr/bin/env bash
case " $* " in *" ip "*) echo 100.64.0.7 ;; *" up "*) echo "fake tailscale up" ;; esac
SH
# fake venv python: pip does nothing, "-m uvicorn ..." runs the fake service with the same arguments
printf '%s\n' '#!/usr/bin/env bash' \
  'if [[ "${1:-} ${2:-}" == "-m pip" ]]; then exit 0; fi' \
  "if [[ \"\${1:-} \${2:-}\" == \"-m uvicorn\" ]]; then exec python3 \"$FAKE\" \"\$@\"; fi" \
  'exec python3 "$@"' > "$WS/beside-venv/bin/python"
chmod +x "$WS/tailscale/bin/tailscaled" "$WS/tailscale/bin/tailscale" "$WS/beside-venv/bin/python"
printf 'glTF-placeholder' > "$WS/beside-sample.glb"
PORT="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
FLAG="$WS/beside-norestart"
blog="$WS/beside-logs/supervisor.log"
SERVE="uvicorn app:app --host 127.0.0.1 --port $PORT "

run_pod() {   # run_pod [NAME=value ...]: start_pod.sh with the fakes; output in $B/out, exit status in $rc
  timeout 60 env WORKSPACE="$WS" RUN_DIR="$RUN" PORT="$PORT" MODE=passthrough BESIDE_INFERENCE_TOKEN=test-token-not-a-secret \
    RESTART_DELAY_S=1 RESTART_MAX_DELAY_S=4 RESTART_STABLE_S=3 FAKE_STARTS="$B/starts" FAKE_CRASH="$B/crash" \
    "$@" bash "$START" > "$B/out" 2>&1
  rc=$?
}
bpid() { cat "$RUN/inference.pid" 2>/dev/null || true; }
bsup() { cat "$RUN/supervisor.pid" 2>/dev/null || true; }
healthy() { curl -fs --max-time 2 -o /dev/null "http://127.0.0.1:$PORT/healthz"; }
new_bpid() { local p; p="$(bpid)"; [[ "$p" != "$1" ]] && alive "$p" && healthy; }
is_service() { tr '\0' ' ' < "/proc/$1/cmdline" | grep -qF -- "$SERVE"; }
show() { sed 's/^/     | /' "$B/out"; }

run_pod
check "first run: exit 0" test "$rc" = 0 || show
p1="$(bpid)"
check "first run: [4/4] line with mode, port and the service's settings" \
  has "$B/out" "[4/4] inference service up (passthrough, 127.0.0.1:$PORT, X-Inference-Token required, keep-alive 75 s, threads 8)"
check "first run: names the supervisor and how to stop on purpose" \
  has "$B/out" "supervisor pid $(bsup) restarts it when it exits ($WS/beside-logs/supervisor.log); stop on purpose: touch $FLAG && kill $p1"
check "first run: pid file is the uvicorn process (its command line)" is_service "$p1"
check "first run: healthz answers" healthy
check "first run: the supervisor runs" alive "$(bsup)"
check "service environment: keep-alive 75" env_of "$p1" UVICORN_TIMEOUT_KEEP_ALIVE=75
check "service environment: sample GLB" env_of "$p1" "BESIDE_SAMPLE_GLB=$WS/beside-sample.glb"
check "service working directory: generation/inference_service" \
  test "$(readlink "/proc/$p1/cwd")" = "$(cd "$REPO/generation/inference_service" && pwd -P)"
check "inference.log: start_pod.sh stamp" grep -qE '^=== [0-9T:-]+Z start_pod.sh ===$' "$WS/beside-logs/inference.log"
sup1="$(bsup)"

run_pod
check "second run: exit 0" test "$rc" = 0 || show
check "second run: already running, same settings line, stop hint with the flag" \
  has "$B/out" "[4/4] inference service already running (passthrough, pid $p1, keep-alive 75 s, threads 8, stop: touch $FLAG && kill $p1)"
check "second run: reports the supervisor" has "$B/out" "supervisor pid $sup1 restarts it when it exits"
check "second run: no settings NOTE" bash -c "! grep -q 'other settings' '$B/out'"
check "second run: nothing new started" test "$(lines "$B/starts"):$(bsup)" = "1:$sup1"

run_pod THREADS=4
check "other THREADS while running: NOTE with the stop hint" \
  has "$B/out" "NOTE: it was started with other settings; stop it (touch $FLAG && kill $p1) and run this again for keep-alive 75 s, threads 4"

kill -9 "$p1"
wait_for 6 new_bpid "$p1" || true
p2="$(bpid)"
check "kill -9: back with a new pid and healthz" new_bpid "$p1"
check "kill -9: new pid is the uvicorn process" is_service "$p2"
check "kill -9: same settings after the restart (keep-alive)" env_of "$p2" UVICORN_TIMEOUT_KEEP_ALIVE=75
check "kill -9: same settings after the restart (threads)" env_of "$p2" OMP_NUM_THREADS=8
check "kill -9: same token after the restart" env_of "$p2" BESIDE_INFERENCE_TOKEN=test-token-not-a-secret
check "kill -9: same working directory" test "$(readlink "/proc/$p2/cwd")" = "$(readlink "/proc/$(bsup)/cwd")"
check "kill -9: supervisor.log has the exit and the restart" has "$blog" "service pid $p1 exited (status 137, signal 9)"
check "kill -9: inference.log marks the restart" grep -qE '^=== [0-9T:-]+Z supervise_inference.sh restart 1 ===$' "$WS/beside-logs/inference.log"
run_pod
check "after the restart: start_pod.sh reports the new pid, same settings" \
  has "$B/out" "[4/4] inference service already running (passthrough, pid $p2, keep-alive 75 s, threads 8, stop: touch $FLAG && kill $p2)"

run_pod MODE=model ALLOW_NO_GPU=1
check "other MODE while running: refused" test "$rc" != 0
check "other MODE while running: says how to stop it" \
  has "$B/out" "runs in passthrough mode, MODE is model - stop it (touch $FLAG && kill $p2) and run this again"

# crash loop: start_pod.sh during a restart delay refuses and names the supervisor
touch "$B/crash"
kill -9 "$p2"
wait_for 10 grep -q "in 4 s\$" "$blog" || true
run_pod
check "service down, supervisor waiting to restart: refused" test "$rc" != 0
check "service down, supervisor waiting to restart: names it" \
  has "$B/out" "the inference service is not running but its supervisor (pid $(bsup)) is"
rm -f "$B/crash"
wait_for 8 new_bpid "$p2" || true
p3="$(bpid)"
check "after the crash loop: back" new_bpid "$p2"

# stop on purpose with the flag, then start_pod.sh right away: waits for the supervisor, removes the flag
sup3="$(bsup)"
touch "$FLAG"
kill -9 "$p3"
run_pod
check "flag + kill -9, then start_pod.sh: exit 0" test "$rc" = 0 || show
check "flag + kill -9: the old supervisor ended" dead "$sup3"
check "flag + kill -9: old supervisor logged not restarting" has "$blog" "$FLAG exists - not restarting, supervisor ends"
check "start_pod.sh removed the flag and said so" has "$B/out" "removed $FLAG"
check "flag removed" test ! -e "$FLAG"
check "start_pod.sh started a new service under a new supervisor" has "$B/out" "[4/4] inference service up (passthrough"
p4="$(bpid)"
check "new service healthy" new_bpid "$p3"

# flag + kill -9 keeps it down
touch "$FLAG"
starts="$(lines "$B/starts")"
kill -9 "$p4"
sleep 2.5
check "flag + kill -9: stays down (no start, no healthz)" bash -c "test '$(lines "$B/starts")' = '$starts' && ! curl -fs --max-time 1 -o /dev/null http://127.0.0.1:$PORT/healthz"
check "flag + kill -9: no supervisor left" lock_free "$RUN"
run_pod
check "start again after a stop on purpose: exit 0" test "$rc" = 0 || show
p5="$(bpid)"

# start-up failure: the service exits at once - start_pod.sh fails and stops the supervisor (no endless retries)
kill -TERM "$(bsup)"
wait_for 5 dead "$p5" || true
check "SIGTERM to the supervisor stopped the service" dead "$p5"
wait_for 3 lock_free "$RUN" || true
touch "$B/crash"
run_pod
check "service exits at start-up: start_pod.sh fails" test "$rc" != 0
check "service exits at start-up: says healthz did not answer" has "$B/out" "inference service did not answer /healthz"
check "service exits at start-up: no supervisor left" lock_free "$RUN"
starts="$(lines "$B/starts")"
sleep 2.5
check "service exits at start-up: no further restarts" test "$(lines "$B/starts")" = "$starts"
check "service exits at start-up: no supervisor WARNING (it ended at once)" bash -c "! grep -q 'did not end within' '$B/out'"
rm -f "$B/crash"

# a service without a supervisor (older start_pod.sh, or the supervisor was killed): reported
run_pod
check "start after the failure: exit 0" test "$rc" = 0 || show
p6="$(bpid)"
kill -9 "$(bsup)"
sleep 0.5
run_pod
check "unsupervised service: still reported as running" has "$B/out" "[4/4] inference service already running (passthrough, pid $p6"
check "unsupervised service: NOTE that nothing restarts it" has "$B/out" "NOTE: no supervisor watches it"
kill -9 "$p6"

echo
echo "$checks checks, $fails failed"
(( fails == 0 ))
