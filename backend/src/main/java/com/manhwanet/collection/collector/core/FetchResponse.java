package com.manhwanet.collection.collector.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 2xx 응답(리다이렉트를 따라간 뒤). 헤더는 Content-Type 만 넘긴다 */
public record FetchResponse(String requestedUrl, String finalUrl, int status, String contentType, byte[] body) {

    public String text() {
        return new String(body, charset());
    }

    private Charset charset() {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String p = part.strip().toLowerCase(Locale.ROOT);
                if (p.startsWith("charset=")) {
                    try {
                        return Charset.forName(p.substring(8).replace("\"", "").strip());
                    } catch (RuntimeException ignored) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
