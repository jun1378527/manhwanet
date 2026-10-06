package com.manhwanet.auth;

import static com.manhwanet.support.AuthTestClient.CSRF_COOKIE;
import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.manhwanet.support.RawHttp;
import com.manhwanet.support.TestDatabaseCleaner;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * local 프로필: HTTP 개발용 쿠키(Secure 없음, __Host- 접두사 없음)와 127.0.0.1 바인딩.
 * test 프로필을 마지막에 두어 테스트 DB 를 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "test"})
class LocalSessionCookieHttpTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
    }

    @Test
    void localProfileBindsToLoopback() {
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    void localCookiesAreNotSecure() throws Exception {
        RawHttp http = new RawHttp(port);
        HttpResponse<String> csrf = http.get("/api/v1/auth/csrf", null);
        String csrfCookie = RawHttp.setCookie(csrf, CSRF_COOKIE).orElseThrow();
        SessionCookieHttpTest.assertCookieAttributes(csrfCookie, false, false);

        HttpResponse<String> signup = http.postJson("/api/v1/auth/signup",
                "{\"email\":\"local-" + System.nanoTime() + "@example.com\",\"password\":\"" + STRONG_PASSWORD
                        + "\",\"nickname\":\"독자\"}",
                RawHttp.pair(csrfCookie), RawHttp.value(csrfCookie));
        assertThat(signup.statusCode()).isEqualTo(201);
        String sessionCookie = RawHttp.setCookie(signup, "MANHWANET_SESSION").orElseThrow();
        SessionCookieHttpTest.assertCookieAttributes(sessionCookie, false, true);
        assertThat(RawHttp.setCookie(signup, SessionCookieHttpTest.SESSION_COOKIE)).isEmpty();
    }
}
