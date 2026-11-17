# Validation

## Automated checks

Run `./gradlew build` with JDK 25+. Tests cover:

- Player baselines, offline UUIDs, idempotent imports, reset handling, negative values, and independent snapshots.
- Series-budget detail rejection, aggregate reserve, escaping, counter restoration, and invalid increments.
- Atomic checkpoint round trips, immutable asynchronous snapshots, backup recovery, and refusal to silently replace corrupt history.
- Concurrent HTTP scrapes, complete snapshots, exact routing, and method rejection.
- Physical input changes, alias deduplication, catalysts, and unsuccessful checks with unchanged inputs.
- Bytecode contracts against GT5-Unofficial 5.09.54.265: recipe-check targets and completion anchors before buffer transfer/clear.
- Dedicated-server class inspection with deliberately missing client-only types, plus a check that the single-block completion hook is outside all output loops.
- `python scripts/check_jar.py` verifies production Mixin metadata/refmaps and Java 8 bytecode; it prevents packaging omissions from passing unnoticed.

The standalone test fixture `dev.gregfana.integration.SmokeExporter` runs for 60 seconds on port 9400 and exports **synthetic** data. It is compiled into test classes only, never into the distributable mod. It is useful for verifying Docker host routing and dashboard expressions without inventing game data.

## Monitoring checks performed

On Windows 11 with Docker Desktop:

- `docker compose -f monitoring/compose.yaml config --quiet` passed.
- Prometheus `promtool check config` passed.
- Prometheus scraped the actual Java HTTP exporter fixture through `host.docker.internal:9400`.
- Observed 200 + imported 100 returned lifetime 300 from Prometheus.
- Every generated dashboard panel expression evaluated without a PromQL error.
- Grafana provisioned Players, Production, and Health dashboards; its Prometheus data-source health check returned OK.

These checks validate infrastructure and query syntax, not game event semantics.

## Dedicated-server results (2026-11-17)

Tested in an isolated copy of the official GTNH 2.9.0 Java 8 server pack on Windows 11, Temurin 1.8.0_512, with a 6 GB maximum heap and 295 loaded mods. No existing user installation/world was changed.

- Production jar booted, early/late mixins applied, the console command worked, and the exporter/checkpoints were healthy.
- An offline ServerUtilities fixture containing 1,200 play ticks, 400 AFK ticks, and two deaths imported as 60 play seconds, 20 AFK seconds, 40 active seconds, and two deaths. Preview listed three baselines; first apply imported three, subsequent applies imported zero. Observed activity stayed zero and the source statistics file stayed unchanged.
- A physical LV macerator processed cobblestone. Input decrements and recipe starts matched, and three completed recipes produced three output items before the empty server stopped ticking tile entities. This test found and fixed an output-loop double-counting bug; the completion hook now runs once after progress increments.
- A deliberately seeded in-progress single-block output buffer was saved at progress 900/1000, then the server was stopped and restarted. Completed runs advanced exactly once (17 to 18), produced items advanced by three (24 to 27), and inputs/starts stayed unchanged. Imported player baselines survived the restart unchanged. These absolute totals include earlier test fixtures; the stated deltas are the assertions.
- EBF and macerator controllers reported supported coverage. A multi-furnace controller reported loaded but unsupported (`machines_supported = 0`). No production zeros were invented for it.
- Including an EBF exposed a dedicated-server reflection failure caused by unrelated client-only signatures. The final implementation uses bytecode-based declaration lookup and targeted method handles, with a regression test and linkage-error containment. The subsequent server run remained healthy with both controllers present.
- The final automated build passed **16 tests**, formatting, Checkstyle, reobfuscation, and production-jar metadata validation.

Not yet verified: a real client connection without the mod, live player AFK/crafting interactions, fully assembled parallel multiblock recipes, all blocked/chance-output scenarios, and the 1,000-machine performance target. Coverage is intentionally conservative until those scenarios are exercised. The test server was stopped after validation.

## In-world acceptance checklist

Use a disposable GTNH 2.9.0 dedicated-server world. Record exact pack/JVM, CPU, memory, player count, loaded machine count, and results. A unit or bytecode-contract test does not replace these scenarios:

1. Connect using an unmodified GTNH client without Gregfana. Confirm `/gregfana status` and `/metrics`.
2. Compare raw playtime, AFK, deaths, kills, mining, and crafting values against ServerUtilities/Minecraft. Cross an AFK transition using SU's configured timer; verify active = play − AFK.
3. Preview/import an online and offline player; import twice. Confirm lifetime totals change once and activity-rate series do not jump.
4. Craft single and shift-click batches (including a multi-output recipe such as planks). Compare operation and output-item counters.
5. Consume food and a potion; use a durable tool. Only completed food/potion use increments the consumption counter.
6. Run a standard macerator, fluid-only recipe, and standard parallel multiblock. Compare actual input deltas, completed runs, parallel executions, and realized output quantities.
7. Test catalysts, chance outputs, full/voiding output hatches, failed checks, power interruption, and machine removal. Verify no transfers or interrupted runs become completions.
8. Unload/reload an active controller, then restart the server during a parallel recipe. Check loaded gauges, persisted totals, and `lastParallel` completion accounting.
9. Attach ME/dual-input hatches or use a custom-processing controller. Verify unavailable coverage without fabricated production zeros.
10. Stop Prometheus/Grafana, exercise gameplay, restart monitoring, and verify cumulative counters persisted. Restart the game server and verify historical imports remain idempotent.
11. Measure baseline tick time versus Gregfana enabled with 1,000 loaded machines for at least 5 minutes each. Include idle and actively processing workloads. Target under 1 ms average added tick time. Record p95/p99 and worst collection/checkpoint pauses as well.

The 1,000-machine overhead target and multiplayer gameplay checklist are acceptance targets, not claimed benchmark results until run and recorded.
