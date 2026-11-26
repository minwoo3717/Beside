#!/usr/bin/env bash
# Test of scripts/failover_vm.sh --planned without GCP or VMs: fake gcloud (VM state in files), fake ssh (logs the VM
# by its HostKeyAlias and the host it was sent to, and the remote command), fake curl. Checks: refuses, without stopping
# prod, when the static IP is not on FROM, TO runs a backend, misses an image, cannot list its images, has no build
# marker for FROM's commit (or a 'pending' one), or is not on the tailnet (BESIDE_ALLOW_NO_TAILNET=1 goes on); the steps
# run in order (stop -> copy -> start -> IP detach -> attach -> public check -> roles); the static IP leaves FROM before
# it reaches TO and TO's temporary IP is dropped; SSH goes to the holder of the static IP directly and to the other VM
# on its internal IP; step 6 leaves FROM a marker so the way back passes; DRY_RUN changes nothing; a failed stop and a
# TO that never gets healthy roll back (IP stays on FROM, FROM backend started again).
#   bash scripts/test_failover_vm.sh            (CI, or WSL on the dev PC)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/vm"
fail() { echo "FAIL: $*"; cat "$WORK/out.txt" 2>/dev/null || true; exit 1; }

cat > "$WORK/bin/gcloud" <<'FAKE'
#!/usr/bin/env bash
args=(); for a in "$@"; do [[ "$a" == --project=* || "$a" == --quiet ]] || args+=("$a"); done
set -- "${args[@]}"
printf 'gcloud %s\n' "$*" >> "$FAKE/calls"
V="$FAKE/vm"
arg() { local k="$1"; shift; for a in "$@"; do [[ "$a" == "$k="* ]] && { printf '%s' "${a#*=}"; return; }; done; }
case "$1 $2 $3" in
  "config get-value project") echo beside-test ;;
  "compute instances list")
    name="$(arg --filter "$@")"; name="${name#name=}"; field="$(arg --format "$@")"; field="${field#value(}"; field="${field%)}"
    case "$field" in
      zone) cat "$V/$name.zone" ;;
      status) echo RUNNING ;;
      'networkInterfaces[0].networkIP') cat "$V/$name.ip" ;;
      'networkInterfaces[0].accessConfigs[0].name') [[ -s "$V/$name.ext" ]] && echo external-nat || true ;;
      'networkInterfaces[0].accessConfigs[0].natIP') cat "$V/$name.ext" 2>/dev/null || true ;;
      'disks[0].source') echo "projects/p/zones/z/disks/$name-boot" ;;
    esac ;;
  "compute addresses describe")
    if [[ "$*" == *'users[0]'* ]]; then cat "$V/ip-user"; else echo 34.64.91.254; fi ;;
  "compute instances delete-access-config")
    [[ "$(cat "$V/$4.ext" 2>/dev/null)" == 34.64.91.254 ]] && : > "$V/ip-user"
    : > "$V/$4.ext" ;;
  "compute instances add-access-config")
    [[ -s "$V/$4.ext" ]] && { echo "already has an access config" >&2; exit 1; }
    echo 34.64.91.254 > "$V/$4.ext"; echo "projects/p/zones/z/instances/$4" > "$V/ip-user" ;;
  "compute disks add-resource-policies") ;;
  *) echo "fake gcloud: unexpected $*" >&2; exit 3 ;;
esac
FAKE
cat > "$WORK/bin/ssh" <<'FAKE'
#!/usr/bin/env bash
alias=""; cmd=""; host=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) [[ "$2" == HostKeyAlias=* ]] && alias="${2#HostKeyAlias=}"; shift 2 ;;
    -i) shift 2 ;;
    beside@*) host="${1#beside@}"; shift; cmd="$*"; break ;;
    *) shift ;;
  esac
done
printf 'ssh %s: %s\n' "$alias" "$cmd" >> "$FAKE/calls"
printf '%s %s\n' "$alias" "$host" >> "$FAKE/hosts"
in_list() { [[ " $2 " == *" $1 "* ]]; }
case "$cmd" in
  *healthz*)
    if [[ "$alias" == "${FAKE_UNHEALTHY:-none}" ]]; then echo '{"status":"down"}'; else echo '{"status":"ok"}'; fi ;;
  *"docker ps -q"*) [[ "$alias" == "${FAKE_TO_RUNNING:-none}" ]] && echo abc123 || true ;;
  *"config --images"*)
    [[ "$alias" == "${FAKE_IMG_LIST_FAIL:-none}" ]] && exit 1
    [[ "$alias" == "${FAKE_MISSING_IMG:-none}" ]] && echo grafana/tempo:2.6.1 || true ;;
  *"cat ~/.beside-built"*)
    if in_list "$alias" "${FAKE_MARKED:-beside-standby}"; then echo "${FAKE_BUILT_FOR:-1234567890abcdef} 2026-11-26T10:00:00Z"; fi ;;
  *"compose"*"stop backend"*) [[ "$alias" == "${FAKE_STOP_FAIL:-none}" ]] && exit 1 || true ;;
  *"rev-parse HEAD"*) echo 1234567890abcdef ;;
  *"tailscale status"*) if in_list "$alias" "${FAKE_NO_TAILNET:-}"; then echo "Logged out."; else echo "100.70.1.2 $alias"; fi ;;
  *"tar -C"*"-cf -"*) printf 'DATA' ;;
  *"tar -C"*"-xf -"*) cat > "$FAKE/received-$alias" ;;
  *"cat ~/Beside/deploy/.env"*) printf 'NOTIFY_WEBHOOK_URL=https://secret\n' ;;
  *"cat > ~/Beside/deploy/.env.new"*) cat > "$FAKE/env-$alias" ;;
  *) : ;;
