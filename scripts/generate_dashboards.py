"""Generate the provisioned Grafana dashboards (Python standard library only)."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "monitoring" / "dashboards"
DATASOURCE = {"type": "prometheus", "uid": "gregfana-prometheus"}


def panel(title, expr, unit="short", kind="timeseries", legend="{{player}}"):
    return {
        "title": title, "type": kind, "datasource": DATASOURCE,
        "targets": [{"refId": "A", "expr": expr, "legendFormat": legend}],
        "fieldConfig": {"defaults": {"unit": unit, "min": 0}, "overrides": []},
        "options": {"legend": {"displayMode": "table", "placement": "bottom"}},
    }


def write(name, panels, variable=None, description=""):
    for i, p in enumerate(panels):
        p["id"] = i + 1
        p["gridPos"] = {"x": (i % 2) * 12, "y": (i // 2) * 8, "w": 12, "h": 8}
    variables = []
    if variable:
        key, metric = variable
        variables.append({
            "name": key, "type": "query", "datasource": DATASOURCE,
            "query": f"label_values({metric}, {key})", "refresh": 1,
            "includeAll": True, "allValue": ".*", "multi": True,
            "current": {"text": "All", "value": "$__all"},
        })
    dashboard = {
        "uid": "gregfana-" + name, "title": "Gregfana / " + name.title(),
        "description": description, "schemaVersion": 39, "version": 1,
        "tags": ["gregfana", "gtnh"], "timezone": "browser", "refresh": "15s",
        "time": {"from": "now-6h", "to": "now"},
        "templating": {"list": variables}, "panels": panels,
    }
    ROOT.mkdir(parents=True, exist_ok=True)
    (ROOT / f"{name}.json").write_text(json.dumps(dashboard, indent=2) + "\n", encoding="utf-8")


def players():
    panels = []
    for metric, title, unit in [
        ("play_seconds", "Lifetime playtime", "s"),
        ("afk_seconds", "Lifetime AFK time (ServerUtilities)", "s"),
        ("active_seconds", "Lifetime active time", "s"),
        ("blocks_mined", "Lifetime blocks mined", "short"),
        ("items_crafted", "Lifetime crafted items", "short"),
        ("items_used", "Lifetime item uses (not consumption)", "short"),
        ("distance_meters", "Lifetime movement", "lengthm"),
        ("deaths", "Lifetime deaths", "short"),
        ("mob_kills", "Lifetime mob kills", "short"),
        ("player_kills", "Lifetime player kills", "short"),
    ]:
        expr = (f'(gregfana_player_{metric}_observed{{player=~"$player"}} + '
                f'gregfana_player_{metric}_imported{{player=~"$player"}}) '
                '* on(player) group_left(name) gregfana_player_info')
        panels.append(panel(title, expr, unit, legend="{{name}}"))
    for metric, title in [("crafting_operations", "Manual crafting operations / minute"),
                          ("manual_crafting_items", "Manual crafting output items / minute"),
                          ("items_consumed", "Food/potion items consumed / minute")]:
        panels.append(panel(title, f'rate(gregfana_player_{metric}_total{{player=~"$player"}}[$__rate_interval]) * 60'))
    panels.append(panel("Mining / minute (new activity only)",
                        'clamp_min(deriv(gregfana_player_blocks_mined_observed{player=~"$player"}[5m]), 0) * 60'))
    panels.append(panel("Registered statistics: observed since installation", 
                        'gregfana_player_stat_observed{player=~"$player"}', legend="{{player}} / {{stat}}"))
    panels.append(panel("AFK source available", 'gregfana_source_available{source="serverutilities_afk"}', kind="stat"))
    write("players", panels, ("player", "gregfana_player_info"),
          "Lifetime = observed + optional imported baseline. Time is game ticks / 20, not wall time. "
          "Imports never affect activity rates. Missing data does not imply zero; check source health.")


def production():
    panels = [
        panel("Loaded machines", 'gregfana_machines_loaded{machine=~"$machine"}', legend="{{machine}}"),
        panel("Active machines", 'gregfana_machines_active{machine=~"$machine"}', legend="{{machine}}"),
        panel("Supported configurations", 'gregfana_machines_supported{machine=~"$machine"}', legend="{{machine}}"),
        panel("Unsupported configurations", 'gregfana_machines_loaded{machine=~"$machine"} - gregfana_machines_supported{machine=~"$machine"}', legend="{{machine}}"),
    ]
    for metric, title in [
        ("runs_started", "Processing runs started / minute"),
        ("runs_completed", "Processing runs completed / minute"),
        ("recipes_completed", "Recipe executions completed / minute (includes parallelism)"),
        ("items_consumed", "Input items consumed / minute"),
        ("items_produced", "Output items generated / minute"),
        ("fluid_consumed_millibuckets", "Input fluid mB / minute"),
        ("fluid_produced_millibuckets", "Output fluid mB / minute"),
    ]:
        panels.append(panel(title, f'rate(gregfana_machine_{metric}_total{{machine=~"$machine"}}[$__rate_interval]) * 60',
                            legend="{{machine}} / {{recipe_map}}"))
    panels.append(panel("Produced items by resource / minute",
        'rate(gregfana_machine_items_produced_by_resource_total{machine=~"$machine"}[$__rate_interval]) * 60',
        legend="{{machine}} / {{item}}"))
    panels.append(panel("Consumed items by resource / minute",
        'rate(gregfana_machine_items_consumed_by_resource_total{machine=~"$machine"}[$__rate_interval]) * 60',
        legend="{{machine}} / {{item}}"))
    write("production", panels, ("machine", "gregfana_machines_loaded"),
          "Only supported processing is counted. Outputs are generated resources at completion, including voided output; "
          "they are not inventory deliveries. ME/dual-input/custom processing is unavailable. See coverage documentation.")


def health():
    write("health", [
        panel("Prometheus scrape up", 'up{job="gregfana"}', kind="stat"),
        panel("Snapshot age", 'time() - gregfana_snapshot_timestamp_seconds', "s"),
        panel("Series in exporter", "gregfana_series", kind="stat"),
        panel("Dropped detail samples / second", "rate(gregfana_detail_dropped_total[$__rate_interval])"),
        panel("Checkpoint healthy", "gregfana_checkpoint_healthy", kind="stat"),
        panel("Checkpoint age", "time() - gregfana_checkpoint_last_success_timestamp_seconds", "s"),
        panel("Collection pass duration", "gregfana_collection_seconds", "s"),
        panel("Source availability", "gregfana_source_available", legend="{{source}}"),
        panel("Source resets", "gregfana_source_resets"),
        panel("Machine hook errors / second", "rate(gregfana_machine_hook_errors_total[$__rate_interval])"),
    ], description="A stale snapshot can indicate collector failure even while HTTP is up.")


if __name__ == "__main__":
    players()
    production()
    health()
