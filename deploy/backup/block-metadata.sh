#!/usr/bin/env bash
# Containers must not get the VM's service-account token (R1). Once the VM carries beside-backup@ (it may create
# objects in the backup bucket), any process in a container could ask the GCE metadata server for that token over HTTP.
# This drops container traffic to 169.254.169.254 in Docker's DOCKER-USER chain (forwarded traffic, before Docker's own
# rules), except port 53: the metadata server is also the VM's DNS resolver, and Docker's resolver forwards container
# lookups there. The host itself (offsite_backup.sh, the guest agent) is not affected: its traffic does not cross FORWARD.
# Run at boot by beside-block-metadata.service (after docker.service, again whenever Docker restarts). Safe to re-run.
#   sudo beside-block-metadata            # apply (default): insert the rule once, at the top of DOCKER-USER
#   sudo beside-block-metadata check      # rule present? a container gets no metadata answer but still resolves names?
#   sudo beside-block-metadata remove
# Limits: IPv4 only (the VM and Docker's networks have no IPv6); containers with network_mode: host are not covered
# (deploy/docker-compose.yml uses none; its comment on switching backend to the host network would bypass this).
set -euo pipefail
IPT="${IPTABLES:-iptables}"
DOCKER="${DOCKER:-docker}"
RULE=(-d 169.254.169.254/32 -p tcp ! --dport 53 -j DROP)

case "${1:-apply}" in
  apply)
    if ! "$IPT" -w -n -L DOCKER-USER >/dev/null 2>&1; then
      echo "chain DOCKER-USER does not exist yet (Docker not started?)" >&2
      exit 1
    fi
    if "$IPT" -w -C DOCKER-USER "${RULE[@]}" 2>/dev/null; then
      echo "metadata block already in DOCKER-USER"
    else
      "$IPT" -w -I DOCKER-USER 1 "${RULE[@]}"
      echo "metadata block inserted at the top of DOCKER-USER"
    fi
    ;;
  remove)
    while "$IPT" -w -C DOCKER-USER "${RULE[@]}" 2>/dev/null; do
      "$IPT" -w -D DOCKER-USER "${RULE[@]}"
    done
    echo "metadata block removed"
    ;;
  check)
    rc=0
    if "$IPT" -w -C DOCKER-USER "${RULE[@]}" 2>/dev/null; then
      echo "ok: rule present ($("$IPT" -w -S DOCKER-USER | grep -m1 169.254.169.254))"
    else
      echo "FAIL: rule missing from DOCKER-USER"
      rc=1
    fi
    # Inside the running backend (it has curl and getent): the metadata HTTP API must time out, DNS must still answer.
    cid="$("$DOCKER" ps -q --filter label=com.docker.compose.project=beside --filter label=com.docker.compose.service=backend | head -n1)"
    if [[ -z "$cid" ]]; then
      echo "skip: no running backend container to test from"
    else
      code="$("$DOCKER" exec "$cid" curl -s -m 5 -o /dev/null -w '%{http_code}' -H 'Metadata-Flavor: Google' \
        http://169.254.169.254/computeMetadata/v1/instance/id 2>/dev/null || true)"
      if [[ "$code" == 000 ]]; then
        echo "ok: container gets no metadata answer"
      else
        echo "FAIL: container reached the metadata server (HTTP $code)"
        rc=1
      fi
      if "$DOCKER" exec "$cid" getent hosts storage.googleapis.com >/dev/null 2>&1; then
        echo "ok: container still resolves names (DNS on 169.254.169.254:53)"
      else
        echo "FAIL: container cannot resolve storage.googleapis.com"
        rc=1
      fi
    fi
    exit "$rc"
    ;;
  *)
    echo "usage: $0 [apply|check|remove]" >&2
    exit 2
    ;;
esac
