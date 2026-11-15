package org.ashoreprospect.net;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A throwaway HTTP server on the loopback interface, used as a fake Node.
 *
 * <p>It listens on {@code 127.0.0.1:0}, so the port is ephemeral and no traffic can leave the
 * machine. {@link #pointNodeAt} also sets {@code SCHEDULER_NODE} through the in-process overlay,
 * so {@code Endpoints.nodeBase()} (and therefore every {@code NodeApi} call) targets it without
 * touching the developer's real environment.
 *
 * <p>One instance holds a single handler, which sees every request. That is what the write paths
 * need: they must observe method, path, headers, and body, and several of them expect exactly
 * one call.
 */
final class FakeNode implements AutoCloseable {

    /** One recorded request. {@code query} is the raw query string, or null when there is none. */
    static final class Call {
        final String method;
        final String path;
        final Map<String, List<String>> headers;
        final String body;
        final String query;

        Call(String method, String path, Map<String, List<String>> headers, String body,
             String query) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
            this.query = query;
        }

        /** {@code path} plus {@code ?query} when one was sent. */
        String target() {
            return query == null || query.isEmpty() ? path : path + "?" + query;
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    List<String> values = e.getValue();
                    return values == null || values.isEmpty() ? null : values.get(0);
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return method + " " + path + " " + body;
        }
    }

    /** How many requests the fake answers, and how. */
    private final HttpServer http;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String replyBody = "{\"ok\":true}";

    FakeNode() throws Exception {
        this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(), new String(body, StandardCharsets.UTF_8),
                    exchange.getRequestURI().getRawQuery()));
            byte[] out = replyBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
        });
        http.start();
    }

    String base() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    /** Points {@code SCHEDULER_NODE} at this server for the rest of the test. */
    void pointNodeAt() throws Exception {
        TestEnv.set(Endpoints.NODE_ENV, base());
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Forgets what has been recorded so far; the reply configuration is unchanged. */
    void resetRecording() {
        calls.clear();
    }

    int callCount() {
        return calls.size();
    }

    Call only() {
        if (calls.size() != 1) {
            throw new AssertionError("expected exactly one request, got " + calls);
        }
        return calls.get(0);
    }

    void replyWith(int newStatus, String body) {
        this.status = newStatus;
        this.replyBody = body;
    }

    @Override
    public void close() {
        try {
            TestEnv.clear(Endpoints.NODE_ENV);
        } catch (Exception e) {
            System.err.println("overlay reset failed: " + e.getMessage());
        }
        http.stop(0);
    }
}
