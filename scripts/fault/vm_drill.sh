#!/usr/bin/env bash
# Failure drills on the Beside VM (scripts/fault/README.md). Copy this directory to ~/fault on the VM; never switch the
# branch of ~/Beside (it is the production compose source) and never --build during drills.
#
#   bash ~/fault/vm_drill.sh <command> [args]
#
# Setup:   init                      write ~/fault/stub-drill.env (drill-only token, mode 600) from ~/beside-stub/stub.env
#          stub [K=V ...]            (re)create beside-stub-drill on beside_default with extra BESIDE_PASSTHROUGH_* knobs
#          up stub|badtoken|pod|loop [K=V ...]   bring up the drill backend (load overlay + drill overlay) and verify it
# Inject:  fd1 | fd2 [s] | fd7 | fd10 [s] | fd11 [s] | pause-stub [s]
# FD5:     fd5-wipe | fd5-restore    (load-test volume only)
# FD4:     loop-setup | loop-fill <free MB> | loop-teardown
# Record:  mark <text> | collect <dir> | state
#
# Every injection arms its own revert first (systemd-run timer), so an interrupted session does not leave the fault in.
# Secrets: tokens are read into shell variables or env files only, never printed. Load-test rules: deploy/loadtest/README.md.
set -uo pipefail
source ~/lt-env.sh                       # cd ~/Beside, C (prod compose), L (C + load overlay), D (public base URL)
F=~/fault
DR="$L -f $F/docker-compose.drill.yml"
TL=$F/out/timeline.txt
POD_IP=100.83.86.62
mkdir -p "$F/out"

ts() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }
mark() { echo "$(ts) $*" | tee -a "$TL"; }
hz() { curl -s -o /dev/null -w '%{http_code}' --max-time 5 --resolve beside-team.duckdns.org:443:127.0.0.1 "$D/api/v1/healthz"; }
wait_hz() {   # wait_hz <label> [max s]: poll loopback healthz every 0.5 s until 200, record the first 200
  local label="$1" max="${2:-300}" end=$((SECONDS + ${2:-300}))
  while (( SECONDS < end )); do
    [[ "$(hz)" == 200 ]] && { mark "$label healthz 200"; return 0; }
    sleep 0.5
  done
  mark "$label healthz not 200 within ${max}s"; return 1
}
need_load_backend() {   # refuse to inject into the production backend
  local m; m="$(docker inspect -f '{{range .Mounts}}{{if .Name}}{{.Name}}{{else}}{{.Source}}{{end}} -> {{.Destination}}{{println}}{{end}}' beside-backend-1)"
  grep -qE '^(beside-loadtest-storage|/mnt/lt-full) -> /app/storage' <<<"$m" || { echo "backend is not the drill backend:"; echo "$m"; exit 3; }
}
drill_token() { sed -n 's/^BESIDE_INFERENCE_TOKEN=//p' "$F/stub-drill.env" | tr -d '\r'; }

cmd="${1:-}"; shift || true
case "$cmd" in
init)
  [[ -f "$F/stub-drill.env" ]] && { echo "stub-drill.env exists"; exit 0; }
  umask 077
  grep -v '^BESIDE_INFERENCE_TOKEN=' ~/beside-stub/stub.env > "$F/stub-drill.env"
  echo "BESIDE_INFERENCE_TOKEN=$(openssl rand -hex 32)" >> "$F/stub-drill.env"
  chmod 600 "$F/stub-drill.env"
  echo "keys: $(cut -d= -f1 "$F/stub-drill.env" | tr '\n' ' ')"
  ;;
stub)
  args=()
  for kv in "$@"; do args+=(-e "BESIDE_PASSTHROUGH_$kv"); done
  docker rm -f beside-stub-drill >/dev/null 2>&1
  # Same code and sample GLB mounts as the existing beside-stub (read-only), own token and knobs.
  docker run -d --name beside-stub-drill --network beside_default --restart no --env-file "$F/stub-drill.env" \
    -v "$HOME/Beside/generation/inference_service:/svc:ro" -v "$HOME/beside-stub/sample.glb:/data/sample.glb:ro" \
    "${args[@]}" beside-stub:local uvicorn app:app --host 0.0.0.0 --port 8001 >/dev/null
  for i in $(seq 1 30); do
    docker exec beside-stub-drill python -c "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8001/healthz',timeout=2).read().decode())" 2>/dev/null && break
    sleep 1
  done
  mark "stub-drill up $*"
  ;;
