package com.manhwanet.auth;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 가입·로그인·로그아웃·me·CSRF 흐름 (실제 PostgreSQL 테스트 DB, 실제 보안 필터 체인).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    private AuthTestClient client;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        client = new AuthTestClient(mockMvc, objectMapper);
    }

    /** 실패 제한은 이메일 키별 메모리 상태이므로 테스트마다 다른 이메일을 쓴다. */
    private static String uniqueEmail() {
        return "reader-" + UUID.randomUUID() + "@example.com";
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    // ---------- CSRF ----------

    @Test
    void csrfEndpointIssuesTokenCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNoContent()).andReturn();
        Cookie cookie = result.getResponse().getCookie(AuthTestClient.CSRF_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(result.getRequest().getSession(false)).as("CSRF 발급만으로 세션을 만들지 않는다").isNull();
    }

    @Test
    void changingRequestWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.com\",\"password\":\"" + STRONG_PASSWORD + "\",\"nickname\":\"독자\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INVALID_CSRF_TOKEN"));

        Cookie cookie = client.fetchCsrf();
        mockMvc.perform(post("/api/v1/auth/login").cookie(cookie).header(AuthTestClient.CSRF_HEADER, "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@example.com\",\"password\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INVALID_CSRF_TOKEN"));
    }

    // ---------- 가입 ----------

    @Test
    void signupCreatesUserWithArgon2HashAndLogsIn() throws Exception {
        String email = uniqueEmail();
        Map<String, Object> request = new HashMap<>();
        request.put("email", "  " + email.toUpperCase() + " ");
        request.put("password", STRONG_PASSWORD);
        request.put("nickname", "  독자  ");
        request.put("role", "ADMIN"); // 무시되어야 한다
        Cookie csrfBefore = client.fetchCsrf();

        MvcResult result = client.postJson("/api/v1/auth/signup", request);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/auth/me");
        assertThat((String) JsonPath.read(body(result), "$.data.email")).isEqualTo(email);
        assertThat((String) JsonPath.read(body(result), "$.data.nickname")).isEqualTo("독자");
        assertThat((String) JsonPath.read(body(result), "$.data.role")).isEqualTo("USER");
        assertThat(body(result)).doesNotContain("password");

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT role, password_hash FROM users WHERE email = ?", email);
        assertThat(row.get("role")).isEqualTo("USER");
        String hash = (String) row.get("password_hash");
        assertThat(hash).startsWith("{argon2}$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain(STRONG_PASSWORD);

        // 자동 로그인: 세션에 SecurityContext 가 저장되고, CSRF 토큰이 새로 발급된다.
        MockHttpSession session = client.session();
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isInstanceOf(SecurityContext.class);
        assertThat(client.csrfCookie()).isNotNull();
        assertThat(client.csrfCookie().getValue()).isNotEqualTo(csrfBefore.getValue());

        MvcResult me = client.get("/api/v1/auth/me");
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(body(me), "$.data.email")).isEqualTo(email);
    }

    @Test
    void signupAutoLoginChangesExistingSessionId() throws Exception {
        String first = uniqueEmail();
        client.signup(first, STRONG_PASSWORD, "첫번째");
        String sessionIdBefore = client.session().getId();

        // 로그인된 세션으로 다른 계정을 가입하면 같은 세션 객체의 ID 가 바뀐다.
        MvcResult result = client.signup(uniqueEmail(), STRONG_PASSWORD, "두번째");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(client.session().getId()).isNotEqualTo(sessionIdBefore);
    }

    @Test
    void duplicateEmailAfterNormalizationIsConflict() throws Exception {
        String email = uniqueEmail();
        client.signup(email, STRONG_PASSWORD, "독자");

        AuthTestClient other = new AuthTestClient(mockMvc, objectMapper);
        MvcResult result = other.signup(" " + email.toUpperCase(), STRONG_PASSWORD, "다른독자");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(body(result), "$.error.code")).isEqualTo("DUPLICATE_RESOURCE");
    }

    @Test
    void duplicateNicknameIsAllowed() throws Exception {
        assertThat(client.signup(uniqueEmail(), STRONG_PASSWORD, "같은닉네임").getResponse().getStatus()).isEqualTo(201);
        AuthTestClient other = new AuthTestClient(mockMvc, objectMapper);
        assertThat(other.signup(uniqueEmail(), STRONG_PASSWORD, "같은닉네임").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void signupValidatesFieldsAndPasswordBoundaries() throws Exception {
        MvcResult invalid = client.signup("not-an-email", "가".repeat(14), "독");
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        List<String> fields = JsonPath.read(body(invalid), "$.error.fieldErrors[*].field");
        assertThat(fields).containsExactlyInAnyOrder("email", "nickname", "password");

        assertThat(new AuthTestClient(mockMvc, objectMapper)
                .signup(uniqueEmail(), "가".repeat(129), "독자").getResponse().getStatus()).isEqualTo(400);
        assertThat(new AuthTestClient(mockMvc, objectMapper)
                .signup(uniqueEmail(), "가".repeat(15) , "독자").getResponse().getStatus()).isEqualTo(201);
        assertThat(new AuthTestClient(mockMvc, objectMapper)
                .signup(uniqueEmail(), "나".repeat(128), "독자").getResponse().getStatus()).isEqualTo(201);
        assertThat(new AuthTestClient(mockMvc, objectMapper)
                .signup(uniqueEmail(), "가".repeat(10), "가".repeat(31)).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void signupRejectsCommonPasswordAndPasswordEqualToEmailOrNickname() throws Exception {
        MvcResult common = client.signup(uniqueEmail(), "qwertyuiopasdfgh", "독자");
        assertThat(common.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(body(common), "$.error.fieldErrors[0].field")).isEqualTo("password");

        String email = uniqueEmail();
        assertThat(new AuthTestClient(mockMvc, objectMapper)
                .signup(email, email.toUpperCase(), "독자").getResponse().getStatus()).isEqualTo(400);
        assertThat(new AuthTestClient(mockMvc, objectMapper)
                .signup(uniqueEmail(), "LongNicknameValue", "longnicknamevalue").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void passwordIsNotTrimmedOrTruncated() throws Exception {
        String email = uniqueEmail();
        String withSpaces = "  " + STRONG_PASSWORD + "  ";
        assertThat(client.signup(email, withSpaces, "독자").getResponse().getStatus()).isEqualTo(201);

        AuthTestClient trimmed = new AuthTestClient(mockMvc, objectMapper);
        assertThat(trimmed.login(email, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(401);
        AuthTestClient exact = new AuthTestClient(mockMvc, objectMapper);
        assertThat(exact.login(email, withSpaces).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void passwordIsNfkcNormalizedConsistentlyForSignupAndLogin() throws Exception {
        String email = uniqueEmail();
        String fullWidth = "ｐｌｕｍ－ｒｉｖｅｒ－ｌａｎｔｅｒｎ"; // NFKC → plum-river-lantern
        assertThat(client.signup(email, fullWidth, "독자").getResponse().getStatus()).isEqualTo(201);
        assertThat(new AuthTestClient(mockMvc, objectMapper).login(email, "plum-river-lantern").getResponse().getStatus())
                .isEqualTo(200);
    }

    // ---------- 로그인 ----------

    @Test
    void loginEstablishesSessionChangesSessionIdAndRotatesCsrf() throws Exception {
        String email = uniqueEmail();
        client.signup(email, STRONG_PASSWORD, "독자");
        client.logout();

        // 로그인 전부터 있던 세션을 흉내 낸다 (예: 다른 사용자로 로그인되어 있던 브라우저).
        AuthTestClient browser = new AuthTestClient(mockMvc, objectMapper);
        MockHttpSession existing = new MockHttpSession();
        String idBefore = existing.getId();
        browser.useSession(existing);
        Cookie csrfBefore = browser.fetchCsrf();

        MvcResult login = browser.login("  " + email.toUpperCase(), STRONG_PASSWORD);

        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(body(login), "$.data.email")).isEqualTo(email);
        assertThat(browser.session().getId()).as("세션 고정 방지").isNotEqualTo(idBefore);
        assertThat(browser.csrfCookie().getValue()).as("로그인 후 CSRF 토큰 교체").isNotEqualTo(csrfBefore.getValue());

        // 세션 유지: 같은 세션으로 me 조회
        assertThat(browser.get("/api/v1/auth/me").getResponse().getStatus()).isEqualTo(200);
        // 교체된 토큰으로 다음 변경 요청이 가능하다.
        assertThat(browser.logout().getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void unknownEmailAndWrongPasswordReturnIdenticalResponses() throws Exception {
        String email = uniqueEmail();
        client.signup(email, STRONG_PASSWORD, "독자");

        MvcResult wrongPassword = new AuthTestClient(mockMvc, objectMapper).login(email, "wrong-password-value-1");
        MvcResult unknownEmail = new AuthTestClient(mockMvc, objectMapper).login(uniqueEmail(), "wrong-password-value-1");

        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(wrongPassword)).isEqualTo(body(unknownEmail));
        assertThat((String) JsonPath.read(body(wrongPassword), "$.error.code")).isEqualTo("INVALID_CREDENTIALS");
        assertThat(wrongPassword.getRequest().getSession(false)).isNull();
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---------- 로그아웃 ----------

    @Test
    void logoutInvalidatesSessionAndClearsCsrfToken() throws Exception {
        client.signup(uniqueEmail(), STRONG_PASSWORD, "독자");
        MockHttpSession oldSession = client.session();

        MvcResult logout = client.logout();

        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
        assertThat(oldSession.isInvalid()).isTrue();
        Cookie cleared = logout.getResponse().getCookie(AuthTestClient.CSRF_COOKIE);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();

        // 기존 세션으로는 더 이상 인증되지 않는다.
        mockMvc.perform(get("/api/v1/auth/me").session(oldSession)).andExpect(status().isUnauthorized());

        // 로그아웃 후 다음 요청에서 새 CSRF 토큰을 받는다.
        assertThat(client.fetchCsrf()).isNotNull();
    }

    @Test
    void logoutWithoutCsrfIsForbidden() throws Exception {
        client.signup(uniqueEmail(), STRONG_PASSWORD, "독자");
        mockMvc.perform(post("/api/v1/auth/logout").session(client.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INVALID_CSRF_TOKEN"));
        assertThat(client.get("/api/v1/auth/me").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anonymousLogoutWithValidCsrfIsIdempotent() throws Exception {
        assertThat(client.logout().getResponse().getStatus()).isEqualTo(204);
    }

    // ---------- 실패 제한 ----------

    @Test
    void fiveFailuresLockTheEmailEvenForCorrectPassword() throws Exception {
        String email = uniqueEmail();
        client.signup(email, STRONG_PASSWORD, "독자");

        for (int i = 0; i < 5; i++) {
            assertThat(new AuthTestClient(mockMvc, objectMapper).login(email, "wrong-password-value-" + i)
                    .getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult locked = new AuthTestClient(mockMvc, objectMapper).login(email, STRONG_PASSWORD);
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat((String) JsonPath.read(body(locked), "$.error.code")).isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
        assertThat(Long.parseLong(locked.getResponse().getHeader("Retry-After"))).isBetween(1L, 900L);
    }

    @Test
    void unknownEmailIsLimitedTheSameWay() throws Exception {
        String email = uniqueEmail();
        for (int i = 0; i < 5; i++) {
            assertThat(new AuthTestClient(mockMvc, objectMapper).login(email, "wrong-password-value-" + i)
                    .getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(new AuthTestClient(mockMvc, objectMapper).login(" " + email.toUpperCase(), "whatever-password-x")
                .getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void successfulLoginResetsFailures() throws Exception {
        String email = uniqueEmail();
        client.signup(email, STRONG_PASSWORD, "독자");
        for (int i = 0; i < 4; i++) {
            new AuthTestClient(mockMvc, objectMapper).login(email, "wrong-password-value-" + i);
        }
        assertThat(new AuthTestClient(mockMvc, objectMapper).login(email, STRONG_PASSWORD).getResponse().getStatus())
                .isEqualTo(200);
        for (int i = 0; i < 4; i++) {
            assertThat(new AuthTestClient(mockMvc, objectMapper).login(email, "wrong-password-value-" + i)
                    .getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(new AuthTestClient(mockMvc, objectMapper).login(email, STRONG_PASSWORD).getResponse().getStatus())
                .isEqualTo(200);
    }
}
