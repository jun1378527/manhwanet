package com.manhwanet.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.support.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V8(외부 ID·공식 링크·필드 근거·관리 변경 기록) 마이그레이션과 실제 PostgreSQL 제약 (#40).
 */
@SpringBootTest
@ActiveProfiles("test")
class ProvenanceMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    private long userId;
    private long workA;
    private long workB;
    private long creatorId;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbc, environment);
        userId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role) VALUES ('a@manhwanet.test', '{noop}x', '관리자', 'ADMIN')
                RETURNING id""", Long.class);
        workA = jdbc.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
        workB = jdbc.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
        creatorId = jdbc.queryForObject("INSERT INTO creators (name) VALUES ('작가') RETURNING id", Long.class);
    }

    @Test
    void v8IsAppliedWithoutCascade() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '8' AND success = TRUE", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT confdeltype FROM pg_constraint
                WHERE contype = 'f' AND conrelid IN ('work_external_ids'::regclass, 'creator_external_ids'::regclass,
                    'work_links'::regclass, 'work_field_sources'::regclass, 'admin_change_log'::regclass)""", String.class))
                .containsExactly("a");
        // 작품을 가리키는 행이 있으면 작품을 지울 수 없다(별점·리뷰·이력 보호)
        insertWorkExternalId(workA, "WIKIDATA", "Q1");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM works WHERE id = ?", workA))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_work_external_ids_work");
    }

    @Test
    void externalIdIsUniquePerSourceExceptInvalidRows() {
        long first = insertWorkExternalId(workA, "WIKIDATA", "Q42");
        // 같은 번호라도 출처가 다르면 다른 식별자
        insertWorkExternalId(workB, "MADB", "Q42");
        assertThatThrownBy(() -> insertWorkExternalId(workB, "WIKIDATA", "Q42"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_work_external_ids_source_id");
        // 잘못 연결(INVALID)로 바꾸면 자리가 비어 올바른 작품에 연결할 수 있다
        jdbc.update("UPDATE work_external_ids SET status = 'INVALID' WHERE id = ?", first);
        insertWorkExternalId(workB, "WIKIDATA", "Q42");
        assertThatThrownBy(() -> jdbc.update("UPDATE work_external_ids SET status = 'ACTIVE' WHERE id = ?", first))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_work_external_ids_source_id");
        // 작가 표는 따로 유일성을 가진다
        jdbc.update("""
                INSERT INTO creator_external_ids (creator_id, source, external_id, verified_at, verified_by)
                VALUES (?, 'WIKIDATA', 'Q42', now(), ?)""", creatorId, userId);

        assertRejected("INSERT INTO work_external_ids (work_id, source, external_id, verified_at, verified_by) "
                + "VALUES (" + workA + ", 'WIKIDATA', 'q7', now(), " + userId + ")", "ck_work_external_ids_wikidata");
        assertRejected("INSERT INTO work_external_ids (work_id, source, external_id, verified_at, verified_by) "
                + "VALUES (" + workA + ", 'VIAF', '1', now(), " + userId + ")", "ck_work_external_ids_source");
        assertRejected("INSERT INTO work_external_ids (work_id, source, external_id, verified_at, verified_by) "
                + "VALUES (" + workA + ", 'NDL', 'a b', now(), " + userId + ")", "ck_work_external_ids_external_id");
        assertRejected("INSERT INTO work_external_ids (work_id, source, external_id, source_url, verified_at, verified_by) "
                + "VALUES (" + workA + ", 'NDL', 'x1', 'http://x.com', now(), " + userId + ")", "ck_work_external_ids_url");
    }

    @Test
    void linksAndFieldSourcesKeepTheirRules() {
        jdbc.update("""
                INSERT INTO work_links (work_id, link_type, url, language_code, verified_at, verified_by)
                VALUES (?, 'PUBLISHER', 'https://publisher.example.com/w/1', 'ko', now(), ?)""", workA, userId);
        assertRejected("INSERT INTO work_links (work_id, link_type, url, language_code, verified_at, verified_by) VALUES ("
                + workA + ", 'PUBLISHER', 'https://publisher.example.com/w/1', 'ko', now(), " + userId + ")",
                "uk_work_links_work_url");
        assertRejected("INSERT INTO work_links (work_id, link_type, url, language_code, verified_at, verified_by) VALUES ("
                + workA + ", 'FAN_SITE', 'https://x.example.com', 'ko', now(), " + userId + ")", "ck_work_links_type");

        // 줄거리 근거는 직접 작성·허락받은 글만
        assertRejected(source("DESCRIPTION", "NULL", "PLATFORM_PAGE_MANUAL", "'https://x.com'"), "ck_field_sources_description");
        // 직접 작성 외에는 근거 위치가 필요하고, 허락받은 글은 허락 근거가 필요
        assertRejected(source("TITLE", "NULL", "WIKIDATA", "NULL"), "ck_field_sources_ref");
        assertRejected(source("DESCRIPTION", "NULL", "LICENSED", "NULL"), "ck_field_sources_licensed");
        // LISTING 근거는 대상 연결이 있어야 하고, 다른 작품의 연결은 가리킬 수 없다
        assertRejected(source("LISTING", "NULL", "STAFF", "NULL"), "ck_field_sources_listing");
        long otherListing = jdbc.queryForObject("""
                INSERT INTO work_platform_listings (work_id, platform_code, platform_work_id, id_scope, url, listing_kind,
                    language_code, first_verified_at, last_verified_at, verified_by)
                VALUES (?, 'RIDI', '1', 'SERIES', 'https://ridibooks.com/books/1', 'MAIN', 'ko', now(), now(), ?)
                RETURNING id""", Long.class, workB, userId);
        assertRejected(source("LISTING", String.valueOf(otherListing), "PLATFORM_PAGE_MANUAL", "'https://ridibooks.com/books/1'"),
                "fk_field_sources_listing");
        jdbc.update(source("DESCRIPTION", "NULL", "STAFF", "NULL"));
    }

    @Test
    void changeLogIsAppendOnlyAndNeedsReasonForChanges() {
        long id = jdbc.queryForObject("""
                INSERT INTO admin_change_log (entity_type, entity_id, action, work_id, after_json, actor_id)
                VALUES ('WORK', ?, 'CREATE', ?, '{"a":1}', ?) RETURNING id""", Long.class, String.valueOf(workA), workA, userId);
        assertThatThrownBy(() -> jdbc.update("UPDATE admin_change_log SET reason = 'x' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM admin_change_log WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");

        assertRejected("INSERT INTO admin_change_log (entity_type, entity_id, action, after_json, actor_id) "
                + "VALUES ('WORK', '1', 'UPDATE', '{}', " + userId + ")", "ck_change_log_before");
        assertRejected("INSERT INTO admin_change_log (entity_type, entity_id, action, before_json, after_json, actor_id) "
                + "VALUES ('WORK', '1', 'UPDATE', '{}', '{}', " + userId + ")", "ck_change_log_reason");
        assertRejected("INSERT INTO admin_change_log (entity_type, entity_id, action, before_json, after_json, actor_id) "
                + "VALUES ('WORK', '1', 'CREATE', '{}', '{}', " + userId + ")", "ck_change_log_before");
        assertRejected("INSERT INTO admin_change_log (entity_type, entity_id, action, after_json, actor_id) "
                + "VALUES ('RATING', '1', 'CREATE', '{}', " + userId + ")", "ck_change_log_entity_type");
        assertRejected("INSERT INTO admin_change_log (entity_type, entity_id, action, after_json, actor_id) "
                + "VALUES ('WORK', '1', 'DELETE', '{}', " + userId + ")", "ck_change_log_action");
    }

    private long insertWorkExternalId(long workId, String source, String externalId) {
        return jdbc.queryForObject("""
                INSERT INTO work_external_ids (work_id, source, external_id, verified_at, verified_by)
                VALUES (?, ?, ?, now(), ?) RETURNING id""", Long.class, workId, source, externalId, userId);
    }

    private String source(String field, String listingId, String type, String sourceUrl) {
        return "INSERT INTO work_field_sources (work_id, field, listing_id, source_type, source_url, verified_at, verified_by) "
                + "VALUES (" + workA + ", '" + field + "', " + listingId + ", '" + type + "', " + sourceUrl + ", now(), "
                + userId + ")";
    }

    private void assertRejected(String sql, String constraint) {
        assertThatThrownBy(() -> jdbc.update(sql))
                .as(sql)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
