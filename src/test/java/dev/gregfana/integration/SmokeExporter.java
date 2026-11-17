package dev.gregfana.integration;

import dev.gregfana.core.Exporter;
import dev.gregfana.core.Metrics;

/** Short-lived synthetic fixture for checking the Docker scrape path, never a game-data source. */
public final class SmokeExporter {

    public static void main(String[] args) throws Exception {
        try (Exporter exporter = new Exporter("0.0.0.0", 9400)) {
            Metrics metrics = new Metrics(50000);
            metrics.gauge("gregfana_test_fixture", Metrics.labels(), 1, false);
            metrics.gauge("gregfana_player_info", Metrics.labels("player", "test-player", "name", "Fixture"), 1, true);
            metrics.gauge("gregfana_player_play_seconds_observed", Metrics.labels("player", "test-player"), 200, false);
            metrics.gauge("gregfana_player_play_seconds_imported", Metrics.labels("player", "test-player"), 100, false);
            metrics.gauge("gregfana_checkpoint_healthy", Metrics.labels(), 1, false);
            exporter.publish(metrics.render());
            System.out.println("Synthetic smoke exporter running for 60 seconds on :9400");
            Thread.sleep(60000);
        }
    }
}