up)
  variant="${1:?stub|badtoken|pod|loop}"; shift
  files="-f $F/docker-compose.drill.yml"
  [[ "$variant" == loop ]] && files="$files -f $F/docker-compose.drill-loop.yml"
  (
    export LOADTEST_PROFILES=real,prod LOADTEST_INFERENCE_TIMEOUT_MS=120000 LOADTEST_INFERENCE_OFFLINE_WAIT_MS=300000
    case "$variant" in
      stub|loop) export LOADTEST_INFERENCE_BASE_URL=http://beside-stub-drill:8001 LOADTEST_INFERENCE_TOKEN="$(drill_token)" ;;
      badtoken)  export LOADTEST_INFERENCE_BASE_URL=http://beside-stub-drill:8001 LOADTEST_INFERENCE_TOKEN="$(openssl rand -hex 32)" ;;
      pod)
        tok="$(sed -n 's/^INFERENCE_TOKEN=//p' deploy/.env | tr -d '\r')"
        [[ -n "$tok" ]] || { echo "no INFERENCE_TOKEN in deploy/.env"; exit 2; }
        export LOADTEST_INFERENCE_BASE_URL="http://$POD_IP:8002" LOADTEST_INFERENCE_TOKEN="$tok"
        # production values: application-real.properties (timeout 120 s since 2026-11-21, REVIEW_CHECKLIST #7; was 600 s)
        export LOADTEST_INFERENCE_TIMEOUT_MS=120000 LOADTEST_INFERENCE_OFFLINE_WAIT_MS=1800000
        export LOADTEST_PREPROCESS_CHECK_ENABLED=true LOADTEST_PREPROCESS_CUTOUT_ENABLED=true
        unset tok ;;
      *) echo "unknown variant $variant"; exit 2 ;;
    esac
    for kv in "$@"; do export "$kv"; done
    $L $files up -d backend 2>&1 | tail -n 2
  ) || exit $?
  need_load_backend
  $L exec -T backend sh -c 'env | grep -E "^(SPRING_PROFILES_ACTIVE|METRICS_ENV|NOTIFY_WEBHOOKURL|STORAGE_BACKUP_|PREPROCESS_|INFERENCE_)" | sed -E "s/^(INFERENCE_TOKEN)=.+/\1=(set, hidden)/" | sort'
  wait_hz "up $variant $*" 120
  ;;
fd1)
  need_load_backend
  pid=$(docker inspect -f '{{.State.Pid}}' beside-backend-1); rc=$(docker inspect -f '{{.RestartCount}}' beside-backend-1)
  mark "FD1 kill -9 backend java pid $pid (restarts $rc)"
  sudo kill -9 "$pid"
  sleep 1
  wait_hz "FD1" 180
  mark "FD1 exit=$(docker inspect -f '{{.State.ExitCode}}' beside-backend-1) restarts=$(docker inspect -f '{{.RestartCount}}' beside-backend-1) started=$(docker inspect -f '{{.State.StartedAt}}' beside-backend-1)"
  ;;
fd2)
  need_load_backend
  secs="${1:-180}"; pid=$(docker inspect -f '{{.State.Pid}}' beside-backend-1)
  sudo systemd-run --quiet --unit "fd2-cont-$(date +%s)" --on-active="${secs}s" /bin/kill -CONT "$pid"
  mark "FD2 SIGSTOP backend pid $pid for ${secs}s (SIGCONT armed)"
  sudo kill -STOP "$pid"
  end=$((SECONDS + secs + 120)); last=""
  while (( SECONDS < end )); do
    h="$(docker inspect -f '{{.State.Health.Status}}' beside-backend-1)"; st="$(docker inspect -f '{{.State.Status}}/{{.RestartCount}}' beside-backend-1)"
    [[ "$h $st" != "$last" ]] && { mark "FD2 docker health=$h state=$st"; last="$h $st"; }
    [[ "$h" == healthy && $SECONDS -gt $((end - 120)) ]] && break
    sleep 2
  done
  wait_hz "FD2 after SIGCONT" 120
  ;;
fd7)
  pid=$(docker inspect -f '{{.State.Pid}}' beside-caddy-1); rc=$(docker inspect -f '{{.RestartCount}}' beside-caddy-1)
  mark "FD7 kill -9 caddy pid $pid (restarts $rc)"
  sudo kill -9 "$pid"
  sleep 0.3
  wait_hz "FD7" 120
  mark "FD7 exit=$(docker inspect -f '{{.State.ExitCode}}' beside-caddy-1) restarts=$(docker inspect -f '{{.RestartCount}}' beside-caddy-1)"
  ;;
pause-stub)
  secs="${1:-240}"
  sudo systemd-run --quiet --unit "fd12-unpause-$(date +%s)" --on-active="${secs}s" /usr/bin/docker unpause beside-stub-drill
  mark "FD12 docker pause beside-stub-drill for ${secs}s (unpause armed)"
  docker pause beside-stub-drill
  ;;
fd10)
  secs="${1:-120}"
  sudo systemd-run --quiet --unit "fd10-unblock-$(date +%s)" --on-active="${secs}s" /usr/sbin/iptables -D FORWARD -d "$POD_IP/32" -j DROP
  mark "FD10 iptables FORWARD drop to $POD_IP for ${secs}s (removal armed)"
  sudo iptables -I FORWARD 1 -d "$POD_IP/32" -j DROP
  $L exec -T backend curl -s -o /dev/null -w 'FD10 check from backend: %{http_code}\n' --max-time 5 "http://$POD_IP:8002/healthz" || true
  ;;
