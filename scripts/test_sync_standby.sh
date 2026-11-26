#!/usr/bin/env bash
# Test of scripts/sync_standby.sh without GCP or VMs: fake gcloud (VM state in files), fake ssh (logs the VM by its
# HostKeyAlias, the host it was sent to, and the remote command). Checks: refuses, changing nothing, when the static IP
# is not on the main server, the standby has no external IP, its role is not standby, it runs a backend, or its commit
# is not an ancestor of the main server's; DRY_RUN changes nothing on the standby but git fetch; the real path runs
# fetch -> pending marker -> fast-forward -> models -> build/pull -> verify -> final marker in that order, only reads
# on the main server (commit, models folder of its .env) and reaches the standby on its internal IP; a failed build or
# an image still missing after it leaves the marker at 'pending' and fails the run.
#   bash scripts/test_sync_standby.sh            (CI, or WSL on the dev PC)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/vm"
fail() { echo "FAIL: $*"; cat "$WORK/out.txt" 2>/dev/null || true; exit 1; }
MAIN_SHA=a44b3ed000000000000000000000000000000000
OLD_SHA=d5f4a32000000000000000000000000000000000

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
      status) echo RUNNING ;;
      'networkInterfaces[0].networkIP') cat "$V/$name.ip" ;;
      'networkInterfaces[0].accessConfigs[0].natIP') cat "$V/$name.ext" 2>/dev/null || true ;;
    esac ;;
  "compute addresses describe")
    if [[ "$*" == *'users[0]'* ]]; then cat "$V/ip-user"; else echo 34.64.91.254; fi ;;
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
case "$cmd" in
  *"rev-parse HEAD"*) if [[ "$alias" == beside-vm ]]; then echo "$MAIN_SHA"; else cat "$FAKE/sb-head"; fi ;;
  *"cat /etc/beside-role"*) echo "${FAKE_ROLE:-standby}" ;;
  *"docker ps -q"*) [[ -n "${FAKE_SB_RUNNING:-}" ]] && echo abc123 || true ;;
  *"merge-base --is-ancestor"*) [[ -z "${FAKE_NOT_ANCESTOR:-}" ]] ;;
  *"merge --ff-only"*) echo "$MAIN_SHA" > "$FAKE/sb-head" ;;
  *"BESIDE_MODELS_DIR"*) [[ "$alias" == beside-vm ]] && echo '"/home/beside/beside-models"   # moved 2026-11-04' || echo /wrong/standby/value ;;
  *" build -q"*) [[ -z "${FAKE_BUILD_FAIL:-}" ]] ;;
  *"config --images"*) [[ -n "${FAKE_MISSING_AFTER:-}" ]] && echo grafana/tempo:2.6.1 || true ;;
  *"> ~/.beside-built"*) printf '%s\n' "$cmd" >> "$FAKE/marker-writes" ;;
  *"tailscale status"*) echo "Logged out." ;;
  *) : ;;
esac
FAKE
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH" FAKE="$WORK" MAIN_SHA
SY="$HERE/sync_standby.sh"

reset() {
  : > "$WORK/calls"; : > "$WORK/hosts"; : > "$WORK/marker-writes"; echo "$OLD_SHA" > "$WORK/sb-head"
  echo 10.178.0.2 > "$WORK/vm/beside-vm.ip"; echo 34.64.91.254 > "$WORK/vm/beside-vm.ext"
  echo 10.178.0.4 > "$WORK/vm/beside-standby.ip"; echo 34.22.1.1 > "$WORK/vm/beside-standby.ext"
  echo "projects/p/zones/asia-northeast3-a/instances/beside-vm" > "$WORK/vm/ip-user"
}
changed() { grep -E "merge --ff-only|fetch_models|compose .* build|> ~/.beside-built" "$WORK/calls" || true; }
before() {   # before <pattern A> <pattern B>: both calls exist and A comes first
  local a b
  a="$(grep -n -m1 -- "$1" <<<"$calls" | cut -d: -f1)"; b="$(grep -n -m1 -- "$2" <<<"$calls" | cut -d: -f1)"
  [[ -n "$a" && -n "$b" && "$a" -lt "$b" ]]
}

# ---- refusals: nothing changes
reset; echo "projects/p/zones/asia-northeast3-b/instances/beside-standby" > "$WORK/vm/ip-user"
if bash "$SY" > "$WORK/out.txt" 2>&1; then fail "ran with the static IP on the standby"; fi
reset; : > "$WORK/vm/beside-standby.ext"
if bash "$SY" > "$WORK/out.txt" 2>&1; then fail "ran without an external IP on the standby"; fi
grep -q "clone_vm.sh ip standby" "$WORK/out.txt" || fail "no hint for the temporary IP"
for c in "FAKE_ROLE=active|the standby is the active server" "FAKE_SB_RUNNING=1|the standby runs a backend" \
         "FAKE_NOT_ANCESTOR=1|the standby is not behind the main server"; do
  reset
  if env "${c%%|*}" bash "$SY" > "$WORK/out.txt" 2>&1; then fail "ran although ${c#*|}"; fi
  [[ -z "$(changed)" ]] || fail "a refused run (${c#*|}) changed: $(changed)"
