package com.manhwanet.global.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import com.manhwanet.support.AdminApiIntegrationTest;
import com.manhwanet.support.TestDatabaseCleaner;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 목록 page·size·offset 지원 범위 (#20, 실제 PostgreSQL·실제 보안 필터·익명 요청).
 * offset = page × size 가 Integer.MAX_VALUE 이하이면 정상 응답(빈 페이지 포함), 넘으면 400 INVALID_INPUT(fieldErrors[page]).
 * 거절된 요청은 SQL 을 하나도 실행하지 않는다(Repository 조회 전에 거절).
 */
@AdminApiIntegrationTest
class PaginationLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private long workId;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();
        workId = jdbcTemplate.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO work_titles (work_id, title, language_code, is_primary) VALUES (?, '달빛 기사단', 'ko', TRUE)", workId);
        jdbcTemplate.update("INSERT INTO creators (name) VALUES ('한서윤')");
        long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role)
                VALUES ('reader@manhwanet.test', '{noop}unused', '독자', 'USER') RETURNING id
                """, Long.class);
        jdbcTemplate.update("INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler) VALUES (?, ?, '페이지 경계 확인용 리뷰입니다.', FALSE)",
                userId, workId);
    }

    private List<String> listPaths() {
        return List.of("/api/v1/works", "/api/v1/creators", "/api/v1/works/" + workId + "/reviews");
    }

    // ---------- 지원 경계: 정상 응답 ----------

    @ParameterizedTest
    @CsvSource({
            "/api/v1/works, 100, 21474836",
            "/api/v1/works, 20, 107374182",
            "/api/v1/works, 1, 2147483647",
            "/api/v1/creators, 100, 21474836",
            "/api/v1/creators, 1, 2147483647",
            "REVIEWS, 50, 42949672",
            "REVIEWS, 10, 214748364",
            "REVIEWS, 1, 2147483647"})
    void largestSupportedPageReturnsEmptyPage(String path, int size, int maxPage) throws Exception {
        MvcResult result = request(resolve(path), maxPage, size);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(body, "$.data.content")).isEmpty();
        assertThat((Integer) JsonPath.read(body, "$.data.page")).isEqualTo(maxPage);
        assertThat((Integer) JsonPath.read(body, "$.data.size")).isEqualTo(size);
        assertThat(((Number) JsonPath.read(body, "$.data.totalElements")).longValue()).isEqualTo(1);
    }

    @Test
    void normalPagesAndDefaultsAreUnchanged() throws Exception {
        for (String path : listPaths()) {
            MvcResult result = mockMvc.perform(get(path)).andReturn();
            assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
            String body = result.getResponse().getContentAsString();
            assertThat((Integer) JsonPath.read(body, "$.data.page")).isZero();
            assertThat((List<?>) JsonPath.read(body, "$.data.content")).hasSize(1);
        }
        assertThat((Integer) JsonPath.read(mockMvc.perform(get("/api/v1/works")).andReturn().getResponse().getContentAsString(),
                "$.data.size")).isEqualTo(20);
        assertThat((Integer) JsonPath.read(mockMvc.perform(get("/api/v1/creators")).andReturn().getResponse().getContentAsString(),
                "$.data.size")).isEqualTo(20);
        assertThat((Integer) JsonPath.read(mockMvc.perform(get("/api/v1/works/" + workId + "/reviews")).andReturn().getResponse()
                .getContentAsString(), "$.data.size")).isEqualTo(10);
    }

    // ---------- 지원 범위 밖: 400 + fieldErrors[page], SQL 미실행 ----------

    @ParameterizedTest
    @CsvSource({
            "/api/v1/works, 100, 21474837",
            "/api/v1/works, 100, 2147483647",
            "/api/v1/works, 20, 107374183",
            "/api/v1/works, 2, 1073741824",
            "/api/v1/creators, 100, 21474837",
            "/api/v1/creators, 100, 2147483647",
            "REVIEWS, 50, 42949673",
            "REVIEWS, 50, 2147483647",
            "REVIEWS, 2, 1073741824"})
    void pagePastSupportedOffsetIsBadRequestOnPageAndNeverQueries(String path, int size, int page) throws Exception {
        MvcResult result = assertRejected(resolve(path) + "?page=" + page + "&size=" + size, "page");
        String reason = JsonPath.read(result.getResponse().getContentAsString(), "$.error.fieldErrors[0].reason");
        assertThat(reason).contains("0 이상 " + PageableFactory.maxPage(size) + " 이하");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "page=-1|page",
            "page=2147483648|page",
            "page=99999999999999999999|page",
            "page=abc|page",
            "page=1.5|page",
            "page=1e3|page",
            "size=0|size",
            "size=-1|size",
            "size=2147483648|size",
            "size=abc|size"})
    void malformedOrOutOfIntRangeValuesAreBadRequest(String query, String field) throws Exception {
        for (String path : listPaths()) {
            assertRejected(path + "?" + query, field);
        }
    }

    @Test
    void sizeAboveEachListLimitIsBadRequest() throws Exception {
        assertRejected("/api/v1/works?size=101", "size");
        assertRejected("/api/v1/creators?size=101", "size");
        assertRejected("/api/v1/works/" + workId + "/reviews?size=51", "size");
    }

    // ---------- helpers ----------

    private String resolve(String path) {
        return "REVIEWS".equals(path) ? "/api/v1/works/" + workId + "/reviews" : path;
    }

    private MvcResult request(String path, int page, int size) throws Exception {
        return mockMvc.perform(get(path).param("page", String.valueOf(page)).param("size", String.valueOf(size))).andReturn();
    }

    /** 400 INVALID_INPUT, 첫 필드 오류가 field, 그리고 이 요청 동안 SQL 이 실행되지 않았는지 */
    private MvcResult assertRejected(String url, String field) throws Exception {
        statistics.clear();
        MvcResult result = mockMvc.perform(get(url)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(url).isEqualTo(400);
        assertThat((String) JsonPath.read(body, "$.error.code")).as(url).isEqualTo("INVALID_INPUT");
        assertThat((String) JsonPath.read(body, "$.error.fieldErrors[0].field")).as(url).isEqualTo(field);
        assertThat(statistics.getPrepareStatementCount()).as("SQL executed for rejected " + url).isZero();
        return result;
    }
}
