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

/** 작품·작가 외부 ID (V8). 표 이름은 ExternalIdOwner 의 고정 값만 쓴다 */
@Repository
public class ExternalIdRepository {

    private static final String COLUMNS = "id, %s AS owner_id, source, external_id, source_url, status, verified_at, "
            + "verified_by, note, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbc;

    public ExternalIdRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ExternalIdRow> findByOwner(ExternalIdOwner owner, long ownerId) {
        return jdbc.query("SELECT " + columns(owner) + " FROM " + owner.table() + " WHERE " + owner.ownerColumn()
                + " = :ownerId ORDER BY id", new MapSqlParameterSource("ownerId", ownerId), (rs, n) -> map(rs, owner));
    }

    public Optional<ExternalIdRow> findById(ExternalIdOwner owner, long id) {
        return jdbc.query("SELECT " + columns(owner) + " FROM " + owner.table() + " WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, n) -> map(rs, owner)).stream().findFirst();
    }

    /** 같은 출처·ID 를 쓰고 있는(INVALID 가 아닌) 행 */
    public Optional<ExternalIdRow> findHolder(ExternalIdOwner owner, ExternalIdSource source, String externalId) {
        return jdbc.query("SELECT " + columns(owner) + " FROM " + owner.table()
                        + " WHERE source = :source AND external_id = :externalId AND status <> 'INVALID'",
                new MapSqlParameterSource().addValue("source", source.name()).addValue("externalId", externalId),
                (rs, n) -> map(rs, owner)).stream().findFirst();
    }

    /**
     * 새 외부 ID. 같은 출처·ID 가 이미 쓰이고 있으면(부분 유니크 인덱스) 아무것도 넣지 않고 빈 값을 돌려준다.
     * 동시에 같은 값을 넣으면 뒤의 요청은 앞 트랜잭션이 끝날 때까지 기다린 뒤 결과를 따른다.
     */
    public Optional<Long> insertIfAbsent(ExternalIdOwner owner, long ownerId, ExternalIdSource source,
            String externalId, String sourceUrl, Instant verifiedAt, long verifiedBy, String note) {
        List<Long> ids = jdbc.queryForList("INSERT INTO " + owner.table() + " (" + owner.ownerColumn()
                        + ", source, external_id, source_url, verified_at, verified_by, note)"
                        + " VALUES (:ownerId, :source, :externalId, :sourceUrl, :verifiedAt, :verifiedBy, :note)"
                        + " ON CONFLICT (source, external_id) WHERE status <> 'INVALID' DO NOTHING RETURNING id",
                new MapSqlParameterSource()
                        .addValue("ownerId", ownerId)
                        .addValue("source", source.name())
                        .addValue("externalId", externalId)
                        .addValue("sourceUrl", sourceUrl)
                        .addValue("verifiedAt", Timestamp.from(verifiedAt))
                        .addValue("verifiedBy", verifiedBy)
                        .addValue("note", note),
                Long.class);
        return ids.stream().findFirst();
    }

    public void update(ExternalIdOwner owner, long id, ExternalIdStatus status, Instant verifiedAt, long verifiedBy,
            String note) {
        int updated = jdbc.update("UPDATE " + owner.table() + " SET status = :status, verified_at = :verifiedAt,"
                        + " verified_by = :verifiedBy, note = :note, updated_at = GREATEST(updated_at, now())"
                        + " WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", status.name())
                        .addValue("verifiedAt", Timestamp.from(verifiedAt))
                        .addValue("verifiedBy", verifiedBy)
                        .addValue("note", note));
        if (updated != 1) {
            throw new IllegalStateException("external id was not updated: " + id);
        }
    }

    private static String columns(ExternalIdOwner owner) {
        return COLUMNS.formatted(owner.ownerColumn());
    }

    private static ExternalIdRow map(ResultSet rs, ExternalIdOwner owner) throws SQLException {
        return new ExternalIdRow(
                rs.getLong("id"),
                owner,
                rs.getLong("owner_id"),
                ExternalIdSource.valueOf(rs.getString("source")),
                rs.getString("external_id"),
                rs.getString("source_url"),
                ExternalIdStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("verified_at").toInstant(),
                rs.getLong("verified_by"),
                rs.getString("note"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
