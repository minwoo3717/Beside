"""Run inside the compose network (beside_default): edge latency from Loki (Caddy access log) vs Spring's own timer
from Prometheus, per minute over the S2 window."""
import json
import sys
import urllib.parse
import urllib.request
import urllib.error

START, END = sys.argv[1], sys.argv[2]   # RFC3339


def get(url):
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, e.read()[:300], file=sys.stderr)
        raise


def prom(q):
    u = "http://prometheus:9090/api/v1/query_range?" + urllib.parse.urlencode(
        {"query": q, "start": START, "end": END, "step": "60"})
    return get(u)["data"]["result"]


def loki(q):
    u = "http://loki:3100/loki/api/v1/query_range?" + urllib.parse.urlencode(
        {"query": q, "start": START, "end": END, "step": "60"})
    return get(u)["data"]["result"]


edge = loki('quantile_over_time(0.95, {service="caddy"} | json | request_uri=~"/api/v1/jobs/[0-9a-f-]+" '
            '| request_method="GET" | unwrap duration | __error__="" [1m]) by (service)')
spring = prom('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket'
              '{env="prod",method="GET",uri="/api/v1/jobs/{id}"}[1m])))')
rows = {}
for series in edge:
    for ts, v in series["values"]:
        rows.setdefault(int(float(ts)), {})["edge_p95_s"] = round(float(v), 4)
for series in spring:
    for ts, v in series["values"]:
        rows.setdefault(int(float(ts)), {})["spring_p95_s"] = round(float(v), 4) if v != "NaN" else None
import datetime
print("utc      edge_p95_s  spring_p95_s")
for ts in sorted(rows):
    r = rows[ts]
    print(datetime.datetime.utcfromtimestamp(ts).strftime("%H:%M"), "  ", r.get("edge_p95_s"), "   ", r.get("spring_p95_s"))
