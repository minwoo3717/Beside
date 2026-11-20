#!/usr/bin/env bash
# Runpod Pod: keep the inference service running. start_pod.sh starts this loop instead of the service itself.
# It runs COMMAND, and when that process exits (crash, out of memory, kill -9) runs it again with the same environment
# and working directory after a delay: RESTART_DELAY_S, doubled after every run shorter than RESTART_STABLE_S, at most
# RESTART_MAX_DELAY_S (5 s -> 10 -> 20 -> 40 -> 60 -> 60 s; back to 5 s once a run lasted 300 s).
# It restarts only a process that exited: a live but stuck service keeps its pid and is left alone. If GET /healthz
# stops answering too (SIGSTOP), the backend's InferenceHealthMonitor reads DOWN after its 2 s timeout (and, with a
# webhook set, posts the GPU message). If /healthz still answers while inference is stuck, the backend still reads UP
# and each job fails INFERENCE_TIMEOUT after inference.timeout-ms (600 s), but /healthz busyForMs (age of the model
# predict or passthrough simulated generation still running) passes inference.stuck-alert-ms (120 s) and, with a
# webhook set, the backend posts one stuck-run message (R6; tested in the backend and inference-service suites, not
# yet seen on a real Pod). A hang in conversion or rigging is not in busyForMs: only the job timeout catches it.
# A model that failed to load (failure drill FD18) leaves a live process too: /healthz then names it in loadError and
# the backend posts one model-load message, while jobs wait PENDING (same tests, not yet seen on a real Pod).
# None of these restarts anything: kill -9 $(cat /var/run/beside/inference.pid) and this loop starts it again.
#
#   supervise_inference.sh COMMAND [ARG...]        (deploy/runpod/test_start_pod_supervisor.sh runs a fake COMMAND)
#
# Files (RUN_DIR /var/run/beside, LOG_DIR /workspace/beside-logs, NORESTART_FILE /workspace/beside-norestart):
#   RUN_DIR/inference.pid    the service's pid, rewritten at every start. Model mode's start_inference_wsl.sh execs
#                            uvicorn, so this is the uvicorn process (the one to kill or SIGSTOP). Between an exit and
#                            the restart it still names the exited process (start_pod.sh sees a start-up crash at
#                            once); removed when this loop ends.
#   RUN_DIR/supervisor.pid   this loop's pid
#   RUN_DIR/supervisor.lock  locked (flock) while this loop runs: a second supervisor refuses to start
#   LOG_DIR/supervisor.log   one UTC-stamped line per start, exit and restart
#   NORESTART_FILE           while it exists an exit is not restarted and this loop ends. To stop the service on purpose
#                            (switch MODE, change settings, F2 / FD18 drills):
#                              touch /workspace/beside-norestart && kill $(cat /var/run/beside/inference.pid)
#                            start_pod.sh removes the file when it starts the service again.
# kill <supervisor pid> (SIGTERM) passes SIGTERM on to the service, waits for it to exit and ends this loop.
# ps shows the service command in this loop's arguments too: stop things by pid, not with pkill -f.
set -uo pipefail

WS="${WORKSPACE:-/workspace}"
RUN_DIR="${RUN_DIR:-/var/run/beside}"
LOG_DIR="${LOG_DIR:-$WS/beside-logs}"
NORESTART_FILE="${NORESTART_FILE:-$WS/beside-norestart}"
DELAY0="${RESTART_DELAY_S:-5}"
MAX_DELAY="${RESTART_MAX_DELAY_S:-60}"
STABLE_S="${RESTART_STABLE_S:-300}"
PID_FILE="$RUN_DIR/inference.pid"
SUP_PID_FILE="$RUN_DIR/supervisor.pid"
LOG="$LOG_DIR/supervisor.log"

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
log() { printf '%s %s\n' "$(now)" "$*" >> "$LOG"; }
die() { echo "ERROR: supervise_inference.sh: $*" >&2; [[ -d "$LOG_DIR" ]] && log "ERROR: $*"; exit 2; }
seconds() { [[ "$2" =~ ^[1-9][0-9]{0,4}$ ]] || die "$1 must be whole seconds, 1-99999 (got: $2)"; }

