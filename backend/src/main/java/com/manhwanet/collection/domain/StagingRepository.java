package com.manhwanet.collection.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 수집 결과 staging (V10). 공개 작품 표(works·work_platform_listings)에는 쓰지 않는다 */
@Repository
public class StagingRepository {

    private static final String COLUMNS = """
            id, platform_code, platform_work_id, id_scope, listing_kind, language_code, url, title_raw, title_clean,
            genres_raw::text AS genres_raw, age_rating_raw, age_class, age_signals::text AS age_signals, serial_status_raw,
            serial_status, cover_url, synopsis_text, synopsis_sha256, synopsis_length, extra::text AS extra,
            field_sources::text AS field_sources, detail_status, content_hash, first_seen_at, last_seen_at, last_changed_at,
            first_run_id, last_run_id, last_failure_reason, last_failure_at, last_failure_run_id, consecutive_failures,
            review_status, linked_listing_id, created_at, updated_at""";

    private final NamedParameterJdbcTemplate jdbc;

    public StagingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StagingRow> findById(long id) {
        return one("SELECT " + COLUMNS + " FROM staging_listings WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public Optional<StagingRow> lockById(long id) {
        return one("SELECT " + COLUMNS + " FROM staging_listings WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", id));
    }

    public Optional<Long> findIdByKey(String platformCode, String platformWorkId) {
        return jdbc.queryForList("SELECT id FROM staging_listings WHERE platform_code = :p AND platform_work_id = :w",
                new MapSqlParameterSource().addValue("p", platformCode).addValue("w", platformWorkId), Long.class)
                .stream().findFirst();
    }

    public Optional<Long> findAliasTarget(String platformCode, String aliasId) {
        return jdbc.queryForList("SELECT staging_id FROM staging_id_aliases WHERE platform_code = :p AND alias_id = :a",
                new MapSqlParameterSource().addValue("p", platformCode).addValue("a", aliasId), Long.class)
                .stream().findFirst();
    }

    /** 대표 ID·별칭이 함께 쓰는 식별 키 (V12) */
    public record IdentityKey(long stagingId, String role) {
    }

    /**
     * 이 트랜잭션이 끝날 때까지 식별 키를 정렬된 순서로 잠근다.
     * 서로 다른 대표 ID·별칭을 동시에 등록해도 교착 없이 한 식별 공간에서 만난다.
     */
    public void lockIdentity(String platformCode, List<String> externalIds) {
        List<String> sorted = externalIds.stream().distinct().sorted().toList();
        for (String externalId : sorted) {
            jdbc.query("""
                    SELECT pg_advisory_xact_lock(hashtext(:p), hashtext(:id))""",
                    new MapSqlParameterSource().addValue("p", platformCode).addValue("id", externalId),
                    (rs, rowNum) -> null);
        }
    }

    public Optional<IdentityKey> findIdentity(String platformCode, String externalId) {
        return jdbc.query("""
                SELECT staging_id, key_role FROM staging_identity_keys
                WHERE platform_code = :p AND external_id = :e""",
                new MapSqlParameterSource().addValue("p", platformCode).addValue("e", externalId),
                rs -> rs.next() ? Optional.of(new IdentityKey(rs.getLong("staging_id"), rs.getString("key_role")))
                        : Optional.empty());
    }

    /** 같은 키가 이미 있으면 넣지 않는다. 누가 가리키는지는 findIdentity 로 확인한다 */
    public void insertIdentity(String platformCode, String externalId, long stagingId, String role) {
        jdbc.update("""
                INSERT INTO staging_identity_keys (platform_code, external_id, staging_id, key_role)
                VALUES (:p, :e, :s, :r)
                ON CONFLICT ON CONSTRAINT pk_staging_identity_keys DO NOTHING""",
                new MapSqlParameterSource().addValue("p", platformCode).addValue("e", externalId)
                        .addValue("s", stagingId).addValue("r", role));
    }

