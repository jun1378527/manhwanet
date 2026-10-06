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
import com.manhwanet.user.application.AdminAccountService;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * /api/v1/admin/** 접근 규칙 (실제 로그인 세션 사용).
 * 이전의 조건부 등록 테스트(AdminApiDisabledByDefaultTest, AdminApiDisabledWithoutLocalProfileTest)를 대체한다.
 * 등록 API 는 이제 항상 등록되며, 인증·인가로 보호된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAuthorizationIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@manhwanet.test";
    private static final Map<String, Object> CREATOR = Map.of("name", "한서윤");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private AdminAccountService adminAccountService;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
    }

    @Test
    void anonymousWithValidCsrfIsUnauthorized() throws Exception {
        AuthTestClient anonymous = new AuthTestClient(mockMvc, objectMapper);
        MvcResult result = anonymous.postJson("/api/v1/admin/creators", CREATOR);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.error.code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void userWithValidCsrfIsForbidden() throws Exception {
        AuthTestClient user = new AuthTestClient(mockMvc, objectMapper);
        user.signup("user@manhwanet.test", STRONG_PASSWORD, "독자");

        MvcResult result = user.postJson("/api/v1/admin/creators", CREATOR);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.error.code")).isEqualTo("FORBIDDEN");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM creators", Integer.class)).isZero();
    }

    @Test
    void adminWithValidCsrfCanRegister() throws Exception {
        adminAccountService.createAdmin(ADMIN_EMAIL, STRONG_PASSWORD, "관리자");
        AuthTestClient admin = new AuthTestClient(mockMvc, objectMapper);
        assertThat(admin.login(ADMIN_EMAIL, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(200);

        MvcResult result = admin.postJson("/api/v1/admin/creators", CREATOR);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.data.name")).isEqualTo("한서윤");
    }

    @Test
    void missingCsrfIsForbiddenEvenForAdmin() throws Exception {
        adminAccountService.createAdmin(ADMIN_EMAIL, STRONG_PASSWORD, "관리자");
        AuthTestClient admin = new AuthTestClient(mockMvc, objectMapper);
        admin.login(ADMIN_EMAIL, STRONG_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/creators").session(admin.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"한서윤\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INVALID_CSRF_TOKEN"));

        mockMvc.perform(post("/api/v1/admin/creators")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"한서윤\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INVALID_CSRF_TOKEN"));
    }

    @Test
    void csrfTokenFromAnotherCookieDoesNotMatch() throws Exception {
        adminAccountService.createAdmin(ADMIN_EMAIL, STRONG_PASSWORD, "관리자");
        AuthTestClient admin = new AuthTestClient(mockMvc, objectMapper);
        admin.login(ADMIN_EMAIL, STRONG_PASSWORD);
        Cookie own = admin.csrfCookie();
        Cookie other = new AuthTestClient(mockMvc, objectMapper).fetchCsrf();

        mockMvc.perform(post("/api/v1/admin/creators").session(admin.session()).cookie(own)
                        .header(AuthTestClient.CSRF_HEADER, other.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"한서윤\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INVALID_CSRF_TOKEN"));
    }

    @Test
    void publicReadsAndHealthStayOpenToAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/works")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/creators")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNoContent());
    }

    @Test
    void adminReadsAlsoRequireAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/works")).andExpect(status().isUnauthorized());
    }
}
