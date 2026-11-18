# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Gregfana is a server-side Forge mod for Minecraft 1.7.10 / GT New Horizons 2.9.0. It exports player statistics and GregTech machine production to Prometheus, with Grafana dashboards in `monitoring/`. Users read README.md; docs/metrics.md is the metric reference, and docs/coverage.md says which machines are tracked.

## Commands

Gradle needs JDK 25+. It provisions the Java 8 toolchain itself, and the mod is compiled to Java 8 bytecode. On this machine JAVA_HOME is often unset; use the Gradle-cached JDK:

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2
./gradlew spotlessApply build --offline -q     # format, then compile + tests + Checkstyle + reobf
./gradlew test --tests dev.gregfana.source.ItemsTest            # single test class
./gradlew test --tests 'dev.gregfana.integration.HookContractTest.cokeOven*'   # single method
python scripts/check_jar.py                    # verifies Mixin metadata/refmaps and Java 8 bytecode in build/libs/gregfana-0.1.0.jar
GRAFANA_TOKEN=... python scripts/pull_dashboards.py   # saves dashboards edited in Grafana to monitoring/dashboards/
```

- `build` fails on Spotless formatting. Run `spotlessApply` before it.
- Build the distributable jar `build/libs/gregfana-0.1.0.jar`, not the `-dev` or `-sources` jar.
- CI (`.github/workflows/build.yml`) runs `build`, `check_jar.py`, `docker compose config`, a JSON check of the dashboards, and `promtool check rules`.

## Architecture

**Threading model.** All collection and accounting happen on the server thread, in `Gregfana.tick`:
- Every 100 ticks: `Players.collect` and `Machines.collect`, then `publish()` renders an immutable snapshot.
- `core/Exporter` (HTTP) serves only that snapshot and never touches game state.
- Every 1200 ticks: `core/Checkpoint` copies state on the server thread and writes `<world>/gregfana/state.json` (plus `.bak`) on a background thread.

**Metrics** (`core/Metrics`) is a small in-house registry, not the Prometheus client library.
- `add` creates counters, which persist through the checkpoint. `gauge` creates gauges, which don't persist.
- The `detail` flag marks high-cardinality series. When `collection.seriesLimit` fills, detail series are dropped first (10% of the budget is reserved for aggregates), and each drop counts toward `gregfana_detail_dropped_total`.
- Player labels are UUIDs; `gregfana_player_info` maps them to names.
- Item labels are `registry:meta`, from `source/Items.id`.

**Player stats** (`source/Players` + `core/PlayerLedger`):
- Reads ServerUtilities/vanilla stat files.
- The first sighting of a stat is stored as a baseline, not counted as activity. `/gregfana import` turns baselines into separate `_imported` gauges, so lifetime = observed + imported with no rate spikes.
- Per-item stats (`stat.mineBlock.*`, `craftItem`, `useItem`, `breakItem`) are split into `action`/`item` labels by `Items.stat`. Numeric vanilla IDs resolve through the block/item registries; mod IDs use `autogen.<name>`.
- Event counters (placed, consumed, crafting) go through `Gregfana.action()`. The crafting counter comes from `CraftingMixin`, an early mixin.

**Machine production** (`source/Machines` + late mixins):
- `BasicMachineMixin`, `MultiMachineMixin`, and `CokeOvenMixin` are `@Pseudo`, `remap=false` mixins against GT classes. They are registered in `LateMixins.getMixins`; early mixins are in `EarlyMixins`.
- Hooks snapshot physical inputs before the recipe check (`ResourceSnapshot`) and count outputs at recipe completion.
- Coverage is generic per class, not per machine:
  - `Machines.level()` assigns NONE, OUTPUTS, or FULL based on whether the class inherits the standard GT methods.
  - It checks this with `ClassShape`, which reads bytecode with ASM. Reflection would crash a dedicated server on client-only signatures.
  - Classes that tick but never reach a hook are detected at runtime (the SEEN/HOOKED/STRIKES sets) and downgraded.
- Never invent zero production for unsupported machines. They get coverage gauges only.

**Hook contracts are pinned.**
- The build compiles against GT5-Unofficial `5.09.54.265`, the version GTNH 2.9.0 ships (see `dependencies.gradle`).
- `integration/HookContractTest` checks GT bytecode: injection targets exist, and the completion anchors come before the buffer transfer/clear. Update these tests whenever you add or move a mixin injection point.
- `integration/ClassShapeTest` covers the dedicated-server class inspection.
- `integration/SmokeExporter` is a test-only fixture that exports synthetic data. It must never end up in the jar; `check_jar.py` asserts that.

**Dashboards** (`monitoring/dashboards/*.json`, edited in Grafana):
- Grafana is the editor; the JSON in git is the record. Provisioning has `allowUiUpdates: true`, so saves work in the UI. `scripts/pull_dashboards.py` pulls every dashboard tagged `gregfana` and names the file after the UID minus `gregfana-`.
- Deploying a file overwrites unsaved-to-git UI edits of that dashboard, so pull before deploying.
- The Feed dashboard reads recording rules in `monitoring/rules/feed.yml` (`gregfana:feed_player_events:increase1m`, `gregfana:feed_machine_events:increase1m`). Each sample is one non-empty 1-minute bucket with real `action`/`item` labels, so panels filter with plain matchers. Loki was rejected as too heavy.
- Rules record only from deployment onward. Change the PromQL there, not in the panels.
- Keep `action!=""` in rules that read `gregfana_player_stat_observed`, to exclude non-item stats.

## Deployment (this user's setup)

- **Game server.** It runs as AMP instance `newgreg01`, reached through the xpipe MCP system named `AMP`.
  - Deploy by downloading the CI build, not by patching classes: `https://github.com/masonbesmer/gregfana/releases/download/latest/gregfana.jar`. Every push to main updates this rolling `latest` prerelease.
  - Restart with `sudo su -l amp -c 'ampinstmgr stop newgreg01'`, then the same command with `start`. The server auto-starts with the instance.
  - The exporter on port 9400 binds to the host's LAN interface, not localhost.
- **Monitoring.** Grafana and Prometheus run on host `lostcoast`, in the Komodo stack `gregfana-monitoring`.
  - The stack is not linked to this repo; its compose file is defined inline in Komodo.
  - To deploy dashboards, copy the JSON over the files of the same names in `/etc/komodo/stacks/gregfana-monitoring/dashboards/`. Grafana file provisioning reloads them. Copying under other names creates duplicate dashboard UIDs.
- Recording rules live in `/etc/komodo/stacks/gregfana-monitoring/rules/`. After changing them, restart the `prometheus` service of the stack.
- **xpipe.** sudo works only at the start of a command line. It fails inside pipes or `&&` chains, so run elevated commands on their own.
- **Git.** Push directly to `main`.
