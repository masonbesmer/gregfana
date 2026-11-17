package dev.gregfana.core;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ResourceSnapshotTest {

    private static final class Stack {

        String id;
        long amount;

        Stack(String id, long amount) {
            this.id = id;
            this.amount = amount;
        }
    }

    @Test
    public void actualConsumptionExcludesCatalystsAndAliasedReferences() {
        ResourceSnapshot<Stack> snapshot = new ResourceSnapshot<>(s -> s.id, s -> s.amount);
        Stack ore = new Stack("ore", 64);
        Stack catalyst = new Stack("circuit", 1);
        Stack fluid = new Stack("water", 8000);
        snapshot.capture(ore);
        snapshot.capture(ore);
        snapshot.capture(catalyst);
        snapshot.capture(fluid);
        ore.amount = 56;
        fluid.amount = 0;
        Map<String, Long> consumed = new HashMap<>();
        snapshot.consumed((id, n) -> consumed.merge(id, n, Long::sum));
        assertEquals(Long.valueOf(8), consumed.get("ore"));
        assertEquals(Long.valueOf(8000), consumed.get("water"));
        assertFalse(consumed.containsKey("circuit"));
    }

    @Test
    public void failedChecksAndIncreasingInputsDoNotBecomeConsumption() {
        ResourceSnapshot<Stack> snapshot = new ResourceSnapshot<>(s -> s.id, s -> s.amount);
        Stack input = new Stack("ore", 10);
        snapshot.capture(input);
        input.amount = 12;
        snapshot.consumed((id, n) -> fail("No consumption should be reported"));
    }
}
