package com.manhwanet.platform;

import static com.manhwanet.platform.PlatformTestSupport.at;
import static com.manhwanet.platform.PlatformTestSupport.code;
import static com.manhwanet.platform.PlatformTestSupport.fields;
import static com.manhwanet.platform.PlatformTestSupport.longAt;
import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 플랫폼 관리자 API (#39): 코드 표 조회, URL 규칙 수정, 운영 상태 변경과 이력.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformAdminApiIntegrationTest {

    private static final String KAKAO_NOTICE = "https://webtoon.kakao.com/event?event_id=7537196590";

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

    private AuthTestClient admin;

    @BeforeEach
    void setUp() throws Exception {
        dropTrigger();
        TestDatabaseCleaner.clean(jdbc, environment);
        admin = PlatformTestSupport.admin(mockMvc, objectMapper, adminAccountService);
    }

    @AfterEach
    void tearDown() {
        dropTrigger();
    }

    private void dropTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_platform_update ON platforms");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_platform_update()");
    }

    @Test
    void listsTenPlatformsWithoutInventedStatusOrTerms() throws Exception {
        MvcResult result = admin.get("/api/v1/admin/platforms");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        List<String> codes = at(result, "$.data[*].code");
        assertThat(codes).containsExactlyInAnyOrder("NAVER_WEBTOON", "KAKAO_PAGE", "KAKAO_WEBTOON", "LEZHIN", "RIDI",
                "BOMTOON", "TOPTOON", "TOOMICS", "COMICO_KR", "MRBLUE");
        List<String> statuses = at(result, "$.data[*].status");
        assertThat(statuses).containsOnly("UNKNOWN");
        List<String> bases = at(result, "$.data[*].statusBasis");
        assertThat(bases).containsOnly("UNVERIFIED");
        List<Object> terms = at(result, "$.data[*].termsCheckedOn");
        assertThat(terms).containsOnlyNulls();
        List<String> scopes = at(result, "$.data[*].defaultIdScope");
        assertThat(scopes).containsOnly("UNKNOWN");

        MvcResult toomics = admin.get("/api/v1/admin/platforms/TOOMICS");
        assertThat((String) at(toomics, "$.data.urlRuleBasis")).isEqualTo("UNVERIFIED");
        assertThat((Object) at(toomics, "$.data.workUrlPattern")).isNull();
        MvcResult mrblue = admin.get("/api/v1/admin/platforms/MRBLUE");
        assertThat((String) at(mrblue, "$.data.urlRuleBasis")).isEqualTo("UNVERIFIED");
        assertThat((Object) at(mrblue, "$.data.workUrlPattern")).isNull();
        assertThat((List<String>) at(mrblue, "$.data.allowedHosts")).containsExactly("m.mrblue.com", "www.mrblue.com");
        assertThat(code(admin.get("/api/v1/admin/platforms/NOPE"))).isEqualTo("PLATFORM_NOT_FOUND");
    }

    @Test
    void onlyAdminWithCsrfCanReadOrChange() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/admin/platforms")).andReturn().getResponse().getStatus()).isEqualTo(401);
        AuthTestClient user = new AuthTestClient(mockMvc, objectMapper);
        user.signup("reader@manhwanet.test", STRONG_PASSWORD, "독자");
        assertThat(user.get("/api/v1/admin/platforms").getResponse().getStatus()).isEqualTo(403);
        assertThat(user.postJson("/api/v1/admin/platforms/COMICO_KR/status", unknownPress(0)).getResponse().getStatus())
                .isEqualTo(403);
        MvcResult anonymous = new AuthTestClient(mockMvc, objectMapper)
                .postJson("/api/v1/admin/platforms/COMICO_KR/status", unknownPress(0));
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
        MvcResult noCsrf = admin.performWithSessionOnly(post("/api/v1/admin/platforms/COMICO_KR/status")
                .contentType(MediaType.APPLICATION_JSON).content(json(unknownPress(0))));
        assertThat(code(noCsrf)).isEqualTo("INVALID_CSRF_TOKEN");
        assertThat(count("platform_status_history")).isZero();
    }

    // ---------- 운영 상태 ----------

    @Test
    void pressReportIsRecordedAsUnknownAndClosingNeedsOfficialEvidence() throws Exception {
        // 보도만으로 종료 처리할 수 없다
        Map<String, Object> closedByPress = status(0, "CLOSED", "PRESS");
        closedByPress.put("evidenceUrl", "https://dealsite.co.kr/articles/144036");
        MvcResult rejected = admin.postJson("/api/v1/admin/platforms/COMICO_KR/status", closedByPress);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
        assertThat(fields(rejected)).contains("statusBasis");

        MvcResult press = admin.postJson("/api/v1/admin/platforms/COMICO_KR/status", unknownPress(0));
        assertThat(press.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) at(press, "$.data.platform.status")).isEqualTo("UNKNOWN");
        assertThat((String) at(press, "$.data.platform.statusBasis")).isEqualTo("PRESS");
        assertThat(longAt(press, "$.data.platform.version")).isEqualTo(1);
        long historyId = longAt(press, "$.data.historyId");

        // 같은 내용이면 이력을 남기지 않고 version 도 그대로
        MvcResult same = admin.postJson("/api/v1/admin/platforms/COMICO_KR/status", unknownPress(1));
        assertThat((Object) at(same, "$.data.historyId")).isNull();
        assertThat(longAt(same, "$.data.platform.version")).isEqualTo(1);

        Map<String, Object> official = status(1, "CLOSED", "OFFICIAL");
        official.put("evidenceUrl", "https://www.comico.kr/notice/1");
        official.put("effectiveOn", "2025-10-31");
        MvcResult closed = admin.postJson("/api/v1/admin/platforms/COMICO_KR/status", official);
        assertThat((String) at(closed, "$.data.platform.status")).isEqualTo("CLOSED");

        MvcResult history = admin.get("/api/v1/admin/platforms/COMICO_KR/status-history");
        List<String> to = at(history, "$.data[*].toStatus");
        assertThat(to).containsExactly("CLOSED", "UNKNOWN");
        List<String> from = at(history, "$.data[*].fromBasis");
        assertThat(from).containsExactly("PRESS", "UNVERIFIED");
        assertThat(longAt(history, "$.data[1].id")).isEqualTo(historyId);
        assertThat(longAt(history, "$.data[0].changedBy")).isEqualTo(PlatformTestSupport.adminId(jdbc));
        assertThat((String) at(history, "$.data[0].effectiveOn")).isEqualTo("2025-10-31");
    }

    @Test
    void mergingNeedsTargetAndRejectsSelfAndCycles() throws Exception {
        Map<String, Object> noTarget = status(0, "MERGING", "OFFICIAL");
        noTarget.put("evidenceUrl", KAKAO_NOTICE);
        assertThat(fields(admin.postJson("/api/v1/admin/platforms/KAKAO_WEBTOON/status", noTarget))).contains("mergedInto");

        Map<String, Object> self = merging(0, "KAKAO_WEBTOON");
        assertThat(fields(admin.postJson("/api/v1/admin/platforms/KAKAO_WEBTOON/status", self))).contains("mergedInto");

        Map<String, Object> missing = merging(0, "NOPE");
        assertThat(fields(admin.postJson("/api/v1/admin/platforms/KAKAO_WEBTOON/status", missing))).contains("mergedInto");

        Map<String, Object> noEvidence = merging(0, "KAKAO_PAGE");
        noEvidence.remove("evidenceUrl");
        assertThat(fields(admin.postJson("/api/v1/admin/platforms/KAKAO_WEBTOON/status", noEvidence))).contains("evidenceUrl");

        Map<String, Object> badEvidence = merging(0, "KAKAO_PAGE");
        badEvidence.put("evidenceUrl", "http://not-https.example.com");
        assertThat(fields(admin.postJson("/api/v1/admin/platforms/KAKAO_WEBTOON/status", badEvidence))).contains("evidenceUrl");

        Map<String, Object> ok = merging(0, "KAKAO_PAGE");
        ok.put("effectiveOn", "2026-11-09");
        MvcResult merged = admin.postJson("/api/v1/admin/platforms/KAKAO_WEBTOON/status", ok);
        assertThat(merged.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) at(merged, "$.data.platform.mergedInto")).isEqualTo("KAKAO_PAGE");

        // KAKAO_PAGE → KAKAO_WEBTOON 은 순환
        MvcResult cycle = admin.postJson("/api/v1/admin/platforms/KAKAO_PAGE/status", merging(0, "KAKAO_WEBTOON"));
        assertThat(cycle.getResponse().getStatus()).isEqualTo(400);
        assertThat(fields(cycle)).contains("mergedInto");

        Map<String, Object> activeWithTarget = status(0, "ACTIVE", "OFFICIAL");
        activeWithTarget.put("evidenceUrl", "https://page.kakao.com");
        activeWithTarget.put("mergedInto", "RIDI");
        assertThat(fields(admin.postJson("/api/v1/admin/platforms/KAKAO_PAGE/status", activeWithTarget))).contains("mergedInto");
        assertThat(count("platform_status_history")).isEqualTo(1);
    }

    @Test
    void staleVersionAndClosedPlatformListingsAreReported() throws Exception {
        long workId = PlatformTestSupport.insertWork(jdbc, "작품");
        admin.postJson("/api/v1/admin/works/" + workId + "/listings",
                PlatformTestSupport.ridi(0, "4921002551"));
        Map<String, Object> closed = status(0, "CLOSED", "OFFICIAL");
        closed.put("evidenceUrl", "https://ridibooks.com/notice");
        MvcResult result = admin.postJson("/api/v1/admin/platforms/RIDI/status", closed);
        assertThat(longAt(result, "$.data.activeListingCount")).isEqualTo(1);
        // 연결 상태는 자동으로 바꾸지 않는다
        assertThat(jdbc.queryForObject("SELECT status FROM work_platform_listings", String.class)).isEqualTo("ACTIVE");

        MvcResult stale = admin.postJson("/api/v1/admin/platforms/RIDI/status", unknownPress(0));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("PLATFORM_VERSION_CONFLICT");
        assertThat(longAt(stale, "$.error.currentVersion")).isEqualTo(1);
        assertThat(count("platform_status_history")).isEqualTo(1);
    }

    @Test
    void historyAndStatusAreSavedTogetherOrNotAtAll() throws Exception {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_fail_platform_update() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.code = 'COMICO_KR' AND NEW.status_basis = 'PRESS' THEN
                        RAISE EXCEPTION 'test failure while updating platform';
                    END IF;
                    RETURN NEW;
                END $$""");
        jdbc.execute("""
                CREATE TRIGGER test_fail_platform_update BEFORE UPDATE ON platforms
                FOR EACH ROW EXECUTE FUNCTION test_fail_platform_update()""");

        MvcResult failed = admin.postJson("/api/v1/admin/platforms/COMICO_KR/status", unknownPress(0));
        assertThat(failed.getResponse().getStatus()).isEqualTo(500);
        assertThat(count("platform_status_history")).isZero();
        assertThat(jdbc.queryForObject("SELECT status_basis FROM platforms WHERE code = 'COMICO_KR'", String.class))
                .isEqualTo("UNVERIFIED");
        assertThat(jdbc.queryForObject("SELECT version FROM platforms WHERE code = 'COMICO_KR'", Long.class)).isZero();
    }

    // ---------- URL 규칙 ----------

    @Test
    void urlRulesCanBeUpdatedWithValidation() throws Exception {
        Map<String, Object> rules = rules(0);
        rules.put("workUrlPattern", "^https://toomics\\.com/webtoon/episode/toon/(?<id>[0-9]+)$");
        rules.put("urlRuleBasis", "OBSERVED");
        rules.put("urlRuleCheckedOn", "2026-10-12");
        rules.put("allowedHosts", List.of("toomics.com", "WWW.TOOMICS.COM"));
        MvcResult ok = admin.putRaw("/api/v1/admin/platforms/TOOMICS", json(rules));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(longAt(ok, "$.data.version")).isEqualTo(1);
        List<String> hosts = at(ok, "$.data.allowedHosts");
        assertThat(hosts).containsExactly("toomics.com", "www.toomics.com");

        // 새 규칙이 연결 검증에 쓰인다
        long workId = PlatformTestSupport.insertWork(jdbc, "투믹스 작품");
        MvcResult mismatch = admin.postJson("/api/v1/admin/works/" + workId + "/listings",
                PlatformTestSupport.listing(0, "TOOMICS", "9", "https://www.toomics.com/webtoon/episode/toon/8"));
        assertThat(fields(mismatch)).contains("url");

        Map<String, Object> bad = rules(1);
        bad.put("workUrlPattern", "toomics(");
        bad.put("urlRuleBasis", "OBSERVED");
        bad.put("allowedHosts", List.of("ridibooks.com", "bad host", "toomics.com", "toomics.com"));
        MvcResult invalid = admin.putRaw("/api/v1/admin/platforms/TOOMICS", json(bad));
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(fields(invalid)).contains("workUrlPattern", "urlRuleCheckedOn", "allowedHosts[1]", "allowedHosts[3]");

        Map<String, Object> taken = rules(1);
        taken.put("allowedHosts", List.of("ridibooks.com"));
        MvcResult hostTaken = admin.putRaw("/api/v1/admin/platforms/TOOMICS", json(taken));
        assertThat(fields(hostTaken)).contains("allowedHosts");

        MvcResult stale = admin.putRaw("/api/v1/admin/platforms/TOOMICS", json(rules(0)));
        assertThat(code(stale)).isEqualTo("PLATFORM_VERSION_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_hosts WHERE platform_code = 'TOOMICS'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void blankEvidenceClosedMergeTargetAndLowercaseCodeAreHandled() throws Exception {
        // 공백뿐인 근거 URL 은 "없음"이 아니라 400 이다
        Map<String, Object> blank = status(0, "UNKNOWN", "PRESS");
        blank.put("evidenceUrl", "   ");
        MvcResult blankResult = admin.postJson("/api/v1/admin/platforms/COMICO_KR/status", blank);
        assertThat(blankResult.getResponse().getStatus()).isEqualTo(400);
        assertThat(fields(blankResult)).contains("evidenceUrl");

        // 종료된 플랫폼으로는 통합할 수 없다
        Map<String, Object> closeRidi = status(0, "CLOSED", "OFFICIAL");
        closeRidi.put("evidenceUrl", "https://ridibooks.com/notice");
        assertThat(admin.postJson("/api/v1/admin/platforms/RIDI/status", closeRidi).getResponse().getStatus()).isEqualTo(200);
        MvcResult intoClosed = admin.postJson("/api/v1/admin/platforms/KAKAO_WEBTOON/status", merging(0, "RIDI"));
        assertThat(intoClosed.getResponse().getStatus()).isEqualTo(400);
        assertThat(fields(intoClosed)).contains("mergedInto");

        // 경로의 코드는 대소문자를 구분하지 않는다
        MvcResult lower = admin.get("/api/v1/admin/platforms/naver_webtoon");
        assertThat(lower.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) at(lower, "$.data.code")).isEqualTo("NAVER_WEBTOON");
        MvcResult lowerMerge = admin.postJson("/api/v1/admin/platforms/kakao_webtoon/status", merging(0, "kakao_page"));
        assertThat(lowerMerge.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) at(lowerMerge, "$.data.platform.mergedInto")).isEqualTo("KAKAO_PAGE");
        assertThat(count("platform_status_history")).isEqualTo(2);
    }

    @Test
    void noteOnlyStatusChangeIsKeptInHistory() throws Exception {
        MvcResult press = admin.postJson("/api/v1/admin/platforms/COMICO_KR/status", unknownPress(0));
        assertThat(longAt(press, "$.data.platform.version")).isEqualTo(1);

        Map<String, Object> noteOnly = unknownPress(1);
        noteOnly.put("note", "보도 메모를 고침");
        MvcResult changed = admin.postJson("/api/v1/admin/platforms/COMICO_KR/status", noteOnly);
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(longAt(changed, "$.data.historyId")).isPositive();
        assertThat(longAt(changed, "$.data.platform.version")).isEqualTo(2);
        assertThat((String) at(changed, "$.data.platform.status")).isEqualTo("UNKNOWN");

        noteOnly.put("version", 2);
        MvcResult same = admin.postJson("/api/v1/admin/platforms/COMICO_KR/status", noteOnly);
        assertThat((Object) at(same, "$.data.historyId")).isNull();
        assertThat(longAt(same, "$.data.platform.version")).isEqualTo(2);

        MvcResult history = admin.get("/api/v1/admin/platforms/COMICO_KR/status-history");
        List<String> notes = at(history, "$.data[*].note");
        assertThat(notes).containsExactly("보도 메모를 고침", "한국 서비스 종료 보도. 공식 공지 미확인");
    }

    @Test
    void closedMergeTargetKeepsRelationAndWarns() throws Exception {
        MvcResult merged = admin.postJson("/api/v1/admin/platforms/KAKAO_WEBTOON/status", merging(0, "KAKAO_PAGE"));
        assertThat(merged.getResponse().getStatus()).isEqualTo(200);
        assertThat((List<String>) at(merged, "$.data.platform.warnings")).isEmpty();

        Map<String, Object> closed = status(0, "CLOSED", "OFFICIAL");
        closed.put("evidenceUrl", "https://page.kakao.com/notice");
        MvcResult close = admin.postJson("/api/v1/admin/platforms/KAKAO_PAGE/status", closed);
        assertThat((String) at(close, "$.data.platform.status")).isEqualTo("CLOSED");
        assertThat((List<String>) at(close, "$.data.platform.warnings")).contains("MERGE_SOURCES_REMAIN");

        MvcResult source = admin.get("/api/v1/admin/platforms/KAKAO_WEBTOON");
        assertThat((String) at(source, "$.data.mergedInto")).isEqualTo("KAKAO_PAGE");
        assertThat((String) at(source, "$.data.status")).isEqualTo("MERGING");
        assertThat((List<String>) at(source, "$.data.warnings")).containsExactly("MERGED_INTO_CLOSED");
    }

    @Test
    void statusChangeListsActiveListingIdsToHandle() throws Exception {
        long workId = PlatformTestSupport.insertWork(jdbc, "작품");
        long first = longAt(admin.postJson("/api/v1/admin/works/" + workId + "/listings",
                PlatformTestSupport.ridi(0, "111")), "$.data.listing.id");
        long second = longAt(admin.postJson("/api/v1/admin/works/" + workId + "/listings",
                PlatformTestSupport.ridi(1, "222")), "$.data.listing.id");
        MvcResult result = admin.postJson("/api/v1/admin/platforms/RIDI/status", unknownPress(0));
        assertThat(longAt(result, "$.data.activeListingCount")).isEqualTo(2);
        List<Number> ids = at(result, "$.data.activeListingIds");
        assertThat(ids.stream().map(Number::longValue).toList()).containsExactly(first, second);
    }

    @Test
    void unsafeUrlPatternsAreRejected() throws Exception {
        String[] patterns = {
                "^https://toomics\\.com/(?<id>[0-9]+)/\\1$",
                "^https://toomics\\.com/(?<id>[0-9]+)/\\k<id>$",
                "^https://toomics\\.com/(?<id>[0-9]+)/\\k'id'$",
                "^https://toomics\\.com/(?=x)(?<id>[0-9]+)$",
                "^https://toomics\\.com/([0-9]+)$",
                "^https://toomics\\.com/(?<id>[0-9]+)/(?<other>[0-9]+)$",
        };
        for (String pattern : patterns) {
            Map<String, Object> rules = rules(0);
            rules.put("workUrlPattern", pattern);
            rules.put("urlRuleBasis", "OBSERVED");
            rules.put("urlRuleCheckedOn", "2026-10-12");
            MvcResult result = admin.putRaw("/api/v1/admin/platforms/TOOMICS", json(rules));
            assertThat(result.getResponse().getStatus()).as(pattern).isEqualTo(400);
            assertThat(fields(result)).as(pattern).contains("workUrlPattern");
        }
        assertThat(jdbc.queryForObject("SELECT version FROM platforms WHERE code = 'TOOMICS'", Long.class)).isZero();
    }

    // ---------- 도우미 ----------

    private Map<String, Object> rules(long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("name", "투믹스");
        body.put("defaultIdScope", "UNKNOWN");
        body.put("urlRuleBasis", "UNVERIFIED");
        body.put("allowedHosts", List.of("toomics.com"));
        return body;
    }

    private static Map<String, Object> status(long version, String toStatus, String basis) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("toStatus", toStatus);
        body.put("statusBasis", basis);
        return body;
    }

    private static Map<String, Object> unknownPress(long version) {
        Map<String, Object> body = status(version, "UNKNOWN", "PRESS");
        body.put("evidenceUrl", "https://dealsite.co.kr/articles/144036");
        body.put("note", "한국 서비스 종료 보도. 공식 공지 미확인");
        return body;
    }

    private static Map<String, Object> merging(long version, String target) {
        Map<String, Object> body = status(version, "MERGING", "OFFICIAL");
        body.put("mergedInto", target);
        body.put("evidenceUrl", KAKAO_NOTICE);
        return body;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
