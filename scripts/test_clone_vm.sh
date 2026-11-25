#!/usr/bin/env bash
# Test of deploy/gcp/clone_vm.sh and deploy/standby/clone_init.sh without GCP or a VM.
# clone_vm.sh: a fake gcloud logs every call. Checks: the copy is created isolated (no external IP, no service account,
# no scopes) from the newest snapshot or a fresh one, with prod's SSH keys; standby gets beside-web + deletion protection,
# staging does not; delete needs --yes; DRY_RUN never changes anything.
# clone_init.sh: fake sudo/systemctl/docker in a temporary root. Checks: it refuses on any host but beside-<role>
# (so never on prod); timers and tailscaled are disabled, the Tailscale state is wiped, webhooks are replaced, staging
# restarts compose with the monitoring profile and standby stops it and removes beside-stub.
#   bash scripts/test_clone_vm.sh            (CI, or WSL on the dev PC)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/.."
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"
fail() { echo "FAIL: $*"; cat "$WORK/out.txt" 2>/dev/null || true; exit 1; }

cat > "$WORK/bin/gcloud" <<'FAKE'
#!/usr/bin/env bash
args=(); for a in "$@"; do [[ "$a" == --project=* || "$a" == --quiet ]] || args+=("$a"); done
set -- "${args[@]}"
printf '%s\n' "$*" >> "$FAKE/calls"
case "$1 $2 $3" in
  "config get-value project") echo beside-test ;;
  "compute snapshots list") echo beside-vm-asia-northeast3-a-20261125191411-abcd ;;
  "compute instances describe")
    case "$*" in
      *beside-vm*ssh-keys*) echo "beside:ssh-ed25519 AAAATEST beside-vm" ;;
      *beside-vm*disks*) echo "https://www.googleapis.com/compute/v1/projects/beside-test/zones/asia-northeast3-a/disks/beside-vm" ;;
      *networkIP*) echo 10.178.0.9 ;;
      *) [[ -e "$FAKE/exists" ]] || exit 1 ;;
    esac ;;
  "compute instances create")
    for a in "$@"; do [[ "$a" == --metadata-from-file=ssh-keys=* ]] && cp "${a#*ssh-keys=}" "$FAKE/keys"; done
    touch "$FAKE/exists" ;;
  "compute disks snapshot") ;;
  "compute instances update") ;;
  "compute instances add-access-config") ;;
  "compute instances delete") rm -f "$FAKE/exists" ;;
  *) echo "fake gcloud: unexpected $*" >&2; exit 3 ;;
esac
FAKE
chmod +x "$WORK/bin/gcloud"
export PATH="$WORK/bin:$PATH" FAKE="$WORK"
CLONE="$ROOT/deploy/gcp/clone_vm.sh"
line() { grep -m1 "^$1" "$WORK/calls" || fail "no call '$1': $(cat "$WORK/calls")"; }
word() { [[ " $1 " == *" $2 "* ]] || fail "'$2' missing from: $1"; }

# ---- usage and dry run
if bash "$CLONE" create prod > "$WORK/out.txt" 2>&1; then fail "took role prod"; fi
DRY_RUN=1 bash "$CLONE" create staging > "$WORK/out.txt" 2>&1 || fail "dry run"
grep -q "compute instances create beside-staging" "$WORK/out.txt" || fail "dry run printed no create"
grep -q "^compute instances create" "$WORK/calls" && fail "dry run created a VM"

# ---- staging from the newest snapshot
: > "$WORK/calls"
bash "$CLONE" create staging > "$WORK/out.txt" 2>&1 || fail "create staging"
c="$(line 'compute instances create')"
for w in beside-staging --zone=asia-northeast3-b --machine-type=e2-standard-2 --no-address --no-service-account \
  --no-scopes --tags=beside-staging --labels=app=beside,role=staging; do word "$c" "$w"; done
word "$c" "--create-disk=boot=yes,auto-delete=yes,name=beside-staging-boot,source-snapshot=beside-vm-asia-northeast3-a-20261125191411-abcd,type=pd-balanced,size=50GB"
[[ "$c" == *deletion-protection* ]] && fail "staging got deletion protection"
[[ "$c" == *beside-web* ]] && fail "staging got the public web tag"
grep -q "AAAATEST" "$WORK/keys" || fail "prod ssh keys not copied"
grep -q "clone_init.sh" "$WORK/out.txt" || fail "no next-step hint"
grep -q "10.178.0.9" "$WORK/out.txt" || fail "internal IP not printed"
if bash "$CLONE" create staging > "$WORK/out.txt" 2>&1; then fail "created over an existing VM"; fi

# ---- temporary IP: standby only, never for staging
if bash "$CLONE" ip staging > "$WORK/out.txt" 2>&1; then fail "gave staging an external IP"; fi
bash "$CLONE" ip standby > "$WORK/out.txt" 2>&1 || fail "ip standby"
c="$(line 'compute instances add-access-config')"
word "$c" beside-standby; word "$c" --access-config-name=external-nat
[[ "$c" == *--address* ]] && fail "the temporary IP must not be the static one"

# ---- delete needs --yes
if bash "$CLONE" delete staging > "$WORK/out.txt" 2>&1; then fail "deleted without --yes"; fi
bash "$CLONE" delete staging --yes > "$WORK/out.txt" 2>&1 || fail "delete staging"
line 'compute instances delete beside-staging' > /dev/null

