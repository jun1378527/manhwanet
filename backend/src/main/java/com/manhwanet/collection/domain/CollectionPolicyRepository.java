package com.manhwanet.collection.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 플랫폼 수집 설정 (V9 platform_collection_policies) */
@Repository
public class CollectionPolicyRepository {

    private static final String COLUMNS = "platform_code, collection_policy, synopsis_policy, cover_policy, note, basis_kind, "
            + "basis_note, decided_by, decided_at, rate_interval_ms, daily_request_cap, version, updated_at";

    private final NamedParameterJdbcTemplate jdbc;

    public CollectionPolicyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PolicyRow> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_collection_policies ORDER BY platform_code",
                (rs, n) -> map(rs));
    }

    public Optional<PolicyRow> findByCode(String code) {
        return one("SELECT " + COLUMNS + " FROM platform_collection_policies WHERE platform_code = :code", code);
    }

    /** 설정 변경용 잠금 */
    public Optional<PolicyRow> lockForUpdate(String code) {
        return one("SELECT " + COLUMNS + " FROM platform_collection_policies WHERE platform_code = :code FOR NO KEY UPDATE",
                code);
    }

    /** 실행 시작·재개용 공유 잠금: 그 사이에 설정이 바뀌지 않게(설정 변경과 직렬화) */
    public Optional<PolicyRow> lockForShare(String code) {
        return one("SELECT " + COLUMNS + " FROM platform_collection_policies WHERE platform_code = :code FOR SHARE", code);
    }

    public int update(String code, long expectedVersion, CollectionPolicy collection, SynopsisPolicy synopsis,
            CoverPolicy cover, String note, BasisKind basisKind, String basisNote, long decidedBy, int rateIntervalMs,
            int dailyRequestCap) {
        return jdbc.update("""
                UPDATE platform_collection_policies
                SET collection_policy = :collection, synopsis_policy = :synopsis, cover_policy = :cover, note = :note,
                    basis_kind = :basisKind, basis_note = :basisNote, decided_by = :decidedBy, decided_at = now(),
                    rate_interval_ms = :rate, daily_request_cap = :cap, version = version + 1,
                    updated_at = GREATEST(updated_at, now())
                WHERE platform_code = :code AND version = :expected""",
                new MapSqlParameterSource()
                        .addValue("code", code)
                        .addValue("expected", expectedVersion)
                        .addValue("collection", collection.name())
                        .addValue("synopsis", synopsis.name())
                        .addValue("cover", cover.name())
                        .addValue("note", note)
                        .addValue("basisKind", basisKind == null ? null : basisKind.name())
                        .addValue("basisNote", basisNote)
                        .addValue("decidedBy", decidedBy)
                        .addValue("rate", rateIntervalMs)
                        .addValue("cap", dailyRequestCap));
    }

    private Optional<PolicyRow> one(String sql, String code) {
        return jdbc.query(sql, new MapSqlParameterSource("code", code), (rs, n) -> map(rs)).stream().findFirst();
    }

    private static PolicyRow map(ResultSet rs) throws SQLException {
        String basis = rs.getString("basis_kind");
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new PolicyRow(
                rs.getString("platform_code"),
                CollectionPolicy.valueOf(rs.getString("collection_policy")),
                SynopsisPolicy.valueOf(rs.getString("synopsis_policy")),
                CoverPolicy.valueOf(rs.getString("cover_policy")),
                rs.getString("note"),
                basis == null ? null : BasisKind.valueOf(basis),
                rs.getString("basis_note"),
                rs.getObject("decided_by", Long.class),
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getInt("rate_interval_ms"),
                rs.getInt("daily_request_cap"),
                rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
