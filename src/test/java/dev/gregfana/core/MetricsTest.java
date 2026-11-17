package dev.gregfana.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class MetricsTest {

    @Test
    public void rendersThousandMachineTypesWithoutLosingSamples() {
        Metrics metrics = new Metrics(50000);
        for (int i = 0; i < 1000; i++) {
            metrics
                .add("gregfana_machine_runs_completed_total", Metrics.labels("machine", "machine_" + i), i + 1, false);
            metrics.gauge("gregfana_machines_loaded", Metrics.labels("machine", "machine_" + i), 1, false);
        }
        String text = metrics.render();
        assertEquals(2000, metrics.size());
        assertEquals(
            1000,
            java.util.Arrays.stream(text.split("\n"))
                .filter(line -> line.startsWith("gregfana_machine_runs_completed_total{"))
                .count());
        assertEquals(
            1000,
            java.util.Arrays.stream(text.split("\n"))
                .filter(line -> line.startsWith("gregfana_machines_loaded{"))
                .count());
    }

    @Test
    public void restoreIntoSmallerBudgetPreservesAggregates() {
        Metrics original = new Metrics(100);
        for (int i = 0; i < 50; i++) original.add("gregfana_detail_total", Metrics.labels("item", "i" + i), 5, true);
        original.add("gregfana_aggregate_total", Metrics.labels(), 250, false);
        Metrics restored = new Metrics(10);
        restored.restore(original.counters());
        assertTrue(
            restored.render()
                .contains("gregfana_aggregate_total 250.0"));
        assertTrue(restored.size() <= 10);
    }

    @Test
    public void detailBudgetPreservesAggregateRoomAndHardCap() {
        Metrics m = new Metrics(10);
        for (int i = 0; i < 100; i++) m.add("gregfana_detail_total", Metrics.labels("item", "i" + i), 1, true);
        m.add("gregfana_total", Metrics.labels(), 100, false);
        assertEquals(10, m.size());
        assertTrue(
            m.render()
                .contains("gregfana_total 100.0"));
        assertTrue(
            m.render()
                .contains("gregfana_detail_dropped_total 91"));
        m.add("gregfana_other_total", Metrics.labels(), 1, false);
        assertEquals(10, m.size());
    }

    @Test
    public void escapesLabelsAndRestoresOnlyCounters() {
        Metrics m = new Metrics(100);
        m.add("gregfana_count_total", Metrics.labels("item", "a\"b\\c\nd"), 12, true);
        m.gauge("gregfana_online", Metrics.labels(), 1, false);
        Metrics restored = new Metrics(100);
        restored.restore(m.counters());
        assertEquals(1, restored.size());
        assertTrue(
            restored.render()
                .contains("a\\\"b\\\\c\\nd"));
        m.add("gregfana_count_total", Metrics.labels("item", "a\"b\\c\nd"), 2, true);
        assertTrue(
            restored.render()
                .contains("12.0"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void countersCannotDecrease() {
        new Metrics(10).add("x", Metrics.labels(), -1, false);
    }
}
