package dev.gregfana;

import java.io.File;
import java.util.regex.Pattern;

import net.minecraftforge.common.config.Configuration;

public final class Config {

    public final String bind;
    public final int port;
    public final int seriesLimit;
    public final Pattern stats;
    public final Pattern machines;

    public Config(File file) {
        Configuration c = new Configuration(file);
        c.load();
        bind = c
            .getString("bind", "exporter", "127.0.0.1", "Bind address; use a private interface for remote scraping.");
        port = c.getInt("port", "exporter", 9400, 1, 65535, "Prometheus HTTP port.");
        seriesLimit = c.getInt(
            "seriesLimit",
            "collection",
            50000,
            1000,
            1000000,
            "Hard series limit; 10% reserved for aggregates.");
        stats = Pattern
            .compile(c.getString("statFilter", "collection", ".*", "Full-match regex for registered statistic IDs."));
        machines = Pattern
            .compile(c.getString("machineFilter", "collection", ".*", "Full-match regex for machine class names."));
        c.save();
    }
}
