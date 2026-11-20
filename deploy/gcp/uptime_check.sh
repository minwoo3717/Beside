#!/usr/bin/env bash
# Outside uptime check of the public server (R3). Today nobody is told when the backend or the whole VM is down; the
# backend's own Discord messages stop with it. Cloud Monitoring asks https://<domain>/api/v1/healthz every minute from
# its checker regions and expects "status":"ok" in the body; an alert policy notifies a channel (e-mail, chosen by you
# in the console) when 2 or more regions have failed for 2 minutes. It sees the public path only (DNS, TLS, Caddy,
# Spring): the database and the GPU are watched by the VM watchdog (deploy/watchdog) and the backend.
# Run it where gcloud is signed in to the project (Cloud Shell, or the dev PC). Re-running is safe.
#   bash uptime_check.sh create <channel>   # <channel>: a notification channel id, or projects/<p>/notificationChannels/<id>
#   bash uptime_check.sh pause              # load tests and drills: alerts off (the check keeps running and recording)
#   bash uptime_check.sh resume             # alerts on again
#   bash uptime_check.sh status
#   DRY_RUN=1 bash uptime_check.sh create 1234567890   # print the gcloud commands, change nothing
# Channel ids: gcloud beta monitoring channels list --format='table(name.basename(),type,displayName)'
#   (make an e-mail channel first in the console: Monitoring > Alerting > Edit notification channels).
# Optional overrides: BESIDE_GCP_PROJECT, BESIDE_DOMAIN (beside-team.duckdns.org), BESIDE_UPTIME_NAME (beside-healthz),
#   BESIDE_ALERT_NAME (beside-healthz down), BESIDE_GCP_VM (beside-vm, named in the alert text).
set -euo pipefail

DRY_RUN="${DRY_RUN:-0}"
VM="${BESIDE_GCP_VM:-beside-vm}"
DOMAIN="${BESIDE_DOMAIN:-beside-team.duckdns.org}"
CHECK_NAME="${BESIDE_UPTIME_NAME:-beside-healthz}"
POLICY_NAME="${BESIDE_ALERT_NAME:-beside-healthz down}"
PROJECT="${BESIDE_GCP_PROJECT:-}"
if [[ -z "$PROJECT" ]] && command -v gcloud >/dev/null 2>&1; then
  PROJECT="$(gcloud config get-value project 2>/dev/null || true)"
fi
if [[ -z "$PROJECT" || "$PROJECT" == "(unset)" ]]; then
  if [[ "$DRY_RUN" != 1 ]]; then
    echo "no project selected: run  gcloud config set project <PROJECT_ID>  (or set BESIDE_GCP_PROJECT) and try again" >&2
    exit 2
  fi
  PROJECT=PROJECT_ID
fi
G=(gcloud --project="$PROJECT" --quiet)

run() { if [[ "$DRY_RUN" == 1 ]]; then printf '+'; printf ' %q' "$@"; printf '\n'; else "$@"; fi; }
# lookup <placeholder> <gcloud args...>: the first value a list prints; DRY_RUN prints the list and answers <placeholder>.
lookup() {
  local placeholder="$1"
  shift
  if [[ "$DRY_RUN" == 1 ]]; then
    printf '? %s\n' "$*" >&2
    printf '%s' "$placeholder"
  else
    "$@" | head -n1
  fi
}
check_id() { lookup "" "${G[@]}" monitoring uptime list-configs --filter="displayName=\"$CHECK_NAME\"" --format='value(name.basename())'; }
policy_id() {
  lookup "${1:-}" "${G[@]}" monitoring policies list --filter="displayName=\"$POLICY_NAME\"" --format='value(name.basename())'
}

usage() {
  echo "usage:" >&2
  grep -E '^#   (DRY_RUN=1 )?bash uptime_check\.sh' "$0" | sed 's/^#   /  /' >&2
  exit 2
}

