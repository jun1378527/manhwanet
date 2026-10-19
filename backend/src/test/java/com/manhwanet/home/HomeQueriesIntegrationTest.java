package com.manhwanet.home;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.dto.CreateCreatorRequest;
import com.manhwanet.provenance.dto.EvidenceRequest;
import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.ChangeVisibilityRequest;
import com.manhwanet.work.dto.CreateWorkRequest;
import java.time.Instant;
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
 * 홈 화면 공개 조회: 전체 대상 별점 순위(GET /works/rankings)와 최근 공개 리뷰(GET /reviews/recent).
 * 정렬 동률의 고정 순서, 페이지 계산, 숨김 작품 제외, 기존 작품 목록과의 호환을 격리 테스트 DB 에서 확인한다.
 * 작품·작가는 서비스로 만들고, 많은 사용자의 별점·리뷰는 준비 단계에서 SQL 로 넣는다(쓰기 경로는 기존 테스트가 확인).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HomeQueriesIntegrationTest {

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
    private int userSeq;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbc, environment);
        adminAccountService.createAdmin("admin@manhwanet.test", STRONG_PASSWORD, "관리자");
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'admin@manhwanet.test'", Long.class);
        userSeq = 0;
    }

    // ---------- 별점 순위 ----------

    @Test
    void ratingOrderRanksAllPublicRatedWorksWithRoundedAverageThenCountThenStableTieBreak() throws Exception {
        Fixture f = rankingFixture();

        String body = body(anonymous("/api/v1/works/rankings?order=rating&size=50"));
        // C 5.0(1) → I 4.45≈4.5(10) → B 4.5(4) → A 4.5(2) → D 3.0(4)
        assertThat(ids(body)).containsExactly(f.c, f.i, f.b, f.a, f.d);
        assertThat(((Number) JsonPath.read(body, "$.data.totalElements")).intValue()).isEqualTo(5);
        assertThat((Object) JsonPath.read(body, "$.data.content[1].rating.average")).hasToString("4.5");
        assertThat(((Number) JsonPath.read(body, "$.data.content[1].rating.count")).intValue()).isEqualTo(10);
        // 숨김 작품(H, 5.0×5)과 별점 없는 작품(E)은 없다
        assertThat(ids(body)).doesNotContain(f.h, f.e);
        // 기본 order 는 rating
        assertThat(ids(body(anonymous("/api/v1/works/rankings?size=50")))).containsExactly(f.c, f.i, f.b, f.a, f.d);
    }

    @Test
    void ratingCountOrderUsesCountThenAverageThenRegistrationTimeThenId() throws Exception {
        Fixture f = rankingFixture();
        String body = body(anonymous("/api/v1/works/rankings?order=ratingCount&size=50"));
        // I(10) → B(4, 4.5) → D(4, 3.0) → A(2) → C(1)
        assertThat(ids(body)).containsExactly(f.i, f.b, f.d, f.a, f.c);
        assertThat(ids(body(anonymous("/api/v1/works/rankings?order=RATINGCOUNT&size=50")))).containsExactly(f.i, f.b, f.d, f.a, f.c);
    }

    @Test
    void completeTiesAreOrderedByRegistrationTimeThenIdAndStayStableAcrossPages() throws Exception {
        long older = work("동률 작품 이전 등록");
        long sameTimeLow = work("동률 작품 같은 시각 1");
        long sameTimeHigh = work("동률 작품 같은 시각 2");
        jdbc.update("UPDATE works SET created_at = ? WHERE id = ?", ts(BASE), older);
        jdbc.update("UPDATE works SET created_at = ? WHERE id IN (?, ?)", ts(BASE.plusSeconds(60)), sameTimeLow, sameTimeHigh);
        for (long w : List.of(older, sameTimeLow, sameTimeHigh)) {
            rate(w, 8, 8);
        }
        List<Long> expected = List.of(Math.max(sameTimeLow, sameTimeHigh), Math.min(sameTimeLow, sameTimeHigh), older);
        for (String order : List.of("rating", "ratingCount")) {
            assertThat(ids(body(anonymous("/api/v1/works/rankings?order=" + order)))).containsExactlyElementsOf(expected);
            List<Long> paged = new java.util.ArrayList<>();
            for (int page = 0; page < 3; page++) {
                paged.addAll(ids(body(anonymous("/api/v1/works/rankings?order=" + order + "&size=1&page=" + page))));
            }
            assertThat(paged).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void pagesAreCutFromTheWholeRankingAndPageMetadataMatchesRankedWorkCount() throws Exception {
        Fixture f = rankingFixture();
        String page1 = body(anonymous("/api/v1/works/rankings?order=rating&size=2&page=1"));
        assertThat(ids(page1)).containsExactly(f.b, f.a);
        assertThat(((Number) JsonPath.read(page1, "$.data.page")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(page1, "$.data.size")).intValue()).isEqualTo(2);
        assertThat(((Number) JsonPath.read(page1, "$.data.totalElements")).intValue()).isEqualTo(5);
        assertThat(((Number) JsonPath.read(page1, "$.data.totalPages")).intValue()).isEqualTo(3);
        assertThat((Boolean) JsonPath.read(page1, "$.data.hasNext")).isTrue();
        String last = body(anonymous("/api/v1/works/rankings?order=rating&size=2&page=2"));
        assertThat(ids(last)).containsExactly(f.d);
        assertThat((Boolean) JsonPath.read(last, "$.data.hasNext")).isFalse();
        String beyond = body(anonymous("/api/v1/works/rankings?order=rating&size=2&page=9"));
        assertThat(ids(beyond)).isEmpty();
        assertThat(((Number) JsonPath.read(beyond, "$.data.totalElements")).intValue()).isEqualTo(5);
    }

    @Test
    void hidingAWorkRemovesItFromTheRankingAndTotal() throws Exception {
        Fixture f = rankingFixture();
        workService.changeVisibility(f.b, new ChangeVisibilityRequest(new JsonInteger(0L), "HIDDEN", "숨김 확인"), adminId);
        String body = body(anonymous("/api/v1/works/rankings?order=rating&size=50"));
        assertThat(ids(body)).containsExactly(f.c, f.i, f.a, f.d);
        assertThat(((Number) JsonPath.read(body, "$.data.totalElements")).intValue()).isEqualTo(4);
    }

    @Test
    void rankingItemsHaveTheSameShapeAsTheWorkListAndTheWorkListIsUnchanged() throws Exception {
        Fixture f = rankingFixture();
        Map<String, Object> ranked = itemById(body(anonymous("/api/v1/works/rankings?order=rating&size=50")), f.b);
        Map<String, Object> listed = itemById(body(anonymous("/api/v1/works?size=50")), f.b);
        assertThat(ranked).isEqualTo(listed);
        assertThat(ranked.keySet()).containsExactlyInAnyOrder("id", "workFormat", "originCountry", "primaryTitle", "creators", "rating", "createdAt", "reviewCount");
        // 기존 목록: 공개 작품 전체(별점 없는 E 포함, 숨김 H 제외), 등록 시각 DESC 그대로
        String list = body(anonymous("/api/v1/works?size=50"));
        assertThat(ids(list)).contains(f.e).doesNotContain(f.h).hasSize(6);
        assertThat(((Number) JsonPath.read(list, "$.data.totalElements")).intValue()).isEqualTo(6);
        assertThat(mockMvc.perform(get("/api/v1/works?sort=rating")).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void rankingRejectsInvalidParameters() throws Exception {
        assertThat(fields(anonymous("/api/v1/works/rankings?order=popular"))).contains("order");
        assertThat(fields(anonymous("/api/v1/works/rankings?size=51"))).contains("size");
        assertThat(fields(anonymous("/api/v1/works/rankings?size=0"))).contains("size");
        assertThat(fields(anonymous("/api/v1/works/rankings?page=-1"))).contains("page");
        String empty = body(anonymous("/api/v1/works/rankings"));
        assertThat(ids(empty)).isEmpty();
        assertThat(((Number) JsonPath.read(empty, "$.data.totalElements")).intValue()).isZero();
        // /works/{id} 와 겹치지 않는다
        assertThat(code(anonymous("/api/v1/works/999999"))).isEqualTo("WORK_NOT_FOUND");
    }

    // ---------- 최근 공개 리뷰 ----------

    @Test
    void recentReviewsAreNewestFirstWithStableTieBreakAndExcludeHiddenWorks() throws Exception {
        long pub = work("공개 작품 가나다");
        long other = work("공개 작품 라마바");
        long hidden = work("숨길 작품");
        long u1 = user("독자하나");
        long u2 = user("아주아주긴닉네임을쓰는독자입니다열다섯자이상");
        long u3 = user("독자셋");
        long r1 = review(u1, pub, "가장 오래된 공개 리뷰입니다.", false, BASE);
        long r2 = review(u2, pub, "같은 시각 리뷰 첫째입니다.", false, BASE.plusSeconds(60));
        long r3 = review(u3, other, "같은 시각 리뷰 둘째, 스포일러입니다.", true, BASE.plusSeconds(60));
        review(u1, hidden, "숨김 작품에 남긴 가장 최신 리뷰입니다.", false, BASE.plusSeconds(120));
        workService.changeVisibility(hidden, new ChangeVisibilityRequest(new JsonInteger(0L), "HIDDEN", "숨김 확인"), adminId);

        String body = body(anonymous("/api/v1/reviews/recent?size=3"));
        List<Number> ids = JsonPath.read(body, "$.data[*].id");
        // 숨김 작품 리뷰는 개수를 잡아먹지 않고 빠진다. 같은 시각은 id DESC
        assertThat(ids.stream().map(Number::longValue).toList()).containsExactly(Math.max(r2, r3), Math.min(r2, r3), r1);
        assertThat(body).doesNotContain("숨김 작품에 남긴").doesNotContain("숨길 작품");
        List<Map<String, Object>> spoilers = JsonPath.read(body, "$.data[?(@.id == " + r3 + ")]");
        Map<String, Object> spoiler = spoilers.get(0);
        assertThat(spoiler).containsEntry("containsSpoiler", true).containsEntry("body", "같은 시각 리뷰 둘째, 스포일러입니다.");
        assertThat(spoiler.keySet()).containsExactlyInAnyOrder("id", "work", "author", "body", "containsSpoiler", "createdAt", "updatedAt", "edited");
        assertThat(JsonPath.<Object>read(body, "$.data[?(@.id == " + r3 + ")].work.primaryTitle.title").toString()).contains("공개 작품 라마바");
        assertThat(body).doesNotContain("userId").doesNotContain("email").doesNotContain("\"version\"");
        assertThat(JsonPath.<List<String>>read(body, "$.data[*].author.nickname")).contains("아주아주긴닉네임을쓰는독자입니다열다섯자이상");
    }

    @Test
    void recentReviewsSizeIsLimitedAndDefaultsToSix() throws Exception {
        long w = work("리뷰 많은 작품");
        for (int i = 0; i < 25; i++) {
            review(user("독자" + i), w, "리뷰 본문 " + i + "번째 문장입니다.", false, BASE.plusSeconds(i));
        }
        assertThat(JsonPath.<List<Object>>read(body(anonymous("/api/v1/reviews/recent")), "$.data")).hasSize(6);
        assertThat(JsonPath.<List<Object>>read(body(anonymous("/api/v1/reviews/recent?size=20")), "$.data")).hasSize(20);
        assertThat(fields(anonymous("/api/v1/reviews/recent?size=21"))).contains("size");
        assertThat(fields(anonymous("/api/v1/reviews/recent?size=0"))).contains("size");
        assertThat(JsonPath.<String>read(body(anonymous("/api/v1/reviews/recent?size=1")), "$.data[0].body")).contains("24번째");
    }

    @Test
    void recentReviewsAreEmptyWithoutReviewsAndOtherReviewPathsStillNeedLogin() throws Exception {
        assertThat(JsonPath.<List<Object>>read(body(anonymous("/api/v1/reviews/recent")), "$.data")).isEmpty();
        // 공개는 GET /reviews/* 한 단계뿐이다. 더 깊은 경로·다른 메서드·내 리뷰는 그대로 로그인 필요
        assertThat(mockMvc.perform(get("/api/v1/reviews/1/extra")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/reviews/1"))
                .andReturn().getResponse().getStatus()).isIn(401, 403);
        assertThat(mockMvc.perform(get("/api/v1/me/reviews/1")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(anonymous("/api/v1/reviews/abc").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void singlePublicReviewReturnsBodyButHidesReviewsOfHiddenWorks() throws Exception {
        long pub = work("공개 작품 한 건 조회");
        long hidden = work("숨길 작품 한 건 조회");
        long spoiler = review(user("한건독자"), pub, "펼쳐야 보이는 스포일러 본문입니다.", true, BASE);
        long onHidden = review(user("숨김독자"), hidden, "숨김 작품의 리뷰 본문입니다.", false, BASE);
        workService.changeVisibility(hidden, new ChangeVisibilityRequest(new JsonInteger(0L), "HIDDEN", "숨김 확인"), adminId);

        String body = body(anonymous("/api/v1/reviews/" + spoiler));
        assertThat(JsonPath.<String>read(body, "$.data.body")).isEqualTo("펼쳐야 보이는 스포일러 본문입니다.");
        assertThat((Boolean) JsonPath.read(body, "$.data.containsSpoiler")).isTrue();
        assertThat(JsonPath.<String>read(body, "$.data.work.primaryTitle.title")).isEqualTo("공개 작품 한 건 조회");
        assertThat(JsonPath.<String>read(body, "$.data.author.nickname")).isEqualTo("한건독자");
        assertThat(code(anonymous("/api/v1/reviews/" + onHidden))).isEqualTo("REVIEW_NOT_FOUND");
        assertThat(body(anonymous("/api/v1/reviews/" + onHidden))).doesNotContain("숨김 작품의 리뷰");
        assertThat(code(anonymous("/api/v1/reviews/999999"))).isEqualTo("REVIEW_NOT_FOUND");
        assertThat(code(anonymous("/api/v1/reviews/0"))).isEqualTo("REVIEW_NOT_FOUND");
    }

    // ---------- 준비 ----------

    private record Fixture(long a, long b, long c, long d, long e, long h, long i) {
    }

    /** A 4.5(2), B 4.5(4), C 5.0(1), D 3.0(4), E 없음, H 숨김 5.0(5), I 4.45(10, 반올림 4.5) */
    private Fixture rankingFixture() {
        long a = work("작품 A");
        long b = work("작품 B");
        long c = work("작품 C");
        long d = work("작품 D");
        long e = work("작품 E 별점 없음");
        long h = work("작품 H 숨김");
        long i = work("작품 I");
        rate(a, 9, 9);
        rate(b, 9, 9, 9, 9);
        rate(c, 10);
        rate(d, 6, 6, 6, 6);
        rate(h, 10, 10, 10, 10, 10);
        rate(i, 9, 9, 9, 9, 9, 9, 9, 9, 9, 8); // 합 89 / (10×2) = 4.45 → 4.5
        workService.changeVisibility(h, new ChangeVisibilityRequest(new JsonInteger(0L), "HIDDEN", "숨김 확인"), adminId);
        return new Fixture(a, b, c, d, e, h, i);
    }

    private long work(String title) {
        long creatorId = creatorService.register(new CreateCreatorRequest("가상작가 " + title, null, null, "테스트 준비"), adminId).id();
        return workService.register(new CreateWorkRequest("WEBTOON", "KR", null,
                List.of(new CreateWorkRequest.TitleRequest(title, "ko", true)),
                List.of(new CreateWorkRequest.CreditRequest(creatorId, "STORY")), null, null, null,
                evidence(staff("TITLE"), staff("CREDIT")), "테스트 준비"), adminId).id();
    }

    private long user(String nickname) {
        userSeq++;
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role) VALUES (?, 'test-only-not-a-hash', ?, 'USER') RETURNING id""",
                Long.class, "reader" + userSeq + "@manhwanet.test", nickname.length() > 30 ? nickname.substring(0, 30) : nickname);
    }

    private void rate(long workId, int... scoreHalves) {
        for (int s : scoreHalves) {
            jdbc.update("INSERT INTO work_ratings (user_id, work_id, score_half) VALUES (?, ?, ?)", user("평가자" + userSeq), workId, s);
        }
    }

    private long review(long userId, long workId, String text, boolean spoiler, Instant at) {
        return jdbc.queryForObject("""
                INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, userId, workId, text, spoiler, ts(at), ts(at));
    }

    private static java.sql.Timestamp ts(Instant at) {
        return java.sql.Timestamp.from(at);
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

    private MvcResult anonymous(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static List<Long> ids(String body) {
        List<Number> ids = JsonPath.read(body, "$.data.content[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private static Map<String, Object> itemById(String body, long id) {
        List<Map<String, Object>> found = JsonPath.read(body, "$.data.content[?(@.id == " + id + ")]");
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private static String code(MvcResult r) throws Exception {
        return JsonPath.read(body(r), "$.error.code");
    }

    private static List<String> fields(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        return JsonPath.read(body(r), "$.error.fieldErrors[*].field");
    }
}