(( $# > 0 )) || die "usage: supervise_inference.sh COMMAND [ARG...]"
seconds RESTART_DELAY_S "$DELAY0"
seconds RESTART_MAX_DELAY_S "$MAX_DELAY"
seconds RESTART_STABLE_S "$STABLE_S"
(( MAX_DELAY >= DELAY0 )) || die "RESTART_MAX_DELAY_S ($MAX_DELAY) is below RESTART_DELAY_S ($DELAY0)"
mkdir -p "$RUN_DIR" "$LOG_DIR" || die "cannot create $RUN_DIR and $LOG_DIR"

# One supervisor at a time: two would each restart the service and fight over the port and the pid file.
# The service and the delay's sleep close fd 8 (8>&-), so a killed supervisor does not leave the lock behind.
exec 8>> "$RUN_DIR/supervisor.lock"
flock -w 2 8 ||
  die "another supervisor is running (pid $(cat "$SUP_PID_FILE" 2>/dev/null || echo unknown), lock $RUN_DIR/supervisor.lock)"
echo $$ > "$SUP_PID_FILE"

child=""      # the running service, while there is one
last=""       # the latest service pid
stopping=0
on_term() {   # SIGTERM/SIGINT: pass it on (and SIGCONT, in case the service is stopped), then end after it exits
  stopping=1
  if [[ -n "$child" ]]; then kill -TERM "$child" 2>/dev/null; kill -CONT "$child" 2>/dev/null; fi
  return 0
}
cleanup() {
  [[ "$(cat "$SUP_PID_FILE" 2>/dev/null)" == "$$" ]] && rm -f "$SUP_PID_FILE"
  if [[ -n "$last" && "$(cat "$PID_FILE" 2>/dev/null)" == "$last" ]] && ! kill -0 "$last" 2>/dev/null; then
    rm -f "$PID_FILE"
  fi
  return 0
}
trap on_term TERM INT
trap cleanup EXIT

delay="$DELAY0"
restarts=0
while :; do
  started="$(date +%s)"
  "$@" 8>&- &
  child=$!
  last="$child"
  echo "$child" > "$PID_FILE.tmp" && mv -f "$PID_FILE.tmp" "$PID_FILE"   # never a half-written pid for readers
  if (( restarts == 0 )); then
    log "start: service pid $child, supervisor pid $$: $*"
  else
    log "restart $restarts: service pid $child"
  fi
  (( stopping )) && on_term   # a signal that came before $child was set
  # wait returns early when a trapped signal arrives: the service is gone only once kill -0 fails.
  # 127: no such child any more (it was reaped), or the command itself was not found.
  while :; do
    wait "$child"
    rc=$?
    (( rc == 127 )) && break
    kill -0 "$child" 2>/dev/null || break
  done
  child=""
  ran=$(( $(date +%s) - started ))
  how="status $rc"
  (( rc > 128 )) && how="status $rc, signal $(( rc - 128 ))"
  if (( stopping )); then
    log "service pid $last exited ($how) after SIGTERM to the supervisor - supervisor ends"
    exit 0
  fi
  if [[ -e "$NORESTART_FILE" ]]; then
    log "service pid $last exited ($how) after $ran s; $NORESTART_FILE exists - not restarting, supervisor ends"
    exit 0
  fi
  (( ran >= STABLE_S )) && delay="$DELAY0"
  restarts=$(( restarts + 1 ))
  log "service pid $last exited ($how) after $ran s; restart $restarts in $delay s"
  if (( ! stopping )); then   # sleep in the background so SIGTERM ends the delay at once
    sleep "$delay" 8>&- &
    sleeper=$!
    wait "$sleeper" 2>/dev/null
    kill "$sleeper" 2>/dev/null
    wait "$sleeper" 2>/dev/null
  fi
  if (( stopping )); then
    log "SIGTERM during the restart delay - supervisor ends"
    exit 0
  fi
  if [[ -e "$NORESTART_FILE" ]]; then
    log "$NORESTART_FILE appeared during the restart delay - not restarting, supervisor ends"
    exit 0
  fi
  delay=$(( delay * 2 ))
  (( delay > MAX_DELAY )) && delay="$MAX_DELAY"
  printf '\n=== %s supervise_inference.sh restart %d ===\n' "$(now)" "$restarts"   # into inference.log, like start_pod.sh
done
