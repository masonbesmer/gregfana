# Metric contract (v1)

All metrics use the `gregfana_` prefix. No arbitrary NBT, coordinates, recipe IDs, or display names are embedded in production labels.

| Family | Type / labels | Meaning |
|---|---|---|
| `player_stat_observed`, `player_stat_imported` | Gauge; `player`, `stat`, plus `action`, `item` on per-item stats | Raw registered-stat units since installation / optional historical baseline |
| `player_<category>_observed`, `player_<category>_imported` | Gauge; `player` | Aggregated categories listed below |
| `player_info` | Gauge=1; `player`, `name` | UUID-to-current-name mapping |
| `player_online` | Gauge; `player` | Online state |
| `player_crafting_operations_total` | Counter; `player` | Completed supported manual crafting pickups |
| `player_manual_crafting_items_total` | Counter; `player` | Actual manual crafting output quantity |
| `player_items_consumed_total` | Counter; `player` | Completed food/potion consumption |
| `player_blocks_placed_total` | Counter; `player` | Block placements by players (Forge `PlaceEvent`, not cancelled); counted from installation of this version |
| `player_<action>_by_item_total` | Counter; `player`, `item` | Detail for the preceding four action counters; `item` is `registry:meta` |
| `machine_runs_started_total`, `machine_runs_completed_total` | Counter; `machine`, `recipe_map` | Processing runs |
| `machine_recipes_completed_total` | Counter; `machine`, `recipe_map` | Executions including GT parallel/batch multiplier |
| `machine_items_consumed_total`, `machine_items_produced_total` | Counter; `machine`, `recipe_map` | Physical input units / generated outputs |
| `machine_fluid_consumed_millibuckets_total`, `machine_fluid_produced_millibuckets_total` | Counter; `machine`, `recipe_map` | Fluid amounts in mB |
| `machine_<resource metric>_by_resource_total` | Counter; preceding labels plus `item` or `fluid` | Item/fluid breakdowns |
| `machines_loaded`, `machines_active`, `machines_supported`, `machines_inputs_untracked` | Gauge; `machine` | Loaded processing controller counts and coverage; inputs_untracked = counted for completions/outputs only |
| `source_available` | Gauge; `source` | 1 = available; 0 = disabled/unavailable |
| `source_resets` | Gauge | Persisted count of source-stat resets detected |
| `series` | Gauge | Number of stored series (excluding renderer diagnostics) |
| `detail_dropped_total` | Counter | Samples rejected by series budget; resets with exporter process |
| `machine_hook_errors_total` | Counter | Caught instrumentation failures |
| `collection_seconds` | Gauge | Last player/machine collection pass duration (excludes serialization and HTTP) |
| `snapshot_timestamp_seconds` | Gauge | Unix timestamp of last successful published snapshot |
| `checkpoint_healthy`, `checkpoint_last_success_timestamp_seconds` | Gauge | Persistence health and last successful disk write |

Player categories: `play_seconds`, `afk_seconds`, `active_seconds`, `blocks_mined`, `items_crafted`, `items_used`, `distance_meters`, `deaths`, `mob_kills`, `player_kills`. Time and distance are converted from tick and centimeter statistics. Raw generic metrics retain source units; achievements and unusual mod statistics remain numeric values.

Source-derived values are gauges because source statistics may be reset/changed externally, and derived active-time values can change when AFK settings change. Use `clamp_min(deriv(..._observed[5m]), 0)` for source-derived activity trends. Event/machine counters use `rate(..._total[5m])`. Never apply rate calculations to imported history or lifetime sums.

Only known nonzero statistics and previously observed statistics are materialized. Missing statistics may be filtered, unsupported, or not yet observed. The coverage/health panels must be consulted before interpreting an absent series.

Per-item statistics (`stat.mineBlock.*`, `stat.craftItem.*`, `stat.useItem.*`, `stat.breakItem.*`) carry `action` = `mined` / `crafted` / `used` / `broken` and `item` = registry name (e.g. `minecraft:log`, `Natura:BerryBush`). Vanilla numeric IDs are resolved through the block (mined) or item registry; an unresolvable ID stays numeric. Stats do not distinguish metadata. Minecraft has no placement statistic: placing a block increments `used`, while `player_blocks_placed_*` counts placements directly.
