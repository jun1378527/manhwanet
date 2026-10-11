package com.manhwanet.auth;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.auth.application.LoginAttemptLimiter;
import com.manhwanet.global.security.PasswordHashGate;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 해시 게이트(503 AUTH_BUSY)와 로그인·가입·관리자 생성의 적용 범위 (실제 PostgreSQL 테스트 DB, 실제 Argon2id).
 * 게이트를 테스트 스레드가 쥐어 과부하를 결정적으로 만든다(P=1, Q=0).
 */
@SpringBootTest(properties = {
        "manhwanet.auth.hash-limit.max-concurrent=1",
        "manhwanet.auth.hash-limit.max-waiting=0",
        "manhwanet.auth.hash-limit.max-wait=50ms"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LoginProtectionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private PasswordHashGate gate;

    @Autowired
    private LoginAttemptLimiter limiter;

    @Autowired
    private AdminAccountService adminAccountService;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        gate.drainStats();
        limiter.drainStats();
    }

    private AuthTestClient client() {
        return new AuthTestClient(mockMvc, objectMapper);
    }

    private static String uniqueEmail() {
        return "guard-" + UUID.randomUUID() + "@example.com";
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private long userCount() {
        return jdbcTemplate.queryForObject("select count(*) from users", Long.class);
    }

    private static void assertAuthBusy(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("1");
        assertThat((String) JsonPath.read(body(result), "$.error.code")).isEqualTo("AUTH_BUSY");
        assertThat(result.getResponse().getCookies()).noneMatch(cookie -> cookie.getName().contains("SESSION"));
    }

    @Test
    void overloadedLoginIsSameFor503ForUnknownAndExistingEmailAndNotCounted() throws Exception {
        String existing = uniqueEmail();
        client().signup(existing, STRONG_PASSWORD, "독자");
        String unknown = uniqueEmail();

        MvcResult existingBusy;
        MvcResult unknownBusy;
        try (PasswordHashGate.Permit held = gate.acquire()) {
            existingBusy = client().login(existing, "wrong-password-value");
            unknownBusy = client().login(unknown, "wrong-password-value");
            for (int i = 0; i < 10; i++) {
                assertAuthBusy(client().login(existing, "wrong-password-value-" + i));
            }
        }
        assertAuthBusy(existingBusy);
        assertAuthBusy(unknownBusy);
        assertThat(body(existingBusy)).isEqualTo(body(unknownBusy));
        assertThat(existingBusy.getResponse().getHeaderNames())
                .containsExactlyInAnyOrderElementsOf(unknownBusy.getResponse().getHeaderNames());

        // 과부하 거절은 실패로 세지 않았다: 4번 틀린 뒤 맞는 비밀번호로 로그인된다.
        for (int i = 0; i < 4; i++) {
            assertThat(client().login(existing, "wrong-password-value-" + i).getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(client().login(existing, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(gate.availablePermits()).isEqualTo(1);
        assertThat(limiter.size()).isZero();
    }

    @Test
    void unknownEmailAndWrongPasswordStillReturnIdentical401() throws Exception {
        String existing = uniqueEmail();
        client().signup(existing, STRONG_PASSWORD, "독자");
        MvcResult wrongPassword = client().login(existing, "wrong-password-value");
        MvcResult unknownEmail = client().login(uniqueEmail(), "wrong-password-value");
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(wrongPassword)).isEqualTo(body(unknownEmail));
        assertThat(wrongPassword.getResponse().getHeader("Retry-After")).isNull();
    }

    @Test
    void signupHashUsesTheSameGateAndSavesNothingWhenBusy() throws Exception {
        String email = uniqueEmail();
        long before = userCount();
        try (PasswordHashGate.Permit held = gate.acquire()) {
            assertAuthBusy(client().signup(email, STRONG_PASSWORD, "독자"));
        }
        assertThat(userCount()).isEqualTo(before);
        assertThat(client().signup(email, STRONG_PASSWORD, "독자").getResponse().getStatus()).isEqualTo(201);
        assertThat(userCount()).isEqualTo(before + 1);
        assertThat(gate.availablePermits()).isEqualTo(1);
    }

    @Test
    void duplicateSignupIsRejectedWithoutHashingEvenWhenBusy() throws Exception {
        String email = uniqueEmail();
        client().signup(email, STRONG_PASSWORD, "독자");
        try (PasswordHashGate.Permit held = gate.acquire()) {
            MvcResult duplicate = client().signup(email, STRONG_PASSWORD, "다른독자");
            assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        }
    }

    @Test
    void adminCreationCommandDoesNotUseTheGate() {
        long before = userCount();
        try (PasswordHashGate.Permit held = gate.acquire()) {
            assertThat(gate.availablePermits()).isZero();
            adminAccountService.createAdmin(uniqueEmail(), "admin-only-passphrase-77", "관리자");
        }
        assertThat(userCount()).isEqualTo(before + 1);
    }

    @Test
    void rejectionLogsCountsOnlyWithoutEmailOrPassword(CapturedOutput output) throws Exception {
        String email = uniqueEmail();
        String password = "very-secret-pass-" + UUID.randomUUID();
        try (PasswordHashGate.Permit held = gate.acquire()) {
            client().login(email, password);
            client().signup(email, password, "닉네임");
        }
        for (int i = 0; i < 6; i++) {
            client().login(email, password); // 5회 401 → 429
        }
        // 1분 주기 작업을 직접 실행한다(테스트에서는 기다리지 않음)
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(monitor(), "runOnce");
        String logs = output.getAll();
        assertThat(logs).contains("login-protection rejections");
        assertThat(logs).contains("hashBusy503=2");
        assertThat(logs).contains("accountLocked429=1");
        assertThat(logs).doesNotContain(email);
        assertThat(logs).doesNotContain(email.substring(0, email.indexOf('@')));
        assertThat(logs).doesNotContain(password);
    }

    @Autowired
    private org.springframework.context.ApplicationContext context;

    private Object monitor() {
        return context.getBean(com.manhwanet.auth.application.LoginProtectionMonitor.class);
    }
}
