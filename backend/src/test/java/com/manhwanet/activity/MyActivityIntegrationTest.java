package com.manhwanet.activity;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.dto.CreateCreatorRequest;
import com.manhwanet.provenance.dto.EvidenceRequest;
import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.ChangeVisibilityRequest;
import com.manhwanet.work.dto.CreateWorkRequest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
 * 내 활동 API: GET /api/v1/me/ratings, /me/reviews (목록), /me/activity (요약).
 * 본인 기록만(세션 사용자), 정렬·동률·페이지, 숨김 작품 기록 보존과 작품 정보 비노출, 본문 앞부분, 예상 사용자 확인,
 * 큰 ID, 캐시 금지, 비로그인 401 을 격리 테스트 DB 에서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyActivityIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Environment environment;
    @Autowired
    private AdminAccountService adminAccountService;
    @Autowired
    private WorkService workService;
    @Autowired
    private CreatorService creatorService;

    private long adminId;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbc, environment);
        adminAccountService.createAdmin("admin@manhwanet.test", STRONG_PASSWORD, "관리자");
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'admin@manhwanet.test'", Long.class);
    }

    @Test
    void ratingsAreOnlyMineOrderedByLastSavedTimeThenIdAndStableAcrossPages() throws Exception {
        AuthTestClient alice = signup("alice");
        AuthTestClient bob = signup("bob");
        long aliceId = userId("alice"), bobId = userId("bob");
        long w1 = work("첫째 작품", "가상작가 하나");
        long w2 = work("둘째 작품", "가상작가 둘");
        long w3 = work("셋째 작품", "가상작가 셋");
        long w4 = work("넷째 작품", "가상작가 넷");
        long r1 = rate(aliceId, w1, 8, BASE.plusSeconds(10));
        long r2 = rate(aliceId, w2, 6, BASE.plusSeconds(30));
        long r3 = rate(aliceId, w3, 10, BASE.plusSeconds(30)); // r2 와 같은 시각 → id DESC
        rate(bobId, w4, 2, BASE.plusSeconds(99));

        String body = body(alice.get("/api/v1/me/ratings"));
        assertThat(workIds(body)).containsExactly(w3, w2, w1);
        assertThat(((Number) JsonPath.read(body, "$.data.totalElements")).intValue()).isEqualTo(3);
        assertThat((Object) JsonPath.read(body, "$.data.content[0].score")).hasToString("5.0");
        assertThat((Boolean) JsonPath.read(body, "$.data.content[0].available")).isTrue();
        assertThat((String) JsonPath.read(body, "$.data.content[0].work.primaryTitle.title")).isEqualTo("셋째 작품");
        assertThat((String) JsonPath.read(body, "$.data.content[0].work.creators[0].name")).isEqualTo("가상작가 셋");
        Map<String, Object> item = JsonPath.read(body, "$.data.content[0]");
        assertThat(item.keySet()).containsExactlyInAnyOrder("workId", "available", "work", "score", "createdAt", "updatedAt");
        assertThat(body).doesNotContain("넷째 작품").doesNotContain("@manhwanet.test").doesNotContain("userId");
        // 페이지를 나눠도 같은 순서
        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            paged.addAll(workIds(body(alice.get("/api/v1/me/ratings?size=1&page=" + page))));
        }
        assertThat(paged).containsExactly(w3, w2, w1);
        assertThat(r3).isGreaterThan(r2).isGreaterThan(r1);
        // 다른 사용자는 자기 것만
        assertThat(workIds(body(bob.get("/api/v1/me/ratings")))).containsExactly(w4);
        // 점수를 바꾸면(PUT, 기존 API) 맨 앞으로
        alice.withHeader("X-Expected-User-Id", String.valueOf(aliceId));
        assertThat(alice.putRaw("/api/v1/me/ratings/" + w1, "{\"score\":3.5}").getResponse().getStatus()).isEqualTo(200);
        assertThat(workIds(body(alice.get("/api/v1/me/ratings")))).containsExactly(w1, w3, w2);
    }

    @Test
    void reviewsShowExcerptSpoilerFlagAndTimesOrderedByCreationThenId() throws Exception {
        AuthTestClient alice = signup("alice");
        long aliceId = userId("alice");
        long w1 = work("리뷰 작품 하나", "가상작가 하나");
        long w2 = work("리뷰 작품 둘", "가상작가 둘");
        long w3 = work("리뷰 작품 셋", "가상작가 셋");
        String longBody = "😀".repeat(10) + "가".repeat(200); // 210 코드 포인트(이모지는 서로게이트 쌍)
        review(aliceId, w1, "짧은 리뷰 본문입니다. 열 글자 넘게.", false, BASE.plusSeconds(10), BASE.plusSeconds(500));
        review(aliceId, w2, longBody, true, BASE.plusSeconds(20), BASE.plusSeconds(20));
        review(aliceId, w3, "같은 시각에 쓴 리뷰입니다. 열 글자 넘게.", false, BASE.plusSeconds(20), BASE.plusSeconds(20));

        String body = body(alice.get("/api/v1/me/reviews"));
        // 작성 시각 DESC → id DESC (수정해도 순서 유지)
        assertThat(workIds(body)).containsExactly(w3, w2, w1);
        Map<String, Object> spoiler = JsonPath.<List<Map<String, Object>>>read(body, "$.data.content[?(@.workId == " + w2 + ")]").get(0);
        String excerpt = (String) spoiler.get("excerpt");
        assertThat(excerpt.codePointCount(0, excerpt.length())).isEqualTo(160);
        assertThat(excerpt).startsWith("😀".repeat(10)).doesNotContain("\uFFFD");
        assertThat(spoiler.get("excerptTruncated")).isEqualTo(true);
        assertThat(spoiler.get("containsSpoiler")).isEqualTo(true);
        Map<String, Object> edited = JsonPath.<List<Map<String, Object>>>read(body, "$.data.content[?(@.workId == " + w1 + ")]").get(0);
        assertThat(edited.get("edited")).isEqualTo(true);
        assertThat(edited.get("excerptTruncated")).isEqualTo(false);
        assertThat(edited.keySet()).containsExactlyInAnyOrder("id", "workId", "available", "work", "excerpt", "excerptTruncated",
                "containsSpoiler", "createdAt", "updatedAt", "edited");
        // version·전체 본문은 목록에 없다(수정은 상세의 내 리뷰 API)
        assertThat(body).doesNotContain("\"version\"").doesNotContain("\"body\"");
        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            paged.addAll(workIds(body(alice.get("/api/v1/me/reviews?size=1&page=" + page))));
        }
        assertThat(paged).containsExactly(w3, w2, w1);
    }

    @Test
    void hiddenWorkRecordsArePreservedButTheirWorkInfoIsNotExposed() throws Exception {
        AuthTestClient alice = signup("alice");
        long aliceId = userId("alice");
        long visible = work("공개 작품", "공개 작가");
        long hidden = work("숨김 비밀 제목", "숨김 비밀 작가");
        rate(aliceId, visible, 8, BASE.plusSeconds(1));
        rate(aliceId, hidden, 9, BASE.plusSeconds(2));
        review(aliceId, hidden, "숨김 작품에 남긴 리뷰입니다. 열 글자 넘게.", false, BASE, BASE);
        workService.changeVisibility(hidden, new ChangeVisibilityRequest(new JsonInteger(0L), "HIDDEN", "숨김 확인"), adminId);

        String ratings = body(alice.get("/api/v1/me/ratings"));
        assertThat(workIds(ratings)).containsExactly(hidden, visible);
        assertThat((Boolean) JsonPath.read(ratings, "$.data.content[0].available")).isFalse();
        assertThat((Object) JsonPath.read(ratings, "$.data.content[0].work")).isNull();
        assertThat((Object) JsonPath.read(ratings, "$.data.content[0].score")).hasToString("4.5");
        assertThat(ratings).doesNotContain("숨김 비밀");
        String reviews = body(alice.get("/api/v1/me/reviews"));
        assertThat((Boolean) JsonPath.read(reviews, "$.data.content[0].available")).isFalse();
        assertThat(reviews).doesNotContain("숨김 비밀").contains("숨김 작품에 남긴 리뷰");
        String summary = body(alice.get("/api/v1/me/activity"));
        assertThat(((Number) JsonPath.read(summary, "$.data.ratingCount")).intValue()).isEqualTo(2);
        assertThat(((Number) JsonPath.read(summary, "$.data.reviewCount")).intValue()).isEqualTo(1);
        // 공개 API 로는 여전히 없음
        assertThat(mockMvc.perform(get("/api/v1/works/" + hidden)).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void requiresLoginValidatesPagingChecksExpectedUserAndIsNotCacheable() throws Exception {
        for (String path : List.of("/api/v1/me/ratings", "/api/v1/me/reviews", "/api/v1/me/activity")) {
            assertThat(mockMvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(401);
        }
        AuthTestClient alice = signup("alice");
        long aliceId = userId("alice");
        for (String path : List.of("/api/v1/me/ratings", "/api/v1/me/reviews")) {
            assertThat(fields(alice.get(path + "?size=51"))).contains("size");
            assertThat(fields(alice.get(path + "?size=0"))).contains("size");
            assertThat(fields(alice.get(path + "?page=-1"))).contains("page");
            assertThat(fields(alice.get(path + "?size=50&page=42949673"))).contains("page");
            MvcResult ok = alice.get(path + "?size=50");
            assertThat(ok.getResponse().getStatus()).isEqualTo(200);
            assertThat(ok.getResponse().getHeader("Cache-Control")).contains("no-store");
            assertThat(((Number) JsonPath.read(body(ok), "$.data.totalElements")).intValue()).isZero();
        }
        // 예상 사용자: 같으면 통과, 다르면 409, 형식이 틀리면 400. 요청값으로 다른 사용자 기록을 볼 수 없다
        AuthTestClient bob = signup("bob");
        long bobId = userId("bob");
        long w = work("남의 작품", "가상작가");
        rate(bobId, w, 4, BASE);
        alice.withHeader("X-Expected-User-Id", String.valueOf(aliceId));
        assertThat(alice.get("/api/v1/me/ratings").getResponse().getStatus()).isEqualTo(200);
        alice.withHeader("X-Expected-User-Id", String.valueOf(bobId));
        MvcResult mismatch = alice.get("/api/v1/me/ratings");
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(body(mismatch), "$.error.code")).isEqualTo("USER_MISMATCH");
        assertThat(alice.get("/api/v1/me/activity").getResponse().getStatus()).isEqualTo(409);
        alice.withHeader("X-Expected-User-Id", "01");
        assertThat(alice.get("/api/v1/me/reviews").getResponse().getStatus()).isEqualTo(400);
        alice.withHeader("X-Expected-User-Id", null);
        assertThat(body(alice.get("/api/v1/me/ratings?userId=" + bobId))).doesNotContain("남의 작품");
    }

    @Test
    void largeIdsStayExact() throws Exception {
        jdbc.execute("ALTER TABLE works ALTER COLUMN id RESTART WITH 9007199254740993");
        AuthTestClient alice = signup("alice");
        long aliceId = userId("alice");
        long big = work("큰 번호 작품", "가상작가");
        rate(aliceId, big, 7, BASE);
        review(aliceId, big, "큰 번호 작품의 리뷰입니다. 열 글자 넘게.", false, BASE, BASE);
        assertThat(body(alice.get("/api/v1/me/ratings"))).contains("\"workId\":9007199254740993").contains("\"id\":9007199254740993");
        assertThat(body(alice.get("/api/v1/me/reviews"))).contains("\"workId\":9007199254740993");
    }

    // ---------- 준비 ----------

    private AuthTestClient signup(String name) throws Exception {
        AuthTestClient client = new AuthTestClient(mockMvc, objectMapper);
        MvcResult r = client.signup(name + "@manhwanet.test", "violet-harbor-compass-" + name, "닉네임" + name);
        assertThat(r.getResponse().getStatus()).as(body(r)).isIn(200, 201);
        return client;
    }

    private long userId(String name) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, name + "@manhwanet.test");
    }

    private long work(String title, String creator) {
        long creatorId = creatorService.register(new CreateCreatorRequest(creator, null, null, "테스트 준비"), adminId).id();
        return workService.register(new CreateWorkRequest("WEBTOON", "KR", null,
                List.of(new CreateWorkRequest.TitleRequest(title, "ko", true)),
                List.of(new CreateWorkRequest.CreditRequest(creatorId, "STORY")), null, null, null,
                evidence(staff("TITLE"), staff("CREDIT")), "테스트 준비"), adminId).id();
    }

    private long rate(long userId, long workId, int scoreHalf, Instant at) {
        return jdbc.queryForObject("""
                INSERT INTO work_ratings (user_id, work_id, score_half, created_at, updated_at) VALUES (?, ?, ?, ?, ?) RETURNING id""",
                Long.class, userId, workId, scoreHalf, Timestamp.from(at), Timestamp.from(at));
    }

    private void review(long userId, long workId, String text, boolean spoiler, Instant created, Instant updated) {
        jdbc.update("""
                INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)""",
                userId, workId, text, spoiler, Timestamp.from(created), Timestamp.from(updated));
    }

    private static Map<String, Object> staff(String field) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("field", field);
        e.put("sourceType", "STAFF");
        e.put("verifiedAt", "2026-10-15T00:00:00Z");
        return e;
    }

    @SafeVarargs
    private List<EvidenceRequest> evidence(Map<String, Object>... items) {
        return objectMapper.convertValue(List.of(items),
                objectMapper.getTypeFactory().constructCollectionType(List.class, EvidenceRequest.class));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<Long> workIds(String body) {
        List<Number> ids = JsonPath.read(body, "$.data.content[*].workId");
        return ids.stream().map(Number::longValue).toList();
    }

    private static List<String> fields(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        return JsonPath.read(body(r), "$.error.fieldErrors[*].field");
    }
}
