package dev.gregfana.core;

import java.util.HashMap;
import java.util.Map;

/** Baselines are immutable historical totals; last values are reset-aware cursors. */
public final class PlayerLedger {

    public static final class Entry {

        public long baseline;
        public long last;
        public long observed;
        public boolean imported;
    }

    public Map<String, Map<String, Entry>> players = new HashMap<>();
    public long resets;

    public Entry observe(String player, String stat, long value) {
        Map<String, Entry> entries = players.computeIfAbsent(player, k -> new HashMap<>());
        Entry e = entries.get(stat);
        if (e == null) {
            e = new Entry();
            e.baseline = Math.max(0, value);
            e.last = value;
            entries.put(stat, e);
        } else if (value >= 0) {
            if (value < e.last) {
                resets++;
                e.observed += value;
            } else {
                e.observed += value - Math.max(0, e.last);
            }
            e.last = value;
        }
        return e;
    }

    public int importHistory(String player, boolean apply) {
        int count = 0;
        for (Entry e : players.getOrDefault(player, new HashMap<>())
            .values()) {
            if (!e.imported && e.baseline > 0) {
                count++;
                if (apply) e.imported = true;
            }
        }
        return count;
    }

    public PlayerLedger copy() {
        PlayerLedger copy = new PlayerLedger();
        copy.resets = resets;
        players.forEach((player, stats) -> {
            Map<String, Entry> target = new HashMap<>();
            stats.forEach((stat, entry) -> {
                Entry e = new Entry();
                e.baseline = entry.baseline;
                e.last = entry.last;
                e.observed = entry.observed;
                e.imported = entry.imported;
                target.put(stat, e);
            });
            copy.players.put(player, target);
        });
        return copy;
    }
}
