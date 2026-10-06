package com.manhwanet.auth;

import static com.manhwanet.support.AuthTestClient.CSRF_COOKIE;
import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.manhwanet.support.RawHttp;
import com.manhwanet.support.TestDatabaseCleaner;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 Tomcat HTTP 응답으로 운영 기본 쿠키 속성과 세션 흐름을 검증한다. (test 프로필 = 운영 기본 쿠키 설정)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionCookieHttpTest {

    static final String SESSION_COOKIE = "__Host-MANHWANET_SESSION";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private ServerProperties serverProperties;

    private RawHttp http;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        http = new RawHttp(port);
    }

    @Test
    void sessionTimeoutIsThirtyMinutes() {
        assertThat(serverProperties.getServlet().getSession().getTimeout()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void productionCookiesHaveRequiredAttributesAndSessionFlowWorks() throws Exception {
        // 1. CSRF 토큰 발급
        HttpResponse<String> csrf = http.get("/api/v1/auth/csrf", null);
        assertThat(csrf.statusCode()).isEqualTo(204);
        String csrfCookie = RawHttp.setCookie(csrf, CSRF_COOKIE).orElseThrow();
        assertCookieAttributes(csrfCookie, true, false);
        assertThat(RawHttp.setCookie(csrf, SESSION_COOKIE)).as("CSRF 발급은 세션을 만들지 않는다").isEmpty();
        String token = RawHttp.value(csrfCookie);

        // 2. 가입 + 자동 로그인 → 세션 쿠키 발급, CSRF 교체
        String email = "http-" + System.nanoTime() + "@example.com";
        HttpResponse<String> signup = http.postJson("/api/v1/auth/signup",
                "{\"email\":\"" + email + "\",\"password\":\"" + STRONG_PASSWORD + "\",\"nickname\":\"독자\"}",
                RawHttp.pair(csrfCookie), token);
        assertThat(signup.statusCode()).isEqualTo(201);
        String sessionCookie = RawHttp.setCookie(signup, SESSION_COOKIE).orElseThrow();
        assertCookieAttributes(sessionCookie, true, true);
        String rotatedCsrf = RawHttp.setCookie(signup, CSRF_COOKIE).orElseThrow();
        assertThat(RawHttp.value(rotatedCsrf)).isNotBlank().isNotEqualTo(token);

        String cookies = RawHttp.pair(sessionCookie) + "; " + RawHttp.pair(rotatedCsrf);
        HttpResponse<String> me = http.get("/api/v1/auth/me", cookies);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains(email);

        // 3. 같은 세션으로 다시 로그인 → 세션 ID(쿠키 값) 변경
        HttpResponse<String> login = http.postJson("/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + STRONG_PASSWORD + "\"}",
                cookies, RawHttp.value(rotatedCsrf));
        assertThat(login.statusCode()).isEqualTo(200);
        String newSessionCookie = RawHttp.setCookie(login, SESSION_COOKIE).orElseThrow();
        assertThat(RawHttp.value(newSessionCookie)).isNotEqualTo(RawHttp.value(sessionCookie));
        String loginCsrf = RawHttp.setCookie(login, CSRF_COOKIE).orElseThrow();
        assertThat(RawHttp.value(loginCsrf)).isNotEqualTo(RawHttp.value(rotatedCsrf));

        // 이전 세션 ID 는 더 이상 유효하지 않다.
        assertThat(http.get("/api/v1/auth/me", RawHttp.pair(sessionCookie)).statusCode()).isEqualTo(401);

        // 4. 로그아웃 → 세션 쿠키·CSRF 쿠키 삭제, 기존 세션 무효
        String loggedIn = RawHttp.pair(newSessionCookie) + "; " + RawHttp.pair(loginCsrf);
        HttpResponse<String> logout = http.postJson("/api/v1/auth/logout", null, loggedIn, RawHttp.value(loginCsrf));
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(RawHttp.setCookie(logout, SESSION_COOKIE).orElseThrow().toLowerCase(Locale.ROOT)).contains("max-age=0");
        assertThat(RawHttp.setCookie(logout, CSRF_COOKIE).orElseThrow().toLowerCase(Locale.ROOT)).contains("max-age=0");
        assertThat(http.get("/api/v1/auth/me", RawHttp.pair(newSessionCookie)).statusCode()).isEqualTo(401);
    }

    @Test
    void anonymousUnauthorizedResponseDoesNotCreateSession() throws Exception {
        HttpResponse<String> me = http.get("/api/v1/auth/me", null);
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(me.body()).contains("\"code\":\"UNAUTHORIZED\"");
        assertThat(RawHttp.setCookie(me, SESSION_COOKIE)).isEmpty();
    }

    @Test
    void firewallRejectionGoesThroughErrorDispatchWithoutBecoming401() throws Exception {
        // StrictHttpFirewall 이 세미콜론이 든 URL 을 거절하면 400 sendError → ERROR 디스패치(/error)가 일어난다.
        // ERROR 디스패치를 인증 대상으로 두면 익명 요청이 401 로 가려진다.
        HttpResponse<String> response = http.get("/api/v1/works;param=1", null);
        assertThat(response.statusCode()).isEqualTo(400);
    }

    /** __Host- 요구 속성: Secure, Path=/, Domain 없음. 공통: SameSite=Lax */
    static void assertCookieAttributes(String setCookie, boolean secure, boolean httpOnly) {
        String lower = setCookie.toLowerCase(Locale.ROOT);
        assertThat(lower).contains("path=/").doesNotContain("domain=").contains("samesite=lax");
        assertThat(lower.contains("; secure")).isEqualTo(secure);
        assertThat(lower.contains("httponly")).isEqualTo(httpOnly);
        assertThat(lower).doesNotContain("expires=").doesNotContain("max-age=");
    }
}
