package com.manhwanet.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 데모용 모의 플랫폼 서버(#67). 127.0.0.1 의 임시 포트에만 열리고, robots.txt·사이트맵·가상 작품 상세만 돌려준다.
 * 표지 이미지는 없다. 데모 수집 단계 동안만 떠 있다.
 */
final class DemoMockSite implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, String> pages = new LinkedHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    DemoMockSite(String baseUrl) throws IOException {
        pages.put("/robots.txt", "User-agent: *\nAllow: /\n");
        StringBuilder sitemap = new StringBuilder();
        for (DemoCatalog.Item item : DemoCatalog.ITEMS) {
            sitemap.append("detail ").append(baseUrl).append("/demo/").append(item.id()).append('\n');
            StringBuilder page = new StringBuilder().append("title=").append(item.title()).append('\n')
                    .append("age=").append(item.age()).append('\n').append("story=").append(item.story()).append('\n');
            if (item.art() != null) {
                page.append("art=").append(item.art()).append('\n');
            }
            pages.put("/demo/" + item.id(), page.toString());
        }
        pages.put("/demo-sitemap.xml", sitemap.toString());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    int requests() {
        return requests.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String body = pages.get(exchange.getRequestURI().getPath());
        byte[] bytes = (body == null ? "not found" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
