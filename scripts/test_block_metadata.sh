#!/usr/bin/env bash
# Test of deploy/backup/block-metadata.sh without root or Docker: a fake iptables keeps the DOCKER-USER chain in a file,
# a fake docker answers for the backend container. Checks that apply waits for Docker's chain, inserts the rule once at
# the top however often it runs, keeps port 53 (DNS) open, and that check/remove report and undo it.
#   bash scripts/test_block_metadata.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../deploy/backup/block-metadata.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"

cat > "$WORK/bin/iptables" <<'FAKE'
#!/usr/bin/env bash
# fake iptables (filter table, chain DOCKER-USER only): rules in $FAKE/chain, one "args" line each
args=(); for a in "$@"; do [[ "$a" == -w ]] || args+=("$a"); done
set -- "${args[@]}"
[[ -f "$FAKE/chain" ]] || { [[ "$1" == -N ]] || { echo "iptables: No chain/target/match by that name." >&2; exit 1; }; }
case "$1" in
  -n) exit 0 ;;                                       # -n -L DOCKER-USER
  -C) shift 2; grep -qxF -- "$*" "$FAKE/chain" ;;
  -I) shift 3; { echo "$*"; cat "$FAKE/chain"; } > "$FAKE/chain.new" && mv "$FAKE/chain.new" "$FAKE/chain" ;;
  -D) shift 2; awk -v r="$*" 'done || $0 != r { print; next } { done = 1 }' "$FAKE/chain" > "$FAKE/chain.new" \
        && mv "$FAKE/chain.new" "$FAKE/chain" ;;
  -S) sed 's/^/-A DOCKER-USER /' "$FAKE/chain" ;;
  *) echo "fake iptables: unexpected $*" >&2; exit 2 ;;
esac
FAKE
cat > "$WORK/bin/docker" <<'FAKE'
#!/usr/bin/env bash
case "$*" in
  "ps -q --filter label=com.docker.compose.project=beside --filter label=com.docker.compose.service=backend") echo c0ffee ;;
  "exec c0ffee curl "*) printf '%s' "$(cat "$FAKE/metadata-code")"; [[ "$(cat "$FAKE/metadata-code")" != 000 ]] || exit 28 ;;
  "exec c0ffee getent hosts storage.googleapis.com") [[ -f "$FAKE/dns-ok" ]] ;;
  *) echo "fake docker: unexpected $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH" FAKE="$WORK"
fail() { echo "FAIL: $*"; cat "$WORK/out.txt" 2>/dev/null; exit 1; }
RULE="-d 169.254.169.254/32 -p tcp ! --dport 53 -j DROP"

# before Docker made DOCKER-USER: fails (the unit runs after docker.service, so this means Docker is broken)
if bash "$SCRIPT" > "$WORK/out.txt" 2>&1; then fail "applied without a DOCKER-USER chain"; fi

echo "-j RETURN" > "$WORK/chain"   # what Docker puts there
bash "$SCRIPT" > "$WORK/out.txt" 2>&1 || fail "apply"
bash "$SCRIPT" apply > "$WORK/out.txt" 2>&1 || fail "second apply"
grep -q "already in DOCKER-USER" "$WORK/out.txt" || fail "second apply did not see the rule"
[[ "$(cat "$WORK/chain")" == "$RULE"$'\n'"-j RETURN" ]] || fail "chain is: $(cat "$WORK/chain")"

# check: rule present, the container gets no metadata answer (curl exit 28, code 000) and still resolves names
echo 000 > "$WORK/metadata-code"
touch "$WORK/dns-ok"
bash "$SCRIPT" check > "$WORK/out.txt" 2>&1 || fail "check with the rule in place"
grep -q "ok: container gets no metadata answer" "$WORK/out.txt" || fail "no metadata line"
grep -q "ok: container still resolves names" "$WORK/out.txt" || fail "no DNS line"
rm "$WORK/dns-ok"
if bash "$SCRIPT" check > "$WORK/out.txt" 2>&1; then fail "check passed without DNS"; fi
touch "$WORK/dns-ok"
echo 200 > "$WORK/metadata-code"
if bash "$SCRIPT" check > "$WORK/out.txt" 2>&1; then fail "check passed while a container reached the metadata server"; fi

bash "$SCRIPT" remove > "$WORK/out.txt" 2>&1 || fail "remove"
[[ "$(cat "$WORK/chain")" == "-j RETURN" ]] || fail "rule left after remove: $(cat "$WORK/chain")"
if bash "$SCRIPT" check > "$WORK/out.txt" 2>&1; then fail "check passed without the rule"; fi
echo "block-metadata.sh: apply once at the top, DNS kept, check and remove ok"
