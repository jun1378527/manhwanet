package com.manhwanet.review;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.support.ApiFixtures;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import java.time.Instant;
import java.util.LinkedHashMap;
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
 * 리뷰 API (실제 PostgreSQL 테스트 DB, 실제 보안 필터·세션·CSRF).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewApiIntegrationTest {

    private static final String BODY = "그림체가 좋고 이야기도 탄탄하다.";
    private static final String BODY2 = "다시 읽어 보니 결말도 납득이 된다.";

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

    // ---------- 보안 규칙 (실제 필터 체인) ----------

    @Test
    void publicListIsOpenToAnonymousButMyReviewsAreNot() throws Exception {
        AuthTestClient anonymous = new AuthTestClient(mockMvc, objectMapper);
        MvcResult list = anonymous.get(listPath(workId));
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(body(list), "$.success")).isTrue();

        assertError(anonymous.get(path(workId)), 401, "UNAUTHORIZED");
        assertError(anonymous.postRaw(path(workId), create(BODY, false)), 401, "UNAUTHORIZED");
        assertError(anonymous.putRaw(path(workId), update(1, 0, BODY, false)), 401, "UNAUTHORIZED");
        assertError(anonymous.delete(path(workId) + "?reviewId=1&version=0"), 401, "UNAUTHORIZED");
        // works/** 공개 규칙은 GET 만이다
        assertError(anonymous.postRaw(listPath(workId), create(BODY, false)), 401, "UNAUTHORIZED");
        assertThat(reviewCount()).isZero();
    }

    @Test
    void publicListNeedsNoCsrfAndUnknownSubPathIsNotFound() throws Exception {
        MvcResult list = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(listPath(workId))).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(list.getResponse().getCookies()).noneMatch(cookie -> cookie.getName().contains("SESSION"));
    }

    @Test
    void missingCsrfIsForbiddenForPostPutDelete() throws Exception {
        AuthTestClient user = signedUpUser("독자");
        Map<String, Object> review = review(user.postRaw(path(workId), create(BODY, false)));

        assertError(user.performWithSessionOnly(post(path(workId))
                .contentType(MediaType.APPLICATION_JSON).content(create(BODY2, false))), 403, "INVALID_CSRF_TOKEN");
        assertError(user.performWithSessionOnly(put(path(workId))
                .contentType(MediaType.APPLICATION_JSON).content(update(id(review), 0, BODY2, true))),
                403, "INVALID_CSRF_TOKEN");
        assertError(user.performWithSessionOnly(delete(path(workId) + "?reviewId=" + id(review) + "&version=0")
                .cookie(user.csrfCookie()).header(AuthTestClient.CSRF_HEADER, "wrong-token")), 403, "INVALID_CSRF_TOKEN");

        assertThat(storedBodies()).containsExactly(BODY);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM work_reviews", Long.class)).isZero();
    }

    // ---------- 작성·수정·삭제 흐름 ----------

    @Test
    void fullLifecycleWithVersionAndTimestampPolicy() throws Exception {
        AuthTestClient user = signedUpUser("독자");

        // 미작성: 200, review null
        MvcResult none = user.get(path(workId));
        assertThat(none.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(none)).contains("\"review\":null");
        assertThat(((Number) JsonPath.read(body(none), "$.data.workId")).longValue()).isEqualTo(workId);

        // 작성: 201 + Location, version 0, created = updated, edited false
        MvcResult created = user.postRaw(path(workId), create("  " + BODY + "\r\n", true));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/me/reviews/" + workId);
        Map<String, Object> first = review(created);
        long reviewId = id(first);
        assertThat(first.get("body")).isEqualTo(BODY);
        assertThat(first.get("containsSpoiler")).isEqualTo(true);
        assertThat(version(first)).isZero();
        assertThat(first.get("edited")).isEqualTo(false);
        Instant createdAt = Instant.parse((String) first.get("createdAt"));
        assertThat(first.get("updatedAt")).isEqualTo(first.get("createdAt"));
        assertThat((String) first.get("createdAt")).endsWith("Z");
        assertThat(review(user.get(path(workId)))).isEqualTo(first);

        // 같은 내용으로 수정: 200, 버전·시각 유지
        MvcResult same = user.putRaw(path(workId), update(reviewId, 0, BODY, true));
        assertThat(same.getResponse().getStatus()).isEqualTo(200);
        assertThat(review(same)).isEqualTo(first);

        // 내용 변경: version 1, created 유지, updated 증가, edited true
        MvcResult changed = user.putRaw(path(workId), update(reviewId, 0, BODY2, false));
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> second = review(changed);
        assertThat(id(second)).isEqualTo(reviewId);
        assertThat(version(second)).isEqualTo(1);
        assertThat(second.get("body")).isEqualTo(BODY2);
        assertThat(second.get("containsSpoiler")).isEqualTo(false);
        assertThat(second.get("createdAt")).isEqualTo(first.get("createdAt"));
        assertThat(Instant.parse((String) second.get("updatedAt"))).isAfter(createdAt);
        assertThat(second.get("edited")).isEqualTo(true);

        // 스포일러 여부만 바꿔도 변경이다
        Map<String, Object> third = review(user.putRaw(path(workId), update(reviewId, 1, BODY2, true)));
        assertThat(version(third)).isEqualTo(2);

        // 오래된 버전: 409, 변경 없음
        assertError(user.putRaw(path(workId), update(reviewId, 1, "오래된 화면에서 고친 내용입니다.", false)),
                409, "REVIEW_VERSION_CONFLICT");
        assertError(user.delete(path(workId) + "?reviewId=" + reviewId + "&version=1"), 409, "REVIEW_VERSION_CONFLICT");
        assertThat(review(user.get(path(workId)))).isEqualTo(third);

        // 삭제: 204, 본문 없음
        MvcResult deleted = user.delete(path(workId) + "?reviewId=" + reviewId + "&version=2");
        assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
        assertThat(deleted.getResponse().getContentAsString()).isEmpty();
        assertThat(body(user.get(path(workId)))).contains("\"review\":null");

        // 이미 없음: DELETE 204, PUT 404
        assertThat(user.delete(path(workId) + "?reviewId=" + reviewId + "&version=2").getResponse().getStatus())
                .isEqualTo(204);
        assertError(user.putRaw(path(workId), update(reviewId, 2, BODY, false)), 404, "REVIEW_NOT_FOUND");

        // 다시 작성: 새 id, version 0, 새 created_at
        Map<String, Object> recreated = review(user.postRaw(path(workId), create(BODY, false)));
        assertThat(id(recreated)).isNotEqualTo(reviewId);
        assertThat(version(recreated)).isZero();
        assertThat(Instant.parse((String) recreated.get("createdAt"))).isAfter(createdAt);
        assertThat(reviewCount()).isEqualTo(1);
    }

    @Test
    void duplicatePostIsConflictAndKeepsExistingReview() throws Exception {
        AuthTestClient user = signedUpUser("독자");
        Map<String, Object> original = review(user.postRaw(path(workId), create(BODY, false)));

        assertError(user.postRaw(path(workId), create(BODY2, true)), 409, "REVIEW_ALREADY_EXISTS");
        assertThat(review(user.get(path(workId)))).isEqualTo(original);
        assertThat(reviewCount()).isEqualTo(1);
    }

    @Test
    void staleRequestsAgainstRecreatedReviewAreConflictsEvenWithSameVersion() throws Exception {
        AuthTestClient user = signedUpUser("독자");
        long oldId = id(review(user.postRaw(path(workId), create(BODY, false))));
        user.delete(path(workId) + "?reviewId=" + oldId + "&version=0");
        Map<String, Object> current = review(user.postRaw(path(workId), create(BODY2, true)));
        assertThat(version(current)).isZero(); // 새 리뷰도 version 0 부터 시작

        assertError(user.putRaw(path(workId), update(oldId, 0, "오래된 화면의 수정 내용입니다.", false)),
                409, "REVIEW_VERSION_CONFLICT");
        assertError(user.delete(path(workId) + "?reviewId=" + oldId + "&version=0"), 409, "REVIEW_VERSION_CONFLICT");
        assertThat(review(user.get(path(workId)))).isEqualTo(current);
    }

    @Test
    void storesNormalizedBodyAndKeepsHtmlAsText() throws Exception {
        AuthTestClient user = signedUpUser("독자");
        String html = "<script>alert('x')</script> &amp; <b>굵게</b>";
        Map<String, Object> saved = review(user.postRaw(path(workId), create("\r\n" + html + "\r\n\r\n둘째 줄\r끝 \u3000", false)));
        assertThat(saved.get("body")).isEqualTo(html + "\n\n둘째 줄\n끝");
        assertThat(storedBodies()).containsExactly(html + "\n\n둘째 줄\n끝");

        String list = body(new AuthTestClient(mockMvc, objectMapper).get(listPath(workId)));
        assertThat((String) JsonPath.read(list, "$.data.content[0].body")).isEqualTo(html + "\n\n둘째 줄\n끝");
    }

    @Test
    void emojiBodyIsCountedInCodePoints() throws Exception {
        AuthTestClient user = signedUpUser("독자");
        String emoji = "\uD83D\uDE00";
        assertError(user.postRaw(path(workId), create(emoji.repeat(9), false)), 400, "INVALID_INPUT");
        assertThat(user.postRaw(path(workId), create(emoji.repeat(10), false)).getResponse().getStatus()).isEqualTo(201);
        long id = id(review(user.get(path(workId))));
        assertThat(user.putRaw(path(workId), update(id, 0, emoji.repeat(2000), false)).getResponse().getStatus())
                .isEqualTo(200);
        assertError(user.putRaw(path(workId), update(id, 1, emoji.repeat(2001), false)), 400, "INVALID_INPUT");
    }

    // ---------- 입력 검증 ----------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"containsSpoiler\":false}|body|리뷰 내용을 입력해 주세요.",
            "{\"body\":null,\"containsSpoiler\":false}|body|리뷰 내용을 입력해 주세요.",
            "{\"body\":\"   \\t\\n \",\"containsSpoiler\":false}|body|리뷰 내용을 입력해 주세요.",
            "{\"body\":1234567890,\"containsSpoiler\":false}|body|리뷰 내용은 문자열이어야 합니다.",
            "{\"body\":[\"가나다라마바사아자차\"],\"containsSpoiler\":false}|body|리뷰 내용은 문자열이어야 합니다.",
            "{\"body\":\"가나다라마바사아자\",\"containsSpoiler\":false}|body|리뷰는 10자 이상 2,000자 이하로 입력해 주세요.",
            "{\"body\":\"가나다라마\\u0000바사아자차\",\"containsSpoiler\":false}|body|사용할 수 없는 문자가 포함되어 있습니다.",
            "{\"body\":\"가나다라마\\u001b바사아자차\",\"containsSpoiler\":false}|body|사용할 수 없는 문자가 포함되어 있습니다.",
            "{\"body\":\"\\u000b가나다라마바사아자차\",\"containsSpoiler\":false}|body|사용할 수 없는 문자가 포함되어 있습니다.",
            "{\"body\":\"가나다라마바사아자차\"}|containsSpoiler|스포일러 여부를 선택해 주세요.",
            "{\"body\":\"가나다라마바사아자차\",\"containsSpoiler\":null}|containsSpoiler|스포일러 여부를 선택해 주세요.",
            "{\"body\":\"가나다라마바사아자차\",\"containsSpoiler\":\"true\"}|containsSpoiler|스포일러 여부는 true 또는 false여야 합니다.",
            "{\"body\":\"가나다라마바사아자차\",\"containsSpoiler\":1}|containsSpoiler|스포일러 여부는 true 또는 false여야 합니다."})
    void rejectsInvalidCreateRequests(String raw, String field, String reason) throws Exception {
        MvcResult result = signedUpUser("독자").postRaw(path(workId), raw);
        assertError(result, 400, "INVALID_INPUT");
        assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].field")).isEqualTo(field);
        assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].reason")).isEqualTo(reason);
        assertThat(reviewCount()).isZero();
    }

    @Test
    void tooLongBodyIsRejected() throws Exception {
        AuthTestClient user = signedUpUser("독자");
        assertError(user.postRaw(path(workId), create("a".repeat(2001), false)), 400, "INVALID_INPUT");
        assertError(user.postRaw(path(workId), create("a".repeat(20_000), false)), 400, "INVALID_INPUT");
        assertThat(user.postRaw(path(workId), create("a".repeat(2000), false)).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void collectsAllFieldErrors() throws Exception {
        AuthTestClient user = signedUpUser("독자");
        MvcResult create = user.postRaw(path(workId), "{}");
        assertThat((List<String>) JsonPath.read(body(create), "$.error.fieldErrors[*].field"))
                .containsExactly("body", "containsSpoiler");

        MvcResult update = user.putRaw(path(workId), "{\"reviewId\":\"7\",\"version\":-1}");
        assertThat((List<String>) JsonPath.read(body(update), "$.error.fieldErrors[*].field"))
                .containsExactly("body", "containsSpoiler", "reviewId", "version");
    }

    @Test
    void emptyOrNullBodyIsFieldErrorAndBrokenJsonIsUnreadable() throws Exception {
        AuthTestClient user = signedUpUser("독자");
        for (String raw : new String[] {"", "null"}) {
            MvcResult result = user.postRaw(path(workId), raw);
            assertError(result, 400, "INVALID_INPUT");
            assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].field")).isEqualTo("body");
        }
        MvcResult broken = user.postRaw(path(workId), "{\"body\":");
        assertError(broken, 400, "INVALID_INPUT");
        assertThat((String) JsonPath.read(body(broken), "$.error.message")).isEqualTo("요청 본문을 읽을 수 없습니다.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"reviewId\":\"1\",\"version\":0|reviewId|리뷰 ID는 1 이상의 정수여야 합니다.",
            "\"reviewId\":1.0,\"version\":0|reviewId|리뷰 ID는 1 이상의 정수여야 합니다.",
            "\"reviewId\":0,\"version\":0|reviewId|리뷰 ID는 1 이상의 정수여야 합니다.",
            "\"reviewId\":9223372036854775808,\"version\":0|reviewId|리뷰 ID는 1 이상의 정수여야 합니다.",
            "\"version\":0|reviewId|리뷰 ID가 필요합니다.",
            "\"reviewId\":null,\"version\":0|reviewId|리뷰 ID가 필요합니다.",
            "\"reviewId\":1,\"version\":\"0\"|version|리뷰 버전은 0 이상의 정수여야 합니다.",
            "\"reviewId\":1,\"version\":-1|version|리뷰 버전은 0 이상의 정수여야 합니다.",
            "\"reviewId\":1,\"version\":1e0|version|리뷰 버전은 0 이상의 정수여야 합니다.",
            "\"reviewId\":1|version|리뷰 버전이 필요합니다."})
    void rejectsInvalidReviewIdOrVersionOnPut(String fields, String field, String reason) throws Exception {
        AuthTestClient user = signedUpUser("독자");
        user.postRaw(path(workId), create(BODY, false));
        MvcResult result = user.putRaw(path(workId),
                "{" + fields + ",\"body\":\"" + BODY2 + "\",\"containsSpoiler\":false}");
        assertError(result, 400, "INVALID_INPUT");
        assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].field")).isEqualTo(field);
        assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].reason")).isEqualTo(reason);
        assertThat(storedBodies()).containsExactly(BODY);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "version=0|reviewId",
            "reviewId=1|version",
            "reviewId=&version=0|reviewId",
            "reviewId=abc&version=0|reviewId",
            "reviewId=1.5&version=0|reviewId",
            "reviewId=9223372036854775808&version=0|reviewId",
            "reviewId=0&version=0|reviewId",
            "reviewId=1&version=-1|version"})
    void rejectsInvalidDeleteQuery(String query, String field) throws Exception {
        AuthTestClient user = signedUpUser("독자");
        user.postRaw(path(workId), create(BODY, false));
        MvcResult result = user.delete(path(workId) + "?" + query);
        assertError(result, 400, "INVALID_INPUT");
        assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].field")).isEqualTo(field);
        assertThat(reviewCount()).isEqualTo(1);
    }

    @Test
    void deleteIgnoresRequestBody() throws Exception {
        AuthTestClient user = signedUpUser("독자");
        long id = id(review(user.postRaw(path(workId), create(BODY, false))));
        MvcResult result = user.performWithSessionOnly(delete(path(workId) + "?reviewId=" + id + "&version=0")
                .cookie(user.csrfCookie()).header(AuthTestClient.CSRF_HEADER, user.csrfCookie().getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reviewId\":999,\"version\":999}"));
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(reviewCount()).isZero();
    }

    @Test
    void inputIsValidatedBeforeWorkExistence() throws Exception {
        AuthTestClient user = signedUpUser("독자");
        assertError(user.postRaw(path(999_999), "{\"body\":1}"), 400, "INVALID_INPUT");
        assertError(user.putRaw(path(999_999), "{}"), 400, "INVALID_INPUT");
        assertError(user.delete(path(999_999) + "?reviewId=0&version=0"), 400, "INVALID_INPUT");
    }

    // ---------- 404·잘못된 workId ----------

    @ParameterizedTest
    @ValueSource(longs = {999_999L, 0L, -1L})
    void missingWorkIsNotFoundForEveryReviewApi(long missingWorkId) throws Exception {
        AuthTestClient user = signedUpUser("독자");
        assertError(new AuthTestClient(mockMvc, objectMapper).get(listPath(missingWorkId)), 404, "WORK_NOT_FOUND");
        assertError(user.get(path(missingWorkId)), 404, "WORK_NOT_FOUND");
        assertError(user.postRaw(path(missingWorkId), create(BODY, false)), 404, "WORK_NOT_FOUND");
        assertError(user.putRaw(path(missingWorkId), update(1, 0, BODY, false)), 404, "WORK_NOT_FOUND");
        assertError(user.delete(path(missingWorkId) + "?reviewId=1&version=0"), 404, "WORK_NOT_FOUND");
        assertThat(reviewCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999"})
    void nonLongWorkIdIsBadRequest(String rawWorkId) throws Exception {
        AuthTestClient user = signedUpUser("독자");
        for (MvcResult result : List.of(
                user.get("/api/v1/me/reviews/" + rawWorkId),
                new AuthTestClient(mockMvc, objectMapper).get("/api/v1/works/" + rawWorkId + "/reviews"))) {
            assertError(result, 400, "INVALID_INPUT");
            assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].field")).isEqualTo("workId");
        }
    }

    // ---------- 사용자 식별·격리 ----------

    @Test
    void userIdInBodyIsIgnored() throws Exception {
        AuthTestClient userA = signedUpUser("가독자");
        AuthTestClient userB = signedUpUser("나독자");
        long userBId = currentUserId(userB);

        MvcResult result = userA.postRaw(path(workId),
                "{\"body\":\"" + BODY + "\",\"containsSpoiler\":false,\"userId\":" + userBId + "}");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(userB.get(path(workId)))).contains("\"review\":null");
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM work_reviews", Long.class))
                .containsExactly(currentUserId(userA));
    }

    @Test
    void userAndAdminChangeOnlyTheirOwnReviewEvenWithAnotherUsersReviewId() throws Exception {
        AuthTestClient user = signedUpUser("독자");
        AuthTestClient other = signedUpUser("다른독자");
        Map<String, Object> userReview = review(user.postRaw(path(workId), create(BODY, false)));
        Map<String, Object> adminReview = review(admin.postRaw(path(workId), create(BODY2, true)));
        long userReviewId = id(userReview);

        // 리뷰가 없는 다른 사용자가 남의 reviewId 로 요청: PUT 404, DELETE 204, 원래 리뷰는 그대로
        assertError(other.putRaw(path(workId), update(userReviewId, 0, "남의 리뷰를 바꾸려는 시도입니다.", false)),
                404, "REVIEW_NOT_FOUND");
        assertThat(other.delete(path(workId) + "?reviewId=" + userReviewId + "&version=0").getResponse().getStatus())
                .isEqualTo(204);

        // 리뷰가 있는 ADMIN 이 USER 의 reviewId 로 요청: 자기 리뷰와 비교되어 409, 아무것도 바뀌지 않음
        assertError(admin.putRaw(path(workId), update(userReviewId, 0, "관리자가 남의 리뷰를 바꾸려는 시도", false)),
                409, "REVIEW_VERSION_CONFLICT");
        assertError(admin.delete(path(workId) + "?reviewId=" + userReviewId + "&version=0"),
                409, "REVIEW_VERSION_CONFLICT");

        assertThat(review(user.get(path(workId)))).isEqualTo(userReview);
        assertThat(review(admin.get(path(workId)))).isEqualTo(adminReview);

        // ADMIN 은 자기 리뷰를 지울 수 있고 USER 리뷰에는 영향이 없다
        assertThat(admin.delete(path(workId) + "?reviewId=" + id(adminReview) + "&version=0").getResponse().getStatus())
                .isEqualTo(204);
        assertThat(review(user.get(path(workId)))).isEqualTo(userReview);
        assertThat(reviewCount()).isEqualTo(1);
    }

    // ---------- 공개 목록 ----------

    @Test
    void publicListOrdersByCreatedAtDescThenIdDescAndIgnoresEdits() throws Exception {
        long u1 = insertUser("일번");
        long u2 = insertUser("이번");
        long u3 = insertUser("삼번");
        long u4 = insertUser("사번");
        long r1 = insertReview(u1, workId, "2026-10-01T00:00:00Z", false);
        long r2 = insertReview(u2, workId, "2026-10-03T00:00:00Z", false);
        long r3 = insertReview(u3, workId, "2026-10-02T00:00:00Z", true);
        long r4 = insertReview(u4, workId, "2026-10-03T00:00:00Z", false); // r2 와 같은 시각 → id 보조 정렬

        assertThat(listIds(listPath(workId))).containsExactly(r4, r2, r3, r1);

        // 가장 오래된 리뷰를 수정해도 순서는 그대로다
        jdbcTemplate.update("UPDATE work_reviews SET body = ?, version = 1, updated_at = now() WHERE id = ?", BODY2, r1);
        assertThat(listIds(listPath(workId))).containsExactly(r4, r2, r3, r1);

        String page = body(new AuthTestClient(mockMvc, objectMapper).get(listPath(workId) + "?size=2&page=1"));
        assertThat(listIds(listPath(workId) + "?size=2&page=1")).containsExactly(r3, r1);
        assertThat(((Number) JsonPath.read(page, "$.data.totalElements")).longValue()).isEqualTo(4);
        assertThat((Integer) JsonPath.read(page, "$.data.totalPages")).isEqualTo(2);
        assertThat((Boolean) JsonPath.read(page, "$.data.hasNext")).isFalse();

        // sort 파라미터는 받지 않는다(무시)
        assertThat(listIds(listPath(workId) + "?sort=createdAt,asc")).containsExactly(r4, r2, r3, r1);
    }

    @Test
    void publicListPageSizeDefaultsToTenAndIsLimitedToFifty() throws Exception {
        for (int i = 0; i < 12; i++) {
            insertReview(insertUser("독자" + i), workId, "2026-10-01T00:00:" + String.format("%02d", i) + "Z", false);
        }
        String first = body(new AuthTestClient(mockMvc, objectMapper).get(listPath(workId)));
        assertThat((Integer) JsonPath.read(first, "$.data.size")).isEqualTo(10);
        assertThat((List<?>) JsonPath.read(first, "$.data.content")).hasSize(10);
        assertThat((Integer) JsonPath.read(first, "$.data.page")).isZero();
        assertThat(((Number) JsonPath.read(first, "$.data.totalElements")).longValue()).isEqualTo(12);

        assertThat(listIds(listPath(workId) + "?size=50")).hasSize(12);
        assertThat((List<?>) JsonPath.read(body(new AuthTestClient(mockMvc, objectMapper)
                .get(listPath(workId) + "?page=5")), "$.data.content")).isEmpty();

        AuthTestClient anonymous = new AuthTestClient(mockMvc, objectMapper);
        assertFieldError(anonymous.get(listPath(workId) + "?size=51"), "size");
        assertFieldError(anonymous.get(listPath(workId) + "?size=0"), "size");
        assertFieldError(anonymous.get(listPath(workId) + "?page=-1"), "page");
        assertFieldError(anonymous.get(listPath(workId) + "?page=2147483647&size=50"), "page");
        assertFieldError(anonymous.get(listPath(workId) + "?page=abc"), "page");
    }

    @Test
    void publicListExposesNicknameAndSpoilerBodyButNoPrivateFields() throws Exception {
        AuthTestClient writer = signedUpUser("스포일러작성자");
        writer.postRaw(path(workId), create(BODY, true));

        AuthTestClient anonymous = new AuthTestClient(mockMvc, objectMapper);
        String list = body(anonymous.get(listPath(workId)));
        Map<String, Object> item = JsonPath.read(list, "$.data.content[0]");
        assertThat(item).containsOnlyKeys("id", "author", "body", "containsSpoiler", "createdAt", "updatedAt", "edited");
        assertThat(item.get("author")).isEqualTo(Map.of("nickname", "스포일러작성자"));
        // 스포일러 리뷰도 공개 API 는 본문을 포함한다 (숨김은 화면 표시 정책)
        assertThat(item.get("containsSpoiler")).isEqualTo(true);
        assertThat(item.get("body")).isEqualTo(BODY);
        assertThat(list).doesNotContain("@manhwanet.test").doesNotContain("\"version\"").doesNotContain("userId");

        // 로그인 여부와 관계없이 같은 응답
        assertThat(body(writer.get(listPath(workId)))).isEqualTo(list);
        assertThat(body(admin.get(listPath(workId)))).isEqualTo(list);
    }

    @Test
    void publicListShowsCurrentNicknameAndSeparatesWorks() throws Exception {
        long otherWork = createWork("별빛 정원");
        long u = insertUser("예전닉네임");
        insertReview(u, workId, "2026-10-01T00:00:00Z", false);
        jdbcTemplate.update("UPDATE users SET nickname = '새닉네임' WHERE id = ?", u);

        String list = body(new AuthTestClient(mockMvc, objectMapper).get(listPath(workId)));
        assertThat((String) JsonPath.read(list, "$.data.content[0].author.nickname")).isEqualTo("새닉네임");
        assertThat((List<?>) JsonPath.read(body(new AuthTestClient(mockMvc, objectMapper)
                .get(listPath(otherWork))), "$.data.content")).isEmpty();
    }

    // ---------- 큰 ID·version ----------

    @Test
    void largeIdsAndVersionStayExactInResponsesAndRequests() throws Exception {
        jdbcTemplate.execute("ALTER TABLE works ALTER COLUMN id RESTART WITH 9007199254740993");
        jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN id RESTART WITH 9007199254740995");
        jdbcTemplate.execute("ALTER TABLE work_reviews ALTER COLUMN id RESTART WITH 9007199254740997");
        long largeWorkId = createWork("큰 번호 작품");
        assertThat(largeWorkId).isEqualTo(9007199254740993L);
        AuthTestClient user = signedUpUser("큰번호독자");
        assertThat(currentUserId(user)).isEqualTo(9007199254740995L);

        String created = body(user.postRaw("/api/v1/me/reviews/9007199254740993", create(BODY, false)));
        assertThat(created).contains("\"workId\":9007199254740993").contains("\"id\":9007199254740997");
        jdbcTemplate.update("UPDATE work_reviews SET version = 9007199254740993");
        assertThat(body(user.get("/api/v1/me/reviews/9007199254740993"))).contains("\"version\":9007199254740993");

        // 2^53 과 2^53+1 은 double 로 같은 값이 된다. 요청 원문의 정확한 값으로 비교하는지 확인한다
        assertError(user.putRaw("/api/v1/me/reviews/9007199254740993",
                update(9007199254740996L, 9007199254740993L, BODY2, false)), 409, "REVIEW_VERSION_CONFLICT");
        assertError(user.putRaw("/api/v1/me/reviews/9007199254740993",
                update(9007199254740997L, 9007199254740992L, BODY2, false)), 409, "REVIEW_VERSION_CONFLICT");
        String updated = body(user.putRaw("/api/v1/me/reviews/9007199254740993",
                update(9007199254740997L, 9007199254740993L, BODY2, false)));
        assertThat(updated).contains("\"version\":9007199254740994").contains("\"id\":9007199254740997");

        String list = body(new AuthTestClient(mockMvc, objectMapper).get("/api/v1/works/9007199254740993/reviews"));
        assertThat(list).contains("\"id\":9007199254740997");
        assertError(new AuthTestClient(mockMvc, objectMapper).get("/api/v1/works/9007199254740992/reviews"),
                404, "WORK_NOT_FOUND");

        assertError(user.delete("/api/v1/me/reviews/9007199254740993?reviewId=9007199254740997&version=9007199254740993"),
                409, "REVIEW_VERSION_CONFLICT");
        assertThat(user.delete("/api/v1/me/reviews/9007199254740993?reviewId=9007199254740997&version=9007199254740994")
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(reviewCount()).isZero();
    }

    // ---------- helpers ----------

    private String create(String reviewBody, boolean spoiler) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("body", reviewBody);
        map.put("containsSpoiler", spoiler);
        return objectMapper.writeValueAsString(map);
    }

    /** reviewId·version 은 long 원문 그대로 JSON 숫자로 쓴다 */
    private String update(long reviewId, long version, String reviewBody, boolean spoiler) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reviewId", reviewId);
        map.put("version", version);
        map.put("body", reviewBody);
        map.put("containsSpoiler", spoiler);
        return objectMapper.writeValueAsString(map);
    }

    private Map<String, Object> review(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isIn(200, 201);
        return JsonPath.read(body(result), "$.data.review");
    }

    private static long id(Map<String, Object> review) {
        return ((Number) review.get("id")).longValue();
    }

    private static long version(Map<String, Object> review) {
        return ((Number) review.get("version")).longValue();
    }

    private List<Long> listIds(String listPath) throws Exception {
        List<Number> ids = JsonPath.read(body(new AuthTestClient(mockMvc, objectMapper).get(listPath)),
                "$.data.content[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private long insertUser(String nickname) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role)
                VALUES (?, '{noop}unused', ?, 'USER') RETURNING id
                """, Long.class, "u-" + UUID.randomUUID() + "@manhwanet.test", nickname);
    }

    private long insertReview(long userId, long targetWorkId, String createdAt, boolean spoiler) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz) RETURNING id
                """, Long.class, userId, targetWorkId, BODY, spoiler, createdAt, createdAt);
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

    private AuthTestClient signedUpUser(String nickname) throws Exception {
        AuthTestClient client = new AuthTestClient(mockMvc, objectMapper);
        MvcResult result = client.signup("reader-" + UUID.randomUUID() + "@manhwanet.test", STRONG_PASSWORD, nickname);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return client;
    }

    private long currentUserId(AuthTestClient client) throws Exception {
        return ((Number) JsonPath.read(body(client.get("/api/v1/auth/me")), "$.data.id")).longValue();
    }

    private static String path(long id) {
        return "/api/v1/me/reviews/" + id;
    }

    private static String listPath(long id) {
        return "/api/v1/works/" + id + "/reviews";
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat((String) JsonPath.read(body(result), "$.error.code")).isEqualTo(code);
        assertThat((Boolean) JsonPath.read(body(result), "$.success")).isFalse();
    }

    private static void assertFieldError(MvcResult result, String field) throws Exception {
        assertError(result, 400, "INVALID_INPUT");
        assertThat((String) JsonPath.read(body(result), "$.error.fieldErrors[0].field")).isEqualTo(field);
    }

    private int reviewCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_reviews", Integer.class);
    }

    private List<String> storedBodies() {
        return jdbcTemplate.queryForList("SELECT body FROM work_reviews", String.class);
    }
}
