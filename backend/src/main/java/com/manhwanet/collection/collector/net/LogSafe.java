package com.manhwanet.collection.collector.net;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그·실패 표본에 쿠키·인증정보가 남지 않게 한다.
 * <ul>
 *   <li>URL: 사용자 정보·조각(#)을 빼고, 이름이 민감해 보이는 쿼리 값은 가린다.</li>
 *   <li>본문: session·token·csrf·password·api key·cookie 같은 이름에 붙은 값, Bearer 토큰,
 *       HTML name/value·meta content 속성을 가린다. 속성이 끝나기 전에 잘려 값인지 알 수 없으면 표본을 남기지 않는다.</li>
 * </ul>
 * 수집기는 요청에 쿠키·인증 헤더를 넣지 않고, 응답 헤더(Set-Cookie 포함)는 저장·기록하지 않는다. 이 클래스는 그 위의 2차 방어다.
 */
public final class LogSafe {

    public static final String REDACTED = "[REDACTED]";
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i).*(token|key|auth|session|sess|sid|sig|password|passwd|pwd|secret|code|credential|jwt|cookie|ticket|nonce).*");
    private static final Pattern BODY_PAIR = Pattern.compile(
            "(?i)(\\b[\\w-]*(?:session|sessid|sid|token|csrf|xsrf|auth|password|passwd|api[_-]?key|access[_-]?key|secret|cookie|jwt|ticket)[\\w-]*"
                    + "[\"']?\\s*[:=]\\s*[\"']?)([^\"'&\\s<>;,]{4,})");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*");

    private LogSafe() {
    }

    public static String url(URI uri) {
        if (uri == null) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        out.append(uri.getScheme() == null ? "" : uri.getScheme() + "://");
        out.append(uri.getHost() == null ? "?" : uri.getHost());
        if (uri.getPort() != -1) {
            out.append(':').append(uri.getPort());
        }
        out.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
        String query = uri.getRawQuery();
        if (query != null) {
            out.append('?');
            String[] pairs = query.split("&", -1);
            for (int i = 0; i < pairs.length; i++) {
                if (i > 0) {
                    out.append('&');
                }
                int eq = pairs[i].indexOf('=');
                String name = eq < 0 ? pairs[i] : pairs[i].substring(0, eq);
                if (eq >= 0 && SENSITIVE_NAME.matcher(name).matches()) {
                    out.append(name).append('=').append(REDACTED);
                } else {
                    out.append(pairs[i]);
                }
            }
        }
        return truncate(out.toString(), 300);
    }

    public static String url(String raw) {
        if (raw == null) {
            return "-";
        }
        try {
            return url(URI.create(raw.strip()));
        } catch (IllegalArgumentException e) {
            return "(형식이 틀린 URL)";
        }
    }

    /**
     * 실패 표본 본문에서 민감해 보이는 값을 가린다.
     * 잘린 속성 때문에 비밀값인지 확정할 수 없으면 null(본문을 저장하지 않음).
     */
    public static byte[] redactBody(byte[] body) {
        if (body == null) {
            return null;
        }
        Redaction redaction = redact(new String(body, StandardCharsets.UTF_8));
        if (!redaction.safe) {
            return null;
        }
        return redaction.text.getBytes(StandardCharsets.UTF_8);
    }

    public static String redactText(String text) {
        if (text == null) {
            return null;
        }
        return redact(text).text;
    }

    private record Redaction(String text, boolean safe) {
    }

    private static Redaction redact(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean safe = true;
        int i = 0;
        while (i < text.length()) {
            int lt = text.indexOf('<', i);
            if (lt < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, lt);
            if (text.startsWith("<!--", lt)) {
                int end = text.indexOf("-->", lt + 4);
                if (end < 0) {
                    out.append(text, lt, text.length());
                    break;
                }
                out.append(text, lt, end + 3);
                i = end + 3;
                continue;
            }
            Tag tag = parseTag(text, lt);
            if (tag == null) {
                out.append('<');
                i = lt + 1;
                continue;
            }
            out.append(tag.rendered);
            safe = safe && tag.safe;
            i = tag.end;
        }
        String masked = BEARER.matcher(out).replaceAll(r -> Matcher.quoteReplacement(r.group(1) + REDACTED));
        masked = BODY_PAIR.matcher(masked).replaceAll(r -> Matcher.quoteReplacement(r.group(1) + REDACTED));
        return new Redaction(masked, safe);
    }

    /** 한 HTML 시작 태그. 민감 속성 값은 가리고, 값이 잘려 민감 여부를 모르면 safe=false */
    private record Tag(String rendered, int end, boolean safe) {
    }

    private record Attr(String name, String value, boolean incomplete, int valueStart, int valueEnd) {
    }

    private static Tag parseTag(String text, int start) {
        int i = start + 1;
        if (i >= text.length() || !isNameStart(text.charAt(i))) {
            return null;
        }
        i++;
        while (i < text.length() && isNameChar(text.charAt(i))) {
            i++;
        }
        List<Attr> attrs = new ArrayList<>();
        boolean closed = false;
        while (i < text.length()) {
            while (i < text.length() && isWs(text.charAt(i))) {
                i++;
            }
            if (i >= text.length()) {
                break;
            }
            char ch = text.charAt(i);
            if (ch == '>') {
                closed = true;
                i++;
                break;
            }
            if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '>') {
                closed = true;
                i += 2;
                break;
            }
            if (!isNameStart(ch)) {
                i++;
                continue;
            }
            int nameStart = i++;
            while (i < text.length() && isNameChar(text.charAt(i))) {
                i++;
            }
            String name = text.substring(nameStart, i);
            while (i < text.length() && isWs(text.charAt(i))) {
                i++;
            }
            String value = null;
            boolean incomplete = false;
            int valueStart = -1;
            int valueEnd = -1;
            if (i < text.length() && text.charAt(i) == '=') {
                i++;
                while (i < text.length() && isWs(text.charAt(i))) {
                    i++;
                }
                if (i >= text.length()) {
                    incomplete = true;
                } else if (text.charAt(i) == '"' || text.charAt(i) == '\'') {
                    char quote = text.charAt(i);
                    valueStart = ++i;
                    int end = text.indexOf(quote, i);
                    if (end < 0) {
                        value = text.substring(i);
                        valueEnd = text.length();
                        i = text.length();
                        incomplete = true;
                    } else {
                        value = text.substring(i, end);
                        valueEnd = end;
                        i = end + 1;
                    }
                } else {
                    valueStart = i;
                    while (i < text.length() && !isWs(text.charAt(i)) && text.charAt(i) != '>' && text.charAt(i) != '/') {
                        i++;
                    }
                    valueEnd = i;
                    value = text.substring(valueStart, valueEnd);
                    if (i >= text.length()) {
                        incomplete = true;
                    }
                }
            }
            attrs.add(new Attr(name, value, incomplete, valueStart, valueEnd));
        }
        boolean labelSensitive = false;
        for (Attr attr : attrs) {
            if (attr.value != null && isLabel(attr.name) && SENSITIVE_NAME.matcher(attr.value).matches()) {
                labelSensitive = true;
            }
        }
        List<int[]> spans = new ArrayList<>();
        boolean safe = true;
        for (Attr attr : attrs) {
            boolean namedSecret = attr.value != null && !isLabel(attr.name) && !isSecretSlot(attr.name)
                    && SENSITIVE_NAME.matcher(attr.name).matches();
            boolean slotSecret = labelSensitive && isSecretSlot(attr.name);
            if (!namedSecret && !slotSecret) {
                if (!closed && attr.incomplete) {
                    safe = false; // 이름보다 값이 앞에 있고 태그가 잘리면 비밀인지 알 수 없다
                }
                continue;
            }
            if (attr.valueStart >= 0) {
                spans.add(new int[] {attr.valueStart, attr.valueEnd});
            }
        }
        int end = closed ? i : text.length();
        return new Tag(replaceSpans(text, start, end, spans), end, safe);
    }

    private static String replaceSpans(String text, int from, int to, List<int[]> spans) {
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        StringBuilder out = new StringBuilder();
        int cursor = from;
        for (int[] span : spans) {
            if (span[0] < cursor || span[1] < span[0] || span[1] > to) {
                continue;
            }
            out.append(text, cursor, span[0]);
            out.append(REDACTED);
            cursor = span[1];
        }
        out.append(text, cursor, to);
        return out.toString();
    }

    private static boolean isLabel(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("name") || n.equals("id") || n.equals("property") || n.equals("itemprop") || n.equals("http-equiv");
    }

    private static boolean isSecretSlot(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("value") || n.equals("content");
    }

    private static boolean isNameStart(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c == '_' || c == ':';
    }

    private static boolean isNameChar(char c) {
        return isNameStart(c) || c >= '0' && c <= '9' || c == '-' || c == '.';
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f';
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
