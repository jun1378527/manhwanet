package com.manhwanet.work;

import static com.manhwanet.support.ApiFixtures.credit;
import static com.manhwanet.support.ApiFixtures.title;
import static com.manhwanet.support.ApiFixtures.workRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.support.AdminApiIntegrationTest;
import com.manhwanet.support.ApiFixtures;
import com.manhwanet.support.TestDatabaseCleaner;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AdminApiIntegrationTest
class WorkApiIntegrationTest {

    private static final String ADMIN_WORKS = "/api/v1/admin/works";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    private ApiFixtures fixtures;
    private long writerId;
    private long artistId;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        fixtures = new ApiFixtures(mockMvc, objectMapper);
        writerId = fixtures.createCreator("윤도현");   // id 1
        artistId = fixtures.createCreator("한서윤");   // id 2
    }

    // ---------- 등록 성공 ----------

    @Test
    void registersWorkWithNormalizedValuesAndCreditOrder() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("workFormat", "WEBTOON");
        request.put("originCountry", " kr ");
        request.put("description", "  몰락한 기사단 이야기  ");
        request.put("titles", List.of(
                title("Moonlight Knights", "EN", false),
                title(" 달빛 기사단　", "ko", true),
                title("月光騎士団", "ja", false),
                title("Moonlight Knights", "en-us", false)));
        request.put("creators", List.of(
                credit(writerId, "ORIGINAL_STORY"),
                credit(artistId, "ART"),
                credit(artistId, "STORY"),
                credit(writerId, "STORY")));

        fixtures.postJson(ADMIN_WORKS, request)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/works/1")))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.workFormat").value("WEBTOON"))
                .andExpect(jsonPath("$.data.originCountry").value("KR"))
                .andExpect(jsonPath("$.data.description").value("몰락한 기사단 이야기"))
                .andExpect(jsonPath("$.data.primaryTitle.title").value("달빛 기사단"))
                .andExpect(jsonPath("$.data.primaryTitle.languageCode").value("ko"))
                // 대표 제목 먼저, 나머지는 등록 순서
                .andExpect(jsonPath("$.data.titles[*].title")
                        .value(contains("달빛 기사단", "Moonlight Knights", "月光騎士団", "Moonlight Knights")))
                .andExpect(jsonPath("$.data.titles[*].languageCode").value(contains("ko", "en", "ja", "en-US")))
                .andExpect(jsonPath("$.data.titles[0].primary").value(true))
                // STORY → ART → ORIGINAL_STORY, 같은 역할은 creatorId 오름차순
                .andExpect(jsonPath("$.data.creators[*].role").value(contains("STORY", "STORY", "ART", "ORIGINAL_STORY")))
                .andExpect(jsonPath("$.data.creators[*].creatorId").value(contains(1, 2, 2, 1)))
                .andExpect(jsonPath("$.data.creators[0].name").value("윤도현"))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_creators WHERE creator_id = ?", Integer.class, artistId))
                .as("동일인이 여러 역할을 맡으면 역할별 행으로 저장")
                .isEqualTo(2);
    }

    @Test
    void allowsSameTitleInAnotherWork() throws Exception {
        fixtures.createWork(workRequest("달빛 기사단", writerId));
        fixtures.createWork(workRequest("달빛 기사단", artistId));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM works", Integer.class)).isEqualTo(2);
    }

    @Test
    void originCountryAndDescriptionAreOptional() throws Exception {
        fixtures.postJson(ADMIN_WORKS, workRequest("달빛 기사단", writerId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.originCountry").doesNotExist())
                .andExpect(jsonPath("$.data.description").doesNotExist());
    }

    // ---------- 입력 오류 ----------

    @Test
    void rejectsMissingRequiredFields() throws Exception {
        fixtures.postJson(ADMIN_WORKS, Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.fieldErrors[*].field").value(hasItem("workFormat")))
                .andExpect(jsonPath("$.error.fieldErrors[*].field").value(hasItem("titles")))
                .andExpect(jsonPath("$.error.fieldErrors[*].field").value(hasItem("creators")));
    }

    @Test
    void rejectsEmptyTitlesAndCreators() throws Exception {
        Map<String, Object> request = workRequest("달빛 기사단", writerId);
        request.put("titles", List.of());
        request.put("creators", List.of());

        fixtures.postJson(ADMIN_WORKS, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[*].field").value(hasItem("titles")))
                .andExpect(jsonPath("$.error.fieldErrors[*].field").value(hasItem("creators")));
    }

    @Test
    void rejectsUnknownWorkFormatAndRole() throws Exception {
        Map<String, Object> request = workRequest("달빛 기사단", writerId);
        request.put("workFormat", "MANHWA");
        request.put("creators", List.of(credit(writerId, "COLORIST")));

        fixtures.postJson(ADMIN_WORKS, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[*].field").value(hasItem("workFormat")))
                .andExpect(jsonPath("$.error.fieldErrors[*].field").value(hasItem("creators[0].role")));
    }

    @Test
    void requiresExactlyOnePrimaryTitle() throws Exception {
        Map<String, Object> none = workRequest("달빛 기사단", writerId);
        none.put("titles", List.of(title("달빛 기사단", "ko", false)));
        fixtures.postJson(ADMIN_WORKS, none)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("titles"));

        Map<String, Object> two = workRequest("달빛 기사단", writerId);
        two.put("titles", List.of(title("달빛 기사단", "ko", true), title("Moonlight Knights", "en", true)));
        fixtures.postJson(ADMIN_WORKS, two)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("titles"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM works", Integer.class)).isZero();
    }

    @Test
    void rejectsInvalidLanguageCountryAndLengths() throws Exception {
        Map<String, Object> request = workRequest("달빛 기사단", writerId);
        request.put("originCountry", "KOR");
        request.put("description", " ");
        request.put("titles", List.of(
                title("달빛 기사단", "ko_KR", true),
                title("　", "en", false),
                title("가".repeat(256), "ja", false),
                title("Too long tag", "en-US-x-aaaaaaaa-bbbbbbbb-cccccccc-dd", false)));

        fixtures.postJson(ADMIN_WORKS, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[*].field").value(contains(
                        "originCountry",
                        "description",
                        "titles[0].languageCode",
                        "titles[1].title",
                        "titles[2].title",
                        "titles[3].languageCode")));
    }

    @Test
    void rejectsNonIsoCountryCode() throws Exception {
        Map<String, Object> request = workRequest("달빛 기사단", writerId);
        request.put("originCountry", "ZZ");

        fixtures.postJson(ADMIN_WORKS, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("originCountry"));
    }

    @Test
    void acceptsDescriptionAtMaxLengthAndRejectsOneMoreCharacter() throws Exception {
        String maxDescription = "가".repeat(5_000);
        Map<String, Object> accepted = workRequest("달빛 기사단", writerId);
        accepted.put("description", maxDescription);

        fixtures.postJson(ADMIN_WORKS, accepted)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.description").value(maxDescription));

        Map<String, Object> rejected = workRequest("다른 작품", writerId);
        rejected.put("description", maxDescription + "가");

        fixtures.postJson(ADMIN_WORKS, rejected)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("description"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM works", Integer.class)).isEqualTo(1);
    }

    // ---------- 중복 ----------

    @Test
    void rejectsDuplicateTitleInSameLanguageAfterNormalization() throws Exception {
        Map<String, Object> request = workRequest("달빛 기사단", writerId);
        request.put("titles", List.of(
                title("달빛 기사단", "ko", true),
                title("Moonlight Knights", "en", false),
                title(" 달빛 기사단 ", "KO", false)));

        fixtures.postJson(ADMIN_WORKS, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("titles[2].title"));
    }

    @Test
    void rejectsDuplicateCreatorRoleInRequest() throws Exception {
        Map<String, Object> request = workRequest("달빛 기사단", writerId);
        request.put("creators", List.of(credit(writerId, "STORY"), credit(artistId, "ART"), credit(writerId, "STORY")));

        fixtures.postJson(ADMIN_WORKS, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("creators[2]"));
    }

    // ---------- 없는 작가 ----------

    @Test
    void rejectsUnknownCreatorWithFieldError() throws Exception {
        Map<String, Object> request = workRequest("달빛 기사단", writerId);
        request.put("creators", List.of(credit(writerId, "STORY"), credit(999L, "ART")));

        fixtures.postJson(ADMIN_WORKS, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("creators[1].creatorId"))
                .andExpect(jsonPath("$.error.fieldErrors[0].reason").value("존재하지 않는 작가입니다."));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM works", Integer.class)).isZero();
    }

    // ---------- 상세 조회 ----------

    @Test
    void getsWorkDetail() throws Exception {
        Map<String, Object> request = workRequest("달빛 기사단", writerId);
        request.put("creators", List.of(credit(artistId, "ART"), credit(writerId, "STORY")));
        long workId = fixtures.createWork(request);

        mockMvc.perform(get("/api/v1/works/{id}", workId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(workId))
                .andExpect(jsonPath("$.data.primaryTitle.title").value("달빛 기사단"))
                .andExpect(jsonPath("$.data.titles.length()").value(1))
                .andExpect(jsonPath("$.data.creators[*].role").value(contains("STORY", "ART")))
                .andExpect(jsonPath("$.data.creators[*].name").value(contains("윤도현", "한서윤")))
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());
    }

    @Test
    void missingWorkReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/works/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
    }

    // ---------- 목록 조회 ----------

    @Test
    void listsWorksWithPrimaryTitleAndCredits() throws Exception {
        Map<String, Object> request = workRequest("달빛 기사단", writerId);
        request.put("titles", List.of(title("Moonlight Knights", "en", false), title("달빛 기사단", "ko", true)));
        request.put("creators", List.of(credit(writerId, "ORIGINAL_STORY"), credit(artistId, "STORY")));
        fixtures.createWork(request);
        fixtures.createWork(workRequest("별의 정원", artistId));

        mockMvc.perform(get("/api/v1/works"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].primaryTitle.title").value("별의 정원"))
                .andExpect(jsonPath("$.data.content[1].primaryTitle.title").value("달빛 기사단"))
                .andExpect(jsonPath("$.data.content[1].primaryTitle.languageCode").value("ko"))
                .andExpect(jsonPath("$.data.content[1].creators[*].role").value(contains("STORY", "ORIGINAL_STORY")))
                .andExpect(jsonPath("$.data.content[1].creators[*].name").value(contains("한서윤", "윤도현")))
                .andExpect(jsonPath("$.data.content[1].titles").doesNotExist());
    }

    @Test
    void sortIsStableWhenCreatedAtIsEqual() throws Exception {
        for (int i = 1; i <= 3; i++) {
            fixtures.createWork(workRequest("작품 " + i, writerId));
        }
        jdbcTemplate.update("UPDATE works SET created_at = ?", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));

        assertIds(get("/api/v1/works"), 3, 2, 1);
        assertIds(get("/api/v1/works").param("sort", "createdAt,desc"), 3, 2, 1);
        assertIds(get("/api/v1/works").param("sort", "createdAt,asc"), 1, 2, 3);
        assertIds(get("/api/v1/works").param("sort", "id,asc"), 1, 2, 3);
    }

    @Test
    void sortsByCreatedAtBeforeId() throws Exception {
        for (int i = 1; i <= 3; i++) {
            fixtures.createWork(workRequest("작품 " + i, writerId));
        }
        jdbcTemplate.update("UPDATE works SET created_at = ? WHERE id = 1", Timestamp.from(Instant.parse("2030-01-01T00:00:00Z")));

        assertIds(get("/api/v1/works"), 1, 3, 2);
        assertIds(get("/api/v1/works").param("sort", "id,desc"), 3, 2, 1);
    }

    @Test
    void paginatesWorks() throws Exception {
        for (int i = 1; i <= 5; i++) {
            fixtures.createWork(workRequest("작품 " + i, writerId));
        }

        mockMvc.perform(get("/api/v1/works").param("page", "1").param("size", "2").param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id").value(contains(3, 4)))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        mockMvc.perform(get("/api/v1/works").param("page", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(5));
    }

    @Test
    void rejectsInvalidPagingInIntegration() throws Exception {
        mockMvc.perform(get("/api/v1/works").param("page", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/works").param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/works").param("sort", "description,asc")).andExpect(status().isBadRequest());
    }

    private void assertIds(org.springframework.test.web.servlet.RequestBuilder request, Integer... ids) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id").value(contains(ids)));
    }
}
