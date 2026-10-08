package com.manhwanet.review;

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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V5 마이그레이션과 work_reviews 의 실제 PostgreSQL 제약 검증.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkReviewMigrationTest {

    private static final String BODY = "그림체가 좋고 결말도 좋았다.";

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
    void v5MigrationIsApplied() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '5' AND success = TRUE", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void expectedConstraintAndIndexNamesExist() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'work_reviews'::regclass", String.class))
                .contains(
                        "uk_work_reviews_user_work",
                        "fk_work_reviews_user",
                        "fk_work_reviews_work",
                        "ck_work_reviews_body_length",
                        "ck_work_reviews_body_not_blank",
                        "ck_work_reviews_version",
                        "ck_work_reviews_updated_at");
        assertThat(jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'work_reviews'", String.class))
                .contains("uk_work_reviews_user_work", "idx_work_reviews_work_created");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_work_reviews_work_created'", String.class))
                .contains("(work_id, created_at DESC, id DESC)");
        // FK 삭제 동작: 작품 CASCADE(c), 사용자 RESTRICT(r)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_work_reviews_work'", String.class)).isEqualTo("c");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_work_reviews_user'", String.class)).isEqualTo("r");
        // contains_spoiler 는 기본값 없이 항상 명시한다
        assertThat(jdbcTemplate.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_name = 'work_reviews' AND column_name = 'contains_spoiler'
                """, String.class)).isNull();
    }

    @Test
    void defaultsAreVersionZeroAndEqualTimestamps() {
        jdbcTemplate.update("INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler) VALUES (?, ?, ?, FALSE)",
                userId, workId, BODY);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM work_reviews", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT created_at = updated_at FROM work_reviews", Boolean.class)).isTrue();
    }

    @Test
    void allowsOneReviewPerUserAndWork() {
        insertReview(userId, workId, BODY);

        assertThatThrownBy(() -> insertReview(userId, workId, "다른 내용으로 한 번 더 씁니다."))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_work_reviews_user_work");

        long otherUser = insertUser("other@manhwanet.test");
        insertReview(otherUser, workId, BODY);
        long otherWork = jdbcTemplate.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
        insertReview(userId, otherWork, BODY);
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void bodyLengthIsCountedInCodePoints() {
        // DB 하한은 1자(최소 10자는 애플리케이션 정책), 상한은 2,000 코드 포인트
        insertReview(userId, workId, "a");
        jdbcTemplate.update("DELETE FROM work_reviews");
        insertReview(userId, workId, "😀".repeat(2000)); // UTF-16 4,000단위지만 2,000자
        assertThat(jdbcTemplate.queryForObject("SELECT char_length(body) FROM work_reviews", Integer.class)).isEqualTo(2000);
        jdbcTemplate.update("DELETE FROM work_reviews");

        assertThatThrownBy(() -> insertReview(userId, workId, "a".repeat(2001)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_work_reviews_body_length");
        assertThatThrownBy(() -> insertReview(userId, workId, ""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_work_reviews_body_length");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "     "})
    void rejectsSpaceOnlyBody(String body) {
        assertThatThrownBy(() -> insertReview(userId, workId, body))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_work_reviews_body_not_blank");
    }

    /** btrim 은 U+0020 만 제거한다. 유니코드 공백만 있는 본문은 애플리케이션이 막아야 한다(최후의 안전장치 한계). */
    @Test
    void dbBlankCheckOnlyTrimsAsciiSpace() {
        insertReview(userId, workId, "\u3000\u3000");
        assertThat(count()).isEqualTo(1);
    }

    /** PostgreSQL TEXT 는 NUL 을 저장할 수 없다. 그래서 애플리케이션이 먼저 거절한다. */
    @Test
    void nulCannotBeStored() {
        assertThatThrownBy(() -> insertReview(userId, workId, "가나다라마\u0000바사아자차"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("0x00");
        assertThat(count()).isZero();
    }

    @Test
    void rejectsNegativeVersionAndUpdatedAtBeforeCreatedAt() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler, version) VALUES (?, ?, ?, FALSE, -1)
                """, userId, workId, BODY))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_work_reviews_version");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler, created_at, updated_at)
                VALUES (?, ?, ?, FALSE, now(), now() - interval '1 second')
                """, userId, workId, BODY))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_work_reviews_updated_at");
    }

    @Test
    void rejectsMissingSpoilerFlag() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler) VALUES (?, ?, ?, NULL)",
                userId, workId, BODY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownUserOrWork() {
        assertThatThrownBy(() -> insertReview(userId + 1000, workId, BODY))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_work_reviews_user");
        assertThatThrownBy(() -> insertReview(userId, workId + 1000, BODY))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_work_reviews_work");
    }

    @Test
    void deletingWorkCascadesToReviews() {
        insertReview(userId, workId, BODY);
        jdbcTemplate.update("DELETE FROM works WHERE id = ?", workId);
        assertThat(count()).isZero();
    }

    @Test
    void deletingUserWithReviewsIsRestricted() {
        insertReview(userId, workId, BODY);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_work_reviews_user");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void reviewsAndRatingsAreIndependentTables() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT confrelid::regclass::text FROM pg_constraint
                WHERE conrelid = 'work_reviews'::regclass AND contype = 'f'
                """, String.class)).containsExactlyInAnyOrder("users", "works");
        assertThat(jdbcTemplate.queryForList("""
                SELECT confrelid::regclass::text FROM pg_constraint
                WHERE conrelid = 'work_ratings'::regclass AND contype = 'f'
                """, String.class)).doesNotContain("work_reviews");
    }

    private long insertUser(String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role)
                VALUES (?, '{noop}unused', '독자', 'USER') RETURNING id
                """, Long.class, email);
    }

    private void insertReview(long user, long work, String body) {
        jdbcTemplate.update("INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler) VALUES (?, ?, ?, FALSE)",
                user, work, body);
    }

    private int count() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_reviews", Integer.class);
    }
}