case "${1:-}" in
  create)
    CHANNEL="${2:-}"
    if [[ "$CHANNEL" =~ ^[0-9]+$ ]]; then
      CHANNEL="projects/$PROJECT/notificationChannels/$CHANNEL"
    elif [[ ! "$CHANNEL" =~ ^projects/[^/]+/notificationChannels/[0-9]+$ ]]; then
      echo "give the notification channel (an id or projects/<p>/notificationChannels/<id>). List them with:" >&2
      echo "  gcloud beta monitoring channels list --project=$PROJECT --format='table(name.basename(),type,displayName)'" >&2
      exit 2
    fi

    echo "[1/3] project $PROJECT: Cloud Monitoring API"
    run "${G[@]}" services enable monitoring.googleapis.com

    echo "[2/3] uptime check $CHECK_NAME: https://$DOMAIN/api/v1/healthz every minute, body must contain \"status\":\"ok\""
    ID="$(check_id)"
    if [[ -n "$ID" ]]; then
      echo "      already exists: $ID (kept as it is)"
    else
      CREATE=("${G[@]}" monitoring uptime create "$CHECK_NAME" --resource-type=uptime-url
        --resource-labels="host=$DOMAIN,project_id=$PROJECT" --protocol=https --port=443 --path=/api/v1/healthz
        --request-method=get --validate-ssl=true --period=1 --timeout=10
        --matcher-content='"status":"ok"' --matcher-type=contains-string --user-labels=app=beside)
      if [[ "$DRY_RUN" == 1 ]]; then
        run "${CREATE[@]}" --format='value(name.basename())'
        ID=CHECK_ID
      else
        ID="$("${CREATE[@]}" --format='value(name.basename())')"
        ID="${ID##*/}"
      fi
    fi
    if [[ -z "$ID" ]]; then
      echo "the uptime check was not created" >&2
      exit 1
    fi

    echo "[3/3] alert policy \"$POLICY_NAME\": 2+ regions failing for 2 min -> $CHANNEL"
    EXISTING="$(policy_id)"
    if [[ -n "$EXISTING" ]]; then
      echo "      already exists: $EXISTING (kept as it is; its channels: gcloud monitoring policies describe $EXISTING --project=$PROJECT)"
    else
      POLICY_FILE="$(mktemp)"
      trap 'rm -f "$POLICY_FILE"' EXIT
      # The console's own uptime alert: per region the newest check_passed, count the false ones per checked URL,
      # fire when more than 1 (so 2+ regions) stays so for 120 s. It closes itself 30 min after the check passes again.
      cat > "$POLICY_FILE" <<JSON
{
  "displayName": "$POLICY_NAME",
  "combiner": "OR",
  "conditions": [
    {
      "displayName": "healthz fails in 2+ regions for 2 min",
      "conditionThreshold": {
        "filter": "metric.type=\"monitoring.googleapis.com/uptime_check/check_passed\" AND metric.label.check_id=\"$ID\" AND resource.type=\"uptime_url\"",
        "aggregations": [
          {
            "alignmentPeriod": "60s",
            "perSeriesAligner": "ALIGN_NEXT_OLDER",
            "crossSeriesReducer": "REDUCE_COUNT_FALSE",
            "groupByFields": ["resource.label.project_id", "resource.label.host"]
          }
        ],
        "comparison": "COMPARISON_GT",
        "thresholdValue": 1,
        "duration": "120s",
        "trigger": {"count": 1}
      }
    }
  ],
  "alertStrategy": {"autoClose": "1800s"},
  "notificationChannels": ["$CHANNEL"],
  "documentation": {
    "mimeType": "text/markdown",
    "content": "https://$DOMAIN/api/v1/healthz fails from 2 or more regions. Check the VM (gcloud compute instances describe $VM), then docker compose ps and logs on it (deploy/README.md). During load tests and drills: deploy/gcp/uptime_check.sh pause."
  },
  "userLabels": {"app": "beside"}
}
JSON
      [[ "$DRY_RUN" == 1 ]] && sed 's/^/      /' "$POLICY_FILE"
      run "${G[@]}" monitoring policies create --policy-from-file="$POLICY_FILE"
    fi
    echo "done. In the console: Monitoring > Uptime checks > $CHECK_NAME shows each region's result within a few minutes."
    ;;

  pause | resume)
    ID="$(policy_id POLICY_ID)"
    if [[ -z "$ID" ]]; then
      echo "no alert policy \"$POLICY_NAME\" (run: $0 create <channel>)" >&2
      exit 1
    fi
    if [[ "$1" == pause ]]; then
      run "${G[@]}" monitoring policies update "$ID" --no-enabled
      echo "alerts paused. The uptime check keeps running. Resume after the test: $0 resume"
    else
      run "${G[@]}" monitoring policies update "$ID" --enabled
      echo "alerts on"
    fi
    ;;

  status)
    run "${G[@]}" monitoring uptime list-configs --filter="displayName=\"$CHECK_NAME\"" \
      --format='table(name.basename(),displayName,period,timeout,httpCheck.path,monitoredResource.labels.host)'
    run "${G[@]}" monitoring policies list --filter="displayName=\"$POLICY_NAME\"" \
      --format='table(name.basename(),displayName,enabled,notificationChannels.list())'
    ;;

  *) usage ;;
esac
