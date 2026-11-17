package dev.gregfana.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

public final class Exporter implements AutoCloseable {

    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private volatile byte[] snapshot = "# Gregfana starting\n".getBytes(StandardCharsets.UTF_8);

    public Exporter(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 16);
        executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread t = new Thread(r, "gregfana-http");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/metrics", exchange -> {
            try {
                if (!exchange.getRequestURI()
                    .getPath()
                    .equals("/metrics")) {
                    exchange.sendResponseHeaders(404, -1);
                } else if (!exchange.getRequestMethod()
                    .equals("GET")) {
                        exchange.sendResponseHeaders(405, -1);
                    } else {
                        byte[] bytes = snapshot;
                        exchange.getResponseHeaders()
                            .set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody()
                            .write(bytes);
                    }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    public int port() {
        return server.getAddress()
            .getPort();
    }

    public void publish(String text) {
        snapshot = text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
