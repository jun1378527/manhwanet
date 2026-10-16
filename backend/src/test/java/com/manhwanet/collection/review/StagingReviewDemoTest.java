package com.manhwanet.collection.review;

import static com.manhwanet.collection.review.ReviewFixtures.createWork;
import static com.manhwanet.collection.review.ReviewFixtures.judge;
import static com.manhwanet.collection.review.ReviewFixtures.link;
import static com.manhwanet.collection.review.ReviewFixtures.newCreator;
import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.collection.collector.CollectorTestConfig;
import com.manhwanet.collection.collector.LoopbackNetwork;
import com.manhwanet.collection.collector.MockSite;
import com.manhwanet.collection.collector.VirtualClock;
import com.manhwanet.collection.collector.core.CollectorEngine;
import com.manhwanet.collection.collector.core.RunReport;
import com.manhwanet.collection.domain.RunKind;
import com.manhwanet.collection.domain.RunStatus;
import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.dto.CreateCreatorRequest;
import com.manhwanet.provenance.dto.EvidenceRequest;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.CreateWorkRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * #51 포트폴리오 시연: 로컬 모의 수집기 → staging → 관리자 검수 → 기존 작품 연결·새 작품 등록·보류·재수집 충돌.
 * <p><b>모든 데이터는 가상이다.</b> 제목·작가에 [DEMO] 를 붙였고, 플랫폼 주소는 해석되지 않는 *.mock.test 이며,
 * 요청은 127.0.0.1 의 모의 HTTP 서버로만 간다. 격리 테스트 DB 에서만 실행한다(개발 DB 사용 안 함).
 * 실행 기록은 build/reports/demo/staging-review-demo.md 에 남는다.
 * <pre>
 * cd backend
 * .\gradlew.bat test --tests "*StagingReviewDemoTest*"
 * </pre>
 */