esac
FAKE
cat > "$WORK/bin/curl" <<'FAKE'
#!/usr/bin/env bash
for a in "$@"; do [[ "$a" == *healthz ]] && { echo '{"status":"ok"}'; exit 0; }; done
echo 401
FAKE
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH" FAKE="$WORK" BESIDE_TIMELINE_DIR="$WORK" BESIDE_WAIT_TRIES=2 BESIDE_WAIT_SLEEP=0
FO="$HERE/failover_vm.sh"

reset() {
  : > "$WORK/calls"; : > "$WORK/hosts"; rm -f "$WORK/received-"* "$WORK/env-"* "$WORK/"failover-*.tsv
  echo asia-northeast3-a > "$WORK/vm/beside-vm.zone"; echo 10.178.0.2 > "$WORK/vm/beside-vm.ip"
  echo 34.64.91.254 > "$WORK/vm/beside-vm.ext"
  echo asia-northeast3-b > "$WORK/vm/beside-standby.zone"; echo 10.178.0.9 > "$WORK/vm/beside-standby.ip"
  echo 34.22.1.1 > "$WORK/vm/beside-standby.ext"   # temporary IP (for its Tailscale login)
  echo "projects/p/zones/asia-northeast3-a/instances/beside-vm" > "$WORK/vm/ip-user"
}
order() {   # the timeline steps must appear in this order
  local tl; tl="$(ls "$WORK"/failover-*.tsv)"
  [[ "$(cut -f3 "$tl" | uniq | tr '\n' ' ')" == "check 1-stop 2-copy 3-start 4-ip 5-public 6-roles " ]] \
    || fail "timeline order: $(cut -f3 "$tl" | uniq | tr '\n' ' ')"
}

stopped_prod() { grep -q "ssh beside-vm: .*stop backend" "$WORK/calls"; }
before() {   # before <pattern A> <pattern B>: both calls exist and A comes first
  local a b
  a="$(grep -n -m1 -- "$1" <<<"$calls" | cut -d: -f1)"; b="$(grep -n -m1 -- "$2" <<<"$calls" | cut -d: -f1)"
  [[ -n "$a" && -n "$b" && "$a" -lt "$b" ]]
}

# ---- refusals: none of them may stop prod
reset
echo "projects/p/zones/asia-northeast3-b/instances/beside-standby" > "$WORK/vm/ip-user"
if bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1; then fail "ran with the IP on the target"; fi
if bash "$FO" --planned beside-vm > "$WORK/out.txt" 2>&1; then fail "ran without a target"; fi
for c in "FAKE_TO_RUNNING=beside-standby|the target runs a backend" \
         "FAKE_MISSING_IMG=beside-standby|an image is missing on the target" \
         "FAKE_IMG_LIST_FAIL=beside-standby|the image listing of the target failed" \
         "FAKE_BUILT_FOR=deadbeefdeadbeef|the target images were built for another commit" \
         "FAKE_BUILT_FOR=pending|the sync of the target was not finished" \
         "FAKE_MARKED=none|the target has no build marker" \
         "FAKE_NO_TAILNET=beside-standby|the target is not on the tailnet"; do
  reset
  if env "${c%%|*}" bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1; then fail "ran although ${c#*|}"; fi
  stopped_prod && fail "a refused run (${c#*|}) stopped prod"
done
reset
FAKE_MISSING_IMG=beside-standby bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1 || true
grep -q "grafana/tempo" "$WORK/out.txt" || fail "missing image not named"

# ---- tailnet: a person may decide to go on; a dry run says it would stop
reset
FAKE_NO_TAILNET=beside-standby BESIDE_ALLOW_NO_TAILNET=1 bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1 \
  || fail "BESIDE_ALLOW_NO_TAILNET=1 did not go on"
reset
FAKE_NO_TAILNET=beside-standby DRY_RUN=1 bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1 \
  || fail "dry run without tailnet"
grep -q "would STOP" "$WORK/out.txt" || fail "dry run did not say a real run would stop without tailnet"