    /**
     * 새 식별 키의 최소 행. 같은 키가 이미 있으면(동시 요청 포함) 아무것도 넣지 않고 빈 값.
     * 내용은 같은 트랜잭션에서 곧바로 채운다.
     */
    public Optional<Long> insertIfAbsent(String platformCode, String platformWorkId, String url, long runId) {
        return jdbc.queryForList("""
                INSERT INTO staging_listings (platform_code, platform_work_id, url, first_seen_at, last_seen_at,
                    first_run_id, last_run_id)
                VALUES (:p, :w, :url, now(), now(), :run, :run)
                ON CONFLICT ON CONSTRAINT uk_staging_platform_work DO NOTHING RETURNING id""",
                new MapSqlParameterSource().addValue("p", platformCode).addValue("w", platformWorkId)
                        .addValue("url", url).addValue("run", runId), Long.class).stream().findFirst();
    }

    /** 내용·상태 갱신. 값은 서비스가 이전 값과 합쳐 모두 채워 보낸다 */
    public void update(long id, StagingRow next, boolean seen, boolean contentChanged, long runId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("idScope", next.idScope())
                .addValue("language", next.languageCode())
                .addValue("url", next.url())
                .addValue("titleRaw", next.titleRaw(), Types.VARCHAR)
                .addValue("titleClean", next.titleClean(), Types.VARCHAR)
                .addValue("genres", next.genresJson())
                .addValue("ageRaw", next.ageRatingRaw(), Types.VARCHAR)
                .addValue("ageClass", next.ageClass())
                .addValue("signals", next.ageSignalsJson())
                .addValue("serialRaw", next.serialStatusRaw(), Types.VARCHAR)
                .addValue("serial", next.serialStatus().name())
                .addValue("cover", next.coverUrl(), Types.VARCHAR)
                .addValue("synopsis", next.synopsisText(), Types.VARCHAR)
                .addValue("synopsisSha", next.synopsisSha256(), Types.VARCHAR)
                .addValue("synopsisLength", next.synopsisLength(), Types.INTEGER)
                .addValue("extra", next.extraJson())
                .addValue("sources", next.fieldSourcesJson())
                .addValue("detail", next.detailStatus().name())
                .addValue("hash", next.contentHash(), Types.VARCHAR)
                .addValue("seen", seen)
                .addValue("changed", contentChanged)
                .addValue("run", runId)
                .addValue("failureReason", next.lastFailureReason() == null ? null : next.lastFailureReason().name(),
                        Types.VARCHAR)
                .addValue("failureRun", next.lastFailureRunId(), Types.BIGINT)
                .addValue("failures", next.consecutiveFailures());
        int updated = jdbc.update("""
                UPDATE staging_listings SET
                    id_scope = :idScope, language_code = :language, url = :url, title_raw = :titleRaw,
                    title_clean = :titleClean, genres_raw = CAST(:genres AS jsonb), age_rating_raw = :ageRaw,
                    age_class = :ageClass, age_signals = CAST(:signals AS jsonb), serial_status_raw = :serialRaw,
                    serial_status = :serial, cover_url = :cover, synopsis_text = :synopsis, synopsis_sha256 = :synopsisSha,
                    synopsis_length = :synopsisLength, extra = CAST(:extra AS jsonb),
                    field_sources = CAST(:sources AS jsonb), detail_status = :detail, content_hash = :hash,
                    last_seen_at = CASE WHEN :seen THEN GREATEST(last_seen_at, now()) ELSE last_seen_at END,
                    last_changed_at = CASE WHEN :changed THEN now() ELSE last_changed_at END,
                    last_run_id = :run,
                    last_failure_reason = :failureReason,
                    last_failure_run_id = :failureRun,
                    last_failure_at = CASE WHEN CAST(:failureReason AS VARCHAR) IS NULL THEN NULL
                        WHEN last_failure_run_id IS DISTINCT FROM :failureRun OR last_failure_reason IS DISTINCT FROM :failureReason
                            THEN now() ELSE last_failure_at END,
                    consecutive_failures = :failures,
                    updated_at = GREATEST(updated_at, now())
                WHERE id = :id""", p);
        if (updated != 1) {
            throw new IllegalStateException("staging listing was not updated: " + id);
        }
    }

