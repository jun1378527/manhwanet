package com.manhwanet.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

/**
 * 실제 HTTP 응답 헤더(Set-Cookie 등)를 그대로 확인하기 위한 최소 클라이언트. 쿠키는 자동 저장하지 않는다.
 * local 프로필 서버는 127.0.0.1 에만 바인딩되므로 localhost 대신 127.0.0.1 로 접속한다.
 */
public final class RawHttp {

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final String baseUrl;

    public RawHttp(int port) {
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    public HttpResponse<String> get(String path, String cookieHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (cookieHeader != null) {
            builder.header("Cookie", cookieHeader);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> postJson(String path, String json, String cookieHeader, String csrfToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (cookieHeader != null) {
            builder.header("Cookie", cookieHeader);
        }
        if (csrfToken != null) {
            builder.header(AuthTestClient.CSRF_HEADER, csrfToken);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 이름이 일치하는 마지막 Set-Cookie 헤더 원문 */
    public static Optional<String> setCookie(HttpResponse<?> response, String name) {
        List<String> headers = response.headers().allValues("Set-Cookie");
        String found = null;
        for (String header : headers) {
            if (header.startsWith(name + "=")) {
                found = header;
            }
        }
        return Optional.ofNullable(found);
    }

    /** Set-Cookie 원문에서 "name=value" 부분 */
    public static String pair(String setCookie) {
        int semicolon = setCookie.indexOf(';');
        return semicolon < 0 ? setCookie : setCookie.substring(0, semicolon);
    }

    public static String value(String setCookie) {
        String pair = pair(setCookie);
        return pair.substring(pair.indexOf('=') + 1);
    }
}
