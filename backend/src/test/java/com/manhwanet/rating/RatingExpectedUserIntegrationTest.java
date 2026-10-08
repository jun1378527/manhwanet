package com.manhwanet.rating;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.support.ApiFixtures;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * 별점의 예상 사용자(X-Expected-User-Id)와 요청의 세션 사용자 일치 검사.
 * 계정이 바뀐 뒤 이전 화면의 PUT·DELETE(새 세션 + 유효한 새 CSRF)가 오면 어느 계정 데이터도 바뀌지 않아야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RatingExpectedUserIntegrationTest {

    private static final String HEADER = "X-Expected-User-Id";

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

    private long workId;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        String adminEmail = "admin-" + UUID.randomUUID() + "@manhwanet.test";
        adminAccountService.createAdmin(adminEmail, STRONG_PASSWORD, "관리자");
        AuthTestClient admin = new AuthTestClient(mockMvc, objectMapper);
        assertThat(admin.login(adminEmail, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        long creatorId = ((Number) JsonPath.read(
                body(admin.postJson("/api/v1/admin/creators", Map.of("name", "한서윤"))), "$.data.id")).longValue();
        workId = ((Number) JsonPath.read(
                body(admin.postJson("/api/v1/admin/works", ApiFixtures.workRequest("달빛 기사단", creatorId))),
                "$.data.id")).longValue();
    }

    @Test
    void mutationsRequireTheHeader() throws Exception {
        AuthTestClient a = signedUp("독자A").withHeader(HEADER, null);
        assertFieldError(a.putRaw(path(), "{\"score\":4.5}"));
        assertFieldError(a.delete(path()));
        assertThat(ratingCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "0", "01", "-1", "1.0", "1e3", "9223372036854775808"})
    void rejectsMalformedHeader(String value) throws Exception {
        AuthTestClient a = signedUp("독자A").withHeader(HEADER, value);
        assertFieldError(a.putRaw(path(), "{\"score\":4.5}"));
        assertThat(ratingCount()).isZero();
    }

    @Test
    void switchedAccountCannotSaveOrDeleteAndNoDataChanges() throws Exception {
        AuthTestClient a = signedUp("독자A");
        long aId = currentUserId(a);
        AuthTestClient b = signedUp("독자B");
        long bId = currentUserId(b);

        assertThat(b.putRaw(path(), "{\"score\":4.0}").getResponse().getStatus()).isEqualTo(200);

        // A 의 화면에서 보낸 요청이 B 의 세션·유효 CSRF 로 도착한다
        b.withHeader(HEADER, String.valueOf(aId));
        assertError(b.putRaw(path(), "{\"score\":1.0}"), 409, "USER_MISMATCH");
        assertError(b.delete(path()), 409, "USER_MISMATCH");
        assertError(b.get(path()), 409, "USER_MISMATCH");
        // 본문·작품이 잘못이어도 사용자 불일치를 먼저 거절한다
        assertError(b.putRaw(path(), "{\"score\":\"4.5\"}"), 409, "USER_MISMATCH");
        assertError(b.putRaw("/api/v1/me/ratings/999999", "{\"score\":4.5}"), 409, "USER_MISMATCH");

        assertThat(rows()).containsExactly(List.<Object>of(bId, 8));

        b.withHeader(HEADER, String.valueOf(bId));
        assertThat(b.delete(path()).getResponse().getStatus()).isEqualTo(204);
        assertThat(ratingCount()).isZero();
    }

    @Test
    void missingCsrfIsStillForbiddenBeforeTheUserCheck() throws Exception {
        AuthTestClient a = signedUp("독자A");
        AuthTestClient b = signedUp("독자B").withHeader(HEADER, String.valueOf(currentUserId(a)));
        MvcResult result = b.performWithSessionOnly(put(path())
                .contentType(MediaType.APPLICATION_JSON).content("{\"score\":4.5}"));
        assertError(result, 403, "INVALID_CSRF_TOKEN");
        assertThat(ratingCount()).isZero();
    }

    @Test
    void getWithoutHeaderIsAllowed() throws Exception {
        AuthTestClient a = signedUp("독자A").withHeader(HEADER, null);
        assertThat(a.get(path()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void matchingLargeUserIdWorksAndRoundedValueIsRejected() throws Exception {
        jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN id RESTART WITH 9007199254740993");
        AuthTestClient a = new AuthTestClient(mockMvc, objectMapper);
        assertThat(a.signup("big-" + UUID.randomUUID() + "@manhwanet.test", STRONG_PASSWORD, "큰번호")
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(currentUserId(a)).isEqualTo(9007199254740993L);

        a.withHeader(HEADER, "9007199254740992");
        assertError(a.putRaw(path(), "{\"score\":4.5}"), 409, "USER_MISMATCH");
        assertThat(ratingCount()).isZero();

        a.withHeader(HEADER, "9007199254740993");
        assertThat(a.putRaw(path(), "{\"score\":4.5}").getResponse().getStatus()).isEqualTo(200);
        assertThat(rows()).containsExactly(List.<Object>of(9007199254740993L, 9));
        assertThat(a.delete(path()).getResponse().getStatus()).isEqualTo(204);
        assertThat(ratingCount()).isZero();
    }

    @Test
    void headerIsNeverUsedAsOwner() throws Exception {
        AuthTestClient a = signedUp("독자A");
        AuthTestClient b = signedUp("독자B");
        a.withHeader(HEADER, String.valueOf(currentUserId(b)));
        assertError(a.putRaw(path(), "{\"score\":5.0}"), 409, "USER_MISMATCH");
        assertThat(ratingCount()).isZero();
    }

    private AuthTestClient signedUp(String nickname) throws Exception {
        AuthTestClient client = new AuthTestClient(mockMvc, objectMapper);
        MvcResult result = client.signup("reader-" + UUID.randomUUID() + "@manhwanet.test", STRONG_PASSWORD, nickname);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return client.withHeader(HEADER, String.valueOf(currentUserId(client)));
    }

    private long currentUserId(AuthTestClient client) throws Exception {
        return ((Number) JsonPath.read(body(client.get("/api/v1/auth/me")), "$.data.id")).longValue();
    }

    private String path() {
        return "/api/v1/me/ratings/" + workId;
    }

    private List<List<Object>> rows() {
        return jdbcTemplate.query("SELECT user_id, score_half FROM work_ratings ORDER BY user_id",
                (rs, i) -> List.of(rs.getLong(1), rs.getInt(2)));
    }

    private int ratingCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_ratings", Integer.class);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat((String) JsonPath.read(body(result), "$.error.code")).isEqualTo(code);
    }

    private static void assertFieldError(MvcResult result) throws Exception {
        assertError(result, 400, "INVALID_INPUT");
        assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].field")).isEqualTo("expectedUserId");
    }
}
