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

/** 필드별 근거 (V8 work_field_sources) */
@Repository
public class FieldSourceRepository {

    private static final String COLUMNS = "id, work_id, field, listing_id, target_ref, source_type, source_ref, source_url, "
            + "status, verified_at, verified_by, note, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbc;

    public FieldSourceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FieldSourceRow> findByWork(long workId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM work_field_sources WHERE work_id = :workId ORDER BY id",
                new MapSqlParameterSource("workId", workId), (rs, n) -> map(rs));
    }

    public Optional<FieldSourceRow> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM work_field_sources WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, n) -> map(rs)).stream().findFirst();
    }

    public long insert(long workId, SourceField field, Long listingId, String targetRef, SourceType sourceType,
            String sourceRef, String sourceUrl, Instant verifiedAt, long verifiedBy, String note) {
        Long id = jdbc.queryForObject("""
                        INSERT INTO work_field_sources (work_id, field, listing_id, target_ref, source_type, source_ref,
                            source_url, verified_at, verified_by, note)
                        VALUES (:workId, :field, :listingId, :targetRef, :sourceType, :sourceRef, :sourceUrl,
                            :verifiedAt, :verifiedBy, :note)
                        RETURNING id""",
                new MapSqlParameterSource()
                        .addValue("workId", workId)
                        .addValue("field", field.name())
                        .addValue("listingId", listingId)
                        .addValue("targetRef", targetRef)
                        .addValue("sourceType", sourceType.name())
                        .addValue("sourceRef", sourceRef)
                        .addValue("sourceUrl", sourceUrl)
                        .addValue("verifiedAt", Timestamp.from(verifiedAt))
                        .addValue("verifiedBy", verifiedBy)
                        .addValue("note", note),
                Long.class);
        if (id == null) {
            throw new IllegalStateException("field source id not returned");
        }
        return id;
    }

    public void updateStatus(long id, FieldSourceStatus status, String note) {
        int updated = jdbc.update("""
                        UPDATE work_field_sources
                        SET status = :status, note = :note, updated_at = GREATEST(updated_at, now())
                        WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status.name()).addValue("note", note));
        if (updated != 1) {
            throw new IllegalStateException("field source was not updated: " + id);
        }
    }

    private static FieldSourceRow map(ResultSet rs) throws SQLException {
        return new FieldSourceRow(
                rs.getLong("id"),
                rs.getLong("work_id"),
                SourceField.valueOf(rs.getString("field")),
                rs.getObject("listing_id", Long.class),
                rs.getString("target_ref"),
                SourceType.valueOf(rs.getString("source_type")),
                rs.getString("source_ref"),
                rs.getString("source_url"),
                FieldSourceStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("verified_at").toInstant(),
                rs.getLong("verified_by"),
                rs.getString("note"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
