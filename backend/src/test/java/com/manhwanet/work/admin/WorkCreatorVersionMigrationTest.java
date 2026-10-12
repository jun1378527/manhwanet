package com.manhwanet.work.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.support.TestDatabaseCleaner;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * V6(works·creators version) 마이그레이션 검증.
 * 기존 데이터 보존은 별도 스키마에 V5 까지 적용하고 데이터를 넣은 뒤 V6 을 적용해 확인한다(테스트 DB 안에서만).
 * Flyway 는 공유 커넥션 풀이 아닌 전용 단일 커넥션으로 실행해, search_path 변경이 다른 테스트로 새지 않게 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkCreatorVersionMigrationTest {

    private static final String SCHEMA = "v6_migration_check";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void v6IsAppliedWithDefaultsAndChecks() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success = TRUE", Integer.class))
                .isEqualTo(1);
        long workId = jdbcTemplate.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
        long creatorId = jdbcTemplate.queryForObject("INSERT INTO creators (name) VALUES ('작가') RETURNING id", Long.class);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, workId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM creators WHERE id = ?", Long.class, creatorId)).isZero();

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE works SET version = -1 WHERE id = ?", workId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_works_version");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE creators SET version = -1 WHERE id = ?", creatorId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_creators_version");
    }

    @Test
    void existingRowsKeepIdsRatingsAndReviewsAndStartAtVersionZero() {
        SingleConnectionDataSource dedicated = new SingleConnectionDataSource(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"),
                true);
        try {
            migrateWithSampleData(dedicated);
        } finally {
            dedicated.destroy();
        }
    }

    private void migrateWithSampleData(SingleConnectionDataSource dedicated) {
        flyway(dedicated, "5").migrate();
        String s = SCHEMA + ".";
        jdbcTemplate.update("INSERT INTO " + s + "creators (id, name) VALUES (7, '기존 작가')");
        jdbcTemplate.update("INSERT INTO " + s + "works (id, work_format) VALUES (42, 'WEBTOON')");
        jdbcTemplate.update("INSERT INTO " + s + "work_titles (work_id, title, language_code, is_primary) VALUES (42, '기존 작품', 'ko', TRUE)");
        jdbcTemplate.update("INSERT INTO " + s + "work_creators (work_id, creator_id, role) VALUES (42, 7, 'STORY')");
        jdbcTemplate.update("INSERT INTO " + s + "users (id, email, password_hash, nickname, role) VALUES (3, 'r@manhwanet.test', '{noop}x', '독자', 'USER')");
        jdbcTemplate.update("INSERT INTO " + s + "work_ratings (user_id, work_id, score_half) VALUES (3, 42, 7)");
        jdbcTemplate.update("INSERT INTO " + s + "work_reviews (user_id, work_id, body, contains_spoiler) VALUES (3, 42, '기존 리뷰는 그대로 남아야 합니다.', FALSE)");

        flyway(dedicated, null).migrate();

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM " + s + "works WHERE id = 42", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM " + s + "creators WHERE id = 7", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT score_half FROM " + s + "work_ratings WHERE work_id = 42", Integer.class))
                .isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + s + "work_reviews WHERE work_id = 42", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM " + s + "work_titles WHERE work_id = 42", String.class))
                .isEqualTo("기존 작품");
    }

    private Flyway flyway(SingleConnectionDataSource dedicated, String target) {
        var config = Flyway.configure()
                .dataSource(dedicated)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }
}
