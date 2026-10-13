package com.manhwanet.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.support.TestDatabaseCleaner;
import java.util.List;
import java.util.Map;
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
 * V7(플랫폼·상태 이력·작품 연결) 마이그레이션과 실제 PostgreSQL 제약.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlatformListingMigrationTest {

    private static final String SCHEMA = "v7_migration_check";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    private long userId;
    private long workId;

    @BeforeEach
    void setUp() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        TestDatabaseCleaner.clean(jdbc, environment);
        userId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role) VALUES ('a@manhwanet.test', '{noop}x', '관리자', 'ADMIN')
                RETURNING id""", Long.class);
        workId = jdbc.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void v7IsAppliedWithExpectedConstraints() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '7' AND success = TRUE", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_constraint
                WHERE conrelid = 'work_platform_listings'::regclass""", String.class))
                .contains("uk_listings_platform_work", "fk_listings_work", "fk_listings_moved_to", "ck_listings_moved",
                        "ck_listings_moved_not_self", "ck_listings_age_class", "ck_listings_scope", "ck_listings_verified");
        // 새 FK 는 CASCADE 가 없다(NO ACTION = 'a')
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT confdeltype FROM pg_constraint
                WHERE contype = 'f' AND conrelid IN ('platforms'::regclass, 'platform_hosts'::regclass,
                    'platform_status_history'::regclass, 'work_platform_listings'::regclass)""", String.class))
                .containsExactly("a");
    }

    @Test
    void seedHasOnlyCodesNamesAndUrlRulesWithBasis() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT code, name, status, status_basis, operator_name, terms_checked_on, default_id_scope,
                       url_rule_basis, url_rule_checked_on, work_url_pattern
                FROM platforms ORDER BY code""");
        assertThat(rows).hasSize(10);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("UNKNOWN");
            assertThat(row.get("status_basis")).isEqualTo("UNVERIFIED");
            assertThat(row.get("operator_name")).isNull();
            assertThat(row.get("terms_checked_on")).isNull();
            assertThat(row.get("default_id_scope")).isEqualTo("UNKNOWN");
            // 관찰한 규칙에는 확인일, 확인하지 못한 곳은 패턴 없음
            if ("OBSERVED".equals(row.get("url_rule_basis"))) {
                assertThat(row.get("url_rule_checked_on")).isNotNull();
                assertThat((String) row.get("work_url_pattern")).contains("(?<id>");
            } else {
                assertThat(row.get("work_url_pattern")).isNull();
            }
        });
        assertThat(jdbc.queryForList("SELECT code FROM platforms WHERE url_rule_basis = 'UNVERIFIED' ORDER BY code",
                String.class)).containsExactly("COMICO_KR", "MRBLUE", "TOOMICS");
    }

    @Test
    void cleanerSeedMatchesMigrationSeed() {
        SingleConnectionDataSource dedicated = new SingleConnectionDataSource(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"),
                true);
        try {
            Flyway.configure().dataSource(dedicated).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
                    .locations("classpath:db/migration").load().migrate();
        } finally {
            dedicated.destroy();
        }
        // 생성·수정 시각을 뺀 모든 열을 비교한다(열이 추가돼도 자동으로 포함)
        String platforms = """
                SELECT (to_jsonb(p) - 'created_at' - 'updated_at')::text AS row
                FROM %s.platforms p ORDER BY code""";
        assertThat(jdbc.queryForList(platforms.formatted("public")))
                .isEqualTo(jdbc.queryForList(platforms.formatted(SCHEMA)));
        String hosts = "SELECT to_jsonb(h)::text AS row FROM %s.platform_hosts h ORDER BY platform_code, host";
        assertThat(jdbc.queryForList(hosts.formatted("public"))).isEqualTo(jdbc.queryForList(hosts.formatted(SCHEMA)));
    }

    @Test
    void platformChecksSeparateOfficialClosureFromReports() {
        assertRejected("UPDATE platforms SET status = 'CLOSED', status_basis = 'PRESS', status_evidence_url = 'https://x.com' WHERE code = 'COMICO_KR'",
                "ck_platforms_closed_official");
        assertRejected("UPDATE platforms SET status = 'MERGING', status_basis = 'OFFICIAL', status_evidence_url = 'https://x.com' WHERE code = 'KAKAO_WEBTOON'",
                "ck_platforms_merging_target");
        assertRejected("UPDATE platforms SET merged_into = 'KAKAO_WEBTOON', status = 'CLOSED', status_basis = 'OFFICIAL', status_evidence_url = 'https://x.com' WHERE code = 'KAKAO_WEBTOON'",
                "ck_platforms_merged_not_self");
        assertRejected("UPDATE platforms SET status_basis = 'PRESS' WHERE code = 'COMICO_KR'", "ck_platforms_evidence");
        assertRejected("UPDATE platforms SET merged_into = 'KAKAO_PAGE' WHERE code = 'KAKAO_WEBTOON'", "ck_platforms_merged_state");
        assertRejected("UPDATE platforms SET url_rule_basis = 'OBSERVED', url_rule_checked_on = NULL WHERE code = 'TOOMICS'",
                "ck_platforms_url_rule_checked");
        assertRejected("INSERT INTO platform_hosts (platform_code, host) VALUES ('TOOMICS', 'ridibooks.com')", "uk_platform_hosts_host");
        assertRejected("INSERT INTO platform_hosts (platform_code, host) VALUES ('TOOMICS', 'Bad_Host')", "ck_platform_hosts_host");
        assertRejected("INSERT INTO platforms (code, name) VALUES ('lower', '소문자')", "ck_platforms_code");
        assertRejected("""
                INSERT INTO platform_status_history (platform_code, from_status, to_status, from_basis, to_basis,
                    evidence_url, changed_by)
                VALUES ('KAKAO_WEBTOON', 'UNKNOWN', 'MERGING', 'UNVERIFIED', 'OFFICIAL', 'https://x.com', %d)"""
                .formatted(userId), "ck_psh_merging_target");
    }

    @Test
    void listingChecksUniqueAndFks() {
        long a = insertListing("KAKAO_PAGE", "100");
        // 같은 번호라도 다른 플랫폼이면 다른 식별자
        insertListing("RIDI", "100");
        assertThatThrownBy(() -> insertListing("KAKAO_PAGE", "100"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_listings_platform_work");

        assertRejected("UPDATE work_platform_listings SET status = 'MOVED' WHERE id = " + a, "ck_listings_moved");
        assertRejected("UPDATE work_platform_listings SET status = 'MOVED', moved_to_listing_id = " + a + " WHERE id = " + a,
                "ck_listings_moved_not_self");
        assertRejected("UPDATE work_platform_listings SET age_class = 'TEEN' WHERE id = " + a, "ck_listings_age_class");
        assertRejected("UPDATE work_platform_listings SET url = 'http://page.kakao.com/content/100' WHERE id = " + a, "ck_listings_url");
        assertRejected("UPDATE work_platform_listings SET platform_work_id = ' 100' WHERE id = " + a, "ck_listings_platform_work_id");
        assertRejected("UPDATE work_platform_listings SET platform_work_id = '1 00' WHERE id = " + a, "ck_listings_platform_work_id");
        assertRejected("UPDATE work_platform_listings SET platform_work_id = '작품1' WHERE id = " + a, "ck_listings_platform_work_id");
        assertRejected("UPDATE work_platform_listings SET last_verified_at = first_verified_at - interval '1 day' WHERE id = " + a,
                "ck_listings_verified");
        assertRejected("UPDATE work_platform_listings SET moved_to_listing_id = 999999, status = 'MOVED' WHERE id = " + a,
                "fk_listings_moved_to");

        // 다른 작품의 연결을 이전 대상으로 둘 수 없다(복합 FK)
        long otherWork = jdbc.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
        long foreign = jdbc.queryForObject("""
                INSERT INTO work_platform_listings (work_id, platform_code, platform_work_id, id_scope, url, listing_kind,
                    language_code, first_verified_at, last_verified_at, verified_by)
                VALUES (?, 'LEZHIN', 'x1', 'SERIES', 'https://example.com/x1', 'MAIN', 'ko', now(), now(), ?)
                RETURNING id""", Long.class, otherWork, userId);
        assertRejected("UPDATE work_platform_listings SET moved_to_listing_id = " + foreign + ", status = 'MOVED' WHERE id = " + a,
                "fk_listings_moved_to");

        // 연결이 있는 작품은 DB 에서도 지울 수 없다(CASCADE 없음). 별점·리뷰를 지키기 위해 삭제 API 도 두지 않는다
        assertThatThrownBy(() -> jdbc.update("DELETE FROM works WHERE id = ?", workId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_listings_work");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM platforms WHERE code = 'KAKAO_PAGE'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertListing(String platform, String id) {
        return jdbc.queryForObject("""
                INSERT INTO work_platform_listings (work_id, platform_code, platform_work_id, id_scope, url, listing_kind,
                    language_code, first_verified_at, last_verified_at, verified_by)
                VALUES (?, ?, ?, 'SERIES', 'https://example.com/' || ?, 'MAIN', 'ko', now(), now(), ?)
                RETURNING id""", Long.class, workId, platform, id, id, userId);
    }

    private void assertRejected(String sql, String constraint) {
        assertThatThrownBy(() -> jdbc.update(sql))
                .as(sql)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
