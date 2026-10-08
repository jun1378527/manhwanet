package com.manhwanet.review;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * 화면이 예상한 사용자(X-Expected-User-Id)와 요청의 세션 사용자 일치 검사 (#22, 실제 보안 필터·세션·CSRF).
 * 계정이 바뀐 뒤 이전 화면의 요청(같은 브라우저의 새 세션 + 유효한 새 CSRF 토큰)이 오면 어떤 데이터도 바뀌지 않아야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewExpectedUserIntegrationTest {

    private static final String HEADER = "X-Expected-User-Id";
    private static final String BODY_A = "A가 쓰던 리뷰 내용입니다. B에게 저장되면 안 됩니다.";
    private static final String BODY_B = "B가 이미 써 둔 리뷰 내용입니다.";

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

    // ---------- 필수·형식 ----------

    @Test
    void mutationsRequireTheHeader() throws Exception {
        AuthTestClient a = signedUp("독자A").withHeader(HEADER, null);
        assertFieldError(a.postRaw(path(), create(BODY_A)));
        assertFieldError(a.putRaw(path(), update(1, 0, BODY_A)));
        assertFieldError(a.delete(path() + "?reviewId=1&version=0"));
        assertThat(reviewCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "0", "01", "-1", "+1", " 1", "1 ", "1.0", "1e3", "9223372036854775808"})
    void rejectsMalformedHeader(String value) throws Exception {
        AuthTestClient a = signedUp("독자A").withHeader(HEADER, value);
        assertFieldError(a.postRaw(path(), create(BODY_A)));
        assertThat(reviewCount()).isZero();
    }

    // ---------- 불일치: 아무것도 바뀌지 않음 ----------

    /**
     * 같은 브라우저에서 A → B 로 로그인이 바뀐 상황: 요청의 세션·CSRF 는 B 의 것이고, 헤더는 A 의 화면이 보낸 A 의 ID 다.
     * CSRF 검사를 통과하더라도(재시도로 새 토큰을 받은 경우와 같음) 서버가 거절해야 한다.
     */
    @Test
    void switchedAccountCannotCreateUpdateOrDeleteAndNoDataChanges() throws Exception {
        AuthTestClient a = signedUp("독자A");
        long aId = currentUserId(a);
        AuthTestClient b = signedUp("독자B");
        long bId = currentUserId(b);

        // B 는 이미 리뷰가 있다
        Map<String, Object> bReview = review(b.postRaw(path(), create(BODY_B)));
        long bReviewId = ((Number) bReview.get("id")).longValue();

        // A 의 화면에서 보낸 요청이 B 의 세션으로 도착
        b.withHeader(HEADER, String.valueOf(aId));
        assertError(b.postRaw(path(), create(BODY_A)), 409, "USER_MISMATCH");
        assertError(b.putRaw(path(), update(bReviewId, 0, BODY_A)), 409, "USER_MISMATCH");
        assertError(b.delete(path() + "?reviewId=" + bReviewId + "&version=0"), 409, "USER_MISMATCH");
        assertError(b.get(path()), 409, "USER_MISMATCH");

        assertThat(rows()).containsExactly(List.of(bId, BODY_B, 0L));

        // A 는 리뷰가 없는 그대로, B 는 원래 리뷰 그대로
        b.withHeader(HEADER, String.valueOf(bId));
        assertThat(body(a.get(path()))).contains("\"review\":null");
        assertThat(review(b.get(path()))).isEqualTo(bReview);
    }

    @Test
    void switchedAccountWithoutReviewStillChangesNothing() throws Exception {
        AuthTestClient a = signedUp("독자A");
        long aId = currentUserId(a);
        Map<String, Object> aReview = review(a.postRaw(path(), create(BODY_A)));
        long aReviewId = ((Number) aReview.get("id")).longValue();
        AuthTestClient b = signedUp("독자B").withHeader(HEADER, String.valueOf(aId));

        // B 에게 리뷰가 없어도 404·204 가 아니라 409: 삭제가 된 것처럼 보이지 않는다
        assertError(b.delete(path() + "?reviewId=" + aReviewId + "&version=0"), 409, "USER_MISMATCH");
        assertError(b.putRaw(path(), update(aReviewId, 0, BODY_B)), 409, "USER_MISMATCH");
        assertThat(rows()).containsExactly(List.of(aId, BODY_A, 0L));
    }

    @Test
    void mismatchIsCheckedBeforeInputValidation() throws Exception {
        AuthTestClient a = signedUp("독자A");
        AuthTestClient b = signedUp("독자B").withHeader(HEADER, String.valueOf(currentUserId(a)));
        assertError(b.postRaw(path(), "{\"body\":1}"), 409, "USER_MISMATCH");
        assertError(b.postRaw("/api/v1/me/reviews/999999", create(BODY_A)), 409, "USER_MISMATCH");
    }

    @Test
    void csrfIsStillCheckedFirst() throws Exception {
        AuthTestClient a = signedUp("독자A");
        AuthTestClient b = signedUp("독자B").withHeader(HEADER, String.valueOf(currentUserId(a)));
        MvcResult result = b.performWithSessionOnly(post(path())
                .contentType(MediaType.APPLICATION_JSON).content(create(BODY_A)));
        assertError(result, 403, "INVALID_CSRF_TOKEN");
        assertThat(reviewCount()).isZero();
    }

    @Test
    void getWithoutHeaderIsAllowed() throws Exception {
        AuthTestClient a = signedUp("독자A").withHeader(HEADER, null);
        assertThat(a.get(path()).getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- 정상·큰 사용자 ID ----------

    @Test
    void matchingLargeUserIdWorksAndRoundedValueIsRejected() throws Exception {
        jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN id RESTART WITH 9007199254740993");
        AuthTestClient a = new AuthTestClient(mockMvc, objectMapper);
        assertThat(a.signup("big-" + UUID.randomUUID() + "@manhwanet.test", STRONG_PASSWORD, "큰번호")
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(currentUserId(a)).isEqualTo(9007199254740993L);

        // double 로 바꾸면 9007199254740992 가 되는 값: 정확히 비교해야 한다
        a.withHeader(HEADER, "9007199254740992");
        assertError(a.postRaw(path(), create(BODY_A)), 409, "USER_MISMATCH");
        assertThat(reviewCount()).isZero();

        a.withHeader(HEADER, "9007199254740993");
        Map<String, Object> created = review(a.postRaw(path(), create(BODY_A)));
        long id = ((Number) created.get("id")).longValue();
        assertThat(a.putRaw(path(), update(id, 0, "큰 사용자 ID로 수정한 리뷰입니다.")).getResponse().getStatus()).isEqualTo(200);
        assertThat(a.delete(path() + "?reviewId=" + id + "&version=1").getResponse().getStatus()).isEqualTo(204);
        assertThat(reviewCount()).isZero();
    }

    @Test
    void headerIsNeverUsedAsAuthor() throws Exception {
        AuthTestClient a = signedUp("독자A");
        AuthTestClient b = signedUp("독자B");
        long bId = currentUserId(b);
        // A 가 B 의 ID 를 보내도 B 로 쓰지 않고 거절한다
        a.withHeader(HEADER, String.valueOf(bId));
        assertError(a.postRaw(path(), create(BODY_A)), 409, "USER_MISMATCH");
        assertThat(reviewCount()).isZero();
    }

    // ---------- helpers ----------

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
        return "/api/v1/me/reviews/" + workId;
    }

    private String create(String reviewBody) throws Exception {
        return objectMapper.writeValueAsString(Map.of("body", reviewBody, "containsSpoiler", false));
    }

    private String update(long reviewId, long version, String reviewBody) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("reviewId", reviewId, "version", version, "body", reviewBody, "containsSpoiler", false));
    }

    private Map<String, Object> review(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isIn(200, 201);
        return JsonPath.read(body(result), "$.data.review");
    }

    private List<List<Object>> rows() {
        return jdbcTemplate.query("SELECT user_id, body, version FROM work_reviews ORDER BY id",
                (rs, i) -> List.of(rs.getLong(1), rs.getString(2), rs.getLong(3)));
    }

    private int reviewCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_reviews", Integer.class);
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
