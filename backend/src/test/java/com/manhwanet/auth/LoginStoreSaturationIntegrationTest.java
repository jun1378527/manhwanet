package com.manhwanet.auth;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.auth.application.LoginAttemptLimiter;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 실제 저장소 포화(max-entries=3)에서 잠금 보존·신규 이메일 503·기존 키 정상 처리 (실제 DB·Argon2id).
 * 제한기 상태가 테스트 사이에 이어지므로 한 흐름으로 확인한다.
 */
@SpringBootTest(properties = "manhwanet.auth.login-limit.max-entries=3")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginStoreSaturationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private LoginAttemptLimiter limiter;

    private MvcResult login(String email, String password) throws Exception {
        return new AuthTestClient(mockMvc, objectMapper).login(email, password);
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String code(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.error.code");
    }

    @Test
    void lockSurvivesSaturationNewEmailsGet503AndExistingKeysWorkNormally() throws Exception {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        String member = email("member");
        new AuthTestClient(mockMvc, objectMapper).signup(member, STRONG_PASSWORD, "회원");

        // 1) 회원 이메일 잠금(5회 실패)
        for (int i = 0; i < 5; i++) {
            assertThat(status(login(member, "wrong-password-" + i))).isEqualTo(401);
        }
        assertThat(status(login(member, STRONG_PASSWORD))).isEqualTo(429);

        // 2) 서로 다른 없는 이메일 2개로 저장소를 채운다(상한 3)
        String other1 = email("other");
        String other2 = email("other");
        assertThat(status(login(other1, "wrong-password"))).isEqualTo(401);
        assertThat(status(login(other2, "wrong-password"))).isEqualTo(401);
        assertThat(limiter.size()).isEqualTo(3);

        // 3) 신규 이메일은 비밀번호 검증 없이 503 AUTH_BUSY, Retry-After = 가장 이른 만료까지(최대 900초)
        for (int i = 0; i < 5; i++) {
            MvcResult busy = login(email("newcomer"), "wrong-password");
            assertThat(status(busy)).isEqualTo(503);
            assertThat(code(busy)).isEqualTo("AUTH_BUSY");
            assertThat(Long.parseLong(busy.getResponse().getHeader("Retry-After"))).isBetween(890L, 900L);
        }
        assertThat(limiter.size()).isEqualTo(3);

        // 4) 잠금은 그대로다(이전 구현은 여기서 잠긴 키가 제거되어 로그인이 허용됐다)
        MvcResult stillLocked = login(member, STRONG_PASSWORD);
        assertThat(status(stillLocked)).isEqualTo(429);
        assertThat(code(stillLocked)).isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
        assertThat(Long.parseLong(stillLocked.getResponse().getHeader("Retry-After"))).isBetween(1L, 900L);

        // 5) 기존 키는 상한과 무관하게 평소대로: 실패 기록 → 5회째 잠금
        for (int i = 0; i < 4; i++) {
            assertThat(status(login(other1, "wrong-password-" + i))).isEqualTo(401);
        }
        assertThat(status(login(other1, "wrong-password"))).isEqualTo(429);
        assertThat(limiter.size()).isEqualTo(3);
    }
}
