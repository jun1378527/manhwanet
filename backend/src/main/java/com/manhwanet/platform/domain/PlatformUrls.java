package com.manhwanet.platform.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 플랫폼 작품 URL 검증. 서버는 URL 을 가져오지 않고(네트워크 요청 없음) 문자열만 검사한다.
 * <ol>
 *   <li>앞뒤 공백 제거, 500자 이하, 공백·제어·서식 문자(zero-width 등) 없음</li>
 *   <li>스킴은 https 만 허용(소문자로 저장), 사용자 정보 금지, 포트는 없거나 443(제거해 저장), 프래그먼트 금지</li>
 *   <li>호스트(소문자 ASCII)가 그 플랫폼의 허용 호스트 목록에 정확히 있어야 함. 하위 도메인·IP·IDN 은 목록에 없으면 거절</li>
 *   <li>경로·쿼리 정규화: 비ASCII 문자는 퍼센트 인코딩하고, 비예약 문자(영문·숫자·-._~)의 퍼센트 인코딩만 한 번 푼다.
 *       나머지는 16진수 대문자로 둔다. 반복해서 풀지 않는다. "."·".." 경로 조각, 제어 문자 인코딩, 역슬래시 인코딩,
 *       경로의 슬래시 인코딩, %25 를 최대 두 겹까지 풀었을 때 나오는 . / \ 제어 문자는 거절한다</li>
 *   <li>플랫폼에 작품 URL 패턴이 있으면 정규화한 URL 전체가 패턴과 일치해야 하고, 패턴의 (?&lt;id&gt;...) 그룹은 platformWorkId 와 같아야 함.
 *       역참조·전후방 탐색은 패턴에 쓸 수 없다. 매칭은 charAt 호출 횟수와 시간으로 끊는다. 이 제한은 스레드를 중단하지 않는다</li>
 * </ol>
 */
public final class PlatformUrls {

