"""Save dashboards edited in Grafana into monitoring/dashboards/ (Python standard library only).

Pulls every dashboard tagged "gregfana". The file name is the UID without its "gregfana-" prefix,
so a new dashboard should get the tag and a UID like "gregfana-<name>".

    python scripts/pull_dashboards.py
GRAFANA_TOKEN (a service account token) and optional GRAFANA_URL (default https://gregfana.latte.cx)
come from the environment or from the gitignored .env at the repo root.
"""
import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
ROOT = REPO / "monitoring" / "dashboards"
if (REPO / ".env").exists():
    for line in (REPO / ".env").read_text(encoding="utf-8").splitlines():
        key, sep, value = line.partition("=")
        if sep and not key.strip().startswith("#"):
            os.environ.setdefault(key.strip(), value.strip())
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
