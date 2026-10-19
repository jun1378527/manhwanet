package com.manhwanet.work;

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
import java.nio.charset.StandardCharsets;
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
 * 공개 작품 상세·목록의 확장 필드: 리뷰 수(reviewCount)와 플랫폼 바로가기(platformLinks).
 * 기존 필드는 그대로 두고 뒤에 덧붙였는지, 바로가기가 연결 상태·이전 관계·등급·플랫폼 종료·URL 규칙을 다시 확인하는지,
 * 관리자 근거·메모·확인자 정보가 나오지 않는지 격리 테스트 DB 에서 확인한다.
 * 플랫폼 URL 은 문자열 검사용 값이며 서버·테스트 모두 그 주소에 요청하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkPublicDetailIntegrationTest {

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
    private int seq;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbc, environment);
        adminAccountService.createAdmin("admin@manhwanet.test", STRONG_PASSWORD, "관리자");
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'admin@manhwanet.test'", Long.class);
        seq = 0;
    }

    @Test
    void reviewCountIsAppendedToDetailListSearchAndRankingWithoutChangingExistingFields() throws Exception {
        long reviewed = work("리뷰 있는 작품");
        long quiet = work("리뷰 없는 작품");
        review(reviewed);
        review(reviewed);
        rate(reviewed, 8);

        String detail = body(anonymous("/api/v1/works/" + reviewed));
        assertThat(((Number) JsonPath.read(detail, "$.data.reviewCount")).intValue()).isEqualTo(2);
        Map<String, Object> data = JsonPath.read(detail, "$.data");
        assertThat(data.keySet()).containsExactly("id", "workFormat", "originCountry", "description", "primaryTitle",
                "titles", "creators", "rating", "createdAt", "updatedAt", "reviewCount", "platformLinks");
        assertThat(((Number) JsonPath.read(body(anonymous("/api/v1/works/" + quiet)), "$.data.reviewCount")).intValue()).isZero();

        for (String path : List.of("/api/v1/works", "/api/v1/works?order=reviewCount", "/api/v1/works/rankings?order=ratingCount")) {
            String list = body(anonymous(path));
            List<Number> counts = JsonPath.read(list, "$.data.content[?(@.id == " + reviewed + ")].reviewCount");
            assertThat(counts).as(path).singleElement().satisfies(n -> assertThat(n.intValue()).isEqualTo(2));
        }
        List<Number> quietCount = JsonPath.read(body(anonymous("/api/v1/works")), "$.data.content[?(@.id == " + quiet + ")].reviewCount");
        assertThat(quietCount).singleElement().satisfies(n -> assertThat(n.intValue()).isZero());
        // 기존 목록 항목의 필드 순서: 새 필드는 맨 뒤
        Map<String, Object> item = ((List<Map<String, Object>>) JsonPath.read(body(anonymous("/api/v1/works")), "$.data.content")).get(0);
        assertThat(item.keySet()).containsExactly("id", "workFormat", "originCountry", "primaryTitle", "creators", "rating",
                "createdAt", "reviewCount");
    }

    @Test
    void platformLinksShowOnlyCurrentGeneralListingsWithRevalidatedUrlsAndNoAdminData() throws Exception {
        long w = work("바로가기 작품");
        listing(w, "NAVER_WEBTOON", "1001", "https://COMIC.naver.com/webtoon/list?titleId=1001", "ACTIVE", "GENERAL", "MAIN");
        listing(w, "KAKAO_PAGE", "2002", "https://page.kakao.com/content/2002", "ACTIVE", "GENERAL", "SEASON");
        // 종료·미확인 연결
        listing(w, "RIDI", "3003", "https://ridibooks.com/books/3003", "ENDED", "GENERAL", "MAIN");
        listing(w, "KAKAO_WEBTOON", "5005", "https://webtoon.kakao.com/content/x/5005", "UNKNOWN", "GENERAL", "MAIN");
        // ID 단위 미확정(id_scope UNKNOWN) 연결
        long unknownScope = listing(w, "KAKAO_WEBTOON", "7007", "https://webtoon.kakao.com/content/y/7007", "ACTIVE", "GENERAL", "MAIN");
        jdbc.update("UPDATE work_platform_listings SET id_scope = 'UNKNOWN' WHERE id = ?", unknownScope);
        // 성인·미확인 등급
        listing(w, "LEZHIN", "adult1", "https://www.lezhin.com/ko/comic/adult1", "ACTIVE", "ADULT", "MAIN");
        listing(w, "BOMTOON", "unk1", "https://www.bomtoon.com/detail/unk1", "ACTIVE", "UNKNOWN", "MAIN");
        // 저장된 URL 이 지금 규칙에 맞지 않음(허용되지 않은 호스트, 외부 ID 불일치)
        listing(w, "NAVER_WEBTOON", "4004", "https://evil.example/webtoon/list?titleId=4004", "ACTIVE", "GENERAL", "MAIN");
        listing(w, "RIDI", "6006", "https://ridibooks.com/books/9999", "ACTIVE", "GENERAL", "EDITION");
        // 이전: 옛 연결(MOVED)은 빼고 이전 대상(ACTIVE)만
        long target = listing(w, "TOOMICS", "new-1", "https://toomics.com/webtoon/new-1", "ACTIVE", "GENERAL", "TRANSLATION");
        long old = listing(w, "TOOMICS", "old-1", "https://toomics.com/webtoon/old-1", "ACTIVE", "GENERAL", "TRANSLATION");
        jdbc.update("UPDATE work_platform_listings SET status = 'MOVED', moved_to_listing_id = ? WHERE id = ?", target, old);
        // 같은 URL 의 연결은 한 번만(작품 URL 패턴이 없는 플랫폼)
        listing(w, "TOOMICS", "dup-1", "https://toomics.com/webtoon/new-1", "ACTIVE", "GENERAL", "TRANSLATION");
        // 종료된 플랫폼
        listing(w, "MRBLUE", "mb1", "https://www.mrblue.com/webtoon/mb1", "ACTIVE", "GENERAL", "MAIN");
        jdbc.update("""
                UPDATE platforms SET status = 'CLOSED', status_basis = 'OFFICIAL', status_evidence_url = 'https://example.test/closed'
                WHERE code = 'MRBLUE'""");
        jdbc.update("UPDATE work_platform_listings SET note = '관리자 메모 비밀', age_rating_raw = '원문등급비밀' WHERE work_id = ?", w);

        String detail = body(anonymous("/api/v1/works/" + w));
        List<Map<String, Object>> links = JsonPath.read(detail, "$.data.platformLinks");
        // 본편 → 번역판 → 시즌 → 판본
        assertThat(links).extracting(l -> l.get("url")).containsExactly(
                "https://comic.naver.com/webtoon/list?titleId=1001",
                "https://toomics.com/webtoon/new-1",
                "https://page.kakao.com/content/2002");
        assertThat(links.get(0)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "platformCode", "NAVER_WEBTOON", "platformName", "네이버웹툰",
                "url", "https://comic.naver.com/webtoon/list?titleId=1001", "listingKind", "MAIN", "languageCode", "ko"));
        assertThat(links).allSatisfy(l -> assertThat(l.keySet())
                .containsExactlyInAnyOrder("platformCode", "platformName", "url", "listingKind", "languageCode"));
        for (String secret : List.of("verifiedBy", "note", "ageRatingRaw", "platformWorkId", "관리자 메모 비밀", "원문등급비밀",
                "evil.example", "old-1", "mrblue", "lezhin", "bomtoon", "7007", "\"listings\"")) {
            assertThat(detail).as(secret).doesNotContain(secret);
        }
        // 연결이 없는 작품은 빈 목록
        long none = work("연결 없는 작품");
        assertThat((List<?>) JsonPath.read(body(anonymous("/api/v1/works/" + none)), "$.data.platformLinks")).isEmpty();
    }

    @Test
    void hiddenWorksStay404AndLargeIdsStayExact() throws Exception {
        jdbc.execute("ALTER TABLE works ALTER COLUMN id RESTART WITH 9007199254740993");
        long big = work("큰 번호 상세 작품");
        review(big);
        String detail = body(anonymous("/api/v1/works/" + big));
        assertThat(detail).contains("\"id\":9007199254740993").contains("\"reviewCount\":1");
        workService.changeVisibility(big, new ChangeVisibilityRequest(new JsonInteger(0L), "HIDDEN", "숨김 확인"), adminId);
        MvcResult hidden = anonymous("/api/v1/works/" + big);
        assertThat(hidden.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(hidden)).doesNotContain("큰 번호 상세 작품").doesNotContain("platformLinks");
    }

    // ---------- 준비 ----------

    private long work(String title) {
        long creatorId = creatorService.register(new CreateCreatorRequest("가상작가 " + title, null, null, "테스트 준비"), adminId).id();
        return workService.register(new CreateWorkRequest("WEBTOON", "KR", null,
                List.of(new CreateWorkRequest.TitleRequest(title, "ko", true)),
                List.of(new CreateWorkRequest.CreditRequest(creatorId, "STORY")), null, null, null,
                evidence(staff("TITLE"), staff("CREDIT")), "테스트 준비"), adminId).id();
    }

    private long listing(long workId, String platform, String platformWorkId, String url, String status, String ageClass,
            String kind) {
        return jdbc.queryForObject("""
                INSERT INTO work_platform_listings (work_id, platform_code, platform_work_id, id_scope, url, listing_kind,
                    language_code, status, age_class, first_verified_at, last_verified_at, verified_by)
                VALUES (?, ?, ?, 'SERIES', ?, ?, 'ko', ?, ?, now(), now(), ?) RETURNING id""",
                Long.class, workId, platform, platformWorkId, url, kind, status, ageClass, adminId);
    }

    private long user() {
        seq++;
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role) VALUES (?, 'test-only-not-a-hash', ?, 'USER') RETURNING id""",
                Long.class, "reader" + seq + "@manhwanet.test", "독자" + seq);
    }

    private void review(long workId) {
        jdbc.update("INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler) VALUES (?, ?, '열 글자가 넘는 리뷰 본문입니다', false)",
                user(), workId);
    }

    private void rate(long workId, int scoreHalf) {
        jdbc.update("INSERT INTO work_ratings (user_id, work_id, score_half) VALUES (?, ?, ?)", user(), workId, scoreHalf);
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
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
