package dev.gregfana.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class Checkpoint implements AutoCloseable {

    public static final class State {

        public int version = 1;
        public PlayerLedger ledger = new PlayerLedger();
        public Metrics.Sample[] counters = new Metrics.Sample[0];
    }

    private final Path path;
    private final Gson gson = new Gson();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gregfana-checkpoint");
        t.setDaemon(true);
        return t;
    });
    public volatile String error = "";
    public volatile long lastSuccess;

    public Checkpoint(Path path) {
        this.path = path;
    }

    public State load() throws IOException {
        if (!Files.exists(path)) return new State();
        try {
            return read(path);
        } catch (RuntimeException | IOException first) {
            Path backup = path.resolveSibling(path.getFileName() + ".bak");
            if (Files.exists(backup)) {
                State recovered = read(backup);
                error = "Recovered backup: " + first.getMessage();
                return recovered;
            }
            throw new IOException("Unreadable checkpoint; refusing to overwrite history", first);
        }
    }

    private State read(Path file) throws IOException {
        JsonElement parsed = new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) throw new IOException("Checkpoint is not an object");
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("version") || !root.has("ledger") || !root.has("counters"))
            throw new IOException("Checkpoint is missing required fields");
        State state = gson.fromJson(root, State.class);
        if (state == null || state.version != 1 || state.ledger == null || state.counters == null)
            throw new IOException("Invalid or unsupported checkpoint");
        return state;
    }

    public void save(State state) {
        // Copy on the owning thread; serialization and disk IO run on the writer.
        State snapshot = new State();
        snapshot.ledger = state.ledger.copy();
        snapshot.counters = new Metrics.Sample[state.counters.length];
        for (int i = 0; i < state.counters.length; i++) {
            Metrics.Sample s = state.counters[i];
            snapshot.counters[i] = new Metrics.Sample(s.name, s.type, s.labels, s.value, s.detail);
        }
        writer.submit(() -> {
            try {
                byte[] bytes = gson.toJson(snapshot)
                    .getBytes(StandardCharsets.UTF_8);
                Files.createDirectories(path.getParent());
                Path temp = path.resolveSibling(path.getFileName() + ".tmp");
                Files.write(temp, bytes);
                if (Files.exists(path) && error.isEmpty()) Files
                    .copy(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
                error = "";
                lastSuccess = System.currentTimeMillis() / 1000;
            } catch (IOException | RuntimeException e) {
                error = e.toString();
            }
        });
    }

    @Override
    public void close() throws InterruptedException {
        writer.shutdown();
        if (!writer.awaitTermination(30, TimeUnit.SECONDS)) error = "Checkpoint shutdown timed out";
    }
}