# ---- dry run: read-only checks only
reset
DRY_RUN=1 bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1 || fail "dry run"
grep -E "access-config|stop backend|tar -C|> ~/.beside-built|rm -f ~/.beside-built" "$WORK/calls" && fail "dry run changed something: $(cat "$WORK/calls")"
grep -q "add-access-config beside-standby" "$WORK/out.txt" || fail "dry run did not print the IP move"
ls "$WORK"/failover-*.tsv > /dev/null 2>&1 && fail "dry run wrote a timeline"

# ---- the real path (D-1)
reset
bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1 || fail "failover"
order
calls="$(cat "$WORK/calls")"
before 'ssh beside-vm: sudo touch /etc/beside-maintenance' 'tar -C' || fail "copied before stopping prod"
before 'delete-access-config beside-vm' 'add-access-config beside-standby' || fail "IP order"
before 'delete-access-config beside-standby' 'add-access-config beside-standby' || fail "temporary IP kept"
grep -q -- "--address=34.64.91.254" <<<"$(grep 'add-access-config beside-standby' <<<"$calls")" || fail "static IP not attached"
[[ "$(cat "$WORK/received-beside-standby")" == DATA ]] || fail "data not copied to the target"
grep -q "secret" "$WORK/env-beside-standby" || fail ".env not copied"
grep -q "secret" "$WORK/out.txt" && fail ".env printed"
grep -q "ssh beside-standby: printf 'active.*rm -f ~/.beside-built" <<<"$calls" || fail "target role / build marker removal"
grep -q "ssh beside-vm: docker compose .* stop; sudo systemctl disable --now beside-watchdog.timer" <<<"$calls" || fail "old prod not stopped"
grep -q "ssh beside-vm: .*printf 'standby.*printf '%s %s\\\\n' 1234567890abcdef .* > ~/.beside-built" <<<"$calls" \
  || fail "old prod has no build marker for the way back"
grep -q "add-resource-policies beside-standby-boot" <<<"$calls" || fail "snapshot schedule not on the new disk"
# the fake ssh does not run the remote line, so check what it would be: a failed 'compose config' must not read as
# "nothing missing" (pipefail and a non-empty list)
grep -q 'set -o pipefail; imgs=$(docker compose .* config --images | sort -u) && \[ -n "$imgs" \]' <<<"$calls" \
  || fail "image listing of the target without pipefail / empty check"
[[ "$(cat "$WORK/vm/ip-user")" == *beside-standby ]] || fail "IP user"
# SSH routing: the holder of the static IP directly, the other one through it to its internal IP
grep -qx "beside-standby 10.178.0.9" "$WORK/hosts" || fail "target not reached on its internal IP before the move"
grep -qx "beside-standby 34.64.91.254" "$WORK/hosts" || fail "target not reached on the static IP after the move"
grep -qx "beside-vm 10.178.0.2" "$WORK/hosts" || fail "old prod not reached on its internal IP after the move"

# ---- the way back (D-3): needs the marker that step 6 leaves on the old main server
reset
echo "projects/p/zones/asia-northeast3-b/instances/beside-standby" > "$WORK/vm/ip-user"
echo 34.64.91.254 > "$WORK/vm/beside-standby.ext"; : > "$WORK/vm/beside-vm.ext"
if DRY_RUN=1 bash "$FO" --planned beside-standby beside-vm > "$WORK/out.txt" 2>&1; then
  fail "went back to a server without a build marker"; fi
FAKE_MARKED=beside-vm DRY_RUN=1 bash "$FO" --planned beside-standby beside-vm > "$WORK/out.txt" 2>&1 \
  || fail "dry run back to beside-vm with its marker"

# ---- step 1 fails -> rollback (prod restarted, nothing copied)
reset
if FAKE_STOP_FAIL=beside-vm bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1; then
  fail "no rollback exit after a failed stop"; fi
calls="$(cat "$WORK/calls")"
grep -q "ssh beside-vm: docker compose .* up -d backend && sudo rm -f /etc/beside-maintenance" <<<"$calls" \
  || fail "prod not restarted after a failed stop"
grep -q "tar -C" <<<"$calls" && fail "copied after a failed stop"

# ---- target never healthy -> rollback
reset
if FAKE_UNHEALTHY=beside-standby bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1; then
  fail "no rollback exit"; fi
calls="$(cat "$WORK/calls")"
grep -q "add-access-config beside-standby" <<<"$calls" && fail "IP moved although the target was unhealthy"
grep -q "ssh beside-vm: docker compose .* up -d backend" <<<"$calls" || fail "prod backend not restarted"
grep -q "ssh beside-standby: docker compose .* stop" <<<"$calls" || fail "target not stopped"
[[ "$(cat "$WORK/vm/ip-user")" == *beside-vm ]] || fail "IP left prod"
echo "failover_vm: refusals (backend, images, marker, tailnet), order, IP move, routing, copy, roles, way back, dry run and rollbacks ok"
