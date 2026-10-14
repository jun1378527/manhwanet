package com.manhwanet.work.admin;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.support.ApiFixtures;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import java.sql.Timestamp;
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
 * 작품·작가 관리자 수정 API (#38, docs/work-platform-admin-spec.md §5.1·§5.2·§7).
 * 실제 로그인 세션·쿠키 CSRF·PostgreSQL 테스트 DB 사용.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkAdminUpdateApiIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@manhwanet.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private AdminAccountService adminAccountService;

    private AuthTestClient admin;
    private long storyId;
    private long artId;
    private long otherId;
    private long workId;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        adminAccountService.createAdmin(ADMIN_EMAIL, STRONG_PASSWORD, "관리자");
        admin = new AuthTestClient(mockMvc, objectMapper);
        assertThat(admin.login(ADMIN_EMAIL, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(200);

        storyId = createCreator("이동건");
        artId = createCreator("한서윤");
        otherId = createCreator("박지");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("workFormat", "WEBTOON");
        request.put("originCountry", "KR");
        request.put("description", "처음 줄거리");
        request.put("titles", List.of(
                ApiFixtures.title("유미의 세포들", "ko", true),
                ApiFixtures.title("Yumi's Cells", "en", false)));
        request.put("creators", List.of(ApiFixtures.credit(storyId, "STORY"), ApiFixtures.credit(storyId, "ART")));
        request.put("evidence", allEvidence());
        MvcResult created = admin.postJson("/api/v1/admin/works", request);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        workId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.data.id")).longValue();
    }

    // ---------- 권한·CSRF ----------

    @Test
    void adminGetReturnsVersion() throws Exception {
        MvcResult result = admin.get("/api/v1/admin/works/" + workId);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(body, "$.data.version")).longValue()).isZero();
        assertThat((String) JsonPath.read(body, "$.data.primaryTitle.title")).isEqualTo("유미의 세포들");
    }

    @Test
    void anonymousAndUserCannotReadOrUpdate() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/admin/works/" + workId)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/admin/creators/" + storyId)).andReturn().getResponse().getStatus())
                .isEqualTo(401);

        AuthTestClient anonymous = new AuthTestClient(mockMvc, objectMapper);
        MvcResult anonymousPut = anonymous.putRaw("/api/v1/admin/works/" + workId, json(updateRequest(0)));
        assertThat(anonymousPut.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(anonymousPut)).isEqualTo("UNAUTHORIZED");

        AuthTestClient user = new AuthTestClient(mockMvc, objectMapper);
        user.signup("user@manhwanet.test", STRONG_PASSWORD, "독자");
        assertThat(user.get("/api/v1/admin/works/" + workId).getResponse().getStatus()).isEqualTo(403);
        MvcResult userPut = user.putRaw("/api/v1/admin/works/" + workId, json(updateRequest(0)));
        assertThat(userPut.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(userPut)).isEqualTo("FORBIDDEN");
        MvcResult userCreatorPut = user.putRaw("/api/v1/admin/creators/" + storyId,
                json(Map.of("version", 0, "name", "바뀐 이름", "reason", "작가 이름 수정")));
        assertThat(userCreatorPut.getResponse().getStatus()).isEqualTo(403);

        assertUnchanged();
        assertThat(creatorName(storyId)).isEqualTo("이동건");
    }

    @Test
    void adminWithoutCsrfIsRejected() throws Exception {
        MvcResult result = admin.performWithSessionOnly(put("/api/v1/admin/works/" + workId)
                .contentType(MediaType.APPLICATION_JSON).content(json(updateRequest(0))));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(result)).isEqualTo("INVALID_CSRF_TOKEN");

        MvcResult creator = admin.performWithSessionOnly(put("/api/v1/admin/creators/" + storyId)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0, "name", "바뀐 이름", "reason", "작가 이름 수정"))));
        assertThat(creator.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(creator)).isEqualTo("INVALID_CSRF_TOKEN");

        assertUnchanged();
        assertThat(creatorName(storyId)).isEqualTo("이동건");
    }

    // ---------- 수정 ----------

    @Test
    void updateReplacesPrimaryTitleAndCreditsAtomicallyAndKeepsWorkId() throws Exception {
        MvcResult result = admin.putRaw("/api/v1/admin/works/" + workId, json(updateRequest(0)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();

        assertThat(((Number) JsonPath.read(body, "$.data.id")).longValue()).isEqualTo(workId);
        assertThat(((Number) JsonPath.read(body, "$.data.version")).longValue()).isEqualTo(1);
        assertThat((String) JsonPath.read(body, "$.data.workFormat")).isEqualTo("PAGE_COMIC");
        assertThat((String) JsonPath.read(body, "$.data.originCountry")).isEqualTo("JP");
        assertThat((Object) JsonPath.read(body, "$.data.description")).isNull();
        assertThat((String) JsonPath.read(body, "$.data.primaryTitle.title")).isEqualTo("Yumi's Cells");
        assertThat((String) JsonPath.read(body, "$.data.primaryTitle.languageCode")).isEqualTo("en");
        List<String> titles = JsonPath.read(body, "$.data.titles[*].title");
        assertThat(titles).containsExactlyInAnyOrder("Yumi's Cells", "유미의 세포들", "ユミの細胞たち");
        List<Integer> creatorIds = JsonPath.read(body, "$.data.creators[*].creatorId");
        List<String> roles = JsonPath.read(body, "$.data.creators[*].role");
        assertThat(creatorIds).containsExactly((int) storyId, (int) artId);
        assertThat(roles).containsExactly("STORY", "ART");

        // DB: 대표 제목은 정확히 1개, 크레딧은 요청 그대로
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM work_titles WHERE work_id = ? AND is_primary", Integer.class, workId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM work_creators WHERE work_id = ?", Integer.class, workId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM works", Integer.class)).isEqualTo(1);

        // 공개 상세에도 반영되고 version 은 노출되지 않는다
        String publicBody = mockMvc.perform(get("/api/v1/works/" + workId)).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(publicBody, "$.data.primaryTitle.title")).isEqualTo("Yumi's Cells");
        assertThat(publicBody).doesNotContain("\"version\"");
    }

    @Test
    void staleVersionReturns409WithCurrentVersionAndChangesNothing() throws Exception {
        assertThat(admin.putRaw("/api/v1/admin/works/" + workId, json(updateRequest(0))).getResponse().getStatus())
                .isEqualTo(200);
        Timestamp updatedAt = updatedAt();

        Map<String, Object> stale = updateRequest(0);
        stale.put("titles", List.of(ApiFixtures.title("오래된 화면의 제목", "ko", true)));
        MvcResult result = admin.putRaw("/api/v1/admin/works/" + workId, json(stale));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        String body = result.getResponse().getContentAsString();
        assertThat(code(result)).isEqualTo("WORK_VERSION_CONFLICT");
        assertThat(((Number) JsonPath.read(body, "$.error.currentVersion")).longValue()).isEqualTo(1);
        assertThat(version()).isEqualTo(1);
        assertThat(updatedAt()).isEqualTo(updatedAt);
        assertThat(primaryTitle()).isEqualTo("Yumi's Cells");
    }

    @Test
    void futureVersionIsAlsoAConflict() throws Exception {
        MvcResult result = admin.putRaw("/api/v1/admin/works/" + workId, json(updateRequest(5)));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.error.currentVersion")).longValue())
                .isZero();
        assertUnchanged();
    }

    @Test
    void sameContentDoesNotBumpVersionOrUpdatedAt() throws Exception {
        Timestamp before = updatedAt();
        Map<String, Object> same = new LinkedHashMap<>();
        same.put("version", 0);
        same.put("workFormat", "WEBTOON");
        same.put("originCountry", "kr");
        same.put("description", "  처음 줄거리  ");
        // 순서가 달라도 같은 내용이다
        same.put("titles", List.of(
                ApiFixtures.title("Yumi's Cells", "en", false),
                ApiFixtures.title("유미의 세포들", "ko", true)));
        same.put("creators", List.of(ApiFixtures.credit(storyId, "ART"), ApiFixtures.credit(storyId, "STORY")));
        same.put("reason", "같은 내용 재전송");

        MvcResult result = admin.putRaw("/api/v1/admin/works/" + workId, json(same));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.version")).longValue())
                .isZero();
        assertThat(version()).isZero();
        assertThat(updatedAt()).isEqualTo(before);
    }

    @Test
    void coreOnlyChangeKeepsTitleAndCreditRows() throws Exception {
        List<Long> titleIds = jdbcTemplate.queryForList("SELECT id FROM work_titles WHERE work_id = ? ORDER BY id", Long.class, workId);
        List<Long> creditIds = jdbcTemplate.queryForList("SELECT id FROM work_creators WHERE work_id = ? ORDER BY id", Long.class, workId);
        Map<String, Object> request = currentContent(0);
        request.put("description", "바뀐 줄거리");

        MvcResult result = admin.putRaw("/api/v1/admin/works/" + workId, json(request));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.version")).longValue()).isEqualTo(1);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.data.description")).isEqualTo("바뀐 줄거리");
        // 제목·크레딧이 같으면 행을 다시 만들지 않는다
        assertThat(jdbcTemplate.queryForList("SELECT id FROM work_titles WHERE work_id = ? ORDER BY id", Long.class, workId))
                .isEqualTo(titleIds);
        assertThat(jdbcTemplate.queryForList("SELECT id FROM work_creators WHERE work_id = ? ORDER BY id", Long.class, workId))
                .isEqualTo(creditIds);
    }

    @Test
    void titleOnlyChangeKeepsCreditRows() throws Exception {
        List<Long> creditIds = jdbcTemplate.queryForList("SELECT id FROM work_creators WHERE work_id = ? ORDER BY id", Long.class, workId);
        Map<String, Object> request = currentContent(0);
        request.put("titles", List.of(ApiFixtures.title("유미의 세포들", "ko", true)));

        MvcResult result = admin.putRaw("/api/v1/admin/works/" + workId, json(request));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        List<String> titles = JsonPath.read(result.getResponse().getContentAsString(), "$.data.titles[*].title");
        assertThat(titles).containsExactly("유미의 세포들");
        assertThat(jdbcTemplate.queryForList("SELECT id FROM work_creators WHERE work_id = ? ORDER BY id", Long.class, workId))
                .isEqualTo(creditIds);
        assertThat(version()).isEqualTo(1);
    }

    @Test
    void invalidInputReturns400AndChangesNothing() throws Exception {
        Map<String, Object> missingVersion = updateRequest(0);
        missingVersion.remove("version");
        assertInvalid(json(missingVersion), "version");

        Map<String, Object> stringVersion = updateRequest(0);
        stringVersion.put("version", "0");
        assertInvalid(json(stringVersion), "version");

        Map<String, Object> negative = updateRequest(0);
        negative.put("version", -1);
        assertInvalid(json(negative), "version");

        assertInvalid(json(updateRequest(0)).replace("\"version\":0", "\"version\":0.0"), "version");

        Map<String, Object> twoPrimary = updateRequest(0);
        twoPrimary.put("titles", List.of(ApiFixtures.title("가", "ko", true), ApiFixtures.title("나", "ko", true)));
        assertInvalid(json(twoPrimary), "titles");

        Map<String, Object> noPrimary = updateRequest(0);
        noPrimary.put("titles", List.of(ApiFixtures.title("가", "ko", false)));
        assertInvalid(json(noPrimary), "titles");

        Map<String, Object> missingCreator = updateRequest(0);
        missingCreator.put("creators", List.of(ApiFixtures.credit(999_999, "STORY")));
        assertInvalid(json(missingCreator), "creators[0].creatorId");

        Map<String, Object> duplicateCredit = updateRequest(0);
        duplicateCredit.put("creators", List.of(ApiFixtures.credit(storyId, "STORY"), ApiFixtures.credit(storyId, "STORY")));
        assertInvalid(json(duplicateCredit), "creators[1]");

        Map<String, Object> noCredits = updateRequest(0);
        noCredits.put("creators", List.of());
        assertInvalid(json(noCredits), "creators");

        assertUnchanged();
    }

    @Test
    void missingWorkReturns404() throws Exception {
        MvcResult result = admin.putRaw("/api/v1/admin/works/999999", json(updateRequest(0)));
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(result)).isEqualTo("WORK_NOT_FOUND");
        assertThat(admin.get("/api/v1/admin/works/999999").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void ratingsReviewsAndIdsArePreservedAcrossUpdates() throws Exception {
        long readerA = insertUser("a@manhwanet.test", "독자A");
        long readerB = insertUser("b@manhwanet.test", "독자B");
        jdbcTemplate.update("INSERT INTO work_ratings (user_id, work_id, score_half) VALUES (?, ?, 9), (?, ?, 6)",
                readerA, workId, readerB, workId);
        jdbcTemplate.update("""
                INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler)
                VALUES (?, ?, '수정 전후로 남아 있어야 하는 리뷰입니다.', FALSE)
                """, readerA, workId);
        List<Map<String, Object>> ratingsBefore = jdbcTemplate.queryForList(
                "SELECT id, user_id, work_id, score_half, updated_at FROM work_ratings ORDER BY id");
        List<Map<String, Object>> reviewsBefore = jdbcTemplate.queryForList(
                "SELECT id, user_id, work_id, body, version, updated_at FROM work_reviews ORDER BY id");
        String summaryBefore = JsonPath.read(admin.get("/api/v1/admin/works/" + workId).getResponse()
                .getContentAsString(), "$.data.rating").toString();

        assertThat(admin.putRaw("/api/v1/admin/works/" + workId, json(updateRequest(0))).getResponse().getStatus())
                .isEqualTo(200);
        Map<String, Object> second = updateRequest(1);
        second.put("creators", List.of(ApiFixtures.credit(otherId, "ORIGINAL_STORY")));
        assertThat(admin.putRaw("/api/v1/admin/works/" + workId, json(second)).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(admin.putRaw("/api/v1/admin/creators/" + storyId,
                json(Map.of("version", 0, "name", "이동건(수정)", "reason", "작가 이름 수정"))).getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbcTemplate.queryForList(
                "SELECT id, user_id, work_id, score_half, updated_at FROM work_ratings ORDER BY id")).isEqualTo(ratingsBefore);
        assertThat(jdbcTemplate.queryForList(
                "SELECT id, user_id, work_id, body, version, updated_at FROM work_reviews ORDER BY id")).isEqualTo(reviewsBefore);
        String detail = admin.get("/api/v1/admin/works/" + workId).getResponse().getContentAsString();
        assertThat(JsonPath.read(detail, "$.data.rating").toString()).isEqualTo(summaryBefore);
        assertThat(((Number) JsonPath.read(detail, "$.data.id")).longValue()).isEqualTo(workId);
        assertThat(((Number) JsonPath.read(detail, "$.data.version")).longValue()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM creators", Integer.class)).isEqualTo(3);
    }

    // ---------- 작가 ----------

    @Test
    void creatorUpdateChangesNameEverywhereAndReportsLinkedWorks() throws Exception {
        String before = admin.get("/api/v1/admin/creators/" + storyId).getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(before, "$.data.version")).longValue()).isZero();
        assertThat(((Number) JsonPath.read(before, "$.data.linkedWorkCount")).longValue()).isEqualTo(1);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("version", 0);
        request.put("name", "  이동건 작가  ");
        request.put("originalName", "Lee Dong-geon");
        request.put("reason", "원어 표기 추가");
        MvcResult result = admin.putRaw("/api/v1/admin/creators/" + storyId, json(request));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(body, "$.data.version")).longValue()).isEqualTo(1);
        assertThat((String) JsonPath.read(body, "$.data.name")).isEqualTo("이동건 작가");
        assertThat((String) JsonPath.read(body, "$.data.originalName")).isEqualTo("Lee Dong-geon");
        assertThat(((Number) JsonPath.read(body, "$.data.id")).longValue()).isEqualTo(storyId);

        // 작품 상세의 크레딧 이름도 바뀐다(작가 ID로 참조)
        String workDetail = mockMvc.perform(get("/api/v1/works/" + workId)).andReturn().getResponse().getContentAsString();
        List<String> creditNames = JsonPath.read(workDetail, "$.data.creators[*].name");
        assertThat(creditNames).containsOnly("이동건 작가");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, workId)).isZero();

        // 같은 내용은 version 을 올리지 않는다
        request.put("version", 1);
        MvcResult same = admin.putRaw("/api/v1/admin/creators/" + storyId, json(request));
        assertThat(((Number) JsonPath.read(same.getResponse().getContentAsString(), "$.data.version")).longValue())
                .isEqualTo(1);

        // 오래된 version 은 409, 현재 version 을 알려 준다
        MvcResult stale = admin.putRaw("/api/v1/admin/creators/" + storyId,
                json(Map.of("version", 0, "name", "오래된 화면", "reason", "작가 이름 수정")));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("CREATOR_VERSION_CONFLICT");
        assertThat(((Number) JsonPath.read(stale.getResponse().getContentAsString(), "$.error.currentVersion")).longValue())
                .isEqualTo(1);
        assertThat(creatorName(storyId)).isEqualTo("이동건 작가");

        // originalName 을 빼면 비운다(전체 교체)
        MvcResult cleared = admin.putRaw("/api/v1/admin/creators/" + storyId, json(Map.of("version", 1, "name", "이동건 작가", "reason", "작가 이름 수정")));
        assertThat((Object) JsonPath.read(cleared.getResponse().getContentAsString(), "$.data.originalName")).isNull();
    }

    @Test
    void creatorUpdateValidatesInput() throws Exception {
        MvcResult missingName = admin.putRaw("/api/v1/admin/creators/" + storyId, json(Map.of("version", 0)));
        assertThat(missingName.getResponse().getStatus()).isEqualTo(400);
        assertThat(fields(missingName)).contains("name");

        MvcResult blank = admin.putRaw("/api/v1/admin/creators/" + storyId,
                json(Map.of("version", 0, "name", "   ", "originalName", " ", "reason", "작가 이름 수정")));
        assertThat(fields(blank)).contains("name", "originalName");

        MvcResult badVersion = admin.putRaw("/api/v1/admin/creators/" + storyId, "{\"version\":\"0\",\"name\":\"가\"}");
        assertThat(fields(badVersion)).contains("version");

        MvcResult missing = admin.putRaw("/api/v1/admin/creators/999999", json(Map.of("version", 0, "name", "가", "reason", "작가 이름 수정")));
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("CREATOR_NOT_FOUND");

        assertThat(creatorName(storyId)).isEqualTo("이동건");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM creators WHERE id = ?", Long.class, storyId)).isZero();
    }

    // ---------- 도우미 ----------

    private Map<String, Object> updateRequest(long version) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("version", version);
        request.put("workFormat", "PAGE_COMIC");
        request.put("originCountry", "JP");
        request.put("titles", List.of(
                ApiFixtures.title("유미의 세포들", "ko", false),
                ApiFixtures.title("Yumi's Cells", "en", true),
                ApiFixtures.title("ユミの細胞たち", "ja", false)));
        request.put("creators", List.of(ApiFixtures.credit(storyId, "STORY"), ApiFixtures.credit(artId, "ART")));
        request.put("evidence", allEvidence());
        request.put("reason", "작품 정보 수정");
        return request;
    }

    /** 제목·크레딧·줄거리 근거(직접 확인, #40) */
    private static List<Map<String, Object>> allEvidence() {
        return List.of(ApiFixtures.evidence("TITLE"), ApiFixtures.evidence("CREDIT"), ApiFixtures.evidence("DESCRIPTION"));
    }

    private Map<String, Object> currentContent(long version) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("version", version);
        request.put("workFormat", "WEBTOON");
        request.put("originCountry", "KR");
        request.put("description", "처음 줄거리");
        request.put("titles", List.of(
                ApiFixtures.title("유미의 세포들", "ko", true),
                ApiFixtures.title("Yumi's Cells", "en", false)));
        request.put("creators", List.of(ApiFixtures.credit(storyId, "STORY"), ApiFixtures.credit(storyId, "ART")));
        request.put("evidence", allEvidence());
        request.put("reason", "작품 정보 수정");
        return request;
    }

    private void assertInvalid(String rawJson, String field) throws Exception {
        MvcResult result = admin.putRaw("/api/v1/admin/works/" + workId, rawJson);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(result)).isEqualTo("INVALID_INPUT");
        assertThat(fields(result)).contains(field);
    }

    private void assertUnchanged() {
        assertThat(version()).isZero();
        assertThat(primaryTitle()).isEqualTo("유미의 세포들");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_titles WHERE work_id = ?", Integer.class, workId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT creator_id, role FROM work_creators WHERE work_id = ? ORDER BY role", workId))
                .extracting(row -> row.get("creator_id") + ":" + row.get("role"))
                .containsExactly(storyId + ":ART", storyId + ":STORY");
    }

    private long version() {
        return jdbcTemplate.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, workId);
    }

    private Timestamp updatedAt() {
        return jdbcTemplate.queryForObject("SELECT updated_at FROM works WHERE id = ?", Timestamp.class, workId);
    }

    private String primaryTitle() {
        return jdbcTemplate.queryForObject(
                "SELECT title FROM work_titles WHERE work_id = ? AND is_primary", String.class, workId);
    }

    private String creatorName(long creatorId) {
        return jdbcTemplate.queryForObject("SELECT name FROM creators WHERE id = ?", String.class, creatorId);
    }

    private long createCreator(String name) throws Exception {
        MvcResult result = admin.postJson("/api/v1/admin/creators", Map.of("name", name));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")).longValue();
    }

    private long insertUser(String email, String nickname) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role) VALUES (?, '{noop}unused', ?, 'USER') RETURNING id
                """, Long.class, email, nickname);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String code(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.error.code");
    }

    private static List<String> fields(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.error.fieldErrors[*].field");
    }
}
