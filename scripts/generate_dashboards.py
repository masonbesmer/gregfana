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


def bars(title, expr, legend):
    """Horizontal bar gauge of the latest value per series (instant query)."""
    p = panel(title, expr, kind="bargauge", legend=legend)
    p["targets"][0]["instant"] = True
    p["options"] = {"orientation": "horizontal", "displayMode": "gradient", "showUnfilled": True,
                    "reduceOptions": {"calcs": ["lastNotNull"], "values": False}}
    return p


def variable(key, metric, label=None):
    return {
        "name": key, "label": label or key, "type": "query", "datasource": DATASOURCE,
        "query": f"label_values({metric}, {key})", "refresh": 2, "sort": 1,
        "includeAll": True, "allValue": ".*", "multi": True,
        "current": {"text": "All", "value": "$__all"},
    }


def write(name, panels, variables=(), description=""):
    for i, p in enumerate(panels):
        p["id"] = i + 1
        p.setdefault("gridPos", {"x": (i % 2) * 12, "y": (i // 2) * 8, "w": 12, "h": 8})
    variables = list(variables)
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
    # Damage stats are damage x 10 in half-hearts: / 20 = hearts.
    def raw(stat_re):
        sel = f'{{stat=~"{stat_re}",player=~"$player"}}'
        return f"(gregfana_player_stat_observed{sel} + gregfana_player_stat_imported{sel})"
    named = " * on(player) group_left(name) gregfana_player_info"
    for stat, title in [("stat.damageDealt", "Lifetime damage dealt (hearts)"),
                        ("stat.damageTaken", "Lifetime damage taken (hearts)")]:
        panels.append(panel(title, f"sum by (name) ({raw(stat)} / 20{named})", legend="{{name}}"))
    panels.append(panel(
        "Damage / minute (hearts, new activity only)",
        'sum by (name, stat) (clamp_min(deriv(gregfana_player_stat_observed{stat=~"stat.damage(Dealt|Taken)",'
        f'player=~"$player"}}[5m]), 0) * 60 / 20{named})', legend="{{name}} {{stat}}"))
    for prefix, title in [("stat.killEntity.", "Lifetime kills by mob (top 25)"),
                          ("stat.entityKilledBy.", "Lifetime deaths by mob (top 25)")]:
        panels.append(bars(title, f'topk(25, sum by (mob) (label_replace({raw(prefix + ".*")}, "mob", "$1", "stat", '
                                  f'"{prefix}(.*)")))', "{{mob}}"))
    # Per-item breakdowns. Stat items are registry names; event counters append ":meta".
    stat = 'player=~"$player",action=~"$action",item=~"$item"'
    lifetime = f'(gregfana_player_stat_observed{{{stat}}} + gregfana_player_stat_imported{{{stat}}})'
    placed = 'gregfana_player_blocks_placed_by_item_total{player=~"$player",item=~"($item)(:[0-9]+)?"}'
    panels += [
        bars("Lifetime items by action (top 25)", f"topk(25, sum by (action, item) ({lifetime}))",
             "{{action}} {{item}}"),
        bars("Lifetime selected items by player (top 25)",
             f"topk(25, sum by (name, action, item) ({lifetime} * on(player) group_left(name) gregfana_player_info))",
             "{{name}} {{action}} {{item}}"),
        panel("Items / minute by action (new activity only)",
              f'sum by (action, item) (clamp_min(deriv(gregfana_player_stat_observed{{{stat}}}[5m]), 0)) * 60',
              legend="{{action}} {{item}}"),
        panel("Blocks placed / minute by item",
              f"sum by (item) (rate({placed}[$__rate_interval])) * 60", legend="{{item}}"),
        bars("Blocks placed since installation (top 25)", f"topk(25, sum by (item) ({placed}))", "{{item}}"),
        bars("Blocks placed by player",
             f"sum by (name) ({placed} * on(player) group_left(name) gregfana_player_info)", "{{name}}"),
    ]
    write("players", panels, [variable("player", "gregfana_player_info"),
                              variable("action", "gregfana_player_stat_observed", "Action"),
                              variable("item", 'gregfana_player_stat_observed{action=~"$action"}', "Item")],
          "Lifetime = observed + optional imported baseline. Time is game ticks / 20, not wall time. "
          "Imports never affect activity rates. Missing data does not imply zero; check source health. "
          "Action/Item filter the per-item panels; 'used' includes block placement, 'Blocks placed' counts only "
          "placements since this feature was installed.")


def production():
    panels = [
        panel("Loaded machines", 'gregfana_machines_loaded{machine=~"$machine"}', legend="{{machine}}"),
        panel("Active machines", 'gregfana_machines_active{machine=~"$machine"}', legend="{{machine}}"),
        panel("Supported configurations", 'gregfana_machines_supported{machine=~"$machine"}', legend="{{machine}}"),
        panel("Unsupported configurations", 'gregfana_machines_loaded{machine=~"$machine"} - gregfana_machines_supported{machine=~"$machine"}', legend="{{machine}}"),
        panel("Outputs only (inputs untracked: ME/dual/custom inputs)", 'gregfana_machines_inputs_untracked{machine=~"$machine"}', legend="{{machine}}"),
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
    write("production", panels, [variable("machine", "gregfana_machines_loaded")],
          "Only supported processing is counted. Outputs are generated resources at completion, including voided output; "
          "they are not inventory deliveries. ME/dual-input/custom processing is unavailable. See coverage documentation.")


ACTION_RE = "${action:regex}"
ITEM = 'item=~"(?i).*${item}.*"'
NAME = 'gregfana_player_info{name=~"$player"}'


def gate(action):
    """Pass-through vector iff `action` matches the Action filter (PromQL cannot filter labels after math)."""
    return (f'(label_replace(label_replace(vector(1), "a", "{action}", "", ""), "ok", "y", "a", "{ACTION_RE}") '
            'and on(ok) label_replace(vector(1), "ok", "y", "", ""))')


def tagged(expr, action):
    return f'(label_replace({expr}, "action", "{action}", "", "") and on() {gate(action)})'


def per_player(expr):
    return f"sum by (name, item) ({expr} * on(player) group_left(name) {NAME})"


def feed_table(title, expr, who, y):
    """One row per 1-minute bucket with activity; newest first."""
    return {
        "title": title, "type": "table", "datasource": DATASOURCE, "interval": "1m",
        "gridPos": {"x": 0, "y": y, "w": 24, "h": 16},
        "targets": [{"refId": "A", "expr": expr, "format": "table", "range": True, "instant": False,
                     "interval": "1m"}],
        "fieldConfig": {"defaults": {"custom": {"filterable": True, "align": "left"}}, "overrides": [
            {"matcher": {"id": "byName", "options": "Time"}, "properties": [{"id": "custom.width", "value": 170}]},
            {"matcher": {"id": "byName", "options": "Count"}, "properties": [{"id": "custom.width", "value": 90}]},
        ]},
        "options": {"showHeader": True, "cellHeight": "sm", "footer": {"show": False}},
        "transformations": [
            {"id": "organize", "options": {
                "excludeByName": {"__name__": True},
                "indexByName": {"Time": 0, who: 1, "action": 2, "item": 3, "Value": 4},
                "renameByName": {who: who.title(), "action": "Action", "item": "Item", "Value": "Count"}}},
            {"id": "sortBy", "options": {"sort": [{"field": "Time", "desc": True}]}},
        ],
    }


def feed():
    inc = "round(increase({}[1m]))"
    # action!="" excludes non-item stats (playtime, distance), which an all-matching regex would include.
    stat = f'gregfana_player_stat_observed{{action!="",action=~"{ACTION_RE}",{ITEM}}}'
    player_parts = [
        # Per-item stats already carry action = mined / crafted / used / broken.
        f"sum by (name, action, item) ({inc.format(stat)} * on(player) group_left(name) {NAME})",
        tagged(per_player(inc.format(f"gregfana_player_blocks_placed_by_item_total{{{ITEM}}}")), "placed"),
        tagged(per_player(inc.format(f"gregfana_player_items_consumed_by_item_total{{{ITEM}}}")), "consumed"),
    ]
    for stat_id, action in [("stat.deaths", "died"), ("stat.playerKills", "killed player")]:
        player_parts.append(tagged(per_player(inc.format(f'gregfana_player_stat_observed{{stat="{stat_id}"}}')),
                                   action))
    # Per-mob kills/deaths: the mob name becomes the item, so "Item contains" filters by mob.
    for prefix, action in [("stat.killEntity.", "killed"), ("stat.entityKilledBy.", "killed by")]:
        sel = f'gregfana_player_stat_observed{{stat=~"{prefix}.*",stat=~"(?i).*${{item}}.*"}}'
        player_parts.append(tagged(per_player(
            f'label_replace({inc.format(sel)}, "item", "$1", "stat", "{prefix}(.*)")'), action))
    # Damage x 10 in half-hearts -> hearts, one decimal.
    for stat_id, action in [("stat.damageDealt", "dealt damage"), ("stat.damageTaken", "took damage")]:
        sel = f'increase(gregfana_player_stat_observed{{stat="{stat_id}"}}[1m]) / 20'
        player_parts.append(tagged(per_player(f'label_replace(round({sel}, 0.1), "item", "hearts", "", "")'),
                                   action))
    online = "(gregfana_player_online - gregfana_player_online offset 1m)"
    player_parts.append(tagged(per_player(f"({online} == 1)"), "joined"))
    player_parts.append(tagged(per_player(f"abs({online} == -1)"), "left"))
    players = "(" + " or ".join(player_parts) + ") > 0"

    machine_parts = []
    for metric, key, action in [("items_produced", "item", "produced"), ("items_consumed", "item", "used input"),
                                ("fluid_produced_millibuckets", "fluid", "produced mB"),
                                ("fluid_consumed_millibuckets", "fluid", "used input mB")]:
        series = f'gregfana_machine_{metric}_by_resource_total{{machine=~"$machine",{ITEM.replace("item", key, 1)}}}'
        body = f"sum by (machine, {key}) ({inc.format(series)})"
        if key == "fluid":
            body = f'sum by (machine, item) (label_replace({body}, "item", "$1", "fluid", "(.*)"))'
        machine_parts.append(tagged(body, action))
    machines = "(" + " or ".join(machine_parts) + ") > 0"

    actions = ["mined", "placed", "crafted", "used", "broken", "consumed", "killed", "killed by", "died",
               "killed player", "dealt damage", "took damage", "joined", "left", "produced", "used input", "produced mB", "used input mB"]
    variables = [
        {"name": "action", "label": "Action", "type": "custom", "query": ",".join(actions),
         "includeAll": True, "allValue": ".*", "multi": True, "current": {"text": "All", "value": "$__all"}},
        variable("player", "gregfana_player_info", "Player") | {"query": "label_values(gregfana_player_info, name)"},
        variable("machine", '{__name__=~"gregfana_machine_.*_by_resource_total"}', "Machine"),
        {"name": "item", "label": "Item contains", "type": "textbox", "query": "", "current": {"text": "", "value": ""}},
    ]
    write("feed", [
        feed_table("Player feed", players, "name", 0),
        feed_table("Machine feed", machines, "machine", 16),
    ], variables,
        "Activity per 1-minute bucket, newest first. Built from Prometheus counters: exact order within a minute, "
        "coordinates and per-kill targets are not recorded. Column headers have their own filters too.")


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
    feed()
    health()