fd11)
  secs="${1:-180}"
  sudo systemd-run --quiet --unit "fd11-netem-del-$(date +%s)" --on-active="${secs}s" /usr/sbin/tc qdisc del dev tailscale0 root
  mark "FD11 netem tailscale0 delay 300ms loss 5% for ${secs}s (removal armed)"
  sudo tc qdisc add dev tailscale0 root netem delay 300ms loss 5%
  ;;
fd5-wipe)
  need_load_backend
  $L stop backend 2>&1 | tail -n 1
  mark "FD5 backend stopped; deleting the load-test DB file"
  docker run --rm -v beside-loadtest-storage:/s alpine:3.22 sh -c 'ls -la /s/db /s/backup; rm -f /s/db/beside-real.mv.db /s/db/beside-real.trace.db; ls -la /s/db'
  ;;
fd5-restore)
  zip="${1:?zip name in storage/backup}"
  [[ "$zip" =~ ^beside-[0-9-]+\.zip$ ]] || { echo "bad zip name"; exit 2; }
  $L stop backend 2>&1 | tail -n 1
  mark "FD5 restore $zip start"
  docker run --rm -v beside-loadtest-storage:/app/storage --entrypoint sh beside-backend:local -c \
    'rm -f /app/storage/db/beside-real.mv.db /app/storage/db/beside-real.trace.db && java -Dloader.main=org.h2.tools.Restore -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher -file "/app/storage/backup/'"$zip"'" -dir /app/storage/db -db beside-real && ls -la /app/storage/db'
  mark "FD5 restore $zip files in place"
  ;;
loop-setup)
  sudo fallocate -l 300M /var/tmp/lt-full.img
  sudo mkfs.ext4 -q -m 0 -F /var/tmp/lt-full.img
  sudo mkdir -p /mnt/lt-full
  sudo mount -o loop /var/tmp/lt-full.img /mnt/lt-full
  sudo chown 10001:10001 /mnt/lt-full
  df -h /mnt/lt-full | tail -1
  ;;
loop-fill)
  free_mb="${1:-15}"
  docker exec beside-backend-1 sh -c 'df -m /app/storage | tail -1' | awk '{ if ($2 > 1024) { print "storage is " $2 " MB > 1 GB: not the loop device"; exit 3 } }' || exit 3
  avail=$(df -m /mnt/lt-full | tail -1 | awk '{print $4}')
  fill=$((avail - free_mb)); (( fill > 0 )) || { echo "only $avail MB free"; exit 0; }
  sudo fallocate -l "${fill}M" /mnt/lt-full/filler.bin
  mark "FD4 filler ${fill} MB, $(df -m /mnt/lt-full | tail -1 | awk '{print $4}') MB left"
  ;;
loop-free)
  sudo rm -f /mnt/lt-full/filler.bin
  mark "FD4 filler removed, $(df -m /mnt/lt-full | tail -1 | awk '{print $4}') MB free"
  ;;
loop-teardown)
  $L stop backend 2>&1 | tail -n 1
  $L rm -s -f backend 2>&1 | tail -n 1
  sudo umount /mnt/lt-full && sudo rm -f /var/tmp/lt-full.img && echo "loop removed"
  ;;
mark) mark "$*" ;;
state)
  echo "backend: $(docker inspect -f '{{.State.Status}} health={{.State.Health.Status}} restarts={{.RestartCount}} started={{.State.StartedAt}}' beside-backend-1)"
  echo "caddy:   $(docker inspect -f '{{.State.Status}} restarts={{.RestartCount}}' beside-caddy-1)"
  echo "stub-drill: $(docker inspect -f '{{.State.Status}} paused={{.State.Paused}}' beside-stub-drill 2>/dev/null)"
  echo "healthz: $(hz)"
  sudo iptables -S FORWARD | grep -c "$POD_IP" | sed 's/^/iptables rules to pod: /'
  tc qdisc show dev tailscale0 | head -1
  ;;
collect)
  out="$F/out/${1:?dir}"; mkdir -p "$out"
  since="${2:-30m}"
  $L logs --no-log-prefix --since "$since" backend > "$out/backend.log" 2>&1
  docker logs --since "$since" beside-stub-drill > "$out/stub.log" 2>&1 || true
  docker run --rm -v beside-loadtest-storage:/s:ro alpine:3.22 cat /s/events.jsonl > "$out/events.jsonl" 2>/dev/null || true
  grep -E "inference service|webhook|stage=FAILED|Started MockBackend|InterruptedJobRecovery|ERROR|WARN" "$out/backend.log" | cut -c1-300 > "$out/backend-key.log"
  cp "$TL" "$out/timeline.txt"
  wc -l "$out"/* | tail -n +1
  ;;
*) sed -n '2,20p' "$0"; exit 2 ;;
esac
