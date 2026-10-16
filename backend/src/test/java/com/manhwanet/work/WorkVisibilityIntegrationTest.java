package com.manhwanet.work;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.dto.CreateCreatorRequest;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.platform.dto.ListingInput;
import com.manhwanet.provenance.dto.EvidenceRequest;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.ChangeVisibilityRequest;
import com.manhwanet.work.dto.CreateWorkRequest;
import com.manhwanet.review.dto.JsonInteger;
import java.time.Instant;
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
 * 작품 공개·숨김 상태(#41 최소 범위). 실제 로그인 세션·쿠키 CSRF·격리 PostgreSQL 테스트 DB, 가상 작품 데이터만 사용.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkVisibilityIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@manhwanet.test";
    private static final String HOST = "toomics.mock.test";

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

    private AuthTestClient admin;
    private long adminId;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseCleaner.clean(jdbc, environment);
        adminAccountService.createAdmin(ADMIN_EMAIL, STRONG_PASSWORD, "관리자");
        admin = new AuthTestClient(mockMvc, objectMapper);
        assertThat(admin.login(ADMIN_EMAIL, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, ADMIN_EMAIL);
        jdbc.update("INSERT INTO platform_hosts (platform_code, host) VALUES ('TOOMICS', ?)", HOST);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        jdbc.execute("DROP TRIGGER IF EXISTS trg_test_fail_visibility ON admin_change_log");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_visibility()");
    }

    // ---------- 권한·입력·version ----------

    @Test
    void onlyAdminWithCsrfReasonAndCurrentVersionCanChangeVisibility() throws Exception {
        long workId = work("[DEMO] 가상의 권한 확인", "전체 이용가");
        String path = "/api/v1/admin/works/" + workId + "/visibility";
        Map<String, Object> hide = Map.of("version", 0, "visibility", "HIDDEN", "reason", "숨김 확인");

        assertThat(status(new AuthTestClient(mockMvc, objectMapper).postJson(path, hide))).isEqualTo(401);
        AuthTestClient user = reader("user1@manhwanet.test");
        assertThat(status(user.postJson(path, hide))).isEqualTo(403);
        assertThat(status(user.get("/api/v1/admin/works/" + workId))).isEqualTo(403);
        MvcResult noCsrf = admin.performWithSessionOnly(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(hide)));
        assertThat(code(noCsrf)).isEqualTo("INVALID_CSRF_TOKEN");
        assertThat(fields(admin.postJson(path, Map.of("version", 0, "visibility", "HIDDEN")))).contains("reason");
        assertThat(fields(admin.postJson(path, Map.of("version", "0", "visibility", "SECRET", "reason", "x"))))
                .contains("version", "visibility");
        MvcResult stale = admin.postJson(path, Map.of("version", 5, "visibility", "HIDDEN", "reason", "숨김"));
        assertThat(code(stale)).isEqualTo("WORK_VERSION_CONFLICT");
        assertThat(((Number) JsonPath.read(body(stale), "$.error.currentVersion")).longValue()).isZero();
        assertThat(code(admin.postJson("/api/v1/admin/works/999999/visibility", hide))).isEqualTo("WORK_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT visibility || ':' || version FROM works", String.class)).isEqualTo("PUBLIC:0");
        assertThat(changes(workId)).isZero();
    }

    // ---------- 숨김 노출 방지·기존 데이터 보존 ----------

    @Test
    void hiddenWorkDisappearsFromPublicPathsButKeepsRatingsReviewsListingsAndId() throws Exception {
        long workId = work("[DEMO] 가상의 숨김 작품", "15세 이용가");
        long other = work("[DEMO] 가상의 다른 작품", "전체 이용가");
        AuthTestClient alice = reader("alice@manhwanet.test");
        AuthTestClient bob = reader("bob@manhwanet.test");
        AuthTestClient carol = reader("carol@manhwanet.test");
        assertThat(status(alice.putRaw("/api/v1/me/ratings/" + workId, "{\"score\": 4.5}"))).isEqualTo(200);
        assertThat(status(bob.putRaw("/api/v1/me/ratings/" + workId, "{\"score\": 3.0}"))).isEqualTo(200);
        MvcResult aliceReview = alice.postJson("/api/v1/me/reviews/" + workId, review("[DEMO] 앨리스의 시연용 리뷰입니다."));
        MvcResult bobReview = bob.postJson("/api/v1/me/reviews/" + workId, review("[DEMO] 밥의 시연용 리뷰 문장입니다."));
        long listingId = jdbc.queryForObject("SELECT id FROM work_platform_listings WHERE work_id = ?", Long.class, workId);
        AuthTestClient visitor = new AuthTestClient(mockMvc, objectMapper);
        Object ratingBefore = JsonPath.read(body(visitor.get("/api/v1/works/" + workId)), "$.data.rating");

        MvcResult hidden = admin.postJson("/api/v1/admin/works/" + workId + "/visibility",
                Map.of("version", 0, "visibility", "HIDDEN", "reason", "[DEMO] 검토를 위해 숨김"));
        assertThat(status(hidden)).as(body(hidden)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(hidden), "$.data.visibility")).isEqualTo("HIDDEN");
        assertThat(((Number) JsonPath.read(body(hidden), "$.data.version")).longValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT action || ':' || (before_json->>'visibility') || ':' || (after_json->>'visibility') || ':' || reason
                FROM admin_change_log WHERE entity_type = 'WORK' AND action = 'STATUS_CHANGE'""", String.class))
                .isEqualTo("STATUS_CHANGE:PUBLIC:HIDDEN:[DEMO] 검토를 위해 숨김");

        // 공개 경로: 없는 작품과 같은 응답(ADMIN 도 같음)
        String missing = body(visitor.get("/api/v1/works/999999"));
        for (AuthTestClient client : List.of(visitor, alice, admin)) {
            MvcResult detail = client.get("/api/v1/works/" + workId);
            assertThat(status(detail)).isEqualTo(404);
            assertThat(body(detail)).isEqualTo(missing);
            assertThat(code(client.get("/api/v1/works/" + workId + "/reviews"))).isEqualTo("WORK_NOT_FOUND");
            List<Integer> ids = JsonPath.read(body(client.get("/api/v1/works?size=100")), "$.data.content[*].id");
            assertThat(ids).containsExactly((int) other);
            assertThat(((Number) JsonPath.read(body(client.get("/api/v1/works")), "$.data.totalElements")).longValue()).isEqualTo(1);
        }
        // 관리자 경로로는 조회된다
        assertThat((String) JsonPath.read(body(admin.get("/api/v1/admin/works/" + workId)), "$.data.visibility")).isEqualTo("HIDDEN");

        // 별점: 본인 기록 조회는 유지, 새로 쓰거나 고치기는 없는 작품과 같은 404
        assertThat(((Number) JsonPath.read(body(alice.get("/api/v1/me/ratings/" + workId)), "$.data.score")).doubleValue())
                .isEqualTo(4.5);
        assertThat(code(carol.get("/api/v1/me/ratings/" + workId))).isEqualTo("WORK_NOT_FOUND");
        assertThat(code(alice.putRaw("/api/v1/me/ratings/" + workId, "{\"score\": 1.0}"))).isEqualTo("WORK_NOT_FOUND");
        assertThat(code(carol.putRaw("/api/v1/me/ratings/" + workId, "{\"score\": 5.0}"))).isEqualTo("WORK_NOT_FOUND");
        assertThat(code(carol.delete("/api/v1/me/ratings/" + workId))).isEqualTo("WORK_NOT_FOUND");
        // 리뷰: 본인 조회·삭제는 유지, 작성·수정은 404
        assertThat((String) JsonPath.read(body(alice.get("/api/v1/me/reviews/" + workId)), "$.data.review.body"))
                .isEqualTo("[DEMO] 앨리스의 시연용 리뷰입니다.");
        assertThat(code(carol.get("/api/v1/me/reviews/" + workId))).isEqualTo("WORK_NOT_FOUND");
        assertThat(code(carol.postJson("/api/v1/me/reviews/" + workId, review("[DEMO] 숨김 작품에 쓰려는 리뷰")))).isEqualTo("WORK_NOT_FOUND");
        Map<String, Object> update = new java.util.LinkedHashMap<>(review("[DEMO] 숨김 중에 고치려는 리뷰"));
        update.put("reviewId", JsonPath.read(body(aliceReview), "$.data.review.id"));
        update.put("version", 0);
        assertThat(code(alice.putRaw("/api/v1/me/reviews/" + workId, objectMapper.writeValueAsString(update)))).isEqualTo("WORK_NOT_FOUND");
        Number bobReviewId = JsonPath.read(body(bobReview), "$.data.review.id");
        assertThat(status(bob.delete("/api/v1/me/reviews/" + workId + "?reviewId=" + bobReviewId + "&version=0"))).isEqualTo(204);
        assertThat(code(carol.delete("/api/v1/me/reviews/" + workId + "?reviewId=1&version=0"))).isEqualTo("WORK_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT score_half FROM work_ratings r JOIN users u ON u.id = r.user_id "
                + "WHERE u.email = 'alice@manhwanet.test'", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_ratings WHERE work_id = ?", Integer.class, workId)).isEqualTo(2);

        // 같은 상태로 다시 보내면 그대로(version·기록 없음)
        MvcResult same = admin.postJson("/api/v1/admin/works/" + workId + "/visibility",
                Map.of("version", 1, "visibility", "HIDDEN", "reason", "다시 숨김"));
        assertThat(((Number) JsonPath.read(body(same), "$.data.version")).longValue()).isEqualTo(1);
        assertThat(changes(workId)).isEqualTo(1);

        // 다시 공개: 같은 ID, 별점·리뷰(작성자가 지운 것 제외)·연결이 그대로
        MvcResult shown = admin.postJson("/api/v1/admin/works/" + workId + "/visibility",
                Map.of("version", 1, "visibility", "PUBLIC", "reason", "[DEMO] 검토 완료"));
        assertThat(status(shown)).as(body(shown)).isEqualTo(200);
        assertThat((Object) JsonPath.read(body(visitor.get("/api/v1/works/" + workId)), "$.data.rating")).isEqualTo(ratingBefore);
        assertThat(((Number) JsonPath.read(body(visitor.get("/api/v1/works/" + workId + "/reviews")), "$.data.totalElements")).intValue())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM work_platform_listings WHERE work_id = ?", Long.class, workId)).isEqualTo(listingId);
        assertThat(status(carol.putRaw("/api/v1/me/ratings/" + workId, "{\"score\": 5.0}"))).isEqualTo(200);
    }

    @Test
    void existingAndAdminRegisteredWorksStayPublicByDefault() throws Exception {
        long workId = work("[DEMO] 가상의 기존 작품", "전체 이용가");
        assertThat(jdbc.queryForObject("SELECT visibility FROM works WHERE id = ?", String.class, workId)).isEqualTo("PUBLIC");
        assertThat(jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns WHERE table_name = 'works' AND column_name = 'visibility'""",
                String.class)).contains("PUBLIC");
        // 기존 행처럼 visibility 를 넣지 않고 만든 행도 PUBLIC
        long raw = jdbc.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
        assertThat(jdbc.queryForObject("SELECT visibility FROM works WHERE id = ?", String.class, raw)).isEqualTo("PUBLIC");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE works SET visibility = 'SECRET' WHERE id = ?", raw))
                .hasMessageContaining("ck_works_visibility");
        jdbc.update("DELETE FROM works WHERE id = ?", raw);
        MvcResult created = admin.postJson("/api/v1/admin/works", Map.of(
                "workFormat", "WEBTOON",
                "titles", List.of(Map.of("title", "[DEMO] 가상의 관리자 등록", "languageCode", "ko", "primary", true)),
                "creators", List.of(Map.of("creatorId", creator("[DEMO] 가상작가 등록"), "role", "STORY")),
                "evidence", List.of(staff("TITLE"), staff("CREDIT"))));
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        assertThat((String) JsonPath.read(body(created), "$.data.visibility")).isEqualTo("PUBLIC");
    }

    // ---------- 공개 전환 정책 ----------

    @Test
    void onlyWorksConfirmedGeneralCanBePublished() throws Exception {
        long general = work("[DEMO] 가상의 일반 작품", "12세 이용가");
        long adult = work("[DEMO] 가상의 성인 작품", "19세 이용가");
        long unknown = work("[DEMO] 가상의 미분류 작품", null);
        long noListing = workWithoutListing("[DEMO] 가상의 연결 없는 작품");
        for (long id : List.of(general, adult, unknown, noListing)) {
            assertThat(status(admin.postJson("/api/v1/admin/works/" + id + "/visibility",
                    Map.of("version", 0, "visibility", "HIDDEN", "reason", "숨김")))).isEqualTo(200);
        }
        for (long id : List.of(adult, unknown, noListing)) {
            MvcResult blocked = admin.postJson("/api/v1/admin/works/" + id + "/visibility",
                    Map.of("version", 1, "visibility", "PUBLIC", "reason", "공개 시도"));
            assertThat(code(blocked)).isEqualTo("WORK_PUBLICATION_BLOCKED");
            assertThat(jdbc.queryForObject("SELECT visibility || ':' || version FROM works WHERE id = ?", String.class, id))
                    .isEqualTo("HIDDEN:1");
        }
        assertThat(status(admin.postJson("/api/v1/admin/works/" + general + "/visibility",
                Map.of("version", 1, "visibility", "PUBLIC", "reason", "공개")))).isEqualTo(200);
    }

    // ---------- 롤백·동시성 ----------

    @Test
    void changeLogFailureRollsBackVisibilityChange() throws Exception {
        long workId = work("[DEMO] 가상의 롤백 작품", "전체 이용가");
        jdbc.execute("CREATE FUNCTION test_fail_visibility() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.action = 'STATUS_CHANGE' AND NEW.entity_type = 'WORK' THEN RAISE EXCEPTION 'injected log failure'; END IF; "
                + "RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER trg_test_fail_visibility BEFORE INSERT ON admin_change_log FOR EACH ROW "
                + "EXECUTE FUNCTION test_fail_visibility()");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> workService.changeVisibility(workId,
                new ChangeVisibilityRequest(new JsonInteger(0L), "HIDDEN", "롤백 확인"), adminId))
                .hasMessageContaining("injected log failure");
        assertThat(jdbc.queryForObject("SELECT visibility || ':' || version FROM works WHERE id = ?", String.class, workId))
                .isEqualTo("PUBLIC:0");
        assertThat(status(new AuthTestClient(mockMvc, objectMapper).get("/api/v1/works/" + workId))).isEqualTo(200);
    }

    @Test
    void concurrentChangesWithSameVersionApplyOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            long workId = work("[DEMO] 가상의 동시 변경 " + round, "전체 이용가");
            CyclicBarrier barrier = new CyclicBarrier(3);
            List<Future<String>> results = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                results.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        workService.changeVisibility(workId, new ChangeVisibilityRequest(new JsonInteger(0L), "HIDDEN", "동시"), adminId);
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
            assertThat(outcomes).containsOnlyOnce("OK");
            assertThat(outcomes.stream().filter(o -> !o.equals("OK"))).containsOnly("WORK_VERSION_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT visibility || ':' || version FROM works WHERE id = ?", String.class, workId))
                    .isEqualTo("HIDDEN:1");
            assertThat(changes(workId)).isEqualTo(1);
        }
    }

    // ---------- 도우미 ----------

    private long work(String title, String ageRaw) {
        long creatorId = creator("[DEMO] 가상작가 " + title.hashCode());
        ListingInput listing = new ListingInput("TOOMICS", "demo-" + Math.abs(title.hashCode()), "https://" + HOST + "/demo/"
                + Math.abs(title.hashCode()), "SERIES", "MAIN", "ko", ageRaw, null, Instant.parse("2026-10-15T00:00:00Z"),
                "[DEMO] 가상 데이터", evidence(staff(null)));
        return workService.register(new CreateWorkRequest("WEBTOON", "KR", null,
                List.of(new CreateWorkRequest.TitleRequest(title, "ko", true)),
                List.of(new CreateWorkRequest.CreditRequest(creatorId, "STORY")), List.of(listing), null, null,
                evidence(staff("TITLE"), staff("CREDIT")), "[DEMO] 테스트 준비"), adminId).id();
    }

    private long workWithoutListing(String title) {
        long creatorId = creator("[DEMO] 가상작가 " + title.hashCode());
        return workService.register(new CreateWorkRequest("WEBTOON", "KR", null,
                List.of(new CreateWorkRequest.TitleRequest(title, "ko", true)),
                List.of(new CreateWorkRequest.CreditRequest(creatorId, "STORY")), null, null, null,
                evidence(staff("TITLE"), staff("CREDIT")), "[DEMO] 테스트 준비"), adminId).id();
    }

    private long creator(String name) {
        return creatorService.register(new CreateCreatorRequest(name, null, null, "[DEMO] 테스트 준비"), adminId).id();
    }

    private static Map<String, Object> staff(String field) {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        if (field != null) {
            e.put("field", field);
        }
        e.put("sourceType", "STAFF");
        e.put("verifiedAt", "2026-10-15T00:00:00Z");
        e.put("note", "[DEMO] 가상 데이터");
        return e;
    }

    @SafeVarargs
    private List<EvidenceRequest> evidence(Map<String, Object>... items) {
        return objectMapper.convertValue(List.of(items),
                objectMapper.getTypeFactory().constructCollectionType(List.class, EvidenceRequest.class));
    }

    private AuthTestClient reader(String email) throws Exception {
        AuthTestClient client = new AuthTestClient(mockMvc, objectMapper);
        assertThat(status(client.signup(email, STRONG_PASSWORD, email.substring(0, email.indexOf('@'))))).isIn(200, 201);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return client.withHeader("X-Expected-User-Id", String.valueOf(id));
    }

    private static Map<String, Object> review(String text) {
        return Map.of("body", text, "containsSpoiler", false);
    }

    private int changes(long workId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_log WHERE entity_type = 'WORK' AND action = 'STATUS_CHANGE' "
                + "AND entity_id = ?", Integer.class, String.valueOf(workId));
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
