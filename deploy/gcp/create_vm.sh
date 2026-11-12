#!/usr/bin/env bash
# Create the Beside main server on Google Compute Engine: static IP, firewall (80/443), Ubuntu 24.04 VM with our SSH key.
# Run it in Cloud Shell (the console's >_ button), where gcloud is already signed in to the project. Re-running is safe:
# resources that already exist are kept as they are. After it prints the IP, continue with deploy/README.md
# (DuckDNS domain -> Tailscale -> deploy/.env -> docker compose up).
#   SSH_PUBKEY='ssh-rsa AAAA... beside-vm' bash create_vm.sh                     # Seoul, zone asia-northeast3-a
#   SSH_PUBKEY='ssh-rsa AAAA... beside-vm' bash create_vm.sh asia-northeast3-b   # when that zone has no capacity
# Optional overrides (prefixed so that variables a shell already has, like NAME or ZONE, never leak in):
#   BESIDE_GCP_PROJECT, BESIDE_GCP_MACHINE (e2-standard-2), BESIDE_GCP_DISK_GB (50), BESIDE_GCP_VM (beside-vm)
# Free Trial accounts: the trial is not billed unless it is upgraded ("activate full account"), so do not upgrade for this.
# Trial accounts cannot attach GPUs, so GPU inference stays outside GCP (team GPU PC / Runpod over Tailscale).
set -euo pipefail

PROJECT="${BESIDE_GCP_PROJECT:-$(gcloud config get-value project 2>/dev/null || true)}"
ZONE="${1:-asia-northeast3-a}"
VM="${BESIDE_GCP_VM:-beside-vm}"
MACHINE="${BESIDE_GCP_MACHINE:-e2-standard-2}"   # 2 dedicated vCPU, 8 GB: backend (3 GB limit, ONNX models) + Caddy + builds
DISK_GB="${BESIDE_GCP_DISK_GB:-50}"
SSH_USER=beside
IP_NAME=beside-ip
TAG=beside-web
FW_RULE=beside-allow-web

if [[ -z "${SSH_PUBKEY:-}" ]]; then
  echo "SSH_PUBKEY is empty: pass the public key line, e.g. SSH_PUBKEY='ssh-rsa AAAA... beside-vm'" >&2
  exit 2
fi
if [[ -z "$PROJECT" || "$PROJECT" == "(unset)" ]]; then
  echo "no project selected: run  gcloud config set project <PROJECT_ID>  and try again" >&2
  exit 2
fi
if [[ ! "$ZONE" =~ ^[a-z]+-[a-z]+[0-9]+-[a-z]$ ]]; then
  echo "not a zone name: $ZONE (expected e.g. asia-northeast3-a)" >&2
  exit 2
fi
REGION="${ZONE%-*}"   # asia-northeast3-a -> asia-northeast3 (the static IP must be in the VM's region)
G=(gcloud --project="$PROJECT" --quiet)

echo "[1/5] project $PROJECT: billing and the Compute Engine API (the first time takes about a minute)"
BILLING="$("${G[@]}" billing projects describe "$PROJECT" --format='value(billingEnabled)' 2>/dev/null || true)"
if [[ "${BILLING,,}" != "true" ]]; then
  echo "      warning: could not confirm billing on $PROJECT. If the next step fails, link the project to the Free Trial" >&2
  echo "      billing account (Billing > My projects) and run this again." >&2
fi
"${G[@]}" services enable compute.googleapis.com

echo "[2/5] network and firewall"
# A new project gets its default network (with default-allow-ssh) from Google a few seconds after the Compute Engine
# API is enabled, so wait for it instead of racing it. First real run, 2026-11-12: the describe still failed, then our
# create hit "networks/default already exists" and the script stopped.
for _ in 1 2 3 4 5 6; do
  "${G[@]}" compute networks describe default >/dev/null 2>&1 && break
  sleep 10
done
if ! "${G[@]}" compute networks describe default >/dev/null 2>&1; then
  "${G[@]}" compute networks create default --subnet-mode=auto
fi
if ! "${G[@]}" compute firewall-rules describe default-allow-ssh >/dev/null 2>&1; then
  "${G[@]}" compute firewall-rules create default-allow-ssh --network=default --direction=INGRESS \
    --allow=tcp:22 --source-ranges=0.0.0.0/0
fi
if ! "${G[@]}" compute firewall-rules describe "$FW_RULE" >/dev/null 2>&1; then
  # Caddy only: 80 (ACME challenge + redirect), 443/tcp, 443/udp (HTTP/3). 8080 is never published by compose.
  "${G[@]}" compute firewall-rules create "$FW_RULE" --network=default --direction=INGRESS \
    --allow=tcp:80,tcp:443,udp:443 --source-ranges=0.0.0.0/0 --target-tags="$TAG"
fi

echo "[3/5] static external IP $IP_NAME ($REGION)"
if ! "${G[@]}" compute addresses describe "$IP_NAME" --region="$REGION" >/dev/null 2>&1; then
  "${G[@]}" compute addresses create "$IP_NAME" --region="$REGION"
fi
IP="$("${G[@]}" compute addresses describe "$IP_NAME" --region="$REGION" --format='value(address)')"

echo "[4/5] VM $VM ($ZONE, $MACHINE, ${DISK_GB}GB, Ubuntu 24.04)"
if "${G[@]}" compute instances describe "$VM" --zone="$ZONE" >/dev/null 2>&1; then
  echo "      already exists: kept as it is"
else
  KEYS="$(mktemp)"
  trap 'rm -f "$KEYS"' EXIT
  printf '%s:%s\n' "$SSH_USER" "$SSH_PUBKEY" > "$KEYS"
  "${G[@]}" compute instances create "$VM" --zone="$ZONE" --machine-type="$MACHINE" \
    --image-family=ubuntu-2404-lts-amd64 --image-project=ubuntu-os-cloud \
    --boot-disk-size="${DISK_GB}GB" --boot-disk-type=pd-balanced \
    --address="$IP" --tags="$TAG" --labels=app=beside \
    --metadata=enable-oslogin=FALSE --metadata-from-file=ssh-keys="$KEYS" \
    --no-service-account --no-scopes \
    --deletion-protection
  # No service account: nothing on the VM calls Google APIs, and with the default one any container could take a token
  # from the metadata server (2026-11-13 audit). SSH keys still come from metadata, which needs no service account.
fi

echo "[5/5] status: $("${G[@]}" compute instances describe "$VM" --zone="$ZONE" --format='value(status)')"
echo "IP=$IP"
echo "next: point the DuckDNS domain at $IP. SSH from the dev PC: ssh -i ~/.ssh/beside-vm.key $SSH_USER@$IP"
