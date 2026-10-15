package com.manhwanet.collection.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.collection.collector.core.BlockDetector;
import com.manhwanet.collection.collector.core.CircuitBreaker;
import com.manhwanet.collection.collector.core.CollectorProperties;
import com.manhwanet.collection.collector.core.GateDetector;
import com.manhwanet.collection.collector.core.RetryAfter;
import com.manhwanet.collection.collector.core.RetryPolicy;
import com.manhwanet.collection.collector.core.UserAgent;
import com.manhwanet.collection.collector.net.HttpTransport.RawResponse;
import com.manhwanet.collection.collector.net.LogSafe;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** 재시도 계산·Retry-After·회로 차단기·사용자 에이전트·차단/인증 화면 판단·로그 가림 */
class CollectorCoreUnitTest {

    @Test
    void retryScheduleIsThirtySecondsTwoEightThirtyMinutesWithUpToTenPercentJitter() {
        SplittableRandom random = new SplittableRandom(1);
        int[] base = {30, 120, 480, 1800};
        for (int i = 0; i < 1000; i++) {
            for (int attempt = 1; attempt <= 4; attempt++) {
                int delay = RetryPolicy.retryDelaySeconds(attempt, random).orElseThrow();
                assertThat(delay).isBetween(base[attempt - 1], base[attempt - 1] + base[attempt - 1] / 10);
            }
        }
        assertThat(RetryPolicy.retryDelaySeconds(5, random)).isEmpty();
        assertThat(RetryPolicy.retryDelaySeconds(0, random)).isEmpty();
        assertThat(RetryPolicy.MAX_RETRIES).isEqualTo(4);
    }

    @Test
    void retryAfterParsesSecondsAndHttpDates() {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        assertThat(RetryAfter.seconds("120", now)).hasValue(120);
        assertThat(RetryAfter.seconds("9999999999", now)).hasValue(RetryAfter.MAX_SECONDS);
        assertThat(RetryAfter.seconds("Thu, 15 Oct 2026 00:02:00 GMT", now)).hasValue(120);
        assertThat(RetryAfter.seconds("Wed, 14 Oct 2026 00:00:00 GMT", now)).hasValue(0);
        assertThat(RetryAfter.seconds("-5", now)).isEmpty();
        assertThat(RetryAfter.seconds("soon", now)).isEmpty();
        assertThat(RetryAfter.seconds(null, now)).isEmpty();
    }

    @Test
    void circuitBreakerOpensOnFiveConsecutiveFailures() {
        CircuitBreaker breaker = new CircuitBreaker();
        for (int i = 0; i < 4; i++) {
            assertThat(breaker.record(true)).isFalse();
        }
        assertThat(breaker.record(true)).isTrue();
        CircuitBreaker reset = new CircuitBreaker();
        for (int i = 0; i < 4; i++) {
            reset.record(true);
        }
        assertThat(reset.record(false)).isFalse();
        assertThat(reset.record(true)).isFalse();
    }

    @Test
    void circuitBreakerOpensWhenMoreThanTwentyPercentOfRecentHundredFail() {
        CircuitBreaker breaker = new CircuitBreaker();
        boolean open = false;
        // 실패 1 : 성공 3 (25%)
        for (int i = 0; i < 100 && !open; i++) {
            open = breaker.record(i % 4 == 0);
        }
        assertThat(open).isTrue();
        CircuitBreaker healthy = new CircuitBreaker();
        boolean opened = false;
        // 실패 1 : 성공 9 (10%)
        for (int i = 0; i < 300; i++) {
            opened |= healthy.record(i % 10 == 0);
        }
        assertThat(opened).isFalse();
    }

