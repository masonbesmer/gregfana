# Gregfana

Server-side Minecraft 1.7.10 / **GT New Horizons 2.9.0** statistics for Prometheus and Grafana.

Gregfana reads the same numeric Minecraft statistics as ServerUtilities leaderboards, including its AFK statistic. It adds manual crafting, food/potion consumption, and supported GregTech processing instrumentation. No additional client mod is required.

## Build

Use a **JDK 25 or newer** to run the maintained GTNH Gradle build. Gradle provisions the Java 8 compilation toolchain; the resulting mod uses Java 8 bytecode and supports GTNH's Java 8 and modern-Java distributions.

```powershell
./gradlew.bat build
```

Linux/macOS: `./gradlew build`. The distributable is `build/libs/gregfana-0.1.0.jar`, not the `-dev` or `-sources` jar. `build` runs unit/contract tests, formatting checks, Checkstyle, and reobfuscation. Run `spotlessApply` to format changes.

The build pins GT5-Unofficial **5.09.54.265**, ServerUtilities **2.4.22**, UniMixins **0.3.1**, Forge **10.13.4.1614**, and Minecraft **1.7.10**. The first build downloads the GTNH toolchain and Minecraft dependencies. See [dependency provenance](docs/dependencies.md).

## Install

1. Stop the GTNH server and back up its world.
2. Place `gregfana-0.1.0.jar` in the server's `mods` directory. GTNH already supplies its dependencies.
3. Start the server. Configuration is generated at `config/gregfana.cfg`.
4. Run `/gregfana status` as an operator, or `gregfana status` in the server console.
5. Verify `http://127.0.0.1:9400/metrics` on the server host.

Collection begins at installation. Existing statistics become baselines, not new activity. Counters and baselines persist in `<world>/gregfana/state.json` with a backup at `state.json.bak`.

## Grafana and Prometheus

The mod defaults to loopback. **A Docker container cannot scrape the host's loopback address.** For the supplied Compose stack, set `exporter.bind` in `config/gregfana.cfg` to a host interface reachable from Docker (or `0.0.0.0` on a host whose firewall restricts port 9400 to your monitoring network), then restart the game server.

```powershell
docker compose -f monitoring/compose.yaml up -d
```

Open [Grafana](http://localhost:3000) and log in with the image's initial `admin` / `admin` credentials; Grafana asks you to change the password. Four dashboards appear in the **Gregfana** folder. [Prometheus](http://localhost:9090) is also bound to host loopback. The exporter has no authentication; keep it on a trusted monitoring network.

`monitoring/prometheus.yml` scrapes `host.docker.internal:9400` every 15 seconds. Compose provides the Linux host-gateway mapping; Docker Desktop supplies host routing on Windows/macOS. If Minecraft runs on another host or in another container, replace the target with that reachable hostname and port. Prometheus stores 90 days of time series; both services use persistent named volumes. `docker compose down` preserves them.

## Historical imports

Operators (permission level 2) and the console can run:

```text
/gregfana import <exact-name|UUID|all> preview
/gregfana import <exact-name|UUID|all> apply
```

Preview lists each available historical statistic and its raw value. Apply imports each player's initial statistic baseline once; repeating it adds nothing. Offline players known to ServerUtilities are supported. No Minecraft/ServerUtilities statistics are changed. Imported values are separate gauges, so imports do not create activity-rate spikes. Lifetime panels combine imported history and observed activity. There is no fabricated historical time series or machine-production backfill.

## Configuration and accounting

| Setting | Default | Meaning |
|---|---|---|
| `exporter.bind` | `127.0.0.1` | HTTP bind address |
| `exporter.port` | `9400` | HTTP port |
| `collection.seriesLimit` | `50000` | Hard series budget, with 10% reserved for aggregate/health series |
| `collection.statFilter` | `.*` | Full-match regular expression for registered statistic IDs |
| `collection.machineFilter` | `.*` | Full-match regular expression for machine implementation class names |

Restart after configuration changes. Detail is dropped before aggregates when the budget fills; `gregfana_detail_dropped_total` makes this visible. Player labels are UUIDs; names are kept in `gregfana_player_info`. Item identities use registry names and subtype metadata, not durability or NBT.

Time uses the leaderboard's game ticks divided by 20, **not wall-clock time during lag**. Active time is playtime minus ServerUtilities AFK time. Disabled AFK tracking is unavailable; Gregfana does not substitute a second AFK detector. Manual crafting operations and output quantities are separate. Item uses are not consumption: food/potion consumption has its own counter.

GT production counts actual physical-input decreases and generated output buffers at recipe completion. Outputs include items the machine subsequently voids; these counters do not represent deliveries into storage. Recipe executions include parallel/batch counts. Unsupported machines/configurations have coverage gauges but no invented production counters. See [coverage](docs/coverage.md) and [metrics](docs/metrics.md).

## Operations

- Game-state collection and accounting run on the server thread. HTTP reads immutable snapshots, normally refreshed every 100 ticks. A stopped Prometheus/Grafana does not stop collection.
- Checkpoints copy state on the server thread and serialize/write on a background thread every 1,200 ticks and at clean shutdown. A crash can lose activity since the last checkpoint (normally up to 60 seconds; longer under lag or IO failure).
- Back up `state.json` and `state.json.bak` with the world. To restore, stop the server first. Corrupt primary checkpoints recover from a valid backup; if neither is readable, collection disables without overwriting history. Do not delete state files during upgrades unless you intend to reset tracking.
- Source-stat decreases are treated as resets; observed counters remain cumulative and `gregfana_source_resets` increases. Negative vanilla values from integer overflow cannot reconstruct missing history and are ignored.
- A busy/unreachable port disables HTTP but preserves collection. Check console logs and `/gregfana status`. The health dashboard reports stale snapshots, checkpoint failures, dropped detail, source availability, and hook errors.
- Test modpack upgrades in a separate world. Hook contracts are pinned to 2.9.0; custom machine paths and changed GT releases need a new audit.

See [validation](docs/validation.md) for automated checks, recorded results, and the in-world acceptance checklist. To change dashboards, edit them in Grafana and save, then put `GRAFANA_TOKEN=<service account token>` in a `.env` file at the repo root (gitignored), run `python scripts/pull_dashboards.py`, and commit `monitoring/dashboards/`. The Feed reads Prometheus recording rules in `monitoring/rules/`.
