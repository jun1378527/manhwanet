package com.manhwanet.collection;

import static com.manhwanet.collection.CollectionTestSupport.OWNER;
import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.collection.application.CrawlRunService;
import com.manhwanet.collection.application.StagingIngestService;
import com.manhwanet.collection.domain.RunKind;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** 수집 설정·실행·staging 관리자 API (#45). 실제 로그인 세션·쿠키 CSRF·PostgreSQL 테스트 DB */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CollectionAdminApiIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@manhwanet.test";

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
    private StagingIngestService ingest;

    private AuthTestClient admin;
    private long adminId;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseCleaner.clean(jdbc, environment);
        adminAccountService.createAdmin(ADMIN_EMAIL, STRONG_PASSWORD, "관리자");
        admin = new AuthTestClient(mockMvc, objectMapper);
        assertThat(admin.login(ADMIN_EMAIL, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, ADMIN_EMAIL);
    }

    @Test
    void onlyAdminWithCsrfCanReadOrChange() throws Exception {
        AuthTestClient anonymous = new AuthTestClient(mockMvc, objectMapper);
        assertThat(status(anonymous.get("/api/v1/admin/collection-policies"))).isEqualTo(401);
        assertThat(status(anonymous.putRaw("/api/v1/admin/collection-policies/RIDI", json(policy(0, "PROBE"))))).isEqualTo(401);
        AuthTestClient user = new AuthTestClient(mockMvc, objectMapper);
        user.signup("reader@manhwanet.test", STRONG_PASSWORD, "독자");
        assertThat(status(user.get("/api/v1/admin/staging-listings"))).isEqualTo(403);
        assertThat(status(user.get("/api/v1/admin/crawl-runs"))).isEqualTo(403);
        assertThat(status(user.putRaw("/api/v1/admin/collection-policies/RIDI", json(policy(0, "PROBE"))))).isEqualTo(403);
        assertThat(status(user.postJson("/api/v1/admin/crawl-runs/1/stop", Map.of("reason", "x")))).isEqualTo(403);
        MvcResult noCsrf = admin.performWithSessionOnly(put("/api/v1/admin/collection-policies/RIDI")
                .contentType(MediaType.APPLICATION_JSON).content(json(policy(0, "PROBE"))));
        assertThat(code(noCsrf)).isEqualTo("INVALID_CSRF_TOKEN");
        MvcResult noCsrfStop = admin.performWithSessionOnly(post("/api/v1/admin/crawl-runs/1/stop")
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "x"))));
        assertThat(code(noCsrfStop)).isEqualTo("INVALID_CSRF_TOKEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_collection_policies WHERE collection_policy <> 'DISABLED'",
                Integer.class)).isZero();
    }

    @Test
    void policyChangesAreVersionedReasonedAndLogged() throws Exception {
        MvcResult list = admin.get("/api/v1/admin/collection-policies");
        List<String> policies = JsonPath.read(body(list), "$.data[*].collectionPolicy");
        assertThat(policies).hasSize(10).containsOnly("DISABLED");

        Map<String, Object> noReason = policy(0, "PROBE");
        noReason.remove("reason");
        assertThat(fields(admin.putRaw("/api/v1/admin/collection-policies/RIDI", json(noReason)))).contains("reason");
        Map<String, Object> bad = policy(0, "ON");
        bad.put("rateIntervalMs", 10);
        bad.put("basisNote", "근거 종류 없이 메모만");
        bad.put("version", "0");
        assertThat(fields(admin.putRaw("/api/v1/admin/collection-policies/RIDI", json(bad))))
                .contains("collectionPolicy", "rateIntervalMs", "basisKind", "version");

        // 외부 허락 문서 없이 켤 수 있고, 바꾼 관리자·시각·사유가 남는다
        MvcResult probe = admin.putRaw("/api/v1/admin/collection-policies/ridi", json(policy(0, "PROBE")));
        assertThat(status(probe)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(probe), "$.data.collectionPolicy")).isEqualTo("PROBE");
        assertThat(((Number) JsonPath.read(body(probe), "$.data.version")).longValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(body(probe), "$.data.decidedBy")).longValue()).isEqualTo(adminId);
        assertThat((Object) JsonPath.read(body(probe), "$.data.basisKind")).isNull();
        MvcResult stale = admin.putRaw("/api/v1/admin/collection-policies/RIDI", json(policy(0, "ENABLED")));
        assertThat(code(stale)).isEqualTo("COLLECTION_POLICY_VERSION_CONFLICT");
        // 같은 내용은 version·기록이 그대로
        assertThat(((Number) JsonPath.read(body(admin.putRaw("/api/v1/admin/collection-policies/RIDI",
                json(policy(1, "PROBE")))), "$.data.version")).longValue()).isEqualTo(1);
        MvcResult log = admin.get("/api/v1/admin/change-log?entityType=COLLECTION_POLICY&entityId=ridi");
        assertThat((String) JsonPath.read(body(log), "$.data.entries[0].reason")).isEqualTo("PROBE 시작");
        assertThat((String) JsonPath.read(body(log), "$.data.entries[0].before.collectionPolicy")).isEqualTo("DISABLED");
        List<Object> entries = JsonPath.read(body(log), "$.data.entries");
        assertThat(entries).hasSize(1);
        assertThat(code(admin.putRaw("/api/v1/admin/collection-policies/NOPE", json(policy(0, "PROBE")))))
                .isEqualTo("PLATFORM_NOT_FOUND");

        // 실행 중에 설정을 끄면 경고만 주고 실행은 따로 중단한다
        long run = runs.start("RIDI", RunKind.PROBE, "ridi-0.1", OWNER, 60, null).id();
        MvcResult off = admin.putRaw("/api/v1/admin/collection-policies/RIDI", json(policy(1, "DISABLED")));
        assertThat(((Number) JsonPath.read(body(off), "$.data.activeRunId")).longValue()).isEqualTo(run);
        List<String> warnings = JsonPath.read(body(off), "$.data.warnings");
        assertThat(warnings).containsExactly("ACTIVE_RUN_REMAINS");
    }

    @Test
    void runsAndStagingCanBeInspectedAndRunsStopped() throws Exception {
        CollectionTestSupport.setPolicy(jdbc, "RIDI", "ENABLED", adminId);
        long run = runs.start("RIDI", RunKind.FULL, "ridi-0.1", OWNER, 60, null).id();
        long staging = ingest.ingest(run, OWNER, CollectionTestSupport.ridi("651673", "유미의 세포들", "12세 이용가")).stagingId();

        MvcResult runList = admin.get("/api/v1/admin/crawl-runs?platformCode=ridi");
        assertThat(((Number) JsonPath.read(body(runList), "$.data.items[0].id")).longValue()).isEqualTo(run);
        MvcResult detail = admin.get("/api/v1/admin/crawl-runs/" + run);
        assertThat((String) JsonPath.read(body(detail), "$.data.status")).isEqualTo("RUNNING");
        assertThat(((Number) JsonPath.read(body(detail), "$.data.taskCounts.PENDING")).longValue()).isZero();

        MvcResult stagingList = admin.get("/api/v1/admin/staging-listings?platformCode=RIDI&reviewStatus=NEW");
        assertThat(((Number) JsonPath.read(body(stagingList), "$.data.items[0].id")).longValue()).isEqualTo(staging);
        assertThat((Object) JsonPath.read(body(stagingList), "$.data.items[0].synopsisText")).isNull();
        MvcResult stagingDetail = admin.get("/api/v1/admin/staging-listings/" + staging);
        assertThat((String) JsonPath.read(body(stagingDetail), "$.data.ageClass")).isEqualTo("GENERAL");
        assertThat((String) JsonPath.read(body(stagingDetail), "$.data.ageRatingRaw")).isEqualTo("12세 이용가");
        List<String> roles = JsonPath.read(body(stagingDetail), "$.data.credits[*].role");
        assertThat(roles).containsExactly("STORY", "ART");
        assertThat((String) JsonPath.read(body(stagingDetail), "$.data.changes[0].changedFields.titleRaw.after"))
                .isEqualTo("유미의 세포들");
        assertThat((String) JsonPath.read(body(stagingDetail), "$.data.synopsisText")).isEqualTo("소개문 유미의 세포들");

        assertThat(fields(admin.postJson("/api/v1/admin/crawl-runs/" + run + "/stop", Map.of()))).contains("reason");
        MvcResult stop = admin.postJson("/api/v1/admin/crawl-runs/" + run + "/stop", Map.of("reason", "점검으로 중단"));
        assertThat((String) JsonPath.read(body(stop), "$.data.status")).isEqualTo("STOP_REQUESTED");
        assertThat(jdbc.queryForObject("SELECT reason FROM admin_change_log WHERE entity_type = 'CRAWL_RUN'", String.class))
                .isEqualTo("점검으로 중단");
        runs.finishStop(run, OWNER, null);
        assertThat(code(admin.postJson("/api/v1/admin/crawl-runs/" + run + "/stop", Map.of("reason", "다시"))))
                .isEqualTo("CRAWL_RUN_STATE_CONFLICT");

        String[] badQueries = {"/api/v1/admin/staging-listings?limit=0", "/api/v1/admin/staging-listings?limit=101",
                "/api/v1/admin/staging-listings?beforeId=99999999999999999999", "/api/v1/admin/staging-listings?reviewStatus=DONE",
                "/api/v1/admin/crawl-runs?beforeId=-1", "/api/v1/admin/staging-listings/99999999999999999999"};
        for (String q : badQueries) {
            assertThat(status(admin.get(q))).as(q).isEqualTo(400);
        }
        assertThat(code(admin.get("/api/v1/admin/staging-listings/" + Long.MAX_VALUE))).isEqualTo("STAGING_LISTING_NOT_FOUND");
        assertThat(code(admin.get("/api/v1/admin/crawl-runs/" + Long.MAX_VALUE))).isEqualTo("CRAWL_RUN_NOT_FOUND");
        assertThat(code(admin.postJson("/api/v1/admin/crawl-runs/" + Long.MAX_VALUE + "/stop", Map.of("reason", "x"))))
                .isEqualTo("CRAWL_RUN_NOT_FOUND");
    }

    private static Map<String, Object> policy(long version, String collection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("collectionPolicy", collection);
        body.put("synopsisPolicy", "COLLECT_PRIVATE");
        body.put("coverPolicy", "URL_ONLY");
        body.put("rateIntervalMs", 5000);
        body.put("dailyRequestCap", 15000);
        body.put("reason", "PROBE 시작");
        return body;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String code(MvcResult result) throws Exception {
        return JsonPath.read(body(result), "$.error.code");
    }

    private static List<String> fields(MvcResult result) throws Exception {
        return JsonPath.read(body(result), "$.error.fieldErrors[*].field");
    }
}
