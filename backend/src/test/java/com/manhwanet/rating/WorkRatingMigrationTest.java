package com.manhwanet.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.support.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V4 마이그레이션과 work_ratings 의 실제 PostgreSQL 제약 검증.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkRatingMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    private long userId;
    private long workId;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        userId = insertUser("reader@manhwanet.test");
        workId = jdbcTemplate.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
    }

    @Test
    void v4MigrationIsApplied() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success = TRUE", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void expectedConstraintAndIndexNamesExist() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'work_ratings'::regclass", String.class))
                .contains(
                        "uk_work_ratings_user_work",
                        "fk_work_ratings_user",
                        "fk_work_ratings_work",
                        "ck_work_ratings_score_half",
                        "ck_work_ratings_updated_at");
        assertThat(jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'work_ratings'", String.class))
                .contains("uk_work_ratings_user_work", "idx_work_ratings_work_id");
        // FK 삭제 동작: 작품 CASCADE(c), 사용자 RESTRICT(r)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_work_ratings_work'", String.class)).isEqualTo("c");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_work_ratings_user'", String.class)).isEqualTo("r");
    }

    @Test
    void allowsOneRatingPerUserAndWork() {
        insertRating(userId, workId, 9);

        assertThatThrownBy(() -> insertRating(userId, workId, 3))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_work_ratings_user_work");

        long otherUser = insertUser("other@manhwanet.test");
        insertRating(otherUser, workId, 3);
        assertThat(count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void acceptsScoreHalfBoundaries(int scoreHalf) {
        insertRating(userId, workId, scoreHalf);
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11, -1})
    void rejectsScoreHalfOutOfRange(int scoreHalf) {
        assertThatThrownBy(() -> insertRating(userId, workId, scoreHalf))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_work_ratings_score_half");
    }

    @Test
    void rejectsUpdatedAtBeforeCreatedAt() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO work_ratings (user_id, work_id, score_half, created_at, updated_at)
                VALUES (?, ?, 5, now(), now() - interval '1 second')
                """, userId, workId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_work_ratings_updated_at");
    }

    @Test
    void rejectsUnknownUserOrWork() {
        assertThatThrownBy(() -> insertRating(userId + 1000, workId, 5))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_work_ratings_user");
        assertThatThrownBy(() -> insertRating(userId, workId + 1000, 5))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_work_ratings_work");
    }

    @Test
    void deletingWorkCascadesToRatings() {
        insertRating(userId, workId, 5);
        jdbcTemplate.update("DELETE FROM works WHERE id = ?", workId);
        assertThat(count()).isZero();
    }

    @Test
    void deletingUserWithRatingsIsRestricted() {
        insertRating(userId, workId, 5);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_work_ratings_user");
        assertThat(count()).isEqualTo(1);
    }

    private long insertUser(String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role)
                VALUES (?, '{noop}unused', '독자', 'USER') RETURNING id
                """, Long.class, email);
    }

    private void insertRating(long user, long work, int scoreHalf) {
        jdbcTemplate.update("INSERT INTO work_ratings (user_id, work_id, score_half) VALUES (?, ?, ?)", user, work, scoreHalf);
    }

    private int count() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_ratings", Integer.class);
    }
}
