package com.manhwanet.provenance.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 작품 공식 링크 (V8 work_links) */
@Repository
public class WorkLinkRepository {

    private static final String COLUMNS = "id, work_id, link_type, url, language_code, status, verified_at, verified_by, "
            + "note, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbc;

    public WorkLinkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<WorkLinkRow> findByWork(long workId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM work_links WHERE work_id = :workId ORDER BY id",
                new MapSqlParameterSource("workId", workId), (rs, n) -> map(rs));
    }

    public Optional<WorkLinkRow> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM work_links WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, n) -> map(rs)).stream().findFirst();
    }

    public Optional<WorkLinkRow> findByWorkAndUrl(long workId, String url) {
        return jdbc.query("SELECT " + COLUMNS + " FROM work_links WHERE work_id = :workId AND url = :url",
                new MapSqlParameterSource().addValue("workId", workId).addValue("url", url),
                (rs, n) -> map(rs)).stream().findFirst();
    }

    /** 같은 작품에 같은 URL 이 이미 있으면(상태와 무관) 넣지 않고 빈 값을 돌려준다 */
    public Optional<Long> insertIfAbsent(long workId, LinkType type, String url, String languageCode,
            Instant verifiedAt, long verifiedBy, String note) {
        return jdbc.queryForList("""
                        INSERT INTO work_links (work_id, link_type, url, language_code, verified_at, verified_by, note)
                        VALUES (:workId, :type, :url, :language, :verifiedAt, :verifiedBy, :note)
                        ON CONFLICT ON CONSTRAINT uk_work_links_work_url DO NOTHING
                        RETURNING id""",
                new MapSqlParameterSource()
                        .addValue("workId", workId)
                        .addValue("type", type.name())
                        .addValue("url", url)
                        .addValue("language", languageCode)
                        .addValue("verifiedAt", Timestamp.from(verifiedAt))
                        .addValue("verifiedBy", verifiedBy)
                        .addValue("note", note),
                Long.class).stream().findFirst();
    }

    public void update(long id, LinkStatus status, Instant verifiedAt, long verifiedBy, String note) {
        int updated = jdbc.update("""
                        UPDATE work_links
                        SET status = :status, verified_at = :verifiedAt, verified_by = :verifiedBy, note = :note,
                            updated_at = GREATEST(updated_at, now())
                        WHERE id = :id""",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", status.name())
                        .addValue("verifiedAt", Timestamp.from(verifiedAt))
                        .addValue("verifiedBy", verifiedBy)
                        .addValue("note", note));
        if (updated != 1) {
            throw new IllegalStateException("work link was not updated: " + id);
        }
    }

    private static WorkLinkRow map(ResultSet rs) throws SQLException {
        return new WorkLinkRow(
                rs.getLong("id"),
                rs.getLong("work_id"),
                LinkType.valueOf(rs.getString("link_type")),
                rs.getString("url"),
                rs.getString("language_code"),
                LinkStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("verified_at").toInstant(),
                rs.getLong("verified_by"),
                rs.getString("note"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
