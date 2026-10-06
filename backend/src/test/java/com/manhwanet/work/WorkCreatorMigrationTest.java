package com.manhwanet.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.support.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V2 마이그레이션과 DB 제약 검증 (테스트 DB).
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkCreatorMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    private long workId;
    private long creatorId;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        creatorId = jdbcTemplate.queryForObject(
                "INSERT INTO creators (name) VALUES ('한서윤') RETURNING id", Long.class);
        workId = jdbcTemplate.queryForObject(
                "INSERT INTO works (work_format, origin_country) VALUES ('WEBTOON', 'KR') RETURNING id", Long.class);
    }

    @Test
    void v2MigrationIsApplied() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = TRUE", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void expectedConstraintAndIndexNamesExist() {
        assertThat(jdbcTemplate.queryForList("SELECT conname FROM pg_constraint", String.class))
                .contains(
                        "uk_work_titles_work_language_title",
                        "uk_work_creators_work_creator_role",
                        "fk_work_titles_work",
                        "fk_work_creators_work",
                        "fk_work_creators_creator",
                        "ck_works_work_format",
                        "ck_works_origin_country",
                        "ck_work_creators_role");
        assertThat(jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class))
                .contains(
                        "uk_work_titles_primary_per_work",
                        "idx_works_created_at_id",
                        "idx_creators_created_at_id",
                        "idx_work_creators_creator_id");
    }

    @Test
    void allowsAtMostOnePrimaryTitlePerWork() {
        insertTitle("달빛 기사단", "ko", true);
        insertTitle("Moonlight Knights", "en", false);

        assertThatThrownBy(() -> insertTitle("月光騎士団", "ja", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_work_titles_primary_per_work");
    }

    @Test
    void rejectsDuplicateTitleInSameLanguageOfSameWork() {
        insertTitle("달빛 기사단", "ko", true);

        assertThatThrownBy(() -> insertTitle("달빛 기사단", "ko", false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_work_titles_work_language_title");
    }

    @Test
    void allowsSameTitleInOtherWork() {
        insertTitle("달빛 기사단", "ko", true);
        long otherWorkId = jdbcTemplate.queryForObject(
                "INSERT INTO works (work_format) VALUES ('PAGE_COMIC') RETURNING id", Long.class);

        jdbcTemplate.update("INSERT INTO work_titles (work_id, title, language_code, is_primary) VALUES (?, ?, ?, ?)",
                otherWorkId, "달빛 기사단", "ko", true);
    }

    @Test
    void rejectsDuplicateCreatorRoleButAllowsOtherRole() {
        insertCredit(creatorId, "STORY");
        insertCredit(creatorId, "ART");

        assertThatThrownBy(() -> insertCredit(creatorId, "STORY"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_work_creators_work_creator_role");
    }

    @Test
    void rejectsInvalidValuesByCheckConstraints() {
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO works (work_format) VALUES ('MANHWA')"))
                .hasMessageContaining("ck_works_work_format");
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO works (work_format, origin_country) VALUES ('WEBTOON', 'kr')"))
                .hasMessageContaining("ck_works_origin_country");
        assertThatThrownBy(() -> insertCredit(creatorId, "COLORIST"))
                .hasMessageContaining("ck_work_creators_role");
        assertThatThrownBy(() -> insertTitle("   ", "ko", true))
                .hasMessageContaining("ck_work_titles_title_not_blank");
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO creators (name) VALUES (' ')"))
                .hasMessageContaining("ck_creators_name_not_blank");
    }

    @Test
    void rejectsUnknownCreatorAndDeletingReferencedCreator() {
        assertThatThrownBy(() -> insertCredit(9_999L, "STORY"))
                .hasMessageContaining("fk_work_creators_creator");

        insertCredit(creatorId, "STORY");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM creators WHERE id = ?", creatorId))
                .hasMessageContaining("fk_work_creators_creator");
    }

    @Test
    void allowsHomonymCreators() {
        jdbcTemplate.update("INSERT INTO creators (name) VALUES ('한서윤')");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM creators WHERE name = '한서윤'", Integer.class))
                .isEqualTo(2);
    }

    private void insertTitle(String title, String languageCode, boolean primary) {
        jdbcTemplate.update("INSERT INTO work_titles (work_id, title, language_code, is_primary) VALUES (?, ?, ?, ?)",
                workId, title, languageCode, primary);
    }

    private void insertCredit(long creator, String role) {
        jdbcTemplate.update("INSERT INTO work_creators (work_id, creator_id, role) VALUES (?, ?, ?)",
                workId, creator, role);
    }
}