    public static final int URL_MAX_LENGTH = 500;
    public static final int PATTERN_MAX_LENGTH = 300;
    /** 관리자 패턴 매칭 시간 제한. charAt 을 부르는 동안만 검사하므로 스레드 중단은 보장하지 않는다 */
    static final long MATCH_TIMEOUT_NANOS = 50_000_000L;
    /** charAt 호출 상한. 시간이 지나기 전에도 역추적을 끊기 위한 한계 */
    static final int MATCH_MAX_READS = 100_000;
    private static final int CACHE_MAX = 200;
    private static final Pattern HOST = Pattern.compile("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    /** 플랫폼 외부 작품 ID: 출력 가능한 ASCII 만(DB ck_listings_platform_work_id 와 같음) */
    private static final Pattern WORK_ID = Pattern.compile("^[!-~]{1,100}$");
    /** 시간 제한으로도 막기 어려운 문법(역참조, 전후방 탐색)은 패턴에 쓸 수 없다. \k&lt;name&gt; 과 \k'name' 모두 포함한다 */
    private static final Pattern FORBIDDEN_SYNTAX = Pattern.compile("\\\\[1-9]|\\\\k\\s*[<'']|\\(\\?<?[=!]");
    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private PlatformUrls() {
    }

    /** 검증 결과. 실패 시 field 는 "url" 또는 "platformWorkId" */
    public record Result(String normalizedUrl, String field, String reason) {
        public boolean ok() {
            return field == null;
        }

        static Result fail(String field, String reason) {
            return new Result(null, field, reason);
        }
    }

    public static boolean isValidWorkId(String id) {
        return id != null && WORK_ID.matcher(id).matches();
    }

    public static Result validate(String rawUrl, Collection<String> allowedHosts, String workUrlPattern,
            String platformWorkId) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return Result.fail("url", "공식 URL은 필수입니다.");
        }
        String value = rawUrl.strip();
        if (value.length() > URL_MAX_LENGTH) {
            return Result.fail("url", "URL은 " + URL_MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (value.codePoints().anyMatch(PlatformUrls::isInvisible)) {
            return Result.fail("url", "URL에 공백·제어·서식 문자를 넣을 수 없습니다.");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return Result.fail("url", "URL 형식이 올바르지 않습니다.");
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https") || uri.isOpaque()) {
            return Result.fail("url", "https URL만 허용합니다.");
        }
        if (uri.getRawUserInfo() != null) {
            return Result.fail("url", "URL에 사용자 정보를 넣을 수 없습니다.");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return Result.fail("url", "다른 포트를 지정한 URL은 허용하지 않습니다.");
        }
        if (uri.getRawFragment() != null) {
            return Result.fail("url", "URL에 #(프래그먼트)를 넣을 수 없습니다.");
        }
        String host = uri.getHost();
        if (host == null) {
            return Result.fail("url", "URL에 호스트가 없습니다.");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (allowedHosts == null) {
            if (!isValidHost(host)) {
                return Result.fail("url", "도메인 이름 형식의 호스트만 허용합니다(IP 주소·localhost 불가): " + host);
            }
        } else if (!allowedHosts.contains(host)) {
            return Result.fail("url", "이 플랫폼에서 허용하지 않은 호스트입니다: " + host);
        }
        String path = normalizeEscapes(ascii(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath()));
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return Result.fail("url", "URL 경로에 . 또는 .. 을 넣을 수 없습니다.");
            }
        }
        String query = uri.getRawQuery() == null ? null : normalizeEscapes(ascii(uri.getRawQuery()));
        if (hasDangerousEncoding(path, true) || (query != null && hasDangerousEncoding(query, false))) {
            return Result.fail("url", "URL에 제어 문자나 인코딩으로 숨긴 경로를 넣을 수 없습니다.");
        }
        String normalized = "https://" + host + path + (query == null ? "" : "?" + query);
        if (normalized.length() > URL_MAX_LENGTH) {
            return Result.fail("url", "URL은 " + URL_MAX_LENGTH + "자 이하여야 합니다.");
        }

        if (workUrlPattern != null) {
            Pattern pattern = compile(workUrlPattern);
            Matcher matcher;
            try {
                matcher = pattern.matcher(new DeadlineCharSequence(normalized, System.nanoTime() + MATCH_TIMEOUT_NANOS));
                if (!matcher.matches()) {
                    return Result.fail("url", "이 플랫폼의 작품 URL 형식과 맞지 않습니다.");
                }
            } catch (MatchTimeoutException e) {
                return Result.fail("url", "이 플랫폼의 URL 패턴 검사가 시간 제한을 넘었습니다. 관리자에게 패턴 확인을 요청해 주세요.");
            }
            if (pattern.namedGroups().containsKey("id") && !Objects.equals(matcher.group("id"), platformWorkId)) {
                return Result.fail("platformWorkId", "URL의 작품 ID와 platformWorkId가 다릅니다.");
            }
        }
        return new Result(normalized, null, null);
    }

    /**
     * 플랫폼이 아닌 https URL(공식 링크·근거·외부 ID 출처) 검사와 정규화 (#40).
     * 플랫폼 URL 과 같은 규칙(https·사용자 정보·포트·조각·숨은 문자·점 경로·인코딩)을 쓰되, 호스트는 목록 대신
     * 도메인 이름 형식만 확인한다. 서버는 이 URL 을 가져오지 않는다.
     */
    public static Result validateGeneral(String rawUrl) {
        return validate(rawUrl, null, null, null);
    }

