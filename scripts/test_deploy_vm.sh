#!/usr/bin/env bash
# Test of scripts/deploy_vm.sh without a VM: fake ssh (runs the remote part here), docker and curl on PATH, a bare repo
# as the remote. Checks that caddy is restarted whenever the running container reads another Caddyfile than the
# checkout, also when this run's pull changed nothing (an earlier run pulled the change and failed at the build).
#   bash scripts/test_deploy_vm.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin" "$WORK/state"
cat > "$WORK/bin/ssh" <<'FAKE'
#!/usr/bin/env bash
# fake ssh <target> "bash -s -- <repo>": run the remote script here, in the fake VM checkout
shift
eval "$@"
FAKE
cat > "$WORK/bin/docker" <<'FAKE'
#!/usr/bin/env bash
# fake docker compose: "exec -T caddy cat" prints what the running caddy read at its last (re)start
state="$FAKE_STATE"
case " $* " in
  *" exec -T caddy cat /etc/caddy/Caddyfile "*) cat "$state/live-Caddyfile" ;;
  *" restart caddy "*) cp deploy/Caddyfile "$state/live-Caddyfile"; echo restart >> "$state/restarts" ;;
  *" up -d --build "*) [[ -f "$state/build-fails" ]] && { echo "build failed" >&2; exit 1; }; true ;;
  *) true ;;
esac
FAKE
cat > "$WORK/bin/curl" <<'FAKE'
#!/usr/bin/env bash
echo '{"status":"UP"}'
FAKE
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH" FAKE_STATE="$WORK/state"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.autocrlf GIT_CONFIG_VALUE_0=false  # the fake repos: no CRLF warnings

# the remote (GitHub) and the VM checkout
git init -q --bare "$WORK/origin.git"
git clone -q "$WORK/origin.git" "$WORK/dev" 2>/dev/null
cd "$WORK/dev"
git config user.email t@example.com && git config user.name test
mkdir deploy && echo "rules v1" > deploy/Caddyfile && echo "BESIDE_DOMAIN=example.test" > deploy/.env.example
git add -A && git commit -qm v1 && git push -q origin HEAD:main 2>/dev/null
git clone -q -b main "$WORK/origin.git" "$WORK/vm" 2>/dev/null
cp "$WORK/vm/deploy/.env.example" "$WORK/vm/deploy/.env"
cp "$WORK/vm/deploy/Caddyfile" "$WORK/state/live-Caddyfile"   # caddy started on v1

deploy() { bash "$HERE/deploy_vm.sh" user@vm "$WORK/vm" > "$WORK/state/out.txt" 2>&1; }
restarts() { [[ -f "$WORK/state/restarts" ]] && wc -l < "$WORK/state/restarts" | tr -d ' ' || echo 0; }
fail() { echo "FAIL: $*"; cat "$WORK/state/out.txt"; exit 1; }

deploy || fail "deploy without changes"
[[ "$(restarts)" == 0 ]] || fail "caddy restarted although its Caddyfile is current"

echo "rules v2 (new basic_auth)" > deploy/Caddyfile && git commit -qam v2 && git push -q origin HEAD:main 2>/dev/null
touch "$WORK/state/build-fails"
if deploy; then fail "the build failure did not stop the deploy"; fi
[[ "$(restarts)" == 0 ]] || fail "caddy restarted after a failed build"
rm "$WORK/state/build-fails"
deploy || fail "deploy after fixing the build"   # this run's pull changes nothing: the old check skipped caddy here
[[ "$(restarts)" == 1 ]] || fail "caddy was not restarted onto the pulled Caddyfile"
cmp -s "$WORK/state/live-Caddyfile" "$WORK/vm/deploy/Caddyfile" || fail "caddy still reads the old Caddyfile"

deploy || fail "second deploy"
[[ "$(restarts)" == 1 ]] || fail "caddy restarted again with nothing to change"
echo "deploy_vm.sh: caddy restart checks ok"
