package com.manhwanet.provenance;

import static com.manhwanet.provenance.ProvenanceTestSupport.at;
import static com.manhwanet.provenance.ProvenanceTestSupport.code;
import static com.manhwanet.provenance.ProvenanceTestSupport.count;
import static com.manhwanet.provenance.ProvenanceTestSupport.evidence;
import static com.manhwanet.provenance.ProvenanceTestSupport.externalId;
import static com.manhwanet.provenance.ProvenanceTestSupport.fields;
import static com.manhwanet.provenance.ProvenanceTestSupport.insertCreator;
import static com.manhwanet.provenance.ProvenanceTestSupport.insertWork;
import static com.manhwanet.provenance.ProvenanceTestSupport.link;
import static com.manhwanet.provenance.ProvenanceTestSupport.longAt;
import static com.manhwanet.provenance.ProvenanceTestSupport.status;
import static com.manhwanet.provenance.ProvenanceTestSupport.workVersion;
import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 작품 외부 ID·공식 링크·필드별 근거·관리 변경 기록 API (#40). 실제 로그인 세션·쿠키 CSRF·PostgreSQL 테스트 DB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkProvenanceApiIntegrationTest {

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
    private long adminId;
    private long workId;
    private long otherWorkId;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseCleaner.clean(jdbc, environment);
        admin = ProvenanceTestSupport.admin(mockMvc, objectMapper, adminAccountService);
        adminId = ProvenanceTestSupport.adminId(jdbc);
        long creatorId = insertCreator(jdbc, "이동건");
        workId = insertWork(jdbc, "유미의 세포들", creatorId);
        otherWorkId = insertWork(jdbc, "다른 작품", creatorId);
    }

    // ---------- 권한·CSRF ----------

    @Test
    void onlyAdminWithCsrfCanChangeOrReadProvenance() throws Exception {
        String path = externalIds(workId);
        // 익명: CSRF 토큰을 갖춰도 401(토큰이 없으면 CSRF 필터가 먼저 403)
        AuthTestClient anonymous = new AuthTestClient(mockMvc, objectMapper);
        assertThat(status(anonymous.postJson(path, externalId(0, "WIKIDATA", "Q1")))).isEqualTo(401);
        assertThat(status(anonymous.patchJson(path + "/1", status(0, "INVALID", "x")))).isEqualTo(401);
        assertThat(status(mockMvc.perform(get("/api/v1/admin/change-log?workId=" + workId)).andReturn())).isEqualTo(401);

        AuthTestClient user = new AuthTestClient(mockMvc, objectMapper);
        user.signup("reader@manhwanet.test", STRONG_PASSWORD, "독자");
        assertThat(status(user.postJson(path, externalId(0, "WIKIDATA", "Q1")))).isEqualTo(403);
        assertThat(status(user.patchJson(path + "/1", status(0, "INVALID", "x")))).isEqualTo(403);
        assertThat(status(user.postJson(links(workId), link(0, "https://publisher.example.com/a")))).isEqualTo(403);
        assertThat(status(user.get("/api/v1/admin/change-log?workId=" + workId))).isEqualTo(403);

        MvcResult noCsrf = admin.performWithSessionOnly(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json(externalId(0, "WIKIDATA", "Q1"))));
        assertThat(code(noCsrf)).isEqualTo("INVALID_CSRF_TOKEN");
        MvcResult noCsrfPatch = admin.performWithSessionOnly(patch(path + "/1").contentType(MediaType.APPLICATION_JSON)
                .content(json(status(0, "INVALID", "x"))));
        assertThat(code(noCsrfPatch)).isEqualTo("INVALID_CSRF_TOKEN");
        assertThat(count(jdbc, "work_external_ids") + count(jdbc, "admin_change_log")).isZero();
        assertThat(workVersion(jdbc, workId)).isZero();
    }

    // ---------- 외부 ID ----------

    @Test
    void externalIdIsAddedWithSourceAndVerificationAndLogged() throws Exception {
        Map<String, Object> body = externalId(0, "WIKIDATA", " q42 ");
        body.put("sourceUrl", "https://www.wikidata.org/wiki/Q42");
        body.put("reason", "Wikidata 항목 확인");
        MvcResult added = admin.postJson(externalIds(workId), body);
        assertThat(status(added)).isEqualTo(201);
        assertThat((String) at(added, "$.data.externalId.externalId")).isEqualTo("Q42");
        assertThat((String) at(added, "$.data.externalId.status")).isEqualTo("ACTIVE");
        assertThat((String) at(added, "$.data.externalId.verifiedAt")).isEqualTo("2026-10-12T00:00:00Z");
        assertThat(longAt(added, "$.data.externalId.verifiedBy")).isEqualTo(adminId);
        assertThat(longAt(added, "$.data.ownerVersion")).isEqualTo(1);
        List<String> noWarnings = at(added, "$.data.warnings");
        assertThat(noWarnings).isEmpty();

        // 형식 규칙을 확인하지 못한 출처는 경고한다
        MvcResult madb = admin.postJson(externalIds(workId), externalId(1, "MADB", "C123456"));
        List<String> warnings = at(madb, "$.data.warnings");
        assertThat(warnings).containsExactly("EXTERNAL_ID_FORMAT_UNVERIFIED");

        MvcResult log = admin.get("/api/v1/admin/change-log?workId=" + workId);
        List<String> types = at(log, "$.data.entries[*].entityType");
        assertThat(types).containsExactly("WORK_EXTERNAL_ID", "WORK_EXTERNAL_ID");
        assertThat((String) at(log, "$.data.entries[1].reason")).isEqualTo("Wikidata 항목 확인");
        assertThat(longAt(log, "$.data.entries[0].actorId")).isEqualTo(adminId);
        assertThat((Object) at(log, "$.data.entries[1].before")).isNull();
        assertThat((String) at(log, "$.data.entries[1].after.externalId")).isEqualTo("Q42");
        // 관리자 이메일은 기록에 남기지 않는다
        assertThat(log.getResponse().getContentAsString()).doesNotContain(ProvenanceTestSupport.ADMIN_EMAIL);
    }

    @Test
    void sameSourceIdCannotBeLinkedTwiceButInvalidFreesIt() throws Exception {
        long first = longAt(admin.postJson(externalIds(workId), externalId(0, "WIKIDATA", "Q7")), "$.data.externalId.id");
        // 같은 번호라도 출처가 다르면 다른 식별자
        assertThat(status(admin.postJson(externalIds(otherWorkId), externalId(0, "NDL", "Q7")))).isEqualTo(201);

        MvcResult duplicate = admin.postJson(externalIds(otherWorkId), externalId(1, "WIKIDATA", "q7"));
        assertThat(status(duplicate)).isEqualTo(409);
        assertThat(code(duplicate)).isEqualTo("EXTERNAL_ID_ALREADY_LINKED");
        assertThat(longAt(duplicate, "$.error.existingWorkId")).isEqualTo(workId);
        assertThat(longAt(duplicate, "$.error.existingExternalIdId")).isEqualTo(first);
        // 같은 작품에 다시 넣어도 409(이미 있는 행의 상태를 바꿔 쓴다)
        assertThat(code(admin.postJson(externalIds(workId), externalId(1, "WIKIDATA", "Q7"))))
                .isEqualTo("EXTERNAL_ID_ALREADY_LINKED");
        assertThat(workVersion(jdbc, otherWorkId)).isEqualTo(1);

        // 사유 없는 상태 변경은 400, 사유를 적고 INVALID 로 바꾸면 자리가 빈다
        assertThat(fields(admin.patchJson(externalIds(workId) + "/" + first, status(1, "INVALID", null)))).contains("reason");
        MvcResult invalid = admin.patchJson(externalIds(workId) + "/" + first, status(1, "INVALID", "다른 작품의 항목이었음"));
        assertThat(status(invalid)).isEqualTo(200);
        assertThat((String) at(invalid, "$.data.externalId.status")).isEqualTo("INVALID");
        assertThat(status(admin.postJson(externalIds(otherWorkId), externalId(1, "WIKIDATA", "Q7")))).isEqualTo(201);
        // 그 뒤 원래 행을 되살리면 409
        MvcResult revive = admin.patchJson(externalIds(workId) + "/" + first, status(2, "ACTIVE", "되살림 시도"));
        assertThat(code(revive)).isEqualTo("EXTERNAL_ID_ALREADY_LINKED");
        assertThat(longAt(revive, "$.error.existingWorkId")).isEqualTo(otherWorkId);
        assertThat(workVersion(jdbc, workId)).isEqualTo(2);

        // 같은 내용이면 version·기록이 그대로
        int logs = count(jdbc, "admin_change_log");
        MvcResult same = admin.patchJson(externalIds(workId) + "/" + first, status(2, "INVALID", "같은 내용"));
        assertThat(longAt(same, "$.data.ownerVersion")).isEqualTo(2);
        assertThat(count(jdbc, "admin_change_log")).isEqualTo(logs);
        assertThat(jdbc.queryForObject("""
                SELECT action FROM admin_change_log WHERE entity_type = 'WORK_EXTERNAL_ID' AND entity_id = ?
                ORDER BY id DESC LIMIT 1""", String.class, String.valueOf(first))).isEqualTo("STATUS_CHANGE");
    }

    @Test
    void externalIdInputVersionAndIdsAreValidated() throws Exception {
        String[][] bad = {
                {"WIKIDATA", "Q0", "externalId"},
                {"WIKIDATA", "12345", "externalId"},
                {"WIKIDATA", "Q1234567890123", "externalId"},
                {"NDL", "a b", "externalId"},
                {"NDL", "작품1", "externalId"},
                {"VIAF", "1", "source"},
        };
        for (String[] c : bad) {
            MvcResult result = admin.postJson(externalIds(workId), externalId(0, c[0], c[1]));
            assertThat(status(result)).as(c[0] + ":" + c[1]).isEqualTo(400);
            assertThat(fields(result)).as(c[0] + ":" + c[1]).contains(c[2]);
        }
        Map<String, Object> badUrl = externalId(0, "NDL", "x1");
        badUrl.put("sourceUrl", "http://ndl.example.jp/x1");
        badUrl.put("verifiedAt", "2999-01-01T00:00:00Z");
        badUrl.put("version", "0");
        assertThat(fields(admin.postJson(externalIds(workId), badUrl))).contains("sourceUrl", "verifiedAt", "version");
        Map<String, Object> localUrl = externalId(0, "NDL", "x1");
        localUrl.put("sourceUrl", "https://127.0.0.1/x");
        assertThat(fields(admin.postJson(externalIds(workId), localUrl))).contains("sourceUrl");

        MvcResult stale = admin.postJson(externalIds(workId), externalId(5, "NDL", "x1"));
        assertThat(code(stale)).isEqualTo("WORK_VERSION_CONFLICT");
        assertThat(longAt(stale, "$.error.currentVersion")).isZero();
        assertThat(code(admin.postJson(externalIds(999_999_999L), externalId(0, "NDL", "x1")))).isEqualTo("WORK_NOT_FOUND");
        assertThat(status(admin.postJson("/api/v1/admin/works/99999999999999999999/external-ids",
                externalId(0, "NDL", "x1")))).isEqualTo(400);
        assertThat(code(admin.patchJson(externalIds(workId) + "/" + Long.MAX_VALUE, status(0, "INVALID", "x"))))
                .isEqualTo("EXTERNAL_ID_NOT_FOUND");

        // 다른 작품의 외부 ID 는 이 작품 경로로 바꿀 수 없다
        long other = longAt(admin.postJson(externalIds(otherWorkId), externalId(0, "KMAS", "K-1")), "$.data.externalId.id");
        assertThat(code(admin.patchJson(externalIds(workId) + "/" + other, status(0, "INVALID", "x"))))
                .isEqualTo("EXTERNAL_ID_NOT_FOUND");
        assertThat(count(jdbc, "work_external_ids")).isEqualTo(1);
        assertThat(workVersion(jdbc, workId)).isZero();
    }

    // ---------- 공식 링크 ----------

    @Test
    void officialLinksAreValidatedAndSeparatedFromPlatformListings() throws Exception {
        MvcResult added = admin.postJson(links(workId), link(0, "HTTPS://Publisher.Example.com:443/works/%7Eyumi"));
        assertThat(status(added)).isEqualTo(201);
        assertThat((String) at(added, "$.data.link.url")).isEqualTo("https://publisher.example.com/works/~yumi");
        long linkId = longAt(added, "$.data.link.id");

        String[] bad = {
                "http://publisher.example.com/a",
                "javascript:alert(1)",
                "https://user@publisher.example.com/a",
                "https://publisher.example.com:8443/a",
                "https://publisher.example.com/a#x",
                "https://192.168.0.1/a",
                "https://localhost/a",
                "https://publisher.example.com/a/../b",
                "https://publisher.example.com/a​",
        };
        for (String url : bad) {
            MvcResult result = admin.postJson(links(workId), link(1, url));
            assertThat(status(result)).as(url).isEqualTo(400);
            assertThat(fields(result)).as(url).contains("url");
        }
        // 플랫폼 작품 페이지는 공식 링크가 아니라 연결로 등록한다
        MvcResult platform = admin.postJson(links(workId), link(1, "https://comic.naver.com/webtoon/list?titleId=651673"));
        assertThat(fields(platform)).contains("url");

        assertThat(code(admin.postJson(links(workId), link(1, "https://publisher.example.com/works/~yumi"))))
                .isEqualTo("WORK_LINK_ALREADY_EXISTS");
        // 다른 작품에는 같은 URL 을 둘 수 있다(같은 출판사 목록 페이지 등)
        assertThat(status(admin.postJson(links(otherWorkId), link(0, "https://publisher.example.com/works/~yumi"))))
                .isEqualTo(201);

        Map<String, Object> broken = status(1, "BROKEN", "출판사 페이지가 열리지 않음");
        broken.put("verifiedAt", "2026-10-13T00:00:00Z");
        MvcResult changed = admin.patchJson(links(workId) + "/" + linkId, broken);
        assertThat((String) at(changed, "$.data.link.status")).isEqualTo("BROKEN");
        assertThat((String) at(changed, "$.data.link.verifiedAt")).isEqualTo("2026-10-13T00:00:00Z");
        assertThat(longAt(changed, "$.data.workVersion")).isEqualTo(2);
        assertThat(code(admin.patchJson(links(otherWorkId) + "/" + linkId, status(1, "REMOVED", "x"))))
                .isEqualTo("WORK_LINK_NOT_FOUND");
        assertThat(count(jdbc, "work_links")).isEqualTo(2);
    }

    // ---------- 필드별 근거 ----------

    @Test
    void fieldSourcesFollowSourceRulesAndAreRetractedNotDeleted() throws Exception {
        long listingId = jdbc.queryForObject("""
                INSERT INTO work_platform_listings (work_id, platform_code, platform_work_id, id_scope, url, listing_kind,
                    language_code, first_verified_at, last_verified_at, verified_by)
                VALUES (?, 'NAVER_WEBTOON', '651673', 'SERIES', 'https://comic.naver.com/webtoon/list?titleId=651673',
                    'MAIN', 'ko', now(), now(), ?) RETURNING id""", Long.class, workId, adminId);
        long otherListing = jdbc.queryForObject("""
                INSERT INTO work_platform_listings (work_id, platform_code, platform_work_id, id_scope, url, listing_kind,
                    language_code, first_verified_at, last_verified_at, verified_by)
                VALUES (?, 'RIDI', '1', 'SERIES', 'https://ridibooks.com/books/1', 'MAIN', 'ko', now(), now(), ?)
                RETURNING id""", Long.class, otherWorkId, adminId);

        Map<String, Object> listingEvidence = evidence("LISTING", "PLATFORM_PAGE_MANUAL", null);
        listingEvidence.put("sourceUrl", "https://comic.naver.com/webtoon/list?titleId=651673");
        listingEvidence.put("listingId", listingId);
        Map<String, Object> title = evidence("TITLE", "PLATFORM_PAGE_MANUAL", "네이버웹툰 작품 페이지");
        title.put("targetRef", "ko:유미의 세포들");
        MvcResult added = admin.postJson(sources(workId), Map.of("version", 0,
                "evidence", List.of(listingEvidence, title, evidence("DESCRIPTION", "STAFF", null))));
        assertThat(status(added)).isEqualTo(201);
        List<String> addedFields = at(added, "$.data.fieldSources[*].field");
        assertThat(addedFields).containsExactly("LISTING", "TITLE", "DESCRIPTION");
        assertThat(longAt(added, "$.data.workVersion")).isEqualTo(1);

        // 줄거리는 직접 작성·허락받은 글만, 허락받은 글은 허락 근거 필요, STAFF 외에는 근거 위치 필요
        Map<String, Object> wrongListing = evidence("LISTING", "PLATFORM_PAGE_MANUAL", "x");
        wrongListing.put("listingId", otherListing);
        MvcResult invalid = admin.postJson(sources(workId), Map.of("version", 1, "evidence", List.of(
                evidence("DESCRIPTION", "PLATFORM_PAGE_MANUAL", "x"),
                evidence("DESCRIPTION", "LICENSED", null),
                evidence("TITLE", "WIKIDATA", null),
                evidence("LISTING", "PLATFORM_PAGE_MANUAL", "x"),
                evidence("NICKNAME", "STAFF", null))));
        assertThat(fields(invalid)).contains("evidence[0].sourceType", "evidence[1].sourceRef", "evidence[2].sourceRef",
                "evidence[3].listingId", "evidence[4].field");
        MvcResult otherWorkListing = admin.postJson(sources(workId), Map.of("version", 1, "evidence", List.of(wrongListing)));
        assertThat(fields(otherWorkListing)).contains("evidence[0].listingId");
        assertThat(fields(admin.postJson(sources(workId), Map.of("version", 1, "evidence", List.of())))).contains("evidence");

        long titleSource = ((Number) at(added, "$.data.fieldSources[1].id")).longValue();
        MvcResult retracted = admin.patchJson(sources(workId) + "/" + titleSource, status(1, "RETRACTED", "다른 판본의 제목"));
        assertThat((String) at(retracted, "$.data.fieldSources[0].status")).isEqualTo("RETRACTED");
        assertThat(count(jdbc, "work_field_sources")).isEqualTo(3);

        MvcResult detail = admin.get("/api/v1/admin/works/" + workId);
        assertThat(longAt(detail, "$.data.version")).isEqualTo(2);
        List<String> statuses = at(detail, "$.data.fieldSources[*].status");
        assertThat(statuses).containsExactly("ACTIVE", "RETRACTED", "ACTIVE");
        assertThat(longAt(detail, "$.data.listings[0].id")).isEqualTo(listingId);
    }

    // ---------- 변경 기록 조회 ----------

    @Test
    void changeLogQueryValidatesFiltersAndPages() throws Exception {
        for (int i = 0; i < 3; i++) {
            admin.postJson(externalIds(workId), externalId(i, "NDL", "n" + i));
        }
        MvcResult first = admin.get("/api/v1/admin/change-log?workId=" + workId + "&limit=2");
        List<Number> ids = at(first, "$.data.entries[*].id");
        assertThat(ids).hasSize(2);
        long next = longAt(first, "$.data.nextBeforeId");
        MvcResult second = admin.get("/api/v1/admin/change-log?workId=" + workId + "&limit=2&beforeId=" + next);
        List<Number> rest = at(second, "$.data.entries[*].id");
        assertThat(rest).hasSize(1);
        assertThat((Object) at(second, "$.data.nextBeforeId")).isNull();

        long externalIdId = jdbc.queryForObject("SELECT id FROM work_external_ids WHERE external_id = 'n1'", Long.class);
        MvcResult byEntity = admin.get("/api/v1/admin/change-log?entityType=work_external_id&entityId=" + externalIdId);
        List<String> entityIds = at(byEntity, "$.data.entries[*].entityId");
        assertThat(entityIds).containsExactly(String.valueOf(externalIdId));

        String[] bad = {"", "?workId=1&creatorId=1", "?entityType=WORK", "?workId=0", "?workId=99999999999999999999",
                "?workId=1&limit=101", "?workId=abc", "?entityType=RATING&entityId=1"};
        for (String query : bad) {
            assertThat(status(admin.get("/api/v1/admin/change-log" + query))).as(query).isEqualTo(400);
        }
    }

    private static String externalIds(long workId) {
        return "/api/v1/admin/works/" + workId + "/external-ids";
    }

    private static String links(long workId) {
        return "/api/v1/admin/works/" + workId + "/links";
    }

    private static String sources(long workId) {
        return "/api/v1/admin/works/" + workId + "/field-sources";
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
