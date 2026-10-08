package com.manhwanet.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.manhwanet.support.AdminApiIntegrationTest;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.UserService;
import jakarta.persistence.EntityManagerFactory;
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
 * 공개 리뷰 목록의 N+1 회귀 테스트.
 * "항상 SQL N개"가 아니라, 같은 페이지 크기에서 리뷰·작성자 수가 늘어도 요청당 SQL 수가 늘지 않는지 비교한다.
 * (Spring Data 는 첫 페이지가 다 차지 않는 등 전체 개수를 이미 알 수 있으면 COUNT 를 생략하므로, 비교하는 두 경우 모두 페이지를 채운다.)
 */
@AdminApiIntegrationTest
class ReviewQueryCountTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private UserService userService;

    private Statistics statistics;
    private long workId;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();
        workId = jdbcTemplate.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
    }

    @Test
    void listQueryCountDoesNotGrowWithNumberOfReviewsAndAuthors() throws Exception {
        addReviews(12); // 작성자 12명, 페이지(10개)를 채움
        long small = countStatements(get("/api/v1/works/{id}/reviews", workId));

        addReviews(40); // 작성자 52명
        mockMvc.perform(get("/api/v1/works/{id}/reviews", workId).param("size", "50"))
                .andExpect(jsonPath("$.data.content.length()").value(50))
                .andExpect(jsonPath("$.data.totalElements").value(52));
        long large = countStatements(get("/api/v1/works/{id}/reviews", workId));
        long largePage = countStatements(get("/api/v1/works/{id}/reviews", workId).param("size", "50"));

        assertThat(small).isPositive();
        assertThat(large).isEqualTo(small);
        assertThat(largePage).isEqualTo(small);
        // 작성자 닉네임은 페이지당 한 번에 읽는다
        assertThat(nicknameQueryExecutions(get("/api/v1/works/{id}/reviews", workId).param("size", "50"))).isEqualTo(1);
    }

    @Test
    void emptyPageSkipsNicknameLookup() throws Exception {
        addReviews(3);
        mockMvc.perform(get("/api/v1/works/{id}/reviews", workId).param("page", "5"))
                .andExpect(jsonPath("$.data.content").isEmpty());
        assertThat(nicknameQueryExecutions(get("/api/v1/works/{id}/reviews", workId).param("page", "5"))).isZero();
    }

    @Test
    void findNicknamesUsesOneQueryAndSkipsEmptyInput() {
        long a = insertUser("가");
        long b = insertUser("나");

        statistics.clear();
        assertThat(userService.findNicknames(List.of())).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isZero();

        statistics.clear();
        Map<Long, String> nicknames = userService.findNicknames(List.of(a, b, a, a + 1000));
        assertThat(nicknames).containsExactlyInAnyOrderEntriesOf(Map.of(a, "가", b, "나"));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    /** 닉네임 일괄 조회 JPQL(User 대상 nickname 조회)이 요청 한 번에 실행된 횟수 */
    private long nicknameQueryExecutions(RequestBuilder request) throws Exception {
        statistics.clear();
        mockMvc.perform(request).andExpect(status().isOk());
        long executions = 0;
        for (String query : statistics.getQueries()) {
            if (query.contains("u.nickname")) {
                executions += statistics.getQueryStatistics(query).getExecutionCount();
            }
        }
        return executions;
    }

    private long countStatements(RequestBuilder request) throws Exception {
        statistics.clear();
        mockMvc.perform(request).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    private void addReviews(int count) {
        for (int i = 0; i < count; i++) {
            long userId = insertUser("독자" + i);
            jdbcTemplate.update("""
                    INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler) VALUES (?, ?, ?, ?)
                    """, userId, workId, "N+1 확인용 리뷰 본문입니다 " + i, i % 2 == 0);
        }
    }

    private long insertUser(String nickname) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, role) VALUES (?, '{noop}unused', ?, 'USER') RETURNING id",
                Long.class, "reviewer-" + System.nanoTime() + "@manhwanet.test", nickname);
    }
}
