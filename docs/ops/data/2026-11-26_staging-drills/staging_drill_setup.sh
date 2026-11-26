#!/usr/bin/env bash
# Run ON beside-staging (as beside). Turns the isolated staging copy into a drill target:
# load-test backend (mock,prod, own volume) but METRICS_ENV=prod so the prod Grafana alert rules apply,
# every alert (backend webhook, Grafana, VM watchdog) to the local collector drill-hook, watchdog on.
set -euo pipefail
[[ "$(hostname | cut -d. -f1)" == beside-staging ]] || { echo "not staging"; exit 2; }
cd ~/Beside

cat > ~/fault/docker-compose.staging-drill.yml <<'EOF'
# staging drill overlay (2026-11-26): after docker-compose.loadtest.yml. Alerts as in prod, sent to drill-hook.
services:
  backend:
    environment:
      METRICS_ENV: prod
      NOTIFY_WEBHOOKURL: http://drill-hook:8080/backend
EOF

# collector on the compose network (backend, grafana) and on the host loopback (watchdog)
if ! docker ps -a --format '{{.Names}}' | grep -qx drill-hook; then
  docker run -d --name drill-hook --restart unless-stopped --network beside_default \
    -p 127.0.0.1:18080:8080 -v "$HOME/fault/hook.py:/hook.py:ro" --entrypoint python3 beside-stub:local -u /hook.py
fi

# .env of the copy: watchdog -> host loopback, grafana -> drill-hook
for kv in "NOTIFY_WEBHOOK_URL=http://127.0.0.1:18080/watchdog" "GRAFANA_ALERT_WEBHOOK_URL=http://drill-hook:8080/grafana"; do
  k="${kv%%=*}"
  if grep -q "^$k=" deploy/.env; then sed -i "s|^$k=.*|$kv|" deploy/.env; else printf '%s\n' "$kv" >> deploy/.env; fi
done

L="docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile monitoring -f deploy/docker-compose.loadtest.yml -f $HOME/fault/docker-compose.staging-drill.yml"
$L up -d 2>&1 | tail -4

# synthetic 1.5 MB GLB for the mock worker (deploy/loadtest/README.md section 3)
python3 -c "import sys; sys.path.insert(0, 'scripts/perf'); import fixtures; open('/tmp/lt-sample-1500000.glb', 'wb').write(fixtures.glb_synthetic(1_500_000, seed=14))"
for i in $(seq 1 30); do
  h="$(curl -fsS --max-time 5 --resolve beside-team.duckdns.org:443:127.0.0.1 https://beside-team.duckdns.org/api/v1/healthz 2>/dev/null)" && break
  sleep 4
done
echo "healthz: $h"
$L exec -T backend sh -c 'mkdir -p /app/storage/results && cat > /app/storage/results/sample-dog.glb' < /tmp/lt-sample-1500000.glb
$L exec -T backend sh -c 'env | grep -E "^(SPRING_PROFILES_ACTIVE|METRICS_ENV|NOTIFY_WEBHOOKURL|MOCK_WORKER_PROCESSINGMS)="'
docker inspect -f '{{range .Mounts}}{{.Name}} -> {{.Destination}}{{println}}{{end}}' beside-backend-1 | grep app/storage

# watchdog on, maintenance off (the response machinery must act during the drill)
sudo rm -f /etc/beside-maintenance
sudo systemctl enable --now beside-watchdog.timer
systemctl is-active beside-watchdog.timer
# test message through the collector
curl -s -o /dev/null -w 'hook: %{http_code}\n' -X POST -d '{"content":"staging drill setup check"}' http://127.0.0.1:18080/setup
docker logs --tail 2 drill-hook
