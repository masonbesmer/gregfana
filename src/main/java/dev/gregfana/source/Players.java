package dev.gregfana.source;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.stats.StatBase;
import net.minecraft.stats.StatList;

import dev.gregfana.Config;
import dev.gregfana.core.Metrics;
import dev.gregfana.core.PlayerLedger;
import serverutils.ServerUtilitiesConfig;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

public final class Players {

    private final Config config;
    private final Metrics metrics;
    private final PlayerLedger ledger;
    public final Map<String, String> names = new LinkedHashMap<>();

    public Players(Config config, Metrics metrics, PlayerLedger ledger) {
        this.config = config;
        this.metrics = metrics;
        this.ledger = ledger;
    }

    public boolean afkEnabled() {
        return ServerUtilitiesConfig.afk.enabled;
    }

    public void collect(boolean includeOffline) {
        metrics.clearGauges("gregfana_player_info");
        for (ForgePlayer p : Universe.get()
            .getPlayers()) {
            String id = p.getId()
                .toString();
            names.put(id, p.getName());
            metrics.gauge("gregfana_player_info", Metrics.labels("player", id, "name", p.getName()), 1, true);
            metrics.gauge("gregfana_player_online", Metrics.labels("player", id), p.isOnline() ? 1 : 0, true);
            if (!includeOffline && !p.isOnline()) continue;
            boolean first = !ledger.players.containsKey(id);
            net.minecraft.stats.StatisticsFile source = p.stats();
            for (Object object : StatList.allStats) {
                StatBase stat = (StatBase) object;
                if (!config.stats.matcher(stat.statId)
                    .matches()) continue;
                long raw = source.writeStat(stat);
                if (raw == 0 && !ledger.players.getOrDefault(id, java.util.Collections.emptyMap())
                    .containsKey(stat.statId)) continue;
                if (!first && !ledger.players.get(id)
                    .containsKey(stat.statId)) ledger.observe(id, stat.statId, 0);
                ledger.observe(id, stat.statId, raw);
            }
            ledger.players.computeIfAbsent(id, k -> new LinkedHashMap<>());
        }
        publish();
    }

    public void publish() {
        for (Map.Entry<String, Map<String, PlayerLedger.Entry>> player : ledger.players.entrySet()) {
            Map<String, Double> observed = new LinkedHashMap<>();
            Map<String, Double> history = new LinkedHashMap<>();
            for (Map.Entry<String, PlayerLedger.Entry> stat : player.getValue()
                .entrySet()) {
                String statId = stat.getKey();
                if (!config.stats.matcher(statId)
                    .matches()) continue;
                PlayerLedger.Entry e = stat.getValue();
                Map<String, String> labels = Metrics.labels("player", player.getKey(), "stat", statId);
                // Source values can be administratively reset: generic values are gauges, not counters.
                metrics.gauge("gregfana_player_stat_observed", labels, e.observed, true);
                metrics.gauge("gregfana_player_stat_imported", labels, e.imported ? e.baseline : 0, true);
                String category = category(statId);
                if (category != null) {
                    double scale = category.endsWith("seconds") ? 0.05 : category.equals("distance_meters") ? 0.01 : 1;
                    observed.merge(category, e.observed * scale, Double::sum);
                    history.merge(category, e.imported ? e.baseline * scale : 0, Double::sum);
                }
            }
            if (afkEnabled()) {
                observed.put(
                    "active_seconds",
                    Math.max(0, observed.getOrDefault("play_seconds", 0D) - observed.getOrDefault("afk_seconds", 0D)));
                history.put(
                    "active_seconds",
                    Math.max(0, history.getOrDefault("play_seconds", 0D) - history.getOrDefault("afk_seconds", 0D)));
            }
            observed.forEach(
                (k, v) -> metrics
                    .gauge("gregfana_player_" + k + "_observed", Metrics.labels("player", player.getKey()), v, false));
            history.forEach(
                (k, v) -> metrics
                    .gauge("gregfana_player_" + k + "_imported", Metrics.labels("player", player.getKey()), v, false));
        }
        metrics.gauge(
            "gregfana_source_available",
            Metrics.labels("source", "serverutilities_afk"),
            afkEnabled() ? 1 : 0,
            false);
        metrics.gauge("gregfana_source_resets", Metrics.labels(), ledger.resets, false);
    }

    private String category(String id) {
        if (id.equals("stat.playOneMinute")) return "play_seconds";
        if (id.equals("serverutilities.stat.time_afk")) return afkEnabled() ? "afk_seconds" : null;
        if (id.startsWith("stat.mineBlock.")) return "blocks_mined";
        if (id.startsWith("stat.craftItem.")) return "items_crafted";
        if (id.startsWith("stat.useItem.")) return "items_used";
        if (id.endsWith("OneCm")) return "distance_meters";
        if (id.equals("stat.deaths")) return "deaths";
        if (id.equals("stat.mobKills")) return "mob_kills";
        if (id.equals("stat.playerKills")) return "player_kills";
        return null;
    }
}
