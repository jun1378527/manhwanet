package com.manhwanet.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.support.TestDatabaseCleaner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** V9~V12(수집 설정·실행 기록·staging·식별 키·검수 후보·표지·누락 현황) 마이그레이션과 실제 PostgreSQL 제약 (#45) */
@SpringBootTest
@ActiveProfiles("test")
class CollectionSchemaMigrationTest {

    private static final String NEW_TABLES = """
            'platform_collection_policies'::regclass, 'crawl_runs'::regclass, 'crawl_tasks'::regclass,
            'crawl_failure_samples'::regclass, 'staging_listings'::regclass, 'staging_id_aliases'::regclass,
            'staging_listing_discoveries'::regclass, 'staging_credits'::regclass, 'staging_listing_changes'::regclass,
            'staging_identity_keys'::regclass,
            'staging_match_candidates'::regclass, 'cover_assets'::regclass, 'cover_asset_stage_history'::regclass,
            'platform_coverage_snapshots'::regclass""";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    private long adminId;
    private long runId;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbc, environment);
        adminId = CollectionTestSupport.insertAdmin(jdbc);
        runId = jdbc.queryForObject("""
                INSERT INTO crawl_runs (platform_code, run_kind, adapter_version) VALUES ('RIDI', 'FULL', 'ridi-0.1')
                RETURNING id""", Long.class);
    }

    @Test
    void migrationsApplyWithoutCascadeAndPoliciesStartDisabled() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).containsSubsequence("8", "9", "10", "11", "12", "13", "14");
        assertThat(jdbc.queryForList("SELECT DISTINCT confdeltype FROM pg_constraint WHERE contype = 'f' AND conrelid IN ("
                + NEW_TABLES + ")", String.class)).containsExactly("a");
        List<Map<String, Object>> policies = jdbc.queryForList("""
                SELECT collection_policy, synopsis_policy, cover_policy, decided_by, basis_kind FROM platform_collection_policies""");
        assertThat(policies).hasSize(10).allSatisfy(p -> {
            assertThat(p.get("collection_policy")).isEqualTo("DISABLED");
            assertThat(p.get("synopsis_policy")).isEqualTo("COLLECT_PRIVATE");
            assertThat(p.get("cover_policy")).isEqualTo("URL_ONLY");
            assertThat(p.get("decided_by")).isNull();
            assertThat(p.get("basis_kind")).isNull();
        });
        // 수집 설정·실행 중단을 변경 기록에 남길 수 있다(V8 CHECK 를 V9 에서 넓힘)
        jdbc.update("""
                INSERT INTO admin_change_log (entity_type, entity_id, action, after_json, actor_id)
                VALUES ('COLLECTION_POLICY', 'RIDI', 'CREATE', '{}', ?)""", adminId);
    }

    @Test
    void policyAndRunConstraints() {
        // 기본값이 아닌 설정은 바꾼 사람·시각이 있어야 한다. 외부 근거는 없어도 된다
        rejected("UPDATE platform_collection_policies SET collection_policy = 'ENABLED' WHERE platform_code = 'RIDI'",
                "ck_pcp_decided");
        jdbc.update("""
                UPDATE platform_collection_policies SET collection_policy = 'ENABLED', decided_by = ?, decided_at = now()
                WHERE platform_code = 'RIDI'""", adminId);
        rejected("UPDATE platform_collection_policies SET basis_note = '메모만' WHERE platform_code = 'RIDI'", "ck_pcp_basis_note");
        rejected("UPDATE platform_collection_policies SET rate_interval_ms = 999 WHERE platform_code = 'RIDI'", "ck_pcp_rate");

        // 플랫폼당 활성 실행은 하나
        rejected("INSERT INTO crawl_runs (platform_code, run_kind, adapter_version) VALUES ('RIDI', 'INCREMENTAL', 'x')",
                "uk_crawl_runs_active");
        jdbc.update("INSERT INTO crawl_runs (platform_code, run_kind, adapter_version) VALUES ('MRBLUE', 'FULL', 'x')");
        rejected("UPDATE crawl_runs SET status = 'COMPLETED' WHERE id = " + runId, "ck_crawl_runs_finished");
        rejected("UPDATE crawl_runs SET status = 'BLOCKED' WHERE id = " + runId, "ck_crawl_runs_blocked");
        rejected("UPDATE crawl_runs SET lease_owner = 'a' WHERE id = " + runId, "ck_crawl_runs_lease");
        jdbc.update("UPDATE crawl_runs SET status = 'COMPLETED', finished_at = now() WHERE id = ?", runId);
        // 끝난 실행 뒤에는 새 실행을 만들 수 있다
        jdbc.update("INSERT INTO crawl_runs (platform_code, run_kind, adapter_version) VALUES ('RIDI', 'INCREMENTAL', 'x')");

        String hash = "a".repeat(64);
        jdbc.update("INSERT INTO crawl_tasks (run_id, task_kind, url, url_hash) VALUES (?, 'DETAIL', 'https://ridibooks.com/books/1', ?)",
                runId, hash);
        rejected("INSERT INTO crawl_tasks (run_id, task_kind, url, url_hash) VALUES (" + runId
                + ", 'DETAIL', 'https://ridibooks.com/books/1', '" + hash + "')", "uk_crawl_tasks");
        rejected("INSERT INTO crawl_tasks (run_id, task_kind, url, url_hash) VALUES (" + runId
                + ", 'DETAIL', 'http://ridibooks.com/books/2', '" + "b".repeat(64) + "')", "ck_crawl_tasks_url");
        rejected("UPDATE crawl_tasks SET last_error = 'CAPTCHA_SOLVED'", "ck_crawl_tasks_error");
    }

    @Test
    void stagingConstraints() {
        long a = insertStaging("RIDI", "100");
        // 같은 번호라도 플랫폼이 다르면 다른 행
        insertStaging("MRBLUE", "100");
        rejected("INSERT INTO staging_listings (platform_code, platform_work_id, url, first_seen_at, last_seen_at, first_run_id, "
                + "last_run_id) VALUES ('RIDI', '100', 'https://ridibooks.com/books/100', now(), now(), " + runId + ", " + runId
                + ")", "uk_staging_platform_work");
        rejected("UPDATE staging_listings SET age_class = 'TEEN' WHERE id = " + a, "ck_staging_age");
        rejected("UPDATE staging_listings SET synopsis_text = '원문만' WHERE id = " + a, "ck_staging_synopsis");
        rejected("UPDATE staging_listings SET consecutive_failures = 1 WHERE id = " + a, "ck_staging_failures");
        rejected("UPDATE staging_listings SET platform_work_id = '1 0' WHERE id = " + a, "ck_staging_platform_work_id");
        rejected("UPDATE staging_listings SET review_status = 'PUBLISHED' WHERE id = " + a, "ck_staging_review");
        jdbc.update("INSERT INTO staging_listing_changes (staging_id, run_id, changed_fields) VALUES (?, ?, '{\"titleRaw\":{}}')",
                a, runId);
        rejected("INSERT INTO staging_listing_changes (staging_id, run_id, changed_fields) VALUES (" + a + ", " + runId
                + ", '{\"x\":{}}')", "uk_staging_changes_run");
        rejected("INSERT INTO staging_credits (staging_id, name_raw, role) VALUES (" + a + ", '작가', 'EDITOR')",
                "ck_staging_credits_role");
        jdbc.update("INSERT INTO staging_id_aliases (platform_code, alias_id, staging_id) VALUES ('RIDI', '101', ?)", a);
        rejected("INSERT INTO staging_id_aliases (platform_code, alias_id, staging_id) VALUES ('RIDI', '101', " + a + ")",
                "pk_staging_id_aliases");
        jdbc.update("""
                INSERT INTO staging_identity_keys (platform_code, external_id, staging_id, key_role)
                VALUES ('RIDI', '100', ?, 'CANONICAL')""", a);
        rejected("""
                INSERT INTO staging_identity_keys (platform_code, external_id, staging_id, key_role)
                VALUES ('RIDI', '100', """ + a + ", 'ALIAS')", "pk_staging_identity_keys");
        // staging 은 공개 작품에 연결되기 전까지 works·연결과 무관하다
        assertThat(CollectionTestSupport.count(jdbc, "works") + CollectionTestSupport.count(jdbc, "work_platform_listings"))
                .isZero();
    }

    @Test
    void candidateCoverAndCoverageConstraints() {
        long a = insertStaging("RIDI", "1");
        long b = insertStaging("MRBLUE", "wt_000000001");
        long work = jdbc.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
        jdbc.update("INSERT INTO staging_match_candidates (staging_id, other_staging_id, score, reasons) VALUES (?, ?, 80, '[]')", a, b);
        // 같은 쌍을 반대 방향으로 다시 만들 수 없다
        rejected("INSERT INTO staging_match_candidates (staging_id, other_staging_id, score, reasons) VALUES (" + b + ", " + a
                + ", 70, '[]')", "uk_smc_pair");
        rejected("INSERT INTO staging_match_candidates (staging_id, work_id, other_staging_id, score, reasons) VALUES (" + a
                + ", " + work + ", " + b + ", 1, '[]')", "ck_smc_target");
        rejected("INSERT INTO staging_match_candidates (staging_id, other_staging_id, score, reasons) VALUES (" + a + ", " + a
                + ", 1, '[]')", "ck_smc_self");
        rejected("INSERT INTO staging_match_candidates (staging_id, work_id, score, reasons, status) VALUES (" + a + ", " + work
                + ", 1, '[]', 'ACCEPTED')", "ck_smc_decided");

        long user = adminId;
        long listing = jdbc.queryForObject("""
                INSERT INTO work_platform_listings (work_id, platform_code, platform_work_id, id_scope, url, listing_kind,
                    language_code, first_verified_at, last_verified_at, verified_by)
                VALUES (?, 'RIDI', '1', 'SERIES', 'https://ridibooks.com/books/1', 'MAIN', 'ko', now(), now(), ?) RETURNING id""",
                Long.class, work, user);
        long cover = jdbc.queryForObject("INSERT INTO cover_assets (listing_id, source_url) VALUES (?, 'https://img.example.com/1.jpg') RETURNING id",
                Long.class, listing);
        rejected("UPDATE cover_assets SET stage = 'DOWNLOADED' WHERE id = " + cover, "ck_cover_storage");
        rejected("INSERT INTO cover_asset_stage_history (cover_id, from_stage, to_stage, approved_by) VALUES (" + cover
                + ", 'URL_COLLECTED', 'URL_COLLECTED', " + user + ")", "ck_cover_hist_stages");
        rejected("INSERT INTO platform_coverage_snapshots (platform_code, taken_on, metrics, status) VALUES ('RIDI', current_date, '{}', 'DONE')",
                "ck_coverage_status");
    }

    private long insertStaging(String platform, String id) {
        return jdbc.queryForObject("""
                INSERT INTO staging_listings (platform_code, platform_work_id, url, first_seen_at, last_seen_at, first_run_id, last_run_id)
                VALUES (?, ?, 'https://example.com/' || ?, now(), now(), ?, ?) RETURNING id""",
                Long.class, platform, id, id, runId, runId);
    }

    private void rejected(String sql, String constraint) {
        assertThatThrownBy(() -> jdbc.update(sql)).as(sql)
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }
}