@SpringBootTest(properties = {
        "manhwanet.collector.contact-url=https://manhwanet.test/collector",
        "manhwanet.collector.read-timeout=1s",
        "manhwanet.collector.total-timeout=2s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({CollectorTestConfig.class, StagingReviewDemoTest.DemoConfig.class})
class StagingReviewDemoTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class DemoConfig {
        @Bean
        DemoToomicsAdapter demoToomicsAdapter() {
            return new DemoToomicsAdapter();
        }
    }

    private static final String B = DemoToomicsAdapter.BASE;

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
    private CollectorEngine engine;
    @Autowired
    private LoopbackNetwork net;
    @Autowired
    private VirtualClock clock;
    @Autowired
    private WorkService workService;
    @Autowired
    private CreatorService creatorService;

    private MockSite site;
    private AuthTestClient admin;
    private long adminId;
    private final StringBuilder log = new StringBuilder();

    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseCleaner.clean(jdbc, environment);
        adminAccountService.createAdmin("demo-admin@manhwanet.test", STRONG_PASSWORD, "시연 관리자");
        admin = new AuthTestClient(mockMvc, objectMapper);
        assertThat(admin.login("demo-admin@manhwanet.test", STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'demo-admin@manhwanet.test'", Long.class);
        ReviewFixtures.preparePlatforms(jdbc, adminId);
        clock.reset();
        site = new MockSite(clock::millis);
        net.reset(site.port());
        net.map(ReviewFixtures.TOOMICS_HOST, "127.0.0.1");
        clock.onSleep(ms -> {
            jdbc.update("UPDATE crawl_tasks SET next_attempt_at = next_attempt_at - make_interval(secs => ?) WHERE status = 'PENDING'",
                    ms / 1000.0);
            jdbc.update("UPDATE crawl_runs SET next_request_at = next_request_at - make_interval(secs => ?) "
                    + "WHERE next_request_at IS NOT NULL", ms / 1000.0);
            jdbc.update("UPDATE platform_collection_policies SET next_request_at = next_request_at - make_interval(secs => ?) "
                    + "WHERE next_request_at IS NOT NULL", ms / 1000.0);
        });
        site.ok("/robots.txt", "User-agent: *\nAllow: /\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        site.close();
        clock.reset();
        Path out = Path.of("build", "reports", "demo", "staging-review-demo.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, log.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void collectReviewLinkCreateHoldAndDetectRecollection() throws Exception {
        say("# staging 검수·숨김 등록·공개 시연 (#51·#41)\n");
        say("> **[DEMO] 모든 작품·작가·주소는 가상 데이터입니다. 실제 작품 정보가 아닙니다.** 요청은 127.0.0.1 모의 서버로만 갔고,"
                + " 격리 테스트 DB 에서 실행했습니다. 실행 시각 " + Instant.now() + "\n");

        long existingWork = existingWork("[DEMO] 가상의 달빛 서점", "[DEMO] 가상작가 하늘");
        say("1. 기존 공개 작품 준비: #" + existingWork + " `[DEMO] 가상의 달빛 서점` (작가 `[DEMO] 가상작가 하늘`)");

        site.ok("/demo-sitemap.xml", "detail " + B + "/demo/demo-1001\ndetail " + B + "/demo/demo-1002\ndetail " + B
                + "/demo/demo-1003\n");
        site.ok("/demo/demo-1001", "title=[DEMO] 가상의 달빛 서점\nage=전체 이용가\nstory=[DEMO] 가상작가 하늘\nart=[DEMO] 가상작가 바다\n");
        site.ok("/demo/demo-1002", "title=[DEMO] 가상의 별빛 정원\nage=15세 이용가\nstory=[DEMO] 가상작가 구름\n");
        site.ok("/demo/demo-1003", "title=[DEMO] 가상의 밤 이야기\nage=19세 이용가\nstory=[DEMO] 가상작가 밤\n");
        RunReport first = engine.start("TOOMICS", RunKind.FULL, "demo-collector");
        assertThat(first.status()).isEqualTo(RunStatus.COMPLETED);
        say("2. 모의 수집기 실행 #" + first.runId() + ": " + first.status() + ", 요청 " + site.requests().size()
                + "건(robots.txt 먼저, 5초 간격) → staging " + count("staging_listings") + "건");
        assertThat(count("works")).isEqualTo(1);
        say("   - 수집만으로는 공개 작품이 생기지 않음: works " + count("works") + "개");

        // 3. 기존 작품과 같은 작품 → 후보 확인 뒤 연결
        long s1001 = staging("demo-1001");
        Map<String, Object> view = postCandidates(s1001);
        List<Map<String, Object>> cands = JsonPath.read(view, "$.candidates");
        say("3. `demo-1001` 후보: " + describe(cands));
        long version = jdbc.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, existingWork);
        MvcResult linked = admin.postJson(review(s1001), link(rev(view), existingWork, version, "[DEMO] 같은 작품 확인(제목·작가 일치)",
                List.of(judge(((Number) cands.get(0).get("id")).longValue(), "SAME_WORK"))));
        assertThat(status(linked)).as(body(linked)).isEqualTo(200);
        say("   - LINK_EXISTING → " + JsonPath.read(body(linked), "$.data.reviewStatus") + ", 작품 #" + existingWork + " 에 연결 추가");

        // 4. 새 작품 등록
        long s1002 = staging("demo-1002");
        Map<String, Object> view2 = postCandidates(s1002);
        MvcResult created = admin.postJson(review(s1002), createWork(rev(view2), "[DEMO] 새 가상 작품 등록",
                List.of(newCreator("[DEMO] 가상작가 구름", "STORY")), List.of()));
        assertThat(status(created)).as(body(created)).isEqualTo(200);
        long newWork = jdbc.queryForObject("SELECT work_id FROM work_platform_listings WHERE platform_work_id = 'demo-1002'", Long.class);
        say("4. `demo-1002` 후보 없음 → CREATE_WORK → 새 작품 #" + newWork + " 를 처음부터 **HIDDEN** 으로 등록 (작가 새로 등록, 근거는 검수자가 STAFF 로 기록)");
        MvcResult again = admin.postJson(review(s1002), createWork(rev(view2), "[DEMO] 다시 승인",
                List.of(newCreator("[DEMO] 가상작가 구름", "STORY")), List.of()));
        say("   - 같은 승인을 다시 보냄 → " + status(again) + " " + code(again) + " (작품 수 그대로 " + count("works") + "개)");
        AuthTestClient visitor = new AuthTestClient(mockMvc, objectMapper);
        AuthTestClient reader = new AuthTestClient(mockMvc, objectMapper);
        reader.signup("demo-reader@manhwanet.test", STRONG_PASSWORD, "시연독자");
        reader.withHeader("X-Expected-User-Id", String.valueOf(
                jdbc.queryForObject("SELECT id FROM users WHERE email = 'demo-reader@manhwanet.test'", Long.class)));
        assertThat(code(visitor.get("/api/v1/works/" + newWork))).isEqualTo("WORK_NOT_FOUND");
        MvcResult earlyRating = reader.putRaw("/api/v1/me/ratings/" + newWork, "{\"score\": 4.5}");
        assertThat(code(earlyRating)).isEqualTo("WORK_NOT_FOUND");
        say("   - 숨김 상태: 공개 상세 " + status(visitor.get("/api/v1/works/" + newWork)) + ", 공개 목록 "
                + publicTitles(visitor) + ", 독자 별점 등록 " + status(earlyRating) + " " + code(earlyRating) + " (없는 작품과 같음)");
        long hiddenVersion = jdbc.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, newWork);
        MvcResult published = admin.postJson("/api/v1/admin/works/" + newWork + "/visibility",
                Map.of("version", hiddenVersion, "visibility", "PUBLIC", "reason", "[DEMO] 검수 완료, 15세 이용가 확인 뒤 공개"));
        assertThat(status(published)).as(body(published)).isEqualTo(200);
        say("   - 관리자가 명시적으로 PUBLIC 전환 → " + JsonPath.read(body(published), "$.data.visibility") + " (version "
                + JsonPath.read(body(published), "$.data.version") + ", 변경 기록·사유 남김)");
        assertThat(status(reader.putRaw("/api/v1/me/ratings/" + newWork, "{\"score\": 4.5}"))).isEqualTo(200);
        MvcResult written = reader.postJson("/api/v1/me/reviews/" + newWork,
                Map.of("body", "[DEMO] 가상 작품에 남긴 시연용 리뷰입니다.", "containsSpoiler", false));
        assertThat(status(written)).as(body(written)).isIn(200, 201);
        say("   - 공개 뒤: 공개 상세 " + status(visitor.get("/api/v1/works/" + newWork)) + ", 독자 별점 4.5·리뷰 작성 → 공개 리뷰 "
                + JsonPath.read(body(visitor.get("/api/v1/works/" + newWork + "/reviews")), "$.data.totalElements") + "건, 별점 집계 "
                + JsonPath.read(body(visitor.get("/api/v1/works/" + newWork)), "$.data.rating"));
        long publicVersion = jdbc.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, newWork);
        admin.postJson("/api/v1/admin/works/" + newWork + "/visibility",
                Map.of("version", publicVersion, "visibility", "HIDDEN", "reason", "[DEMO] 숨김 동작 확인"));
        say("   - 다시 HIDDEN: 공개 상세 " + status(visitor.get("/api/v1/works/" + newWork)) + ", 공개 리뷰 목록 "
                + status(visitor.get("/api/v1/works/" + newWork + "/reviews")) + ", 독자 본인 별점 조회 "
                + status(reader.get("/api/v1/me/ratings/" + newWork)) + "(본인 기록 유지), 별점·리뷰 행 "
                + jdbc.queryForObject("SELECT COUNT(*) FROM work_ratings WHERE work_id = ?", Integer.class, newWork) + "·"
                + jdbc.queryForObject("SELECT COUNT(*) FROM work_reviews WHERE work_id = ?", Integer.class, newWork) + " 보존");
        long hiddenAgain = jdbc.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, newWork);
        admin.postJson("/api/v1/admin/works/" + newWork + "/visibility",
                Map.of("version", hiddenAgain, "visibility", "PUBLIC", "reason", "[DEMO] 다시 공개"));
        assertThat((Object) JsonPath.read(body(visitor.get("/api/v1/works/" + newWork)), "$.data.rating.count")).isEqualTo(1);
        say("   - 다시 PUBLIC: 같은 작품 ID #" + newWork + ", 별점·리뷰 그대로");

        // 5. 성인 등급: 숨김으로 등록은 되지만 공개는 막힘(D-15 미결정)
        long s1003 = staging("demo-1003");
        Map<String, Object> view3 = postCandidates(s1003);
        MvcResult adultCreated = admin.postJson(review(s1003), createWork(rev(view3), "[DEMO] 성인 등급 숨김 등록",
                List.of(newCreator("[DEMO] 가상작가 밤", "STORY")), List.of()));
        assertThat(status(adultCreated)).as(body(adultCreated)).isEqualTo(200);
        long adultWork = jdbc.queryForObject("SELECT work_id FROM work_platform_listings WHERE platform_work_id = 'demo-1003'", Long.class);
        MvcResult blocked = admin.postJson("/api/v1/admin/works/" + adultWork + "/visibility",
                Map.of("version", 0, "visibility", "PUBLIC", "reason", "[DEMO] 공개 시도"));
        assertThat(code(blocked)).isEqualTo("WORK_PUBLICATION_BLOCKED");
        say("5. `demo-1003`(19세 이용가) CREATE_WORK → 숨김 작품 #" + adultWork + " 등록, PUBLIC 전환 → " + status(blocked) + " "
                + code(blocked) + " (ADULT·UNKNOWN 공개 표시 정책 D-15 미결정)");

        // 6. 검수 중 재수집
        site.ok("/demo-sitemap.xml", "detail " + B + "/demo/demo-1004\n");
        site.ok("/demo/demo-1004", "title=[DEMO] 가상의 겨울 정류장\nage=전체 이용가\nstory=[DEMO] 가상작가 눈\n");
        RunReport second = engine.start("TOOMICS", RunKind.INCREMENTAL, "demo-collector");
        long s1004 = staging("demo-1004");
        Map<String, Object> seen = postCandidates(s1004);
        say("6. 수집 #" + second.runId() + " 로 `demo-1004` 추가, 검수자가 revision " + rev(seen) + " 을 봄");
        site.ok("/demo/demo-1004", "title=[DEMO] 가상의 겨울 정류장 (개정)\nage=전체 이용가\nstory=[DEMO] 가상작가 눈\n");
        RunReport third = engine.start("TOOMICS", RunKind.INCREMENTAL, "demo-collector");
        MvcResult stale = admin.postJson(review(s1004), createWork(rev(seen), "[DEMO] 본 내용으로 등록",
                List.of(newCreator("[DEMO] 가상작가 눈", "STORY")), List.of()));
        assertThat(code(stale)).isEqualTo("STAGING_REVISION_CONFLICT");
        say("   - 승인 직전에 수집 #" + third.runId() + " 이 제목을 바꿈 → 승인 " + status(stale) + " " + code(stale)
                + " (현재 revision " + JsonPath.read(body(stale), "$.error.currentVersion") + ")");
        Map<String, Object> fresh = postCandidates(s1004);
        MvcResult ok = admin.postJson(review(s1004), createWork(rev(fresh), "[DEMO] 바뀐 제목 확인 뒤 등록",
                List.of(newCreator("[DEMO] 가상작가 눈", "STORY")), List.of()));
        assertThat(status(ok)).as(body(ok)).isEqualTo(200);
        say("   - 최신 내용(`" + JsonPath.read(fresh, "$.titleClean") + "`) 확인 뒤 다시 승인 → " + JsonPath.read(body(ok), "$.data.reviewStatus"));

        // 결과
        say("\n## 결과\n");
        say("| 작품 | 제목 | 공개 상태 | 연결 |");
        say("|---|---|---|---|");
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT w.id, t.title, w.visibility, string_agg(l.platform_code || ':' || l.platform_work_id, ', ' ORDER BY l.id) AS listings
                FROM works w JOIN work_titles t ON t.work_id = w.id AND t.is_primary
                LEFT JOIN work_platform_listings l ON l.work_id = w.id GROUP BY w.id, t.title, w.visibility ORDER BY w.id""")) {
            say("| #" + row.get("id") + " | " + row.get("title") + " | " + row.get("visibility") + " | "
                    + (row.get("listings") == null ? "-" : row.get("listings")) + " |");
        }
        say("\n| staging | 검수 상태 | revision |");
        say("|---|---|---|");
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT platform_work_id, review_status, revision FROM staging_listings ORDER BY platform_work_id")) {
            say("| " + row.get("platform_work_id") + " | " + row.get("review_status") + " | " + row.get("revision") + " |");
        }
        say("\n검수 변경 기록 " + jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_log WHERE entity_type = 'STAGING_LISTING'",
                Integer.class) + "건, 검수 결정 " + count("staging_review_decisions") + "건. 표지 다운로드·실제 플랫폼 요청 없음.");

        assertThat(count("works")).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT visibility FROM works ORDER BY id", String.class))
                .containsExactly("PUBLIC", "PUBLIC", "HIDDEN", "HIDDEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_platform_listings WHERE work_id = ?", Integer.class, existingWork))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT DISTINCT split_part(url, '/', 3) FROM work_platform_listings", String.class))
                .containsOnly(ReviewFixtures.TOOMICS_HOST);
        assertThat(net.connectedAddresses()).containsOnly("127.0.0.1");
        assertThat(jdbc.queryForList("SELECT title FROM work_titles", String.class)).allMatch(t -> t.startsWith("[DEMO]"));
    }

    // ---------- 도우미 ----------

    private void say(String line) {
        log.append(line).append('\n');
        System.out.println("[DEMO] " + line);
    }

    private long existingWork(String title, String creatorName) {
        long creatorId = creatorService.register(new CreateCreatorRequest(creatorName, null, null, "[DEMO] 시연 준비"), adminId).id();
        List<EvidenceRequest> evidence = objectMapper.convertValue(List.of(ReviewFixtures.staff("TITLE"), ReviewFixtures.staff("CREDIT")),
                objectMapper.getTypeFactory().constructCollectionType(List.class, EvidenceRequest.class));
        return workService.register(new CreateWorkRequest("WEBTOON", "KR", null, List.of(new CreateWorkRequest.TitleRequest(title, "ko", true)),
                List.of(new CreateWorkRequest.CreditRequest(creatorId, "STORY")), null, null, null, evidence, "[DEMO] 시연 준비"),
                adminId).id();
    }

    private String publicTitles(AuthTestClient client) throws Exception {
        List<String> titles = JsonPath.read(body(client.get("/api/v1/works")), "$.data.content[*].primaryTitle.title");
        return titles.toString();
    }

    private long staging(String platformWorkId) {
        return jdbc.queryForObject("SELECT id FROM staging_listings WHERE platform_work_id = ?", Long.class, platformWorkId);
    }

    private Map<String, Object> postCandidates(long stagingId) throws Exception {
        MvcResult r = admin.postJson("/api/v1/admin/staging-listings/" + stagingId + "/candidates", Map.of());
        assertThat(status(r)).as(body(r)).isEqualTo(200);
        return JsonPath.read(body(r), "$.data");
    }

    private static String review(long stagingId) {
        return "/api/v1/admin/staging-listings/" + stagingId + "/review";
    }

    private static long rev(Map<String, Object> view) {
        return ((Number) view.get("revision")).longValue();
    }

    private static String describe(List<Map<String, Object>> candidates) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            out.add("작품 #" + c.get("workId") + " `" + c.get("workTitle") + "` 점수 " + c.get("score") + " 근거 " + c.get("reasons")
                    + " 경고 " + c.get("warnings"));
        }
        return out.isEmpty() ? "없음" : String.join("; ", out);
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
}
