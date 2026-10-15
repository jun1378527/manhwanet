package com.manhwanet.collection.collector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/** 127.0.0.1 에만 묶는 모의 HTTP 서버. 요청(경로·헤더·가상 시각)을 기록한다 */
public final class MockSite implements AutoCloseable {

    public record Request(String host, String target, Map<String, List<String>> headers, long atMillis) {
    }

    @FunctionalInterface
    public interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final LongSupplier clockMillis;

    public MockSite(LongSupplier clockMillis) throws IOException {
        this.clockMillis = clockMillis;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            String target = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            String host = exchange.getRequestHeaders().getFirst("Host");
            requests.add(new Request(host, target, Map.copyOf(exchange.getRequestHeaders()), clockMillis.getAsLong()));
            Handler handler = handlers.get(host + target);
            if (handler == null) {
                handler = handlers.get(target);
            }
            try {
                if (handler == null) {
                    send(exchange, 404, "not found");
                } else {
                    handler.handle(exchange);
                }
            } catch (IOException | RuntimeException e) {
                // 클라이언트가 먼저 끊은 경우 등
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** 경로(+쿼리) 또는 "호스트+경로" 에 응답을 등록 */
    public MockSite on(String target, Handler handler) {
        handlers.put(target, handler);
        return this;
    }

    public MockSite ok(String target, String body) {
        return on(target, ex -> send(ex, 200, body));
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public List<String> targets() {
        return requests.stream().map(Request::target).toList();
    }

    public long count(String target) {
        return requests.stream().filter(r -> r.target().equals(target)).count();
    }

    public void clear() {
        handlers.clear();
        requests.clear();
    }

    public static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    public static Handler status(int status, String body, String... headerPairs) {
        return ex -> {
            for (int i = 0; i + 1 < headerPairs.length; i += 2) {
                ex.getResponseHeaders().add(headerPairs[i], headerPairs[i + 1]);
            }
            send(ex, status, body);
        };
    }

    public static Handler redirect(int status, String location) {
        return ex -> {
            ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(status, -1);
        };
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
