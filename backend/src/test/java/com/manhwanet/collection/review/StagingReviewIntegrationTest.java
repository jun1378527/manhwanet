package com.manhwanet.collection.review;

import static com.manhwanet.collection.review.ReviewFixtures.createWork;
import static com.manhwanet.collection.review.ReviewFixtures.existingCreator;
import static com.manhwanet.collection.review.ReviewFixtures.ingest;
import static com.manhwanet.collection.review.ReviewFixtures.judge;
import static com.manhwanet.collection.review.ReviewFixtures.link;
import static com.manhwanet.collection.review.ReviewFixtures.listing;
import static com.manhwanet.collection.review.ReviewFixtures.newCreator;
import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.collection.application.CrawlRunService;
import com.manhwanet.collection.application.StagingIngestService;
import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.dto.CreateCreatorRequest;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.CreateWorkRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
 * staging 검수·공개 등록 연동(#51). 실제 로그인 세션·쿠키 CSRF·격리 PostgreSQL 테스트 DB, 가상 작품 데이터만 사용.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StagingReviewIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@manhwanet.test";
    private static final List<String[]> CREDITS = List.<String[]>of(new String[] {"[DEMO] 가상작가 하늘", "글"},
            new String[] {"[DEMO] 가상작가 바다", "그림"});

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
    private CrawlRunService runs;
    @Autowired
    private StagingIngestService ingestService;
    @Autowired
    private StagingReviewService reviewService;
    @Autowired
    private WorkService workService;
    @Autowired
    private CreatorService creatorService;

    private AuthTestClient admin;
    private long adminId;
    private long toomicsRun;
    private long mrblueRun;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseCleaner.clean(jdbc, environment);
        adminAccountService.createAdmin(ADMIN_EMAIL, STRONG_PASSWORD, "관리자");
        admin = new AuthTestClient(mockMvc, objectMapper);
        assertThat(admin.login(ADMIN_EMAIL, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, ADMIN_EMAIL);
        ReviewFixtures.preparePlatforms(jdbc, adminId);
        toomicsRun = ReviewFixtures.startRun(runs, "TOOMICS");
        mrblueRun = ReviewFixtures.startRun(runs, "MRBLUE");
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        jdbc.execute("DROP TRIGGER IF EXISTS trg_test_fail_review ON staging_review_decisions");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_review()");
    }

    // ---------- 권한·입력 ----------

    @Test
    void onlyAdminWithCsrfAndReasonCanReview() throws Exception {
        long id = stage("TOOMICS", "demo-0001", "[DEMO] 가상의 별빛 정원", "전체 이용가");
        String path = "/api/v1/admin/staging-listings/" + id;
        Map<String, Object> body = createWork(1, "가상 작품 신규 등록", List.of(newCreator("[DEMO] 가상작가 하늘", "STORY")), List.of());

        AuthTestClient anonymous = new AuthTestClient(mockMvc, objectMapper);
        assertThat(status(anonymous.get(path + "/review"))).isEqualTo(401);
        assertThat(status(anonymous.postJson(path + "/review", body))).isEqualTo(401);
        AuthTestClient user = new AuthTestClient(mockMvc, objectMapper);
        user.signup("reader@manhwanet.test", STRONG_PASSWORD, "독자");
        assertThat(status(user.get(path + "/review"))).isEqualTo(403);
        assertThat(status(user.postJson(path + "/review", body))).isEqualTo(403);
        assertThat(status(user.postJson(path + "/candidates", Map.of()))).isEqualTo(403);
        MvcResult noCsrf = admin.performWithSessionOnly(post(path + "/review").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
        assertThat(code(noCsrf)).isEqualTo("INVALID_CSRF_TOKEN");

        Map<String, Object> noReason = createWork(1, null, List.of(newCreator("[DEMO] 가상작가 하늘", "STORY")), List.of());
        assertThat(fields(admin.postJson(path + "/review", noReason))).contains("reason");
        Map<String, Object> bad = createWork(1, "사유", List.of(Map.of("newCreatorName", "x")), List.of());
        bad.put("stagingRevision", "1");
        bad.put("decision", "MERGE");
        assertThat(fields(admin.postJson(path + "/review", bad))).contains("decision", "stagingRevision");
        assertThat(count("works")).isZero();
        assertThat(status(admin.get("/api/v1/admin/staging-listings/999999/review"))).isEqualTo(404);
    }

    // ---------- 새 작품 등록 ----------

    @Test
    void createWorkRegistersPublicWorkListingCreatorsAndLogsInOneStep() throws Exception {
        long id = stage("TOOMICS", "demo-0001", "[DEMO] 가상의 별빛 정원", "전체 이용가");
        MvcResult view = admin.get("/api/v1/admin/staging-listings/" + id + "/review");
        assertThat(((Number) JsonPath.read(body(view), "$.data.revision")).longValue()).isEqualTo(1);
        assertThat((List<?>) JsonPath.read(body(view), "$.data.candidates")).isEmpty();

        Map<String, Object> request = createWork(1, "[DEMO] 가상 작품 신규 등록",
                List.of(newCreator("[DEMO] 가상작가 하늘", "STORY"), newCreator("[DEMO] 가상작가 바다", "ART")), List.of());
        MvcResult done = admin.postJson("/api/v1/admin/staging-listings/" + id + "/review", request);

        assertThat(status(done)).as(body(done)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(done), "$.data.reviewStatus")).isEqualTo("NEW_WORK");
        long workId = jdbc.queryForObject("SELECT id FROM works", Long.class);
        long listingId = jdbc.queryForObject("SELECT id FROM work_platform_listings WHERE work_id = ?", Long.class, workId);
        assertThat(((Number) JsonPath.read(body(done), "$.data.linkedListingId")).longValue()).isEqualTo(listingId);
        assertThat(jdbc.queryForObject("SELECT platform_code || ':' || platform_work_id || ':' || age_class FROM work_platform_listings",
                String.class)).isEqualTo("TOOMICS:demo-0001:GENERAL");
        assertThat(jdbc.queryForObject("SELECT title FROM work_titles WHERE is_primary", String.class)).isEqualTo("[DEMO] 가상의 별빛 정원");
        assertThat(count("creators")).isEqualTo(2);
        // 줄거리·표지는 옮기지 않는다
        assertThat(jdbc.queryForObject("SELECT description FROM works", String.class)).isNull();
        // 변경 기록: 작품·연결·작가·근거와 검수 결정이 같은 사유로
        assertThat(jdbc.queryForList("SELECT DISTINCT entity_type FROM admin_change_log WHERE reason = '[DEMO] 가상 작품 신규 등록'",
                String.class)).contains("WORK", "LISTING", "CREATOR", "STAGING_LISTING");
        assertThat(jdbc.queryForObject("SELECT after_json->>'decision' FROM admin_change_log WHERE entity_type = 'STAGING_LISTING'",
                String.class)).isEqualTo("CREATE_WORK");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_field_sources WHERE source_type = 'STAFF'", Integer.class))
                .isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_field_sources WHERE source_type NOT IN ('STAFF')", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT decision || ':' || staging_revision FROM staging_review_decisions", String.class))
                .isEqualTo("CREATE_WORK:1");
        // 공개 상세에 보인다(공개 등록)
        assertThat(status(new AuthTestClient(mockMvc, objectMapper).get("/api/v1/works/" + workId))).isEqualTo(200);

        // 같은 요청을 다시 보내도 작품·연결이 늘지 않는다
        MvcResult again = admin.postJson("/api/v1/admin/staging-listings/" + id + "/review", request);
        assertThat(code(again)).isEqualTo("STAGING_ALREADY_REVIEWED");
        assertThat(count("works")).isEqualTo(1);
        assertThat(count("work_platform_listings")).isEqualTo(1);
        assertThat(count("staging_review_decisions")).isEqualTo(1);
    }

    @Test
    void adultOrUnknownItemsAreNotRegisteredAsNewPublicWorks() throws Exception {
        long adult = stage("TOOMICS", "demo-0019", "[DEMO] 가상의 밤 이야기", "19세 이용가");
        long unknown = stage("TOOMICS", "demo-0020", "[DEMO] 가상의 표기 없음", null);
        for (long id : List.of(adult, unknown)) {
            MvcResult r = admin.postJson("/api/v1/admin/staging-listings/" + id + "/review",
                    createWork(1, "등록 시도", List.of(newCreator("[DEMO] 가상작가 밤", "STORY")), List.of()));
            assertThat(code(r)).isEqualTo("STAGING_NOT_REVIEWABLE");
        }
        assertThat(count("works")).isZero();
        assertThat(count("creators")).isZero();
        // 기존 작품 연결은 할 수 있다(연결은 공개 응답에 나가지 않음)
        long workId = existingWork("[DEMO] 가상의 밤 이야기", "[DEMO] 가상작가 밤");
        List<Map<String, Object>> judged = judgeAll(adult, workId);
        MvcResult linked = admin.postJson("/api/v1/admin/staging-listings/" + adult + "/review",
                link(1, workId, 0, "성인 판본 연결", judged));
        assertThat(status(linked)).as(body(linked)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT age_class FROM work_platform_listings", String.class)).isEqualTo("ADULT");
    }

    @Test
    void unconfirmedDetailCannotBeLinkedButCanBeHeldOrRejected() throws Exception {
        long id = ingest(ingestService, toomicsRun, listing("TOOMICS", "demo-0404", "[DEMO] 가상 사라진 작품", "전체 이용가",
                CREDITS, "NOT_FOUND", List.of()));
        long workId = existingWork("[DEMO] 가상 다른 작품", "[DEMO] 가상작가 누군가");
        long rev = revision(id);
        assertThat(code(admin.postJson("/api/v1/admin/staging-listings/" + id + "/review", link(rev, workId, 0, "연결", List.of()))))
                .isEqualTo("STAGING_NOT_REVIEWABLE");
        MvcResult hold = admin.postJson("/api/v1/admin/staging-listings/" + id + "/review",
                Map.of("decision", "HOLD", "stagingRevision", rev, "reason", "재수집 뒤 확인"));
        assertThat((String) JsonPath.read(body(hold), "$.data.reviewStatus")).isEqualTo("ON_HOLD");
        MvcResult reject = admin.postJson("/api/v1/admin/staging-listings/" + id + "/review",
                Map.of("decision", "REJECT", "stagingRevision", rev, "reason", "만화넷 대상 아님"));
        assertThat((String) JsonPath.read(body(reject), "$.data.reviewStatus")).isEqualTo("REJECTED");
        assertThat(jdbc.queryForList("SELECT decision FROM staging_review_decisions ORDER BY id", String.class))
                .containsExactly("HOLD", "REJECT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_log WHERE entity_type = 'STAGING_LISTING'", Integer.class))
                .isEqualTo(2);
        assertThat(count("work_platform_listings")).isZero();
    }

    // ---------- 기존 작품 연결·중복 후보 ----------

    @Test
    void linkExistingWorkRequiresExplicitCandidateJudgementAndWorkVersion() throws Exception {
        long workId = existingWork("[DEMO] 가상의 달빛 서점", "[DEMO] 가상작가 하늘");
        long id = stage("MRBLUE", "demo-m001", "[DEMO] 가상의 달빛 서점", "전체 이용가");

        MvcResult generated = admin.postJson("/api/v1/admin/staging-listings/" + id + "/candidates", Map.of());
        assertThat((String) JsonPath.read(body(generated), "$.data.reviewStatus")).isEqualTo("CANDIDATES");
        List<Integer> ids = JsonPath.read(body(generated), "$.data.candidates[*].id");
        assertThat(ids).hasSize(1);
        assertThat((List<String>) JsonPath.read(body(generated), "$.data.candidates[0].reasons"))
                .containsExactly("TITLE_MATCH", "CREATOR_OVERLAP");
        assertThat(((Number) JsonPath.read(body(generated), "$.data.candidates[0].workId")).longValue()).isEqualTo(workId);
        // 다시 만들어도 후보가 늘지 않고 ID 가 같다
        MvcResult again = admin.postJson("/api/v1/admin/staging-listings/" + id + "/candidates", Map.of());
        assertThat((List<Integer>) JsonPath.read(body(again), "$.data.candidates[*].id")).isEqualTo(ids);
        assertThat(count("staging_match_candidates")).isEqualTo(1);

        String path = "/api/v1/admin/staging-listings/" + id + "/review";
        assertThat(code(admin.postJson(path, link(1, workId, 0, "가상 작품 연결", List.of()))))
                .isEqualTo("STAGING_REVIEW_CANDIDATES_UNRESOLVED");
        List<Map<String, Object>> same = List.of(judge(ids.get(0), "SAME_WORK"));
        assertThat(code(admin.postJson(path, link(1, workId, 7, "가상 작품 연결", same)))).isEqualTo("WORK_VERSION_CONFLICT");
        assertThat(fields(admin.postJson(path, link(1, workId, 0, "가상 작품 연결", List.of(judge(ids.get(0), "DIFFERENT"))))))
                .anyMatch(f -> f.startsWith("candidates"));
        // 실패한 시도는 아무것도 남기지 않았다
        assertThat(count("work_platform_listings")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM staging_match_candidates", String.class)).isEqualTo("OPEN");

        MvcResult linked = admin.postJson(path, link(1, workId, 0, "가상 작품 연결", same));
        assertThat(status(linked)).as(body(linked)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(linked), "$.data.reviewStatus")).isEqualTo("LINKED");
        assertThat(jdbc.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, workId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM staging_match_candidates", String.class)).isEqualTo("ACCEPTED");
        assertThat(count("works")).isEqualTo(1);
    }

    @Test
    void sameTitleIsNeverMergedAutomaticallyAndHomonymsNeedConfirmation() throws Exception {
        long existing = existingWork("[DEMO] 가상의 여름 일기", "[DEMO] 가상작가 다른이");
        long existingCreator = jdbc.queryForObject("SELECT id FROM creators WHERE name = '[DEMO] 가상작가 다른이'", Long.class);
        long id = stage("TOOMICS", "demo-0002", "[DEMO] 가상의 여름 일기 시즌2", "15세 이용가");
        Map<String, Object> view = candidates(id);
        List<Integer> ids = JsonPath.read(view, "$.candidates[*].id");
        assertThat(ids).hasSize(1);
        assertThat((List<String>) JsonPath.read(view, "$.candidates[0].warnings")).contains("DIFFERENT_CREATORS", "SEASON");

        String path = "/api/v1/admin/staging-listings/" + id + "/review";
        // 같은 작품이라고 판단하면서 새 작품을 만들 수 없다
        assertThat(status(admin.postJson(path, createWork(1, "새 작품", List.of(newCreator("[DEMO] 가상작가 하늘", "STORY")),
                List.of(judge(ids.get(0), "SAME_WORK")))))).isEqualTo(400);
        // 이름이 같은 작가를 새로 만들려면 동명이인 확인이 필요하다
        MvcResult homonym = admin.postJson(path, createWork(1, "새 작품", List.of(newCreator("[DEMO] 가상작가 다른이", "STORY")),
                List.of(judge(ids.get(0), "DIFFERENT"))));
        assertThat(fields(homonym)).contains("work.credits[0].newCreatorName");
        assertThat(count("creators")).isEqualTo(1);

        Map<String, Object> confirmed = new java.util.LinkedHashMap<>(newCreator("[DEMO] 가상작가 다른이", "STORY"));
        confirmed.put("sameNameConfirmed", true);
        MvcResult created = admin.postJson(path, createWork(1, "제목은 같지만 다른 작품",
                List.of(confirmed, existingCreator(existingCreator, "ART")), List.of(judge(ids.get(0), "DIFFERENT"))));
        assertThat(status(created)).as(body(created)).isEqualTo(200);
        assertThat(count("works")).isEqualTo(2);
        assertThat(count("creators")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM staging_match_candidates", String.class)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_platform_listings WHERE work_id = ?", Integer.class, existing)).isZero();
    }

    @Test
    void identityKeyConflictsAreExplicit() throws Exception {
        long workA = existingWork("[DEMO] 가상의 푸른 탑", "[DEMO] 가상작가 탑");
        addListing(workA, "TOOMICS", "demo-0100");
        long workB = existingWork("[DEMO] 가상의 붉은 탑", "[DEMO] 가상작가 붉은");
        addListing(workB, "TOOMICS", "demo-0300");

        // 같은 플랫폼 작품 키가 이미 작품 A 에 연결됨
        long same = stage("TOOMICS", "demo-0100", "[DEMO] 가상의 푸른 탑", "전체 이용가");
        Map<String, Object> view = candidates(same);
        assertThat((List<String>) JsonPath.read(view, "$.candidates[0].reasons")).contains("LISTING_KEY");
        int candidate = JsonPath.read(view, "$.candidates[0].id");
        String path = "/api/v1/admin/staging-listings/" + same + "/review";
        MvcResult conflict = admin.postJson(path, createWork(1, "새 작품", List.of(newCreator("[DEMO] 가상작가 새", "STORY")),
                List.of(judge(candidate, "DIFFERENT"))));
        assertThat(code(conflict)).isEqualTo("PLATFORM_LISTING_ALREADY_LINKED");
        assertThat(((Number) JsonPath.read(body(conflict), "$.error.existingWorkId")).longValue()).isEqualTo(workA);
        assertThat(count("works")).isEqualTo(2);
        assertThat(count("creators")).isEqualTo(2);
        MvcResult wrongWork = admin.postJson(path, link(1, workB, 1, "다른 작품에 연결", List.of(judge(candidate, "DIFFERENT"))));
        assertThat(code(wrongWork)).isEqualTo("PLATFORM_LISTING_ALREADY_LINKED");
        MvcResult linked = admin.postJson(path, link(1, workA, 1, "이미 연결된 작품 확인", List.of(judge(candidate, "SAME_WORK"))));
        assertThat(status(linked)).as(body(linked)).isEqualTo(200);
        assertThat(count("work_platform_listings")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, workA)).isEqualTo(1);

        // 별칭 ID 가 다른 작품의 연결 키와 겹침
        long aliased = ingest(ingestService, toomicsRun, listing("TOOMICS", "demo-0301", "[DEMO] 가상의 붉은 탑 외전", "전체 이용가",
                CREDITS, "OK", List.of("demo-0300")));
        Map<String, Object> aliasView = candidates(aliased);
        List<Map<String, Object>> judged = new ArrayList<>();
        for (Integer cid : (List<Integer>) JsonPath.read(aliasView, "$.candidates[*].id")) {
            judged.add(judge(cid, "DIFFERENT"));
        }
        MvcResult aliasConflict = admin.postJson("/api/v1/admin/staging-listings/" + aliased + "/review",
                createWork(revision(aliased), "새 작품", List.of(newCreator("[DEMO] 가상작가 새", "STORY")), judged));
        assertThat(code(aliasConflict)).isEqualTo("PLATFORM_LISTING_ALREADY_LINKED");
        assertThat(((Number) JsonPath.read(body(aliasConflict), "$.error.existingWorkId")).longValue()).isEqualTo(workB);
    }

    @Test
    void samePlatformWorkFromTwoPlatformsEndsInOneWork() throws Exception {
        long a = stage("TOOMICS", "demo-0007", "[DEMO] 가상의 시계탑", "12세 이용가");
        long b = stage("MRBLUE", "demo-m007", "[DEMO] 가상의 시계탑", "12세 이용가");
        Map<String, Object> viewA = candidates(a);
        assertThat((Object) JsonPath.read(viewA, "$.candidates[0].otherStagingId")).isEqualTo((int) b);
        int pair = JsonPath.read(viewA, "$.candidates[0].id");
        MvcResult created = admin.postJson("/api/v1/admin/staging-listings/" + a + "/review", createWork(1, "가상 작품 신규",
                List.of(newCreator("[DEMO] 가상작가 하늘", "STORY"), newCreator("[DEMO] 가상작가 바다", "ART")),
                List.of(judge(pair, "SAME_WORK"))));
        assertThat(status(created)).as(body(created)).isEqualTo(200);
        long workId = jdbc.queryForObject("SELECT id FROM works", Long.class);

        // B: 보류했다가 다시 검수해 같은 작품에 연결
        assertThat(status(admin.postJson("/api/v1/admin/staging-listings/" + b + "/review",
                Map.of("decision", "HOLD", "stagingRevision", 1, "reason", "작가 확인 중")))).isEqualTo(200);
        Map<String, Object> viewB = candidates(b);
        List<Map<String, Object>> openB = JsonPath.read(viewB, "$.candidates[?(@.status == 'OPEN')]");
        assertThat(openB).hasSize(1);
        assertThat(((Number) openB.get(0).get("workId")).longValue()).isEqualTo(workId);
        long version = jdbc.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, workId);
        MvcResult linked = admin.postJson("/api/v1/admin/staging-listings/" + b + "/review", link(1, workId, version, "다른 플랫폼 같은 작품",
                List.of(judge(((Number) openB.get(0).get("id")).longValue(), "SAME_WORK"))));
        assertThat(status(linked)).as(body(linked)).isEqualTo(200);
        assertThat(count("works")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT platform_code FROM work_platform_listings WHERE work_id = ? ORDER BY platform_code",
                String.class, workId)).containsExactly("MRBLUE", "TOOMICS");
        assertThat(jdbc.queryForObject("SELECT status FROM staging_match_candidates WHERE other_staging_id IS NOT NULL", String.class))
                .isEqualTo("ACCEPTED");
    }

    // ---------- 자동 STALE 재등장 (#66) ----------

    @Test
    void staleWorkCandidateReopensButExplicitRejectionStays() throws Exception {
        long workId = existingWork("[DEMO] 가상의 재등장 서점", "[DEMO] 가상작가 재등장");
        long id = stage("TOOMICS", "demo-re-w", "[DEMO] 가상의 재등장 서점", "전체 이용가");
        long candidateId = onlyCandidate(id);
        assertOpen(candidateId);

        retitle("TOOMICS", "demo-re-w", "[DEMO] 가상의 재등장 서점 임시");
        candidates(id);
        assertStale(candidateId);

        retitle("TOOMICS", "demo-re-w", "[DEMO] 가상의 재등장 서점");
        candidates(id);
        assertOpen(candidateId);
        assertThat(count("staging_match_candidates")).isEqualTo(1);

        String path = "/api/v1/admin/staging-listings/" + id + "/review";
        long revision = revision(id);
        assertThat(code(admin.postJson(path, link(revision, workId, 0, "[DEMO] 판단 없이 연결", List.of()))))
                .isEqualTo("STAGING_REVIEW_CANDIDATES_UNRESOLVED");
        assertThat(jdbc.queryForObject("SELECT review_status FROM staging_listings WHERE id = ?", String.class, id))
                .isEqualTo("CANDIDATES");

        MvcResult linked = admin.postJson(path, link(revision, workId, 0, "[DEMO] 다시 판단 후 연결",
                List.of(judge(candidateId, "SAME_WORK"))));
        assertThat(status(linked)).as(body(linked)).isEqualTo(200);
        assertJudged(candidateId, "ACCEPTED");

        long other = existingWork("[DEMO] 가상의 명시 거절 서점", "[DEMO] 가상작가 명시");
        long explicit = stage("MRBLUE", "demo-re-x", "[DEMO] 가상의 명시 거절 서점", "전체 이용가");
        long explicitCandidate = onlyCandidate(explicit);
        MvcResult created = admin.postJson("/api/v1/admin/staging-listings/" + explicit + "/review",
                createWork(revision(explicit), "[DEMO] 다른 작품으로 등록",
                        List.of(newCreator("[DEMO] 가상작가 새재등장", "STORY")), List.of(judge(explicitCandidate, "DIFFERENT"))));
        assertThat(status(created)).as(body(created)).isEqualTo(200);
        assertJudged(explicitCandidate, "REJECTED");
        java.sql.Timestamp decidedAt = jdbc.queryForObject(
                "SELECT decided_at FROM staging_match_candidates WHERE id = ?", java.sql.Timestamp.class, explicitCandidate);

        retitle("MRBLUE", "demo-re-x", "[DEMO] 가상의 명시 거절 서점 임시");
        candidates(explicit);
        retitle("MRBLUE", "demo-re-x", "[DEMO] 가상의 명시 거절 서점");
        candidates(explicit);
        assertJudged(explicitCandidate, "REJECTED");
        assertThat(jdbc.queryForObject("SELECT decided_at FROM staging_match_candidates WHERE id = ?", java.sql.Timestamp.class,
                explicitCandidate)).isEqualTo(decidedAt);
        assertThat(jdbc.queryForObject("SELECT warnings::text FROM staging_match_candidates WHERE id = ?", String.class,
                explicitCandidate)).doesNotContain("STALE");
        assertThat(jdbc.queryForObject("SELECT id FROM works WHERE id = ?", Long.class, other)).isEqualTo(other);
    }

    @Test
    void stalePairCandidateReopensButExplicitRejectionStays() throws Exception {
        long left = stage("TOOMICS", "demo-re-p1", "[DEMO] 가상의 쌍 재등장", "전체 이용가");
        long right = stage("MRBLUE", "demo-re-p2", "[DEMO] 가상의 쌍 재등장", "전체 이용가");
        long pairId = onlyCandidate(left);
        assertThat(jdbc.queryForObject("SELECT other_staging_id FROM staging_match_candidates WHERE id = ?", Long.class, pairId))
                .isEqualTo(right);
        assertOpen(pairId);

        retitle("TOOMICS", "demo-re-p1", "[DEMO] 가상의 쌍 재등장 임시");
        candidates(left);
        assertStale(pairId);

        retitle("TOOMICS", "demo-re-p1", "[DEMO] 가상의 쌍 재등장");
        candidates(left);
        assertOpen(pairId);
        assertThat(count("staging_match_candidates")).isEqualTo(1);

        String path = "/api/v1/admin/staging-listings/" + left + "/review";
        long revision = revision(left);
        assertThat(code(admin.postJson(path, createWork(revision, "[DEMO] 판단 없이 등록",
                List.of(newCreator("[DEMO] 가상작가 쌍", "STORY")), List.of())))).isEqualTo("STAGING_REVIEW_CANDIDATES_UNRESOLVED");
        assertThat(count("works")).isZero();

        MvcResult created = admin.postJson(path, createWork(revision, "[DEMO] 다시 판단 후 등록",
                List.of(newCreator("[DEMO] 가상작가 쌍", "STORY")), List.of(judge(pairId, "DIFFERENT"))));
        assertThat(status(created)).as(body(created)).isEqualTo(200);
        assertJudged(pairId, "REJECTED");

        long a = stage("TOOMICS", "demo-re-e1", "[DEMO] 가상의 쌍 명시 거절", "전체 이용가");
        long b = stage("MRBLUE", "demo-re-e2", "[DEMO] 가상의 쌍 명시 거절", "전체 이용가");
        long explicitPair = onlyCandidate(a);
        MvcResult explicit = admin.postJson("/api/v1/admin/staging-listings/" + a + "/review",
                createWork(revision(a), "[DEMO] 다른 수집 항목",
                        List.of(newCreator("[DEMO] 가상작가 쌍명시", "STORY")), List.of(judge(explicitPair, "DIFFERENT"))));
        assertThat(status(explicit)).as(body(explicit)).isEqualTo(200);
        assertJudged(explicitPair, "REJECTED");
        Long decidedBy = jdbc.queryForObject("SELECT decided_by FROM staging_match_candidates WHERE id = ?", Long.class, explicitPair);

        retitle("TOOMICS", "demo-re-e1", "[DEMO] 가상의 쌍 명시 거절 임시");
        candidates(a);
        retitle("TOOMICS", "demo-re-e1", "[DEMO] 가상의 쌍 명시 거절");
        candidates(a);
        assertJudged(explicitPair, "REJECTED");
        assertThat(jdbc.queryForObject("SELECT decided_by FROM staging_match_candidates WHERE id = ?", Long.class, explicitPair))
                .isEqualTo(decidedBy);
        assertThat(jdbc.queryForObject("SELECT other_staging_id FROM staging_match_candidates WHERE id = ?", Long.class, explicitPair))
                .isEqualTo(b);
        assertThat(jdbc.queryForObject("SELECT warnings::text FROM staging_match_candidates WHERE id = ?", String.class, explicitPair))
                .doesNotContain("STALE");
    }

    // ---------- 재수집 충돌 ----------

    @Test
    void recollectionDuringReviewIsDetected() throws Exception {
        long id = stage("TOOMICS", "demo-0005", "[DEMO] 가상의 겨울 정류장", "전체 이용가");
        long seen = revision(id);
        // 같은 내용을 다시 수집하면 revision 이 그대로
        ingest(ingestService, toomicsRun, listing("TOOMICS", "demo-0005", "[DEMO] 가상의 겨울 정류장", "전체 이용가", CREDITS));
        assertThat(revision(id)).isEqualTo(seen);
        // 검수하는 동안 제목이 바뀌어 다시 수집됨
        ingest(ingestService, toomicsRun, listing("TOOMICS", "demo-0005", "[DEMO] 가상의 겨울 정류장 (개정)", "전체 이용가", CREDITS));
        assertThat(revision(id)).isEqualTo(seen + 1);

        Map<String, Object> request = createWork(seen, "본 내용으로 등록", List.of(newCreator("[DEMO] 가상작가 하늘", "STORY")),
                List.of());
        MvcResult stale = admin.postJson("/api/v1/admin/staging-listings/" + id + "/review", request);
        assertThat(code(stale)).isEqualTo("STAGING_REVISION_CONFLICT");
        assertThat(((Number) JsonPath.read(body(stale), "$.error.currentVersion")).longValue()).isEqualTo(seen + 1);
        assertThat(count("works")).isZero();
        assertThat(count("creators")).isZero();

        // 판단한 뒤 같은 제목의 작품이 새로 생기면 열린 후보가 바뀌어 다시 판단해야 한다
        existingWork("[DEMO] 가상의 겨울 정류장 (개정)", "[DEMO] 가상작가 하늘");
        request.put("stagingRevision", seen + 1);
        assertThat(code(admin.postJson("/api/v1/admin/staging-listings/" + id + "/review", request)))
                .isEqualTo("STAGING_REVIEW_CANDIDATES_UNRESOLVED");
        assertThat(count("works")).isEqualTo(1);
    }

    // ---------- 동시 승인·롤백 ----------

    @Test
    void concurrentApprovalsCreateOneWorkAndOneListing() throws Exception {
        for (int round = 0; round < 5; round++) {
            String pid = "demo-c" + round;
            long id = stage("TOOMICS", pid, "[DEMO] 가상 동시 승인 " + round, "전체 이용가");
            StagingReviewRequest request = objectMapper.convertValue(createWork(1, "동시 승인 " + round,
                    List.of(newCreator("[DEMO] 가상작가 동시" + round, "STORY")), List.of()), StagingReviewRequest.class);
            CyclicBarrier barrier = new CyclicBarrier(3);
            List<Future<String>> results = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                results.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        reviewService.review(id, request, adminId);
                        return "OK";
                    } catch (BusinessException e) {
                        return e.getErrorCode().name();
                    }
                }));
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).as("round " + round).containsOnlyOnce("OK");
            assertThat(outcomes.stream().filter(o -> !o.equals("OK"))).containsOnly("STAGING_ALREADY_REVIEWED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_platform_listings WHERE platform_work_id = ?", Integer.class, pid))
                    .isEqualTo(1);
        }
        assertThat(count("works")).isEqualTo(5);
        assertThat(count("creators")).isEqualTo(5);
        assertThat(count("staging_review_decisions")).isEqualTo(5);
    }

    @Test
    void failureAfterRegistrationRollsEverythingBack() throws Exception {
        long id = stage("TOOMICS", "demo-0009", "[DEMO] 가상 롤백 작품", "전체 이용가");
        long workId = existingWork("[DEMO] 가상 롤백 대상", "[DEMO] 가상작가 롤백");
        Map<String, Integer> before = counts();
        jdbc.execute("CREATE FUNCTION test_fail_review() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'injected review failure'; END $$");
        jdbc.execute("CREATE TRIGGER trg_test_fail_review BEFORE INSERT ON staging_review_decisions "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_review()");

        StagingReviewRequest create = objectMapper.convertValue(createWork(1, "롤백 확인",
                List.of(newCreator("[DEMO] 가상작가 새사람", "STORY")), List.of()), StagingReviewRequest.class);
        assertThatThrownBy(() -> reviewService.review(id, create, adminId)).hasMessageContaining("injected review failure");
        StagingReviewRequest linkReq = objectMapper.convertValue(link(1, workId, 0, "롤백 확인", List.of()), StagingReviewRequest.class);
        assertThatThrownBy(() -> reviewService.review(id, linkReq, adminId)).hasMessageContaining("injected review failure");

        assertThat(counts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT review_status || ':' || COALESCE(linked_listing_id::text, '-') FROM staging_listings "
                + "WHERE id = ?", String.class, id)).isEqualTo("NEW:-");
        assertThat(jdbc.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, workId)).isZero();
    }

    // ---------- 도우미 ----------

    private long stage(String platform, String id, String title, String ageRaw) {
        long run = platform.equals("TOOMICS") ? toomicsRun : mrblueRun;
        return ingest(ingestService, run, listing(platform, id, title, ageRaw, CREDITS));
    }

    private long revision(long stagingId) {
        return jdbc.queryForObject("SELECT revision FROM staging_listings WHERE id = ?", Long.class, stagingId);
    }

    /** 기존 공개 작품(가상). 기존 관리자 서비스로 만든다 */
    private long existingWork(String title, String creatorName) {
        long creatorId = creatorService.register(new CreateCreatorRequest(creatorName, null, null, "[DEMO] 테스트 준비"), adminId).id();
        return workService.register(new CreateWorkRequest("WEBTOON", "KR", null,
                List.of(new CreateWorkRequest.TitleRequest(title, "ko", true)),
                List.of(new CreateWorkRequest.CreditRequest(creatorId, "STORY")), null, null, null,
                objectMapper.convertValue(List.of(ReviewFixtures.staff("TITLE"), ReviewFixtures.staff("CREDIT")),
                        objectMapper.getTypeFactory().constructCollectionType(List.class,
                                com.manhwanet.provenance.dto.EvidenceRequest.class)),
                "[DEMO] 테스트 준비"), adminId).id();
    }

    private void addListing(long workId, String platform, String platformWorkId) throws Exception {
        long version = jdbc.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, workId);
        Map<String, Object> body = new java.util.LinkedHashMap<>(ReviewFixtures.listingOptions());
        body.put("version", version);
        body.put("platformCode", platform);
        body.put("platformWorkId", platformWorkId);
        body.put("url", ReviewFixtures.url(platform, platformWorkId));
        body.put("idScope", "SERIES");
        body.put("languageCode", "ko");
        body.put("verifiedAt", "2026-10-15T00:00:00Z");
        MvcResult r = admin.postJson("/api/v1/admin/works/" + workId + "/listings", body);
        assertThat(status(r)).as(body(r)).isEqualTo(201);
    }

    private void retitle(String platform, String platformWorkId, String title) {
        long run = platform.equals("TOOMICS") ? toomicsRun : mrblueRun;
        ingest(ingestService, run, listing(platform, platformWorkId, title, "전체 이용가", CREDITS));
    }

    private long onlyCandidate(long stagingId) throws Exception {
        List<Integer> ids = JsonPath.read(candidates(stagingId), "$.candidates[*].id");
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    private void assertOpen(long candidateId) {
        assertThat(jdbc.queryForObject("SELECT status FROM staging_match_candidates WHERE id = ?", String.class, candidateId))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT decided_by FROM staging_match_candidates WHERE id = ?", Long.class, candidateId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT decided_at FROM staging_match_candidates WHERE id = ?", java.sql.Timestamp.class,
                candidateId)).isNull();
        assertThat(jdbc.queryForObject("SELECT warnings::text FROM staging_match_candidates WHERE id = ?", String.class, candidateId))
                .doesNotContain("STALE");
    }

    private void assertStale(long candidateId) {
        assertThat(jdbc.queryForObject("SELECT status FROM staging_match_candidates WHERE id = ?", String.class, candidateId))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT decided_by FROM staging_match_candidates WHERE id = ?", Long.class, candidateId))
                .isEqualTo(adminId);
        assertThat(jdbc.queryForObject("SELECT decided_at FROM staging_match_candidates WHERE id = ?", java.sql.Timestamp.class,
                candidateId)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT warnings::text FROM staging_match_candidates WHERE id = ?", String.class, candidateId))
                .contains("STALE");
    }

    private void assertJudged(long candidateId, String status) {
        assertThat(jdbc.queryForObject("SELECT status FROM staging_match_candidates WHERE id = ?", String.class, candidateId))
                .isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT decided_by FROM staging_match_candidates WHERE id = ?", Long.class, candidateId))
                .isEqualTo(adminId);
        assertThat(jdbc.queryForObject("SELECT decided_at FROM staging_match_candidates WHERE id = ?", java.sql.Timestamp.class,
                candidateId)).isNotNull();
    }

    private Map<String, Object> candidates(long stagingId) throws Exception {
        MvcResult r = admin.postJson("/api/v1/admin/staging-listings/" + stagingId + "/candidates", Map.of());
        assertThat(status(r)).as(body(r)).isEqualTo(200);
        return JsonPath.read(body(r), "$.data");
    }

    private List<Map<String, Object>> judgeAll(long stagingId, long sameWorkId) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> list = JsonPath.read(candidates(stagingId), "$.candidates");
        for (Map<String, Object> c : list) {
            boolean same = c.get("workId") != null && ((Number) c.get("workId")).longValue() == sameWorkId;
            out.add(judge(((Number) c.get("id")).longValue(), same ? "SAME_WORK" : "DIFFERENT"));
        }
        return out;
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        for (String t : List.of("works", "work_titles", "work_creators", "creators", "work_platform_listings", "work_field_sources",
                "admin_change_log", "staging_review_decisions", "staging_match_candidates")) {
            out.put(t, count(t));
        }
        return out;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static String code(MvcResult r) throws Exception {
        return JsonPath.read(body(r), "$.error.code");
    }

    private static List<String> fields(MvcResult r) throws Exception {
        return JsonPath.read(body(r), "$.error.fieldErrors[*].field");
    }
}
