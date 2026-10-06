package com.manhwanet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 실제 PostgreSQL(테스트 DB)에 연결하는 통합 테스트.
 * 실행 전 docker compose 로 PostgreSQL 이 떠 있어야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ManhwanetApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void flywayMigrationIsApplied() {
        Integer appliedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                Integer.class);

        assertThat(appliedCount).isEqualTo(1);
    }

    @Test
    void healthEndpointReportsDatabaseUp() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.database").value("UP"));
    }

    /**
     * 인증 도입 후 공개 경로 외에는 기본 거부한다. 없는 경로라도 익명은 401, 로그인 사용자는 404 를 받는다.
     * (이전 테스트는 익명 요청이 404 를 받는지 검증했다)
     */
    @Test
    void unknownPathReturnsNotFoundInCommonFormatForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/v1/not-existing-path").with(user("user@manhwanet.test").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void unknownNonPublicPathRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/not-existing-path"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void missingResourceOnPublicPathStillReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/works/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/creators/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CREATOR_NOT_FOUND"));
    }

    @Test
    void v3MigrationIsApplied() {
        Integer appliedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = TRUE",
                Integer.class);

        assertThat(appliedCount).isEqualTo(1);
    }
}