# ---- standby from a fresh snapshot
: > "$WORK/calls"
bash "$CLONE" create standby now > "$WORK/out.txt" 2>&1 || fail "create standby now"
s="$(line 'compute disks snapshot')"
word "$s" beside-vm; word "$s" --zone=asia-northeast3-a; word "$s" --storage-location=asia-northeast3
[[ "$s" == *--snapshot-names=beside-vm-standby-* ]] || fail "snapshot name: $s"
c="$(line 'compute instances create')"
word "$c" --deletion-protection; word "$c" --tags=beside-standby,beside-web; word "$c" --no-address
bash "$CLONE" delete standby --yes > "$WORK/out.txt" 2>&1 || fail "delete standby"
grep -q "^compute instances update beside-standby --zone=asia-northeast3-b --no-deletion-protection" "$WORK/calls" \
  || fail "deletion protection not lifted before delete"

# ---- clone_init.sh in a fake root
INIT="$ROOT/deploy/standby/clone_init.sh"
R="$WORK/root"
mkdir -p "$R/etc" "$R/ts" "$R/backup" "$R/repo/deploy"
cat > "$WORK/bin/sudo" <<'EOF'
#!/usr/bin/env bash
exec "$@"
EOF
cat > "$WORK/bin/systemctl" <<'EOF'
#!/usr/bin/env bash
printf 'systemctl %s\n' "$*" >> "$FAKE/sys"
EOF
cat > "$WORK/bin/docker" <<'EOF'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >> "$FAKE/sys"
EOF
chmod +x "$WORK/bin/sudo" "$WORK/bin/systemctl" "$WORK/bin/docker"
export BESIDE_ETC="$R/etc" BESIDE_TS_STATE="$R/ts" BESIDE_BACKUP_STATE="$R/backup" BESIDE_REPO="$R/repo"
reset_root() {
  : > "$WORK/sys"
  rm -f "$R/etc/"*
  touch "$R/ts/tailscaled.state" "$R/backup/last.ok"
  printf 'BESIDE_DOMAIN=x\nNOTIFY_WEBHOOK_URL=https://discord.example/secret\nOTHER=1\n' > "$R/repo/deploy/.env"
}

reset_root
if BESIDE_HOSTNAME=beside-vm bash "$INIT" staging > "$WORK/out.txt" 2>&1; then fail "clone_init ran on prod"; fi
if BESIDE_HOSTNAME=beside-vm.asia-northeast3-a.c.beside-514511.internal bash "$INIT" staging > "$WORK/out.txt" 2>&1; then fail "clone_init ran on prod (FQDN)"; fi
if BESIDE_HOSTNAME=beside-standby bash "$INIT" staging > "$WORK/out.txt" 2>&1; then fail "role and host differ"; fi
[[ -s "$WORK/sys" ]] && fail "refused run still changed something"

reset_root
CLONE_WEBHOOK_URL=http://drill-hook:8080/h BESIDE_HOSTNAME=beside-staging.asia-northeast3-b.c.beside-514511.internal bash "$INIT" staging > "$WORK/out.txt" 2>&1 \
  || fail "clone_init staging"
[[ "$(cat "$R/etc/beside-role")" == staging ]] || fail "role file"
[[ -e "$R/etc/beside-maintenance" ]] || fail "maintenance flag"
[[ -e "$R/ts/tailscaled.state" ]] && fail "tailscale state not wiped"
[[ -e "$R/backup/last.ok" ]] && fail "last.ok kept"
grep -q "systemctl disable --now beside-watchdog.timer" "$WORK/sys" || fail "watchdog timer"
grep -q "systemctl disable --now beside-backup.timer" "$WORK/sys" || fail "backup timer"
grep -q "systemctl disable --now tailscaled" "$WORK/sys" || fail "tailscaled"
grep -q "^NOTIFY_WEBHOOK_URL=http://drill-hook:8080/h$" "$R/repo/deploy/.env" || fail "notify webhook"
grep -q "^GRAFANA_ALERT_WEBHOOK_URL=http://drill-hook:8080/h$" "$R/repo/deploy/.env" || fail "grafana webhook"
grep -q "secret" "$R/repo/deploy/.env" && fail "prod webhook left"
grep -q "secret" "$WORK/out.txt" && fail "prod webhook printed"
grep -q "^OTHER=1$" "$R/repo/deploy/.env" || fail "other .env lines changed"
grep -q "docker compose .*--profile monitoring up -d" "$WORK/sys" || fail "staging compose up"
grep -q "docker rm" "$WORK/sys" && fail "staging removed the stub"

reset_root
BESIDE_HOSTNAME=beside-standby bash "$INIT" standby > "$WORK/out.txt" 2>&1 || fail "clone_init standby"
grep -q "^NOTIFY_WEBHOOK_URL=$" "$R/repo/deploy/.env" || fail "standby webhook not emptied"
grep -q "docker compose .*--profile monitoring stop" "$WORK/sys" || fail "standby compose stop"
grep -q "docker rm -f beside-stub" "$WORK/sys" || fail "standby stub"
# repeat is safe
BESIDE_HOSTNAME=beside-standby bash "$INIT" standby > "$WORK/out.txt" 2>&1 || fail "clone_init standby again"
[[ "$(grep -c '^GRAFANA_ALERT_WEBHOOK_URL=' "$R/repo/deploy/.env")" == 1 ]] || fail "webhook line duplicated"

# dry run changes nothing
reset_root
DRY_RUN=1 BESIDE_HOSTNAME=beside-staging bash "$INIT" staging > "$WORK/out.txt" 2>&1 || fail "dry run init"
[[ -e "$R/ts/tailscaled.state" ]] || fail "dry run wiped tailscale"
[[ -s "$WORK/sys" ]] && fail "dry run called systemctl/docker"
echo "clone_vm/clone_init: isolation, roles, deletes and dry runs ok"
