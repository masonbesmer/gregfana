package dev.gregfana.core;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class CheckpointTest {

    @Test(expected = java.io.IOException.class)
    public void emptyObjectIsNotTreatedAsAnEmptyWorld() throws Exception {
        Path file = Files.createTempDirectory("gregfana-empty")
            .resolve("state.json");
        Files.write(file, "{}".getBytes(StandardCharsets.UTF_8));
        try (Checkpoint cp = new Checkpoint(file)) {
            cp.load();
        }
    }

    @Test
    public void persistsIndependentSnapshotAndRecoversBackup() throws Exception {
        Path file = Files.createTempDirectory("gregfana-test")
            .resolve("state.json");
        Checkpoint.State state = new Checkpoint.State();
        state.ledger.observe("offline", "stat", 200);
        state.ledger.importHistory("offline", true);
        try (Checkpoint cp = new Checkpoint(file)) {
            cp.save(state);
            state.ledger.observe("offline", "stat", 250);
        }
        try (Checkpoint cp = new Checkpoint(file)) {
            Checkpoint.State loaded = cp.load();
            assertEquals(
                0,
                loaded.ledger.players.get("offline")
                    .get("stat").observed);
            assertTrue(
                loaded.ledger.players.get("offline")
                    .get("stat").imported);
            cp.save(state);
        }
        Files.write(file, "broken".getBytes(StandardCharsets.UTF_8));
        try (Checkpoint cp = new Checkpoint(file)) {
            assertEquals(
                0,
                cp.load().ledger.players.get("offline")
                    .get("stat").observed);
            assertFalse(cp.error.isEmpty());
        }
    }

    @Test(expected = java.io.IOException.class)
    public void corruptHistoryIsNotSilentlyReset() throws Exception {
        Path file = Files.createTempDirectory("gregfana-invalid")
            .resolve("state.json");
        Files.write(file, "null".getBytes(StandardCharsets.UTF_8));
        try (Checkpoint cp = new Checkpoint(file)) {
            cp.load();
        }
    }
}