    /** 정규화된 URL 의 호스트(소문자). 형식이 틀리면 null */
    public static String hostOf(String normalizedUrl) {
        try {
            String host = new URI(normalizedUrl).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** 관리자가 입력한 작품 URL 패턴 검사. 문제가 없으면 null, 있으면 사유 */
    public static String checkPattern(String pattern) {
        if (pattern.length() > PATTERN_MAX_LENGTH) {
            return "URL 패턴은 " + PATTERN_MAX_LENGTH + "자 이하여야 합니다.";
        }
        if (!pattern.startsWith("^https://") || !pattern.endsWith("$")) {
            return "URL 패턴은 ^https:// 로 시작하고 $ 로 끝나야 합니다.";
        }
        if (FORBIDDEN_SYNTAX.matcher(pattern).find()) {
            return "URL 패턴에 역참조·전후방 탐색은 쓸 수 없습니다.";
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "URL 패턴이 올바른 정규식이 아닙니다.";
        }
        if (!compiled.namedGroups().containsKey("id") || compiled.namedGroups().size() != 1) {
            return "URL 패턴에는 작품 ID 를 담는 이름 있는 그룹 (?<id>...) 이 정확히 하나 있어야 합니다.";
        }
        return null;
    }

    public static boolean isValidHost(String host) {
        return host != null && host.length() <= 253 && HOST.matcher(host).matches();
    }

    private static boolean isInvisible(int cp) {
        int type = Character.getType(cp);
        return Character.isWhitespace(cp) || Character.isISOControl(cp) || type == Character.FORMAT
                || type == Character.SPACE_SEPARATOR || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.PRIVATE_USE || type == Character.SURROGATE;
    }

    /** 비ASCII 문자를 UTF-8 퍼센트 인코딩으로 바꾼다 */
    private static String ascii(String raw) {
        StringBuilder out = new StringBuilder();
        raw.codePoints().forEach(cp -> {
            if (cp < 0x80) {
                out.appendCodePoint(cp);
            } else {
                for (byte b : new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    out.append('%').append(String.format("%02X", b & 0xFF));
                }
            }
        });
        return out.toString();
    }

    /** RFC 3986 6.2.2: 비예약 문자의 퍼센트 인코딩은 풀고, 나머지 인코딩은 16진수 대문자로 */
    private static String normalizeEscapes(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length() && isHex(raw.charAt(i + 1))
                    && isHex(raw.charAt(i + 2))) {
                int value = Integer.parseInt(raw.substring(i + 1, i + 3), 16);
                char decoded = (char) value;
                if (value < 0x80 && (Character.isLetterOrDigit(decoded) || "-._~".indexOf(decoded) >= 0)) {
                    out.append(decoded);
                } else {
                    out.append('%').append(raw.substring(i + 1, i + 3).toUpperCase(Locale.ROOT));
                }
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    /**
     * 저장 값과 한두 번 더 해석한 값이 갈라질 수 있는 인코딩을 거절한다.
     * 비예약 문자는 이미 한 번 풀어 두었으므로 여기서 다시 풀지 않는다.
     * %25 는 최대 두 겹까지만 안쪽 바이트를 보고, 그 이상은 반복 디코딩하지 않는다.
     * 경로의 %2F 는 일부 서버가 슬래시로 해석하므로 거절한다. 쿼리의 %2F 는 값으로 남긴다.
     */
    private static boolean hasDangerousEncoding(String value, boolean path) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '%' && dangerousByte(value, i + 1, 0, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dangerousByte(String value, int hexAt, int depth, boolean path) {
        if (depth > 2 || hexAt + 1 >= value.length() || !isHex(value.charAt(hexAt)) || !isHex(value.charAt(hexAt + 1))) {
            return false;
        }
        int b = Integer.parseInt(value.substring(hexAt, hexAt + 2), 16);
        if (b <= 0x1F || b == 0x7F || b == '\\') {
            return true;
        }
        if (path && b == '/') {
            return true;
        }
        if (depth > 0 && b == '.') {
            return true;
        }
        if (b == 0x25 && depth < 2) {
            return dangerousByte(value, hexAt + 2, depth + 1, path);
        }
        return false;
    }

    private static Pattern compile(String pattern) {
        Pattern cached = CACHE.get(pattern);
        if (cached != null) {
            return cached;
        }
        if (CACHE.size() >= CACHE_MAX) {
            CACHE.clear();
        }
        Pattern compiled = Pattern.compile(pattern);
        CACHE.put(pattern, compiled);
        return compiled;
    }

    private static final class MatchTimeoutException extends RuntimeException {
        MatchTimeoutException() {
            super(null, null, false, false);
        }
    }

    /**
     * charAt 마다 시간과 호출 횟수를 확인하는 문자열.
     * 정규식 엔진이 charAt 을 부르는 동안 매칭은 예외로 끝난다.
     * Thread.interrupt 는 하지 않으며, charAt 을 부르지 않는 경로는 이 한계로 멈추지 않는다.
     * 부분 문자열도 같은 호출 횟수를 공유한다.
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final String value;
        private final long deadline;
        private final int[] reads;

        private DeadlineCharSequence(String value, long deadline) {
            this(value, deadline, new int[1]);
        }

        private DeadlineCharSequence(String value, long deadline, int[] reads) {
            this.value = value;
            this.deadline = deadline;
            this.reads = reads;
        }

        @Override
        public int length() {
            return value.length();
        }

        @Override
        public char charAt(int index) {
            if (++reads[0] > MATCH_MAX_READS || System.nanoTime() > deadline) {
                throw new MatchTimeoutException();
            }
            return value.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(value.substring(start, end), deadline, reads);
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