    @Test
    void userAgentNamesCollectorAndContactAndRefusesMissingContact() {
        assertThat(UserAgent.of("0.1.0", "https://manhwanet.example/about"))
                .isEqualTo("ManhwanetCollector/0.1.0 (+https://manhwanet.example/about)");
        assertThatThrownBy(() -> UserAgent.of("0.1.0", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserAgent.of("0.1.0", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserAgent.of("0.1.0", "http://x.example")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserAgent.of("0.1.0", "https://x.example/a b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserAgent.of("0.1.0", "https://x.example/\r\nX: y")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserAgent.of("1 0", "https://x.example")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void propertiesClampToSafeRanges() {
        CollectorProperties defaults = new CollectorProperties(null, null, null, null, null, null, null, null);
        assertThat(defaults.contactUrl()).isNull();
        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.maxRedirects()).isEqualTo(5);
        assertThat(defaults.leaseSeconds()).isEqualTo(300);
        CollectorProperties extreme = new CollectorProperties("https://x", "1", Duration.ZERO, Duration.ofHours(1),
                Duration.ofHours(1), Long.MAX_VALUE, 50, 5);
        assertThat(extreme.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(extreme.totalTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(extreme.maxBodyBytes()).isEqualTo(50L * 1024 * 1024);
        assertThat(extreme.maxRedirects()).isEqualTo(5);
        assertThat(extreme.leaseSeconds()).isEqualTo(180);
    }

    @Test
    void blockDetectorRecognisesForbiddenChallengesAndCaptchas() {
        assertThat(BlockDetector.isBlocked(response(403, Map.of(), ""))).isTrue();
        assertThat(BlockDetector.isBlocked(response(451, Map.of(), ""))).isTrue();
        assertThat(BlockDetector.isBlocked(response(503, Map.of("cf-mitigated", List.of("challenge")), ""))).isTrue();
        assertThat(BlockDetector.isBlocked(response(200, Map.of(), "<script src=\"https://www.google.com/recaptcha/api.js\">"))).isTrue();
        assertThat(BlockDetector.isBlocked(response(200, Map.of(), "<p>비정상적인 접근이 감지되었습니다</p>"))).isTrue();
        assertThat(BlockDetector.isBlocked(response(200, Map.of(), "<h1>작품 상세</h1>"))).isFalse();
        // 성인 작품 안내 문구는 접근 차단으로 보지 않는다
        assertThat(BlockDetector.isBlocked(response(200, Map.of(), "19세 미만 청소년의 접근이 제한된 작품입니다"))).isFalse();
        assertThat(BlockDetector.isBlocked(response(404, Map.of(), "없음"))).isFalse();
    }

    @Test
    void gateDetectorRecognisesLoginAdultAndPaymentRedirects() {
        for (String url : List.of("https://ridibooks.com/account/login?return_url=x", "https://nid.naver.com/nidlogin.login",
                "https://accounts.kakao.com/login", "https://www.mrblue.com/webtoon/wt_1?reason=adult",
                "https://x.example/adult/verify", "https://x.example/payment/charge", "https://x.example/auth/kakao",
                "https://x.example/member/join")) {
            assertThat(GateDetector.isGate(URI.create(url))).as(url).isTrue();
        }
        for (String url : List.of("https://ridibooks.com/books/123", "https://www.mrblue.com/webtoon/wt_000012345",
                "https://page.kakao.com/content/123")) {
            assertThat(GateDetector.isGate(URI.create(url))).as(url).isFalse();
        }
    }

    @Test
    void logSafeMasksSensitiveQueryValuesAndBodySecrets() {
        String url = LogSafe.url(URI.create("https://user:pw@x.example/a/b?id=3&session=SECRET1&access_token=SECRET2&page=2#frag"));
        assertThat(url).isEqualTo("https://x.example/a/b?id=3&session=[REDACTED]&access_token=[REDACTED]&page=2");
        String body = new String(LogSafe.redactBody(("{\"csrfToken\":\"T0KEN-123\",\"title\":\"제목\"} JSESSIONID=ABCD1234 "
                + "api_key: XYZ98765 Authorization: Bearer eyJhbGci.payload.sig").getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        assertThat(body).contains("제목").doesNotContain("T0KEN-123").doesNotContain("ABCD1234").doesNotContain("XYZ98765")
                .doesNotContain("eyJhbGci");
        String html = """
                <input name="csrf_token" value="HTMLSECRET1">
                <input value="HTMLSECRET2" name="sessionid">
                <input NAME="CSRF_TOKEN" VALUE="HTMLSECRET3">
                <input name=csrf_token value=HTMLSECRET4>
                <input
                  name="csrf_token"
                  value="HTMLSECRET5">
                <meta name="csrf-token" content="HTMLSECRET6">
                <meta content="HTMLSECRET7" name="csrf-token">
                <meta name=csrf-token content=HTMLSECRET8>
                <div data-token="HTMLSECRET9">본문</div>
                """;
        String masked = new String(LogSafe.redactBody(html.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        assertThat(masked).contains("본문").contains("[REDACTED]");
        for (int n = 1; n <= 9; n++) {
            assertThat(masked).doesNotContain("HTMLSECRET" + n);
        }
        // 가리기 전에 값을 자르면 일부만 남을 수 있다. 이름이 보이면 잘린 값도 가리고, 이름보다 값이 앞에 있어 판단할 수 없으면 저장하지 않는다
        String known = "<input name=\"csrf_token\" value=\"HTMLSECRET1\">";
        String cutKnown = known.substring(0, known.indexOf("HTMLSECRET1") + 4);
        String redactedCut = new String(LogSafe.redactBody(cutKnown.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        assertThat(redactedCut).doesNotContain("HTML").doesNotContain("SECR");
        assertThat(LogSafe.redactBody("PARSE <input value=\"HTMLSECRET1".getBytes(StandardCharsets.UTF_8))).isNull();
    }

    private static RawResponse response(int status, Map<String, List<String>> headers, String body) {
        return new RawResponse(status, headers, body.getBytes(StandardCharsets.UTF_8));
    }
}