done
if bash "$SY" beside-vm beside-vm > "$WORK/out.txt" 2>&1; then fail "ran with the same VM twice"; fi

# ---- dry run: only git fetch runs
reset
DRY_RUN=1 bash "$SY" > "$WORK/out.txt" 2>&1 || fail "dry run"
[[ -z "$(changed)" ]] || fail "dry run changed: $(changed)"
grep -q "fetch -q origin" "$WORK/calls" || fail "dry run did not fetch"
grep -q "merge --ff-only -q $MAIN_SHA" "$WORK/out.txt" || fail "dry run did not print the fast-forward"
grep -qE "4 ok|5 marker" "$WORK/out.txt" && fail "dry run claimed a verify or a marker"
[[ "$(cat "$WORK/sb-head")" == "$OLD_SHA" ]] || fail "dry run moved the standby"

# ---- the real path
reset
bash "$SY" > "$WORK/out.txt" 2>&1 || fail "sync"
calls="$(cat "$WORK/calls")"
before 'fetch -q origin' "printf 'pending" || fail "pending marker before fetch"
before "printf 'pending" 'merge --ff-only' || fail "merged before the pending marker"
before 'merge --ff-only' 'fetch_models.sh' || fail "models before code"
before 'fetch_models.sh' ' build -q' || fail "build before models"
before ' build -q' 'config --images' || fail "verified before the build"
before 'config --images' "printf '%s %s" || fail "final marker before the verify"
grep -q "fetch_models.sh /home/beside/beside-models <" <<<"$calls" || fail "models dir of the main .env not used: $(grep fetch_models <<<"$calls")"
grep -q "pull -q --ignore-buildable" <<<"$calls" || fail "images not pulled"
# the fake ssh does not run the remote line: a failed build must stop the pull and fail the call
grep -q " build -q </dev/null && docker compose .* pull -q" <<<"$calls" || fail "build and pull not chained with &&"
grep -q -- "--profile monitoring build" <<<"$calls" || fail "monitoring profile not built"
grep -q "set -o pipefail" <<<"$(grep 'config --images' <<<"$calls")" || fail "image listing without pipefail"
[[ "$(tail -n1 "$WORK/marker-writes")" == "printf '%s %s\n' $MAIN_SHA "* ]] || fail "final marker not for the main commit: $(cat "$WORK/marker-writes")"
grep "^ssh beside-vm:" <<<"$calls" | grep -vE "rev-parse HEAD|BESIDE_MODELS_DIR" && fail "did more than read on the main server"
grep -qE "compose .* (up|start|restart)" <<<"$calls" && fail "started something on the standby"
grep -v "^beside-standby 10.178.0.4$" "$WORK/hosts" | grep -v "^beside-vm 34.64.91.254$" && fail "a command went to the wrong host"
grep -qx "beside-standby 10.178.0.4" "$WORK/hosts" || fail "standby not reached on its internal IP"
[[ "$(cat "$WORK/sb-head")" == "$MAIN_SHA" ]] || fail "standby not at the main commit"
grep -q "4 ok" "$WORK/out.txt" || fail "no verify ok"

# ---- already at the commit: no merge, still builds
reset; echo "$MAIN_SHA" > "$WORK/sb-head"
bash "$SY" > "$WORK/out.txt" 2>&1 || fail "sync at the same commit"
grep -q "merge --ff-only" "$WORK/calls" && fail "merged although already at the commit"
grep -q " build -q" "$WORK/calls" || fail "no build at the same commit"

# ---- a failed build or an image still missing: the run fails and the marker stays 'pending'
for c in "FAKE_BUILD_FAIL=1|the build failed" "FAKE_MISSING_AFTER=1|an image is missing after the build"; do
  reset
  if env "${c%%|*}" bash "$SY" > "$WORK/out.txt" 2>&1; then fail "ok although ${c#*|}"; fi
  [[ "$(tail -n1 "$WORK/marker-writes")" == "printf 'pending "* ]] || fail "marker not left at pending when ${c#*|}"
done
reset; FAKE_MISSING_AFTER=1 bash "$SY" > "$WORK/out.txt" 2>&1 || true
grep -q "grafana/tempo" "$WORK/out.txt" || fail "missing image not named"
echo "sync_standby: refusals, dry run, order, main only read, routing, same-commit, build-failure and missing-image paths ok"
