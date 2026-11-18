"""Save dashboards edited in Grafana into monitoring/dashboards/ (Python standard library only).

Pulls every dashboard tagged "gregfana". The file name is the UID without its "gregfana-" prefix,
so a new dashboard should get the tag and a UID like "gregfana-<name>".

    GRAFANA_TOKEN=<service account token> python scripts/pull_dashboards.py
GRAFANA_URL defaults to https://gregfana.latte.cx.
"""
import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "monitoring" / "dashboards"
URL = os.environ.get("GRAFANA_URL", "https://gregfana.latte.cx").rstrip("/")
TOKEN = os.environ.get("GRAFANA_TOKEN") or sys.exit("Set GRAFANA_TOKEN to a Grafana service account token.")


def get(path):
    req = urllib.request.Request(URL + path, headers={"Authorization": f"Bearer {TOKEN}"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)


for hit in get("/api/search?type=dash-db&tag=gregfana"):
    uid = hit["uid"]
    dashboard = get("/api/dashboards/uid/" + urllib.parse.quote(uid))["dashboard"]
    # Instance-specific fields; leaving them in makes every pull a diff.
    for key in ("id", "version"):
        dashboard.pop(key, None)
    path = ROOT / f"{uid.removeprefix('gregfana-')}.json"
    path.write_text(json.dumps(dashboard, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{uid} -> {path.relative_to(ROOT.parents[1])}")
