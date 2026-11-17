package dev.gregfana.core;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Test;

public class ExporterTest {

    @Test
    public void concurrentScrapesSeeWholeSnapshotsAndRoutesAreStrict() throws Exception {
        try (Exporter server = new Exporter("127.0.0.1", 0)) {
            server.publish("gregfana_a 1\n");
            ExecutorService clients = Executors.newFixedThreadPool(4);
            try {
                List<Future<String>> reads = new ArrayList<>();
                for (int i = 0; i < 40; i++) {
                    reads.add(clients.submit(() -> {
                        try (InputStream in = new URL("http://127.0.0.1:" + server.port() + "/metrics").openStream()) {
                            return new Scanner(in, "UTF-8").useDelimiter("\\A")
                                .next();
                        }
                    }));
                    server.publish(i % 2 == 0 ? "gregfana_a 1\n" : "gregfana_b 2\n");
                }
                for (Future<String> result : reads) {
                    String body = result.get();
                    assertTrue(body.equals("gregfana_a 1\n") || body.equals("gregfana_b 2\n"));
                }
                HttpURLConnection bad = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + server.port() + "/metrics/bad").openConnection();
                assertEquals(404, bad.getResponseCode());
                bad.disconnect();
                HttpURLConnection post = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + "/metrics")
                    .openConnection();
                post.setRequestMethod("POST");
                assertEquals(405, post.getResponseCode());
                post.disconnect();
            } finally {
                clients.shutdownNow();
            }
        }
    }
}
