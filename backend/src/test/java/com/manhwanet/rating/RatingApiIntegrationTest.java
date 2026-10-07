package com.manhwanet.rating;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.support.ApiFixtures;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * 내 별점 API와 작품 응답의 공개 집계 (실제 PostgreSQL 테스트 DB, 실제 보안 필터·세션·CSRF).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RatingApiIntegrationTest {

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

    private AuthTestClient admin;
    private long creatorId;
    private long workId;

    /** 작품 등록도 실제 ADMIN 로그인 세션과 쿠키 CSRF 로 한다. */
    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        String adminEmail = "admin-" + UUID.randomUUID() + "@manhwanet.test";
        adminAccountService.createAdmin(adminEmail, STRONG_PASSWORD, "관리자");
        admin = new AuthTestClient(mockMvc, objectMapper);
        assertThat(admin.login(adminEmail, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        creatorId = createCreator("한서윤");
        workId = createWork("달빛 기사단");
    }

    // ---------- 인증·CSRF ----------

    @Test
    void anonymousIsUnauthorizedForAllMethods() throws Exception {
        AuthTestClient anonymous = new AuthTestClient(mockMvc, objectMapper);
        assertError(anonymous.get(path(workId)), 401, "UNAUTHORIZED");
        assertError(anonymous.putRaw(path(workId), "{\"score\":4.5}"), 401, "UNAUTHORIZED");
        assertError(anonymous.delete(path(workId)), 401, "UNAUTHORIZED");
        assertThat(ratingCount()).isZero();
    }

    @Test
    void missingCsrfIsForbiddenForPutAndDelete() throws Exception {
        AuthTestClient user = signedUpUser();
        assertThat(user.putRaw(path(workId), "{\"score\":4.5}").getResponse().getStatus()).isEqualTo(200);

        MvcResult putWithoutCsrf = user.performWithSessionOnly(put(path(workId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"score\":1.0}"));
        assertError(putWithoutCsrf, 403, "INVALID_CSRF_TOKEN");

        MvcResult deleteWithoutCsrf = user.performWithSessionOnly(delete(path(workId))
                .cookie(user.csrfCookie()).header(AuthTestClient.CSRF_HEADER, "wrong-token"));
        assertError(deleteWithoutCsrf, 403, "INVALID_CSRF_TOKEN");

        assertThat(storedScoreHalf()).containsExactly(9);
    }

    // ---------- 등록·수정·삭제 ----------

    @Test
    void unratedReturnsNullScore() throws Exception {
        MvcResult result = signedUpUser().get(path(workId));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = body(result);
        assertThat(((Number) JsonPath.read(body, "$.data.workId")).longValue()).isEqualTo(workId);
        assertThat((Object) JsonPath.read(body, "$.data.score")).isNull();
        assertThat((Object) JsonPath.read(body, "$.data.createdAt")).isNull();
        assertThat((Object) JsonPath.read(body, "$.data.updatedAt")).isNull();
        assertThat(body).contains("\"score\":null");
    }

    @Test
    void putCreatesUpdatesAndDeleteRemovesWithTimestampPolicy() throws Exception {
        AuthTestClient user = signedUpUser();

        // 등록: 200, created_at = updated_at
        MvcResult created = user.putRaw(path(workId), "{\"score\":4.5}");
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(created)).contains("\"score\":4.5");
        Instant createdAt = instant(created, "createdAt");
        assertThat(instant(created, "updatedAt")).isEqualTo(createdAt);
        assertThat(body(user.get(path(workId)))).contains("\"score\":4.5");

        // 같은 점수로 다시 PUT: 200, 시각 유지
        MvcResult same = user.putRaw(path(workId), "{\"score\":4.50}");
        assertThat(same.getResponse().getStatus()).isEqualTo(200);
        assertThat(instant(same, "createdAt")).isEqualTo(createdAt);
        assertThat(instant(same, "updatedAt")).isEqualTo(createdAt);

        // 다른 점수로 수정: 200, created_at 유지, updated_at 증가
        MvcResult updated = user.putRaw(path(workId), "{\"score\":3}");
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(updated)).contains("\"score\":3.0");
        assertThat(instant(updated, "createdAt")).isEqualTo(createdAt);
        assertThat(instant(updated, "updatedAt")).isAfter(createdAt);
        assertThat(ratingCount()).isEqualTo(1);

        // 삭제: 204, 반복 삭제도 204
        assertThat(user.delete(path(workId)).getResponse().getStatus()).isEqualTo(204);
        assertThat(body(user.get(path(workId)))).contains("\"score\":null");
        MvcResult again = user.delete(path(workId));
        assertThat(again.getResponse().getStatus()).isEqualTo(204);
        assertThat(again.getResponse().getContentAsString()).isEmpty();
        assertThat(ratingCount()).isZero();

        // 삭제 후 재등록: 새 created_at
        MvcResult recreated = user.putRaw(path(workId), "{\"score\":0.5}");
        assertThat(recreated.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(recreated)).contains("\"score\":0.5");
        assertThat(instant(recreated, "createdAt")).isAfter(createdAt);
        assertThat(instant(recreated, "updatedAt")).isEqualTo(instant(recreated, "createdAt"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"score\":0.5}|0.5", "{\"score\":5}|5.0", "{\"score\":5.0}|5.0", "{\"score\":4.50}|4.5",
            "{\"score\":1E+0}|1.0", "{\"score\":45e-1}|4.5"})
    void acceptsJsonNumberScores(String raw, String expected) throws Exception {
        MvcResult result = signedUpUser().putRaw(path(workId), raw);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result)).contains("\"score\":" + expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"score\":\"4.5\"}|별점은 숫자여야 합니다.",
            "{\"score\":\"\"}|별점은 숫자여야 합니다.",
            "{\"score\":true}|별점은 숫자여야 합니다.",
            "{\"score\":[4.5]}|별점은 숫자여야 합니다.",
            "{\"score\":{\"value\":4.5}}|별점은 숫자여야 합니다.",
            "{\"score\":null}|별점을 선택해 주세요.",
            "{}|별점을 선택해 주세요.",
            "null|별점을 선택해 주세요.",
            "{\"score\":0}|별점은 0.5점부터 5.0점까지 0.5점 단위로 입력해 주세요.",
            "{\"score\":-0.5}|별점은 0.5점부터 5.0점까지 0.5점 단위로 입력해 주세요.",
            "{\"score\":5.5}|별점은 0.5점부터 5.0점까지 0.5점 단위로 입력해 주세요.",
            "{\"score\":4.25}|별점은 0.5점부터 5.0점까지 0.5점 단위로 입력해 주세요.",
            "{\"score\":4.50000000000000000001}|별점은 0.5점부터 5.0점까지 0.5점 단위로 입력해 주세요.",
            "{\"score\":1E+400}|별점은 0.5점부터 5.0점까지 0.5점 단위로 입력해 주세요."})
    void rejectsInvalidScoresWithFieldError(String raw, String reason) throws Exception {
        MvcResult result = signedUpUser().putRaw(path(workId), raw);
        assertError(result, 400, "INVALID_INPUT");
        assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].field")).isEqualTo("score");
        assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].reason")).isEqualTo(reason);
        assertThat(ratingCount()).isZero();
    }

    @Test
    void emptyBodyIsMissingScore() throws Exception {
        MvcResult result = signedUpUser().putRaw(path(workId), "");
        assertError(result, 400, "INVALID_INPUT");
        assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].field")).isEqualTo("score");
    }

    @Test
    void rejectsUnreadableBody() throws Exception {
        assertError(signedUpUser().putRaw(path(workId), "{\"score\":"), 400, "INVALID_INPUT");
        assertThat(ratingCount()).isZero();
    }

    @Test
    void invalidScoreIsCheckedBeforeWorkExistence() throws Exception {
        assertError(signedUpUser().putRaw(path(999_999), "{\"score\":\"4.5\"}"), 400, "INVALID_INPUT");
    }

    // ---------- 404·잘못된 workId ----------

    @ParameterizedTest
    @ValueSource(longs = {999_999L, 0L, -1L})
    void missingWorkIsNotFoundForAllMethods(long missingWorkId) throws Exception {
        AuthTestClient user = signedUpUser();
        assertError(user.get(path(missingWorkId)), 404, "WORK_NOT_FOUND");
        assertError(user.putRaw(path(missingWorkId), "{\"score\":4.5}"), 404, "WORK_NOT_FOUND");
        assertError(user.delete(path(missingWorkId)), 404, "WORK_NOT_FOUND");
        assertThat(ratingCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999"})
    void nonLongWorkIdIsBadRequest(String rawWorkId) throws Exception {
        MvcResult result = signedUpUser().get("/api/v1/me/ratings/" + rawWorkId);
        assertError(result, 400, "INVALID_INPUT");
        assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].field")).isEqualTo("workId");
    }

    // ---------- 사용자 식별·격리 ----------

    @Test
    void userIdInBodyIsIgnored() throws Exception {
        AuthTestClient userA = signedUpUser();
        AuthTestClient userB = signedUpUser();
        long userBId = currentUserId(userB);

        MvcResult result = userA.putRaw(path(workId), "{\"score\":4.5,\"userId\":" + userBId + "}");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        assertThat(body(userB.get(path(workId)))).contains("\"score\":null");
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM work_ratings", Long.class))
                .containsExactly(currentUserId(userA));
    }

    @Test
    void userAndAdminEachAccessOnlyTheirOwnRating() throws Exception {
        AuthTestClient user = signedUpUser();

        assertThat(user.putRaw(path(workId), "{\"score\":2.0}").getResponse().getStatus()).isEqualTo(200);
        assertThat(admin.putRaw(path(workId), "{\"score\":5.0}").getResponse().getStatus()).isEqualTo(200);

        assertThat(body(user.get(path(workId)))).contains("\"score\":2.0");
        assertThat(body(admin.get(path(workId)))).contains("\"score\":5.0");

        // ADMIN 의 삭제는 자기 별점만 지운다
        assertThat(admin.delete(path(workId)).getResponse().getStatus()).isEqualTo(204);
        assertThat(body(user.get(path(workId)))).contains("\"score\":2.0");
        assertThat(body(admin.get(path(workId)))).contains("\"score\":null");

        // USER 의 수정은 ADMIN 데이터에 영향 없음
        assertThat(admin.putRaw(path(workId), "{\"score\":1.5}").getResponse().getStatus()).isEqualTo(200);
        assertThat(user.putRaw(path(workId), "{\"score\":3.5}").getResponse().getStatus()).isEqualTo(200);
        assertThat(body(admin.get(path(workId)))).contains("\"score\":1.5");
        assertThat(ratingCount()).isEqualTo(2);
    }

    // ---------- 공개 집계 ----------

    @Test
    void worksExposeAverageAndCountWithHalfUpRounding() throws Exception {
        long unratedWorkId = createWork("별빛 정원");
        AuthTestClient a = signedUpUser();
        AuthTestClient b = signedUpUser();

        assertRating(workId, null, 0);
        assertRating(unratedWorkId, null, 0);

        a.putRaw(path(workId), "{\"score\":4.5}");
        b.putRaw(path(workId), "{\"score\":4.0}");
        // (9 + 8) / 4 = 4.25 → HALF_UP 4.3
        assertRating(workId, "4.3", 2);
        assertRating(unratedWorkId, null, 0);

        b.putRaw(path(workId), "{\"score\":3.0}");
        // (9 + 6) / 4 = 3.75 → 3.8
        assertRating(workId, "3.8", 2);

        a.delete(path(workId));
        assertRating(workId, "3.0", 1);

        b.delete(path(workId));
        assertRating(workId, null, 0);
    }

    @Test
    void registrationResponseHasEmptyRating() throws Exception {
        String body = body(admin.postJson("/api/v1/admin/works", ApiFixtures.workRequest("새 작품", creatorId)));
        assertThat(body).contains("\"rating\":{\"average\":null,\"count\":0}");
    }

    // ---------- 큰 ID ----------

    @Test
    void largeWorkAndUserIdsStayExact() throws Exception {
        jdbcTemplate.execute("ALTER TABLE works ALTER COLUMN id RESTART WITH 9007199254740993");
        jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN id RESTART WITH 9007199254740995");
        long largeWorkId = createWork("큰 번호 작품");
        assertThat(largeWorkId).isEqualTo(9007199254740993L);
        AuthTestClient user = signedUpUser();
        assertThat(currentUserId(user)).isEqualTo(9007199254740995L);

        MvcResult put = user.putRaw("/api/v1/me/ratings/9007199254740993", "{\"score\":4.5}");
        assertThat(put.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(put)).contains("\"workId\":9007199254740993");
        assertThat(body(user.get("/api/v1/me/ratings/9007199254740993"))).contains("\"workId\":9007199254740993");
        // 2^53+1 과 2^53 은 double 로 같은 값이 되므로 원문이 그대로인지 확인한다
        assertThat(user.get("/api/v1/me/ratings/9007199254740992").getResponse().getStatus()).isEqualTo(404);

        String detail = body(user.get("/api/v1/works/9007199254740993"));
        assertThat(detail).contains("\"id\":9007199254740993").contains("\"rating\":{\"average\":4.5,\"count\":1}");
        assertThat(body(user.get("/api/v1/works"))).contains("\"id\":9007199254740993");
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM work_ratings", Long.class)).isEqualTo(9007199254740995L);
    }

    // ---------- helpers ----------

    private void assertRating(long id, String average, long count) throws Exception {
        String expected = "\"rating\":{\"average\":" + (average == null ? "null" : average) + ",\"count\":" + count + "}";
        AuthTestClient anonymous = new AuthTestClient(mockMvc, objectMapper);
        String detail = body(anonymous.get("/api/v1/works/" + id));
        assertThat(detail).contains(expected);

        String list = body(anonymous.get("/api/v1/works?size=100"));
        List<Map<String, Object>> matches = JsonPath.read(list, "$.data.content[?(@.id == " + id + ")].rating");
        assertThat(matches).hasSize(1);
        assertThat(((Number) matches.get(0).get("count")).longValue()).isEqualTo(count);
        Object actualAverage = matches.get(0).get("average");
        if (average == null) {
            assertThat(actualAverage).isNull();
        } else {
            assertThat(String.valueOf(actualAverage)).isEqualTo(average);
        }
    }

    private long createCreator(String name) throws Exception {
        MvcResult result = admin.postJson("/api/v1/admin/creators", Map.of("name", name));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return ((Number) JsonPath.read(body(result), "$.data.id")).longValue();
    }

    private long createWork(String title) throws Exception {
        MvcResult result = admin.postJson("/api/v1/admin/works", ApiFixtures.workRequest(title, creatorId));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return ((Number) JsonPath.read(body(result), "$.data.id")).longValue();
    }

    private AuthTestClient signedUpUser() throws Exception {
        AuthTestClient client = new AuthTestClient(mockMvc, objectMapper);
        MvcResult result = client.signup("reader-" + UUID.randomUUID() + "@manhwanet.test", STRONG_PASSWORD, "독자");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return client;
    }

    private long currentUserId(AuthTestClient client) throws Exception {
        return ((Number) JsonPath.read(body(client.get("/api/v1/auth/me")), "$.data.id")).longValue();
    }

    private static String path(long id) {
        return "/api/v1/me/ratings/" + id;
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static Instant instant(MvcResult result, String field) throws Exception {
        return Instant.parse(JsonPath.read(body(result), "$.data." + field));
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat((String) JsonPath.read(body(result), "$.error.code")).isEqualTo(code);
        assertThat((Boolean) JsonPath.read(body(result), "$.success")).isFalse();
    }

    private int ratingCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_ratings", Integer.class);
    }

    private List<Integer> storedScoreHalf() {
        return jdbcTemplate.queryForList("SELECT score_half FROM work_ratings", Integer.class);
    }
}
