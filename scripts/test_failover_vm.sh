#!/usr/bin/env bash
# Test of scripts/failover_vm.sh --planned without GCP or VMs: fake gcloud (VM state in files), fake ssh (logs the VM
# by its HostKeyAlias and the remote command), fake curl. Checks: refuses when the static IP is not on FROM or TO runs a
# backend; the steps run in order (stop -> copy -> start -> IP detach -> attach -> public check -> roles); the static IP
# leaves FROM before it reaches TO and TO's temporary IP is dropped; DRY_RUN changes nothing; a TO that never gets
# healthy rolls back (IP stays on FROM, FROM backend started again, TO stopped).
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
alias=""; cmd=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) [[ "$2" == HostKeyAlias=* ]] && alias="${2#HostKeyAlias=}"; shift 2 ;;
    -i) shift 2 ;;
    beside@*) shift; cmd="$*"; break ;;
    *) shift ;;
  esac
done
printf 'ssh %s: %s\n' "$alias" "$cmd" >> "$FAKE/calls"
case "$cmd" in
  *healthz*)
    if [[ "$alias" == "${FAKE_UNHEALTHY:-none}" ]]; then echo '{"status":"down"}'; else echo '{"status":"ok"}'; fi ;;
  *"docker ps -q"*) [[ "$alias" == "${FAKE_TO_RUNNING:-none}" ]] && echo abc123 || true ;;
  *"rev-parse HEAD"*) echo 1234567890abcdef ;;
  *"tailscale status"*) echo "100.70.1.2 $alias" ;;
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
  : > "$WORK/calls"; rm -f "$WORK/received-"* "$WORK/env-"* "$WORK/"failover-*.tsv
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

# ---- refusals
reset
echo "projects/p/zones/asia-northeast3-b/instances/beside-standby" > "$WORK/vm/ip-user"
if bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1; then fail "ran with the IP on the target"; fi
reset
if FAKE_TO_RUNNING=beside-standby bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1; then
  fail "ran while the target has a backend"; fi
grep -q "compose stop backend" "$WORK/calls" && fail "a refused run stopped prod"
if bash "$FO" --planned beside-vm > "$WORK/out.txt" 2>&1; then fail "ran without a target"; fi

# ---- dry run: read-only checks only
reset
DRY_RUN=1 bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1 || fail "dry run"
grep -qE "access-config|compose stop|tar -C" "$WORK/calls" && fail "dry run changed something: $(cat "$WORK/calls")"
grep -q "add-access-config beside-standby" "$WORK/out.txt" || fail "dry run did not print the IP move"
ls "$WORK"/failover-*.tsv > /dev/null 2>&1 && fail "dry run wrote a timeline"

# ---- the real path
reset
bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1 || fail "failover"
order
calls="$(cat "$WORK/calls")"
n() { grep -n -m1 -- "$1" <<<"$calls" | cut -d: -f1; }
[[ "$(n 'ssh beside-vm: sudo touch /etc/beside-maintenance')" -lt "$(n 'tar -C')" ]] || fail "copied before stopping prod"
[[ "$(n 'delete-access-config beside-vm')" -lt "$(n 'add-access-config beside-standby')" ]] || fail "IP order"
[[ "$(n 'delete-access-config beside-standby')" -lt "$(n 'add-access-config beside-standby')" ]] || fail "temporary IP kept"
grep -q -- "--address=34.64.91.254" <<<"$(grep 'add-access-config beside-standby' <<<"$calls")" || fail "static IP not attached"
[[ "$(cat "$WORK/received-beside-standby")" == DATA ]] || fail "data not copied to the target"
grep -q "secret" "$WORK/env-beside-standby" || fail ".env not copied"
grep -q "secret" "$WORK/out.txt" && fail ".env printed"
grep -q "ssh beside-standby: printf 'active" <<<"$calls" || fail "target role"
grep -q "ssh beside-vm: docker compose .* stop; sudo systemctl disable --now beside-watchdog.timer" <<<"$calls" || fail "old prod not stopped"
grep -q "add-resource-policies beside-standby-boot" <<<"$calls" || fail "snapshot schedule not on the new disk"
[[ "$(cat "$WORK/vm/ip-user")" == *beside-standby ]] || fail "IP user"

# ---- target never healthy -> rollback
reset
if FAKE_UNHEALTHY=beside-standby bash "$FO" --planned beside-vm beside-standby > "$WORK/out.txt" 2>&1; then
  fail "no rollback exit"; fi
calls="$(cat "$WORK/calls")"
grep -q "add-access-config beside-standby" <<<"$calls" && fail "IP moved although the target was unhealthy"
grep -q "ssh beside-vm: docker compose .* up -d backend" <<<"$calls" || fail "prod backend not restarted"
grep -q "ssh beside-standby: docker compose .* stop" <<<"$calls" || fail "target not stopped"
[[ "$(cat "$WORK/vm/ip-user")" == *beside-vm ]] || fail "IP left prod"
echo "failover_vm: refusals, order, IP move, copy, roles, dry run and rollback ok"