    /** #51: 수집 revision 을 1 올린다 */
    public void bumpRevision(long id) {
        jdbc.update("UPDATE staging_listings SET revision = revision + 1 WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    /** #51: 현재 수집 revision */
    public long revision(long id) {
        return jdbc.queryForObject("SELECT revision FROM staging_listings WHERE id = :id", new MapSqlParameterSource("id", id),
                Long.class);
    }

    public List<StagingCreditRow> findCredits(long stagingId) {
        return jdbc.query("""
                SELECT id, name_raw, role_raw, role, platform_creator_id, position FROM staging_credits
                WHERE staging_id = :id ORDER BY position, id""", new MapSqlParameterSource("id", stagingId),
                (rs, n) -> new StagingCreditRow(rs.getLong("id"), rs.getString("name_raw"), rs.getString("role_raw"),
                        CreditRole.valueOf(rs.getString("role")), rs.getString("platform_creator_id"), rs.getInt("position")));
    }

    /** 크레딧은 수집 결과의 스냅샷이라 통째로 바꾼다(이전 값은 변경 이력에 남는다) */
    public void replaceCredits(long stagingId, List<StagingCreditRow> credits) {
        jdbc.update("DELETE FROM staging_credits WHERE staging_id = :id", new MapSqlParameterSource("id", stagingId));
        for (StagingCreditRow credit : credits) {
            jdbc.update("""
                    INSERT INTO staging_credits (staging_id, name_raw, role_raw, role, platform_creator_id, position)
                    VALUES (:id, :name, :roleRaw, :role, :pcid, :pos)""",
                    new MapSqlParameterSource().addValue("id", stagingId).addValue("name", credit.nameRaw())
                            .addValue("roleRaw", credit.roleRaw(), Types.VARCHAR).addValue("role", credit.role().name())
                            .addValue("pcid", credit.platformCreatorId(), Types.VARCHAR).addValue("pos", credit.position()));
        }
    }

    public void upsertDiscovery(long stagingId, String sourceKey, long runId) {
        jdbc.update("""
                INSERT INTO staging_listing_discoveries (staging_id, source_key, first_seen_at, last_seen_at, last_run_id)
                VALUES (:id, :key, now(), now(), :run)
                ON CONFLICT ON CONSTRAINT pk_staging_discoveries
                DO UPDATE SET last_seen_at = GREATEST(staging_listing_discoveries.last_seen_at, now()), last_run_id = :run""",
                new MapSqlParameterSource().addValue("id", stagingId).addValue("key", sourceKey).addValue("run", runId));
    }

    public List<Map<String, Object>> findDiscoveries(long stagingId) {
        return jdbc.queryForList("""
                SELECT source_key AS "sourceKey", first_seen_at AS "firstSeenAt", last_seen_at AS "lastSeenAt",
                       last_run_id AS "lastRunId"
                FROM staging_listing_discoveries WHERE staging_id = :id ORDER BY source_key""",
                new MapSqlParameterSource("id", stagingId));
    }

    /** 별칭 등록. 이미 있으면 그 별칭이 가리키는 staging id 를 돌려준다 */
    public long insertAlias(String platformCode, String aliasId, long stagingId) {
        jdbc.update("""
                INSERT INTO staging_id_aliases (platform_code, alias_id, staging_id) VALUES (:p, :a, :s)
                ON CONFLICT ON CONSTRAINT pk_staging_id_aliases DO NOTHING""",
                new MapSqlParameterSource().addValue("p", platformCode).addValue("a", aliasId).addValue("s", stagingId));
        return findAliasTarget(platformCode, aliasId).orElseThrow();
    }

    public List<String> findAliases(long stagingId) {
        return jdbc.queryForList("SELECT alias_id FROM staging_id_aliases WHERE staging_id = :id ORDER BY alias_id",
                new MapSqlParameterSource("id", stagingId), String.class);
    }

    public Optional<String> findChange(long stagingId, long runId) {
        return jdbc.queryForList("SELECT changed_fields::text FROM staging_listing_changes WHERE staging_id = :s AND run_id = :r",
                new MapSqlParameterSource().addValue("s", stagingId).addValue("r", runId), String.class).stream().findFirst();
    }

    /** 실행당 한 줄: 이미 있으면 합친 값으로 바꾼다(재개해도 줄이 늘지 않음) */
    public void upsertChange(long stagingId, long runId, String changedFieldsJson) {
        jdbc.update("""
                INSERT INTO staging_listing_changes (staging_id, run_id, changed_fields) VALUES (:s, :r, CAST(:f AS jsonb))
                ON CONFLICT ON CONSTRAINT uk_staging_changes_run
                DO UPDATE SET changed_fields = EXCLUDED.changed_fields, changed_at = now()""",
                new MapSqlParameterSource().addValue("s", stagingId).addValue("r", runId).addValue("f", changedFieldsJson));
    }

    public List<Map<String, Object>> findChanges(long stagingId, int limit) {
        return jdbc.queryForList("""
                SELECT id, run_id AS "runId", changed_fields::text AS "changedFields", changed_at AS "changedAt"
                FROM staging_listing_changes WHERE staging_id = :s ORDER BY id DESC LIMIT :limit""",
                new MapSqlParameterSource().addValue("s", stagingId).addValue("limit", limit));
    }

    public List<StagingRow> findPage(String platformCode, ReviewStatus review, DetailStatus detail, long beforeId, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("before", beforeId).addValue("limit", limit);
        StringBuilder where = new StringBuilder("id < :before");
        if (platformCode != null) {
            where.append(" AND platform_code = :platform");
            p.addValue("platform", platformCode);
        }
        if (review != null) {
            where.append(" AND review_status = :review");
            p.addValue("review", review.name());
        }
        if (detail != null) {
            where.append(" AND detail_status = :detail");
            p.addValue("detail", detail.name());
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM staging_listings WHERE " + where + " ORDER BY id DESC LIMIT :limit",
                p, (rs, n) -> map(rs));
    }

    private Optional<StagingRow> one(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, (rs, n) -> map(rs)).stream().findFirst();
    }

    private static StagingRow map(ResultSet rs) throws SQLException {
        String failure = rs.getString("last_failure_reason");
        return new StagingRow(
                rs.getLong("id"),
                rs.getString("platform_code"),
                rs.getString("platform_work_id"),
                rs.getString("id_scope"),
                rs.getString("listing_kind"),
                rs.getString("language_code"),
                rs.getString("url"),
                rs.getString("title_raw"),
                rs.getString("title_clean"),
                rs.getString("genres_raw"),
                rs.getString("age_rating_raw"),
                rs.getString("age_class"),
                rs.getString("age_signals"),
                rs.getString("serial_status_raw"),
                SerialStatus.valueOf(rs.getString("serial_status")),
                rs.getString("cover_url"),
                rs.getString("synopsis_text"),
                rs.getString("synopsis_sha256"),
                rs.getObject("synopsis_length", Integer.class),
                rs.getString("extra"),
                rs.getString("field_sources"),
                DetailStatus.valueOf(rs.getString("detail_status")),
                rs.getString("content_hash"),
                ts(rs.getTimestamp("first_seen_at")),
                ts(rs.getTimestamp("last_seen_at")),
                ts(rs.getTimestamp("last_changed_at")),
                rs.getLong("first_run_id"),
                rs.getLong("last_run_id"),
                failure == null ? null : DetailStatus.valueOf(failure),
                ts(rs.getTimestamp("last_failure_at")),
                rs.getObject("last_failure_run_id", Long.class),
                rs.getInt("consecutive_failures"),
                ReviewStatus.valueOf(rs.getString("review_status")),
                rs.getObject("linked_listing_id", Long.class),
                ts(rs.getTimestamp("created_at")),
                ts(rs.getTimestamp("updated_at")));
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
