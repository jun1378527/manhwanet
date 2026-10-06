package com.manhwanet.work;

import static com.manhwanet.support.ApiFixtures.credit;
import static com.manhwanet.support.ApiFixtures.title;
import static com.manhwanet.support.ApiFixtures.workRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.support.AdminApiIntegrationTest;
import com.manhwanet.support.ApiFixtures;
import com.manhwanet.support.TestDatabaseCleaner;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * N+1 회귀 테스트.
 * 정확한 쿼리 개수가 아니라, 데이터(작품·제목·작가 수)가 늘어도 조회 요청의 SQL 실행 수가 늘지 않는지 비교한다.
 * 각 측정은 HTTP 요청 단위(새 영속성 컨텍스트)이며 2차 캐시는 사용하지 않는다.
 */
@AdminApiIntegrationTest
class WorkQueryCountTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private ApiFixtures fixtures;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        fixtures = new ApiFixtures(mockMvc, objectMapper);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();
    }

    @Test
    void listQueryCountDoesNotGrowWithNumberOfWorksAndCredits() throws Exception {
        createWorks(2, 1, 1);
        long small = countStatements(get("/api/v1/works").param("size", "50"));

        createWorks(18, 3, 4);
        mockMvc.perform(get("/api/v1/works").param("size", "50"))
                .andExpect(jsonPath("$.data.content.length()").value(20));
        long large = countStatements(get("/api/v1/works").param("size", "50"));

        assertThat(small).isPositive();
        assertThat(large).isEqualTo(small);
    }

    @Test
    void detailQueryCountDoesNotGrowWithNumberOfTitlesAndCredits() throws Exception {
        long simpleWorkId = createWork(1, 1);
        long richWorkId = createWork(6, 5);

        long simple = countStatements(get("/api/v1/works/{id}", simpleWorkId));
        long rich = countStatements(get("/api/v1/works/{id}", richWorkId));

        assertThat(simple).isPositive();
        assertThat(rich).isEqualTo(simple);
    }

    private long countStatements(RequestBuilder request) throws Exception {
        statistics.clear();
        mockMvc.perform(request).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    private void createWorks(int count, int titlesPerWork, int creatorsPerWork) throws Exception {
        for (int i = 0; i < count; i++) {
            createWork(titlesPerWork, creatorsPerWork);
        }
    }

    private long createWork(int titleCount, int creatorCount) throws Exception {
        List<Map<String, Object>> titles = new ArrayList<>();
        titles.add(title("대표 제목 " + System.nanoTime(), "ko", true));
        for (int i = 1; i < titleCount; i++) {
            titles.add(title("Alt title " + i, "en", false));
        }
        List<Map<String, Object>> credits = new ArrayList<>();
        for (int i = 0; i < creatorCount; i++) {
            credits.add(credit(fixtures.createCreator("작가 " + i), i % 2 == 0 ? "STORY" : "ART"));
        }
        Map<String, Object> request = workRequest("unused", 1L);
        request.put("titles", titles);
        request.put("creators", credits);
        return fixtures.createWork(request);
    }
}
