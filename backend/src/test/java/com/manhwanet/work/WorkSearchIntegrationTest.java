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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
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
 * 작품 찾기(GET /api/v1/works 의 q·format·platform·order, GET /api/v1/works/filters).
 * 전체 공개 작품 대상 검색·필터·정렬, 페이지 계산, 숨김 제외, 여러 연결로 인한 중복 방지, 기존 목록 호환을 격리 테스트 DB 에서 확인한다.
 * 작품·작가는 서비스로 만들고, 플랫폼 연결·별점·등록 시각은 준비 단계에서 SQL 로 넣는다(쓰기 경로는 기존 테스트가 확인).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkSearchIntegrationTest {

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
    private int seq;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbc, environment);
        adminAccountService.createAdmin("admin@manhwanet.test", STRONG_PASSWORD, "관리자");
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'admin@manhwanet.test'", Long.class);
        seq = 0;
    }

    // ---------- 검색 ----------

    @Test
    void searchMatchesAnyTitleOrCreatorNameCaseInsensitivelyAcrossAllPublicWorksAndTrimsTheQuery() throws Exception {
        long sky1 = creator("김하늘", null);
        long sky2 = creator("김하늘", "Kim Haneul");
        long other = creator("박바다", null);
        long moon = work("WEBTOON", List.of(title("달빛 상점", "ko", true), title("Moonlight Shop", "en", false)), List.of(other));
        long a = work("WEBTOON", List.of(title("하늘 정원", "ko", true)), List.of(sky1));
        long b = work("PAGE_COMIC", List.of(title("구름 열차", "ko", true)), List.of(sky2));
        // 같은 이름의 다른 작가 둘이 함께 참여한 작품도 결과에 한 번만 나온다
        long both = work("WEBTOON", List.of(title("두 하늘", "ko", true)), List.of(sky1, sky2));
        for (int i = 0; i < 25; i++) {
            work("WEBTOON", List.of(title("관련 없는 작품 " + i, "ko", true)), List.of(other));
        }

        // 다른 이름(영문 별칭) · 대소문자 무시 · 앞뒤 공백 무시. 25개를 넘는 전체에서 찾는다(현재 페이지만 거르지 않음)
        assertThat(ids(search("q=" + enc("  moonLIGHT  ")))).containsExactly(moon);
        String sky = search("q=" + enc(" 김하늘 ") + "&size=2");
        assertThat(total(sky)).isEqualTo(3);
        List<Long> all = new ArrayList<>(ids(sky));
        all.addAll(ids(search("q=" + enc("김하늘") + "&size=2&page=1")));
        assertThat(all).containsExactlyInAnyOrder(a, b, both).doesNotHaveDuplicates();
        // 작가 원어 이름, 연속 공백은 하나로
        assertThat(ids(search("q=" + enc("kim   haneul")))).containsExactlyInAnyOrder(b, both);
        // 조합형(NFD)으로 입력한 한글도 같은 글자로 찾는다
        assertThat(total(search("q=" + enc(Normalizer.normalize("김하늘", Normalizer.Form.NFD))))).isEqualTo(3);
        // 공백만 있는 검색어는 조건이 없는 것과 같다
        assertThat(total(search("q=" + enc("   ")))).isEqualTo(29);
    }

    @Test
    void wildcardEscapeAndQuoteCharactersAreSearchedLiterally() throws Exception {
        long c = creator("특수작가", null);
        long percent = work("WEBTOON", List.of(title("100% 진심", "ko", true)), List.of(c));
        long underscore = work("WEBTOON", List.of(title("snake_case 일기", "ko", true)), List.of(c));
        long backslash = work("WEBTOON", List.of(title("역슬래시 \\ 이야기", "ko", true)), List.of(c));
        long quote = work("WEBTOON", List.of(title("그녀의 'O' 노트", "ko", true)), List.of(c));
        work("WEBTOON", List.of(title("평범한 제목", "ko", true)), List.of(c));

        assertThat(ids(search("q=" + enc("%")))).containsExactly(percent);
        assertThat(ids(search("q=" + enc("_")))).containsExactly(underscore);
        assertThat(ids(search("q=" + enc("\\")))).containsExactly(backslash);
        assertThat(ids(search("q=" + enc("'O'")))).containsExactly(quote);
        assertThat(total(search("q=" + enc("' OR 1=1 --")))).isZero();
        // 제어 문자(NUL 등)는 공백으로 바뀌어 오류 없이 처리된다
        assertThat(ids(search("q=" + enc("100%\u0000진심")))).containsExactly(percent);
        assertThat(ids(search("q=" + enc("\t100%\n")))).containsExactly(percent);
    }

    @Test
    void hiddenWorksAndTheirTitlesAndCreatorsNeverAppearInResultsCountsOrPlatformOptions() throws Exception {
        long secretCreator = creator("숨은작가", null);
        long c = creator("공개작가", null);
        long hidden = work("WEBTOON", List.of(title("숨김 비밀 작품", "ko", true)), List.of(secretCreator, c));
        long visible = work("WEBTOON", List.of(title("공개 작품", "ko", true)), List.of(c));
        listing(hidden, "LEZHIN", "ACTIVE");
        listing(visible, "NAVER_WEBTOON", "ACTIVE");
        rate(hidden, 10, 10, 10);
        hide(hidden);

        assertThat(total(search("q=" + enc("비밀")))).isZero();
        assertThat(total(search("q=" + enc("숨은작가")))).isZero();
        assertThat(ids(search("q=" + enc("공개작가")))).containsExactly(visible);
        assertThat(total(search("platform=LEZHIN"))).isZero();
        for (String order : List.of("recent", "popular", "rating", "ratingCount", "reviewCount", "title")) {
            String body = search("order=" + order);
            assertThat(ids(body)).containsExactly(visible);
            assertThat(total(body)).isEqualTo(1);
        }
        String filters = body(anonymous("/api/v1/works/filters"));
        assertThat(JsonPath.<List<String>>read(filters, "$.data.platforms[*].code")).containsExactly("NAVER_WEBTOON");
        assertThat(filters).doesNotContain("숨김").doesNotContain("숨은작가");
    }

    // ---------- 필터 ----------

    @Test
    void platformFilterUsesOnlyActiveListingsAndCountsEachWorkOnce() throws Exception {
        long c = creator("연결작가", null);
        long many = work("WEBTOON", List.of(title("여러 연결 작품", "ko", true)), List.of(c));
        long single = work("PAGE_COMIC", List.of(title("하나 연결 작품", "ko", true)), List.of(c));
        long ended = work("WEBTOON", List.of(title("종료 연결 작품", "ko", true)), List.of(c));
        long none = work("WEBTOON", List.of(title("연결 없는 작품", "ko", true)), List.of(c));
        // 같은 플랫폼 연결 둘(예: 본편·시즌) + 다른 플랫폼 연결
        listing(many, "NAVER_WEBTOON", "ACTIVE");
        listing(many, "NAVER_WEBTOON", "ACTIVE");
        listing(many, "KAKAO_PAGE", "ACTIVE");
        listing(single, "NAVER_WEBTOON", "ACTIVE");
        listing(ended, "RIDI", "ENDED");
        listing(ended, "NAVER_WEBTOON", "UNKNOWN");

        String naver = search("platform=NAVER_WEBTOON&size=1");
        assertThat(total(naver)).isEqualTo(2);
        assertThat(((Number) JsonPath.read(naver, "$.data.totalPages")).intValue()).isEqualTo(2);
        List<Long> pages = new ArrayList<>(ids(naver));
        pages.addAll(ids(search("platform=NAVER_WEBTOON&size=1&page=1")));
        assertThat(pages).containsExactlyInAnyOrder(many, single).doesNotHaveDuplicates();
        assertThat(ids(search("platform=KAKAO_PAGE"))).containsExactly(many);
        assertThat(total(search("platform=RIDI"))).isZero();
        // 형식에 맞지만 없는 코드는 빈 결과
        assertThat(total(search("platform=NOPE"))).isZero();
        // 조합: 형식 + 플랫폼 + 검색어
        assertThat(ids(search("platform=NAVER_WEBTOON&format=PAGE_COMIC"))).containsExactly(single);
        assertThat(ids(search("platform=NAVER_WEBTOON&format=WEBTOON&q=" + enc("여러")))).containsExactly(many);
        assertThat(total(search("format=WEBTOON"))).isEqualTo(3);
        assertThat(ids(search("format=PAGE_COMIC"))).containsExactly(single);
        assertThat(ids(search("format=WEBTOON&size=50"))).contains(none);

        // 선택지: 공개 작품에 ACTIVE 연결이 있는 플랫폼만, 작품 기준 수
        String filters = body(anonymous("/api/v1/works/filters"));
        List<Map<String, Object>> platforms = JsonPath.read(filters, "$.data.platforms");
        assertThat(platforms).extracting(p -> p.get("code")).containsExactlyInAnyOrder("NAVER_WEBTOON", "KAKAO_PAGE");
        assertThat(platforms).filteredOn(p -> "NAVER_WEBTOON".equals(p.get("code")))
                .singleElement().satisfies(p -> {
                    assertThat(p.get("name")).isEqualTo("네이버웹툰");
                    assertThat(((Number) p.get("workCount")).intValue()).isEqualTo(2);
                });
    }

    // ---------- 정렬 ----------

    @Test
    void ratingSortsPutRatedWorksFirstAndUnratedWorksLastWithStableTieBreaks() throws Exception {
        long c = creator("정렬작가", null);
        long a = work("WEBTOON", List.of(title("가 작품", "ko", true)), List.of(c)); // 4.5(2)
        long b = work("WEBTOON", List.of(title("나 작품", "ko", true)), List.of(c)); // 4.5(4)
        long d = work("WEBTOON", List.of(title("다 작품", "ko", true)), List.of(c)); // 3.0(5)
        long i = work("WEBTOON", List.of(title("라 작품", "ko", true)), List.of(c)); // 4.45→4.5(10)
        long u1 = work("WEBTOON", List.of(title("마 평가 없음", "ko", true)), List.of(c));
        long u2 = work("WEBTOON", List.of(title("바 평가 없음", "ko", true)), List.of(c));
        createdAt(a, 1);
        createdAt(b, 2);
        createdAt(d, 3);
        createdAt(i, 4);
        createdAt(u1, 5);
        createdAt(u2, 5);
        rate(a, 9, 9);
        rate(b, 9, 9, 9, 9);
        rate(d, 6, 6, 6, 6, 6);
        rate(i, 9, 9, 9, 9, 9, 9, 9, 9, 9, 8);
        long uHigh = Math.max(u1, u2);
        long uLow = Math.min(u1, u2);

        // 평점순: 평균(반올림) → 평가 수 → 등록 시각 → ID. 평가 없는 작품은 뒤(최근 등록 순)
        List<Long> rating = List.of(i, b, a, d, uHigh, uLow);
        assertThat(ids(search("order=rating"))).containsExactlyElementsOf(rating);
        // 평가 많은 순: 평가 수 → 평균 → 등록 시각 → ID
        List<Long> count = List.of(i, d, b, a, uHigh, uLow);
        assertThat(ids(search("order=ratingCount"))).containsExactlyElementsOf(count);
        assertThat(ids(search("order=RATINGCOUNT"))).containsExactlyElementsOf(count);
        // 모든 정렬에서 건수는 같다(평가 없는 작품도 결과에 들어간다)
        for (String order : List.of("recent", "popular", "rating", "ratingCount", "reviewCount", "title")) {
            assertThat(total(search("order=" + order))).isEqualTo(6);
        }
        // 페이지를 나눠도 같은 순서
        assertThat(paged("order=rating", 6)).containsExactlyElementsOf(rating);
        assertThat(paged("order=ratingCount", 6)).containsExactlyElementsOf(count);
    }

    @Test
    void recentAndTitleSortsHaveStableTieBreaksAcrossPages() throws Exception {
        long c = creator("제목작가", null);
        long apple = work("WEBTOON", List.of(title("apple", "en", true)), List.of(c));
        long banana = work("WEBTOON", List.of(title("Banana", "en", true)), List.of(c));
        long ga = work("WEBTOON", List.of(title("가나다", "ko", true)), List.of(c));
        long ha = work("WEBTOON", List.of(title("하늘", "ko", true)), List.of(c));
        long digit = work("WEBTOON", List.of(title("1등 작품", "ko", true)), List.of(c));
        // 같은 대표 제목(언어가 달라 다른 작품)
        long same1 = work("PAGE_COMIC", List.of(title("같은 제목", "ko", true)), List.of(c));
        long same2 = work("PAGE_COMIC", List.of(title("같은 제목", "ja", true)), List.of(c));
        for (long w : List.of(apple, banana, ga, ha, digit, same1, same2)) {
            createdAt(w, 1); // 모두 같은 등록 시각
        }

        // 제목순: 대소문자 무시 코드 포인트 순 → 같은 제목은 ID 오름차순
        List<Long> byTitle = List.of(digit, apple, banana, ga, Math.min(same1, same2), Math.max(same1, same2), ha);
        assertThat(ids(search("order=title"))).containsExactlyElementsOf(byTitle);
        assertThat(paged("order=title", 7)).containsExactlyElementsOf(byTitle);
        // 최근 등록: 같은 시각이면 ID 내림차순 = 기존 목록 기본 정렬과 같다
        List<Long> recent = List.of(same2, same1, digit, ha, ga, banana, apple);
        assertThat(ids(search("order=recent"))).containsExactlyElementsOf(recent);
        assertThat(paged("order=recent", 7)).containsExactlyElementsOf(recent);
        assertThat(ids(body(anonymous("/api/v1/works")))).containsExactlyElementsOf(recent);
    }

    @Test
    void popularCountsDistinctUsersActiveInTheLast30DaysAndReviewCountCountsReviews() throws Exception {
        long c = creator("인기작가", null);
        long p1 = work("WEBTOON", List.of(title("인기 하나", "ko", true)), List.of(c));
        long p2 = work("WEBTOON", List.of(title("인기 둘", "ko", true)), List.of(c));
        long p3 = work("WEBTOON", List.of(title("인기 셋", "ko", true)), List.of(c));
        long p4 = work("WEBTOON", List.of(title("인기 넷", "ko", true)), List.of(c));
        long p5 = work("WEBTOON", List.of(title("인기 다섯 활동 없음", "ko", true)), List.of(c));
        long hidden = work("WEBTOON", List.of(title("인기 숨김", "ko", true)), List.of(c));
        Instant now = Instant.now();
        Instant recent = now.minus(java.time.Duration.ofDays(3));
        Instant old = now.minus(java.time.Duration.ofDays(40));
        // p1: 최근 별점 3명, 오래된 리뷰 2건
        for (int i = 0; i < 3; i++) {
            rateAt(user(), p1, 8, recent);
        }
        reviewAt(user(), p1, old);
        reviewAt(user(), p1, old);
        // p2: 한 사람이 최근 별점과 리뷰를 모두 남김(1명) + 오래된 별점 4개 → 활동 1, 별점 5, 리뷰 1
        long both = user();
        rateAt(both, p2, 6, recent);
        reviewAt(both, p2, recent);
        for (int i = 0; i < 4; i++) {
            rateAt(user(), p2, 6, old);
        }
        // p3: 오래된 별점 5개뿐 → 활동 0, 별점 5
        for (int i = 0; i < 5; i++) {
            rateAt(user(), p3, 10, old);
        }
        // p4: 최근 리뷰 1명 + 최근 별점 1명 → 활동 2, 별점 1, 리뷰 1
        reviewAt(user(), p4, recent);
        rateAt(user(), p4, 4, recent);
        // 오래 전에 남겼어도 최근에 고친 별점은 최근 활동이다
        long edited = user();
        rateAt(edited, p3, 10, old);
        jdbc.update("UPDATE work_ratings SET updated_at = ? WHERE user_id = ? AND work_id = ?", java.sql.Timestamp.from(recent), edited, p3);
        // 숨김 작품은 활동이 많아도 결과에 없다
        for (int i = 0; i < 6; i++) {
            rateAt(user(), hidden, 10, recent);
        }
        hide(hidden);
        createdAt(p1, 1);
        createdAt(p2, 2);
        createdAt(p3, 3);
        createdAt(p4, 4);
        createdAt(p5, 5);

        // 인기순: 최근 활동 이용자 수 → 전체 별점 수 → 등록 시각 → ID
        // p1(3) → p4(2) → p3(1, 별점 6) → p2(1, 별점 5) → p5(0)
        List<Long> popular = List.of(p1, p4, p3, p2, p5);
        assertThat(ids(search("order=popular"))).containsExactlyElementsOf(popular);
        assertThat(ids(search("order=POPULAR"))).containsExactlyElementsOf(popular);
        assertThat(paged("order=popular", 5)).containsExactlyElementsOf(popular);
        // 리뷰많은순: 리뷰 수 → 별점 수 → 등록 시각 → ID. 리뷰 없는 작품도 뒤에 포함
        // p1(2) → p2(1, 별점 5) → p4(1, 별점 1) → p3(0, 별점 6) → p5(0)
        List<Long> reviews = List.of(p1, p2, p4, p3, p5);
        assertThat(ids(search("order=reviewCount"))).containsExactlyElementsOf(reviews);
        assertThat(paged("order=reviewCount", 5)).containsExactlyElementsOf(reviews);
        for (String order : List.of("popular", "reviewCount")) {
            assertThat(total(search("order=" + order))).isEqualTo(5);
        }
    }

    // ---------- 페이지 ----------

    @Test
    void pageMetadataIsComputedFromTheFilteredTotalAndPagesBeyondTheEndAreEmpty() throws Exception {
        long c = creator("페이지작가", null);
        for (int i = 0; i < 7; i++) {
            work(i % 2 == 0 ? "WEBTOON" : "PAGE_COMIC", List.of(title("페이지 작품 " + i, "ko", true)), List.of(c));
        }
        String first = search("format=WEBTOON&size=3");
        assertThat(total(first)).isEqualTo(4);
        assertThat(((Number) JsonPath.read(first, "$.data.totalPages")).intValue()).isEqualTo(2);
        assertThat((Boolean) JsonPath.read(first, "$.data.hasNext")).isTrue();
        assertThat(((Number) JsonPath.read(first, "$.data.size")).intValue()).isEqualTo(3);
        String last = search("format=WEBTOON&size=3&page=1");
        assertThat(ids(last)).hasSize(1);
        assertThat((Boolean) JsonPath.read(last, "$.data.hasNext")).isFalse();
        String beyond = search("format=WEBTOON&size=3&page=5");
        assertThat(ids(beyond)).isEmpty();
        assertThat(total(beyond)).isEqualTo(4);
        assertThat(((Number) JsonPath.read(beyond, "$.data.page")).intValue()).isEqualTo(5);
        String empty = search("q=" + enc("없는 제목"));
        assertThat(ids(empty)).isEmpty();
        assertThat(total(empty)).isZero();
        assertThat(((Number) JsonPath.read(empty, "$.data.totalPages")).intValue()).isZero();
    }

    @Test
    void largeIdsAreWrittenAsExactJsonIntegers() throws Exception {
        jdbc.execute("ALTER TABLE works ALTER COLUMN id RESTART WITH 9007199254740993");
        long c = creator("큰번호작가", null);
        long big = work("WEBTOON", List.of(title("큰 번호 작품", "ko", true)), List.of(c));
        assertThat(big).isEqualTo(9007199254740993L);
        String body = search("q=" + enc("큰 번호"));
        assertThat(body).contains("\"id\":9007199254740993");
        assertThat(body(anonymous("/api/v1/works?order=title"))).contains("\"id\":9007199254740993");
    }

    // ---------- 기존 API 호환 ----------

    @Test
    void existingListCallsKeepTheirBehaviorAndSearchResponsesHaveTheSameShape() throws Exception {
        long c = creator("호환작가", null);
        long a = work("WEBTOON", List.of(title("호환 A", "ko", true)), List.of(c));
        long b = work("PAGE_COMIC", List.of(title("호환 B", "ko", true)), List.of(c));
        long h = work("WEBTOON", List.of(title("호환 숨김", "ko", true)), List.of(c));
        rate(a, 8);
        hide(h);

        // 조건 없는 기존 호출: 공개 작품, 최근 등록 순, sort 지원 그대로
        assertThat(ids(body(anonymous("/api/v1/works")))).containsExactly(b, a);
        assertThat(ids(body(anonymous("/api/v1/works?sort=id,asc")))).containsExactly(a, b);
        assertThat(ids(body(anonymous("/api/v1/works?sort=createdAt,desc&page=0&size=20")))).containsExactly(b, a);
        // 빈 검색어·빈 조건은 기존 경로(sort 와 함께 써도 된다)
        assertThat(ids(body(anonymous("/api/v1/works?q=&format=&platform=&order=&sort=id,asc")))).containsExactly(a, b);
        assertThat(fields(anonymous("/api/v1/works?sort=rating"))).contains("sort");

        // 같은 항목 모양
        Map<String, Object> listed = itemById(body(anonymous("/api/v1/works")), a);
        Map<String, Object> searched = itemById(search("q=" + enc("호환")), a);
        assertThat(searched).isEqualTo(listed);
        assertThat(searched.keySet()).containsExactlyInAnyOrder("id", "workFormat", "originCountry", "primaryTitle", "creators", "rating", "createdAt", "reviewCount");
        assertThat(JsonPath.<Map<String, Object>>read(search("q=" + enc("호환")), "$.data").keySet())
                .containsExactlyInAnyOrder("content", "page", "size", "totalElements", "totalPages", "hasNext");

        // 홈 순위 API 는 그대로: 별점 없는 작품 제외
        assertThat(ids(body(anonymous("/api/v1/works/rankings?order=rating")))).containsExactly(a);
        // 상세: 숨김은 404, /works/filters 와 겹치지 않는다
        assertThat(anonymous("/api/v1/works/" + h).getResponse().getStatus()).isEqualTo(404);
        assertThat(anonymous("/api/v1/works/" + a).getResponse().getStatus()).isEqualTo(200);
        assertThat(anonymous("/api/v1/works/filters").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void invalidSearchParametersAreRejectedWithFieldErrors() throws Exception {
        assertThat(fields(anonymous("/api/v1/works?order=hot"))).contains("order");
        assertThat(fields(anonymous("/api/v1/works?format=MANGA"))).contains("format");
        assertThat(fields(anonymous("/api/v1/works?format=webtoon"))).contains("format");
        assertThat(fields(anonymous("/api/v1/works?platform=naver"))).contains("platform");
        assertThat(fields(anonymous("/api/v1/works?q=" + enc("가".repeat(101))))).contains("q");
        assertThat(anonymous("/api/v1/works?q=" + enc("  " + "가".repeat(100) + "  ")).getResponse().getStatus()).isEqualTo(200);
        assertThat(fields(anonymous("/api/v1/works?order=title&sort=id,asc"))).contains("sort");
        assertThat(fields(anonymous("/api/v1/works?order=title&size=101"))).contains("size");
        assertThat(fields(anonymous("/api/v1/works?order=title&page=-1"))).contains("page");
        assertThat(fields(anonymous("/api/v1/works?order=title&size=100&page=21474837"))).contains("page");
    }

    // ---------- 준비 ----------

    private long creator(String name, String originalName) {
        return creatorService.register(new CreateCreatorRequest(name, originalName, null, "테스트 준비"), adminId).id();
    }

    private static CreateWorkRequest.TitleRequest title(String text, String language, boolean primary) {
        return new CreateWorkRequest.TitleRequest(text, language, primary);
    }

    private long work(String format, List<CreateWorkRequest.TitleRequest> titles, List<Long> creatorIds) {
        return workService.register(new CreateWorkRequest(format, "KR", null, titles,
                creatorIds.stream().map(id -> new CreateWorkRequest.CreditRequest(id, "STORY")).toList(), null, null, null,
                evidence(staff("TITLE"), staff("CREDIT")), "테스트 준비"), adminId).id();
    }

    private void listing(long workId, String platform, String status) {
        seq++;
        jdbc.update("""
                INSERT INTO work_platform_listings (work_id, platform_code, platform_work_id, id_scope, url, listing_kind,
                    language_code, status, age_class, first_verified_at, last_verified_at, verified_by)
                VALUES (?, ?, ?, 'SERIES', ?, 'MAIN', 'ko', ?, 'GENERAL', now(), now(), ?)""",
                workId, platform, "T" + seq, "https://example.test/" + seq, status, adminId);
    }

    private void rate(long workId, int... scoreHalves) {
        for (int s : scoreHalves) {
            seq++;
            long user = jdbc.queryForObject("""
                    INSERT INTO users (email, password_hash, nickname, role) VALUES (?, 'test-only-not-a-hash', ?, 'USER') RETURNING id""",
                    Long.class, "reader" + seq + "@manhwanet.test", "평가자" + seq);
            jdbc.update("INSERT INTO work_ratings (user_id, work_id, score_half) VALUES (?, ?, ?)", user, workId, s);
        }
    }

    private long user() {
        seq++;
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role) VALUES (?, 'test-only-not-a-hash', ?, 'USER') RETURNING id""",
                Long.class, "reader" + seq + "@manhwanet.test", "독자" + seq);
    }

    private void rateAt(long userId, long workId, int scoreHalf, Instant at) {
        jdbc.update("INSERT INTO work_ratings (user_id, work_id, score_half, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                userId, workId, scoreHalf, java.sql.Timestamp.from(at), java.sql.Timestamp.from(at));
    }

    private void reviewAt(long userId, long workId, Instant at) {
        jdbc.update("""
                INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler, created_at, updated_at)
                VALUES (?, ?, '리뷰 본문', false, ?, ?)""", userId, workId, java.sql.Timestamp.from(at), java.sql.Timestamp.from(at));
    }

    private void createdAt(long workId, int minutes) {
        jdbc.update("UPDATE works SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(BASE.plusSeconds(60L * minutes)), workId);
    }

    private void hide(long workId) {
        workService.changeVisibility(workId, new ChangeVisibilityRequest(new JsonInteger(0L), "HIDDEN", "숨김 확인"), adminId);
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

    private List<Long> paged(String query, int expected) throws Exception {
        List<Long> all = new ArrayList<>();
        for (int page = 0; page < expected + 1; page++) {
            all.addAll(ids(search(query + "&size=1&page=" + page)));
        }
        return all;
    }

    private String search(String query) throws Exception {
        MvcResult r = anonymous("/api/v1/works?" + query);
        assertThat(r.getResponse().getStatus()).as(query).isEqualTo(200);
        return body(r);
    }

    private static String enc(String value) {
        // MockMvc 는 쿼리의 + 를 공백으로 바꾸지 않으므로 공백은 %20 으로 보낸다(브라우저·Tomcat 은 둘 다 공백)
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private MvcResult anonymous(String path) throws Exception {
        return mockMvc.perform(get(java.net.URI.create(path))).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static long total(String body) {
        return ((Number) JsonPath.read(body, "$.data.totalElements")).longValue();
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

    private static List<String> fields(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        return JsonPath.read(body(r), "$.error.fieldErrors[*].field");
    }
}
