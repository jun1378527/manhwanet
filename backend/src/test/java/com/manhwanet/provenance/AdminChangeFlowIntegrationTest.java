package com.manhwanet.provenance;

import static com.manhwanet.provenance.ProvenanceTestSupport.at;
import static com.manhwanet.provenance.ProvenanceTestSupport.code;
import static com.manhwanet.provenance.ProvenanceTestSupport.count;
import static com.manhwanet.provenance.ProvenanceTestSupport.evidence;
import static com.manhwanet.provenance.ProvenanceTestSupport.externalId;
import static com.manhwanet.provenance.ProvenanceTestSupport.fields;
import static com.manhwanet.provenance.ProvenanceTestSupport.insertCreator;
import static com.manhwanet.provenance.ProvenanceTestSupport.longAt;
import static com.manhwanet.provenance.ProvenanceTestSupport.status;
import static com.manhwanet.provenance.ProvenanceTestSupport.workVersion;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 기존 등록·수정 API 의 #40 확장: 등록과 함께 넣는 연결·외부 ID·링크·근거, 근거 필수 규칙, 수정 사유와 변경 기록,
 * 작가 외부 ID, 연결·플랫폼 변경 기록, 별점·리뷰·ID 보존.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminChangeFlowIntegrationTest {

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
    private long creatorId;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseCleaner.clean(jdbc, environment);
        admin = ProvenanceTestSupport.admin(mockMvc, objectMapper, adminAccountService);
        adminId = ProvenanceTestSupport.adminId(jdbc);
        creatorId = insertCreator(jdbc, "이동건");
    }

    // ---------- 작품 등록 ----------

    @Test
    void registerSavesListingsExternalIdsLinksAndEvidenceTogether() throws Exception {
        MvcResult created = admin.postJson("/api/v1/admin/works", fullRegistration());
        assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(201);
        long workId = longAt(created, "$.data.id");
        assertThat(longAt(created, "$.data.version")).isZero();
        assertThat((String) at(created, "$.data.listings[0].platformCode")).isEqualTo("NAVER_WEBTOON");
        assertThat((String) at(created, "$.data.externalIds[0].externalId")).isEqualTo("Q123");
        assertThat((String) at(created, "$.data.links[0].url")).isEqualTo("https://publisher.example.com/yumi");
        List<String> fieldsWithSources = at(created, "$.data.fieldSources[*].field");
        assertThat(fieldsWithSources).containsExactlyInAnyOrder("TITLE", "CREDIT", "DESCRIPTION", "LISTING");
        long listingId = longAt(created, "$.data.listings[0].id");
        assertThat(jdbc.queryForObject("SELECT listing_id FROM work_field_sources WHERE field = 'LISTING'", Long.class))
                .isEqualTo(listingId);

        List<String> logged = jdbc.queryForList(
                "SELECT entity_type || ':' || action FROM admin_change_log WHERE work_id = ? ORDER BY id", String.class, workId);
        assertThat(logged).containsExactly("WORK:CREATE", "LISTING:CREATE", "FIELD_SOURCE:CREATE",
                "WORK_EXTERNAL_ID:CREATE", "WORK_LINK:CREATE", "FIELD_SOURCE:CREATE", "FIELD_SOURCE:CREATE",
                "FIELD_SOURCE:CREATE");
        assertThat(jdbc.queryForList("SELECT DISTINCT actor_id FROM admin_change_log", Long.class)).containsExactly(adminId);

        // 같은 요청을 다시 보내면(연결·외부 ID 가 같으므로) 409 이고 작품이 두 번 생기지 않는다
        MvcResult retry = admin.postJson("/api/v1/admin/works", fullRegistration());
        assertThat(code(retry)).isEqualTo("PLATFORM_LISTING_ALREADY_LINKED");
        assertThat(longAt(retry, "$.error.existingWorkId")).isEqualTo(workId);
        Map<String, Object> onlyExternal = fullRegistration();
        onlyExternal.remove("listings");
        MvcResult retryExternal = admin.postJson("/api/v1/admin/works", onlyExternal);
        assertThat(code(retryExternal)).isEqualTo("EXTERNAL_ID_ALREADY_LINKED");
        assertThat(longAt(retryExternal, "$.error.existingWorkId")).isEqualTo(workId);
        assertThat(count(jdbc, "works")).isEqualTo(1);
        assertThat(count(jdbc, "work_titles")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_log WHERE entity_type = 'WORK'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void registerRequiresEvidenceAndValidatesNestedInputsBeforeSaving() throws Exception {
        Map<String, Object> noEvidence = registration("근거 없음");
        noEvidence.remove("evidence");
        noEvidence.put("description", "직접 쓴 줄거리");
        MvcResult missing = admin.postJson("/api/v1/admin/works", noEvidence);
        assertThat(status(missing)).isEqualTo(400);
        List<String> reasons = at(missing, "$.error.fieldErrors[*].reason");
        assertThat(reasons).anyMatch(r -> r.contains("TITLE")).anyMatch(r -> r.contains("CREDIT"))
                .anyMatch(r -> r.contains("DESCRIPTION"));

        Map<String, Object> bad = fullRegistration();
        Map<String, Object> listing = firstListing(bad);
        listing.remove("evidence");
        listing.put("url", "https://evil.example.com/webtoon/list?titleId=651673");
        bad.put("externalIds", List.of(Map.of("source", "WIKIDATA", "externalId", "X1", "verifiedAt", "2026-10-12T00:00:00Z"),
                Map.of("source", "WIKIDATA", "externalId", "X1", "verifiedAt", "2026-10-12T00:00:00Z")));
        bad.put("links", List.of(Map.of("linkType", "PUBLISHER", "url", "https://ridibooks.com/books/1",
                "languageCode", "ko", "verifiedAt", "2026-10-12T00:00:00Z")));
        MvcResult invalid = admin.postJson("/api/v1/admin/works", bad);
        assertThat(fields(invalid)).contains("listings[0].evidence", "listings[0].url", "externalIds[0].externalId",
                "externalIds[1].externalId");
        // 링크 플랫폼 호스트 검사는 다른 입력이 모두 맞을 때 한다
        Map<String, Object> platformLink = registration("링크만 틀림");
        platformLink.put("links", bad.get("links"));
        assertThat(fields(admin.postJson("/api/v1/admin/works", platformLink))).contains("links[0].url");
        assertThat(count(jdbc, "works") + count(jdbc, "admin_change_log")).isZero();
    }

    // ---------- 작품 수정 ----------

    @Test
    void workUpdateNeedsReasonAndEvidenceForChangedFieldsAndIsLogged() throws Exception {
        long workId = longAt(admin.postJson("/api/v1/admin/works", registration("처음 제목")), "$.data.id");
        int logsAfterCreate = count(jdbc, "admin_change_log");

        Map<String, Object> noReason = update(0, "바뀐 제목");
        noReason.remove("reason");
        assertThat(fields(admin.putRaw(path(workId), json(noReason)))).contains("reason");

        Map<String, Object> noTitleEvidence = update(0, "바뀐 제목");
        noTitleEvidence.put("evidence", List.of(evidence("CREDIT", "STAFF", null)));
        MvcResult missing = admin.putRaw(path(workId), json(noTitleEvidence));
        assertThat(status(missing)).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT title FROM work_titles WHERE work_id = ?", String.class, workId))
                .isEqualTo("처음 제목");
        assertThat(workVersion(jdbc, workId)).isZero();

        // 제목만 바뀌면 TITLE 근거만 있으면 된다
        Map<String, Object> ok = update(0, "바뀐 제목");
        ok.put("evidence", List.of(evidence("TITLE", "PLATFORM_PAGE_MANUAL", "작품 페이지 제목")));
        MvcResult updated = admin.putRaw(path(workId), json(ok));
        assertThat(status(updated)).as(updated.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(longAt(updated, "$.data.version")).isEqualTo(1);
        MvcResult log = admin.get("/api/v1/admin/change-log?entityType=WORK&entityId=" + workId);
        assertThat((String) at(log, "$.data.entries[0].action")).isEqualTo("UPDATE");
        assertThat((String) at(log, "$.data.entries[0].reason")).isEqualTo("제목 오타 수정");
        assertThat((String) at(log, "$.data.entries[0].before.titles[0].title")).isEqualTo("처음 제목");
        assertThat((String) at(log, "$.data.entries[0].after.titles[0].title")).isEqualTo("바뀐 제목");
        assertThat(count(jdbc, "admin_change_log")).isEqualTo(logsAfterCreate + 2);

        // 같은 내용은 version·기록·근거가 그대로
        int sources = count(jdbc, "work_field_sources");
        Map<String, Object> same = update(1, "바뀐 제목");
        same.put("evidence", List.of(evidence("TITLE", "STAFF", null)));
        assertThat(longAt(admin.putRaw(path(workId), json(same)), "$.data.version")).isEqualTo(1);
        assertThat(count(jdbc, "admin_change_log")).isEqualTo(logsAfterCreate + 2);
        assertThat(count(jdbc, "work_field_sources")).isEqualTo(sources);

        // 줄거리를 새로 쓰면 STAFF·LICENSED 근거가 필요하다
        Map<String, Object> description = update(1, "바뀐 제목");
        description.put("description", "직접 쓴 줄거리");
        description.put("evidence", List.of(evidence("DESCRIPTION", "PUBLISHER_PAGE", "출판사 소개")));
        assertThat(fields(admin.putRaw(path(workId), json(description)))).contains("evidence[0].sourceType");
        description.put("evidence", List.of(evidence("DESCRIPTION", "LICENSED", "출판사 허락 메일 2026-10-12")));
        assertThat(status(admin.putRaw(path(workId), json(description)))).isEqualTo(200);
    }

    // ---------- 작가 ----------

    @Test
    void creatorExternalIdsAndNameChangesAreVersionedAndLogged() throws Exception {
        Map<String, Object> creator = new LinkedHashMap<>();
        creator.put("name", "한서윤");
        creator.put("externalIds", List.of(Map.of("source", "WIKIDATA", "externalId", "Q900",
                "verifiedAt", "2026-10-12T00:00:00Z")));
        MvcResult created = admin.postJson("/api/v1/admin/creators", creator);
        assertThat(status(created)).isEqualTo(201);
        long id = longAt(created, "$.data.id");
        assertThat((String) at(created, "$.data.externalIds[0].externalId")).isEqualTo("Q900");
        assertThat(longAt(created, "$.data.version")).isZero();

        // 같은 Wikidata ID 를 다른 작가에 연결할 수 없다(등록 전체 롤백)
        Map<String, Object> other = new LinkedHashMap<>(creator);
        other.put("name", "동명이인");
        MvcResult duplicate = admin.postJson("/api/v1/admin/creators", other);
        assertThat(code(duplicate)).isEqualTo("EXTERNAL_ID_ALREADY_LINKED");
        assertThat(longAt(duplicate, "$.error.existingCreatorId")).isEqualTo(id);
        assertThat(count(jdbc, "creators")).isEqualTo(2);

        String externalPath = "/api/v1/admin/creators/" + id + "/external-ids";
        MvcResult added = admin.postJson(externalPath, externalId(0, "NDL", "00123456"));
        assertThat(longAt(added, "$.data.ownerVersion")).isEqualTo(1);
        assertThat((String) at(added, "$.data.ownerType")).isEqualTo("CREATOR");
        assertThat(code(admin.postJson(externalPath, externalId(0, "KMAS", "k1")))).isEqualTo("CREATOR_VERSION_CONFLICT");
        long ndl = longAt(added, "$.data.externalId.id");
        assertThat(longAt(admin.patchJson(externalPath + "/" + ndl, status(1, "SUPERSEDED", "새 전거로 대체")),
                "$.data.ownerVersion")).isEqualTo(2);
        assertThat(code(admin.patchJson("/api/v1/admin/creators/" + creatorId + "/external-ids/" + ndl,
                status(0, "INVALID", "x")))).isEqualTo("EXTERNAL_ID_NOT_FOUND");

        assertThat(fields(admin.putRaw("/api/v1/admin/creators/" + id, json(Map.of("version", 2, "name", "한서윤 작가")))))
                .contains("reason");
        MvcResult renamed = admin.putRaw("/api/v1/admin/creators/" + id,
                json(Map.of("version", 2, "name", "한서윤 작가", "reason", "필명 표기 통일")));
        assertThat(longAt(renamed, "$.data.version")).isEqualTo(3);
        MvcResult log = admin.get("/api/v1/admin/change-log?creatorId=" + id);
        List<String> actions = at(log, "$.data.entries[*].action");
        assertThat(actions).containsExactly("UPDATE", "STATUS_CHANGE", "CREATE", "CREATE", "CREATE");
        assertThat((String) at(log, "$.data.entries[0].before.name")).isEqualTo("한서윤");
        assertThat(longAt(log, "$.data.entries[0].after.linkedWorkCount")).isZero();
    }

    // ---------- 연결·플랫폼 ----------

    @Test
    void listingAndPlatformChangesNeedReasonAndAreLogged() throws Exception {
        long workId = longAt(admin.postJson("/api/v1/admin/works", registration("작품")), "$.data.id");
        String listings = path(workId) + "/listings";
        Map<String, Object> noEvidence = listing("RIDI", "100", "https://ridibooks.com/books/100");
        noEvidence.put("version", 0);
        noEvidence.remove("evidence");
        assertThat(fields(admin.postJson(listings, noEvidence))).contains("evidence");

        Map<String, Object> ridi = listing("RIDI", "100", "https://ridibooks.com/books/100");
        ridi.put("version", 0);
        long a = longAt(admin.postJson(listings, ridi), "$.data.listing.id");
        Map<String, Object> ridi2 = listing("RIDI", "200", "https://ridibooks.com/books/200");
        ridi2.put("version", 1);
        long b = longAt(admin.postJson(listings, ridi2), "$.data.listing.id");

        Map<String, Object> end = new LinkedHashMap<>();
        end.put("version", 2);
        end.put("url", "https://ridibooks.com/books/100");
        end.put("idScope", "SERIES");
        end.put("listingKind", "MAIN");
        end.put("languageCode", "ko");
        end.put("status", "MOVED");
        end.put("movedToListingId", b);
        assertThat(fields(admin.putRaw(listings + "/" + a, json(end)))).contains("reason");
        end.put("reason", "같은 작품의 새 판본으로 이전");
        assertThat(status(admin.putRaw(listings + "/" + a, json(end)))).isEqualTo(200);

        Map<String, Object> move = new LinkedHashMap<>();
        move.put("version", 3);
        move.put("newListing", listing("RIDI", "300", "https://ridibooks.com/books/300"));
        move.put("reason", "리디 ID 변경");
        assertThat(status(admin.postJson(listings + "/" + b + "/move", move))).isEqualTo(201);
        List<String> listingLog = jdbc.queryForList("""
                SELECT entity_id || ':' || action FROM admin_change_log WHERE entity_type = 'LISTING' ORDER BY id""",
                String.class);
        long c = jdbc.queryForObject("SELECT id FROM work_platform_listings WHERE platform_work_id = '300'", Long.class);
        // a 는 b 로 옮겨졌다가, b 가 c 로 옮겨질 때 c 를 가리키도록 평탄화된다(그것도 기록)
        assertThat(listingLog).containsExactly(a + ":CREATE", b + ":CREATE", a + ":STATUS_CHANGE", c + ":CREATE",
                b + ":MOVE", a + ":UPDATE");

        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("version", 0);
        platform.put("toStatus", "UNKNOWN");
        platform.put("statusBasis", "PRESS");
        platform.put("evidenceUrl", "https://news.example.com/a");
        assertThat(fields(admin.postJson("/api/v1/admin/platforms/COMICO_KR/status", platform))).contains("reason");
        platform.put("reason", "종료 보도 확인(공식 공지 미확인)");
        assertThat(status(admin.postJson("/api/v1/admin/platforms/comico_kr/status", platform))).isEqualTo(200);
        MvcResult platformLog = admin.get("/api/v1/admin/change-log?entityType=PLATFORM&entityId=comico_kr");
        assertThat((String) at(platformLog, "$.data.entries[0].action")).isEqualTo("STATUS_CHANGE");
        assertThat((String) at(platformLog, "$.data.entries[0].before.statusBasis")).isEqualTo("UNVERIFIED");
        assertThat((String) at(platformLog, "$.data.entries[0].after.platform.statusBasis")).isEqualTo("PRESS");
    }

    // ---------- 보존 ----------

    @Test
    void ratingsReviewsAndIdsArePreserved() throws Exception {
        long workId = longAt(admin.postJson("/api/v1/admin/works", registration("보존 확인")), "$.data.id");
        long reader = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role) VALUES ('r@manhwanet.test', '{noop}x', '독자', 'USER')
                RETURNING id""", Long.class);
        jdbc.update("INSERT INTO work_ratings (user_id, work_id, score_half) VALUES (?, ?, 9)", reader, workId);
        jdbc.update("INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler) VALUES (?, ?, '근거를 바꿔도 남아야 하는 리뷰입니다.', FALSE)",
                reader, workId);
        List<Map<String, Object>> ratings = jdbc.queryForList("SELECT * FROM work_ratings ORDER BY id");
        List<Map<String, Object>> reviews = jdbc.queryForList("SELECT * FROM work_reviews ORDER BY id");

        admin.postJson(path(workId) + "/external-ids", externalId(0, "WIKIDATA", "Q5"));
        admin.postJson(path(workId) + "/links", ProvenanceTestSupport.link(1, "https://publisher.example.com/p"));
        Map<String, Object> ok = update(2, "보존 확인 2");
        ok.put("evidence", List.of(evidence("TITLE", "STAFF", null)));
        assertThat(status(admin.putRaw(path(workId), json(ok)))).isEqualTo(200);

        assertThat(jdbc.queryForList("SELECT * FROM work_ratings ORDER BY id")).isEqualTo(ratings);
        assertThat(jdbc.queryForList("SELECT * FROM work_reviews ORDER BY id")).isEqualTo(reviews);
        assertThat(jdbc.queryForObject("SELECT id FROM works", Long.class)).isEqualTo(workId);
        String detail = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/works/" + workId)).andReturn().getResponse().getContentAsString();
        // 공개 상세에는 아직 출처를 노출하지 않는다(#42)
        assertThat(detail).doesNotContain("Q5").doesNotContain("externalIds").doesNotContain("fieldSources");
    }

    // ---------- 도우미 ----------

    private Map<String, Object> registration(String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workFormat", "WEBTOON");
        body.put("titles", List.of(Map.of("title", title, "languageCode", "ko", "primary", true)));
        body.put("creators", List.of(Map.of("creatorId", creatorId, "role", "STORY")));
        body.put("evidence", List.of(evidence("TITLE", "STAFF", null), evidence("CREDIT", "STAFF", null)));
        return body;
    }

    private Map<String, Object> fullRegistration() {
        Map<String, Object> body = registration("유미의 세포들");
        body.put("description", "직접 쓴 짧은 소개");
        body.put("evidence", List.of(evidence("TITLE", "PLATFORM_PAGE_MANUAL", "네이버웹툰 작품 페이지"),
                evidence("CREDIT", "PLATFORM_PAGE_MANUAL", "네이버웹툰 작품 페이지"),
                evidence("DESCRIPTION", "STAFF", null)));
        body.put("listings", List.of(listing("NAVER_WEBTOON", "651673",
                "https://comic.naver.com/webtoon/list?titleId=651673")));
        body.put("externalIds", List.of(Map.of("source", "WIKIDATA", "externalId", "Q123",
                "verifiedAt", "2026-10-12T00:00:00Z")));
        body.put("links", List.of(Map.of("linkType", "OFFICIAL_SITE", "url", "https://publisher.example.com/yumi",
                "languageCode", "ko", "verifiedAt", "2026-10-12T00:00:00Z")));
        body.put("reason", "파일럿 등록");
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstListing(Map<String, Object> body) {
        List<Map<String, Object>> listings = (List<Map<String, Object>>) body.get("listings");
        Map<String, Object> copy = new LinkedHashMap<>(listings.get(0));
        body.put("listings", List.of(copy));
        return copy;
    }

    private static Map<String, Object> listing(String platform, String id, String url) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platformCode", platform);
        body.put("platformWorkId", id);
        body.put("url", url);
        body.put("idScope", "SERIES");
        body.put("listingKind", "MAIN");
        body.put("languageCode", "ko");
        body.put("ageRatingRaw", "12세 이용가");
        body.put("verifiedAt", "2026-10-12T00:00:00Z");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceType", "PLATFORM_PAGE_MANUAL");
        evidence.put("sourceUrl", url);
        evidence.put("verifiedAt", "2026-10-12T00:00:00Z");
        body.put("evidence", List.of(evidence));
        return body;
    }

    private Map<String, Object> update(long version, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("workFormat", "WEBTOON");
        body.put("titles", List.of(Map.of("title", title, "languageCode", "ko", "primary", true)));
        body.put("creators", List.of(Map.of("creatorId", creatorId, "role", "STORY")));
        body.put("reason", "제목 오타 수정");
        return body;
    }

    private static String path(long workId) {
        return "/api/v1/admin/works/" + workId;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
