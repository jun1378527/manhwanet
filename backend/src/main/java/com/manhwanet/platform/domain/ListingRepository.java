package com.manhwanet.platform.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * work_platform_listings SQL. 삭제 SQL 은 두지 않는다(상태 변경만).
 */
@Repository
public class ListingRepository {

    private static final String COLUMNS = """
            id, work_id, platform_code, platform_work_id, id_scope, url, listing_kind, language_code, status,
            moved_to_listing_id, age_rating_raw, age_class, first_verified_at, last_verified_at, verified_by, note,
            created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ListingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ListingRow> findByWorkId(long workId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM work_platform_listings WHERE work_id = :workId ORDER BY id",
                Map.of("workId", workId), (rs, n) -> map(rs));
    }

    public Optional<ListingRow> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM work_platform_listings WHERE id = :id",
                Map.of("id", id), (rs, n) -> map(rs)).stream().findFirst();
    }

    public Optional<ListingRow> findByPlatformWorkId(String platformCode, String platformWorkId) {
        return jdbc.query("""
                SELECT %s FROM work_platform_listings
                WHERE platform_code = :platformCode AND platform_work_id = :platformWorkId
                """.formatted(COLUMNS), Map.of("platformCode", platformCode, "platformWorkId", platformWorkId),
                (rs, n) -> map(rs)).stream().findFirst();
    }

    /**
     * 새 연결. 같은 (platform_code, platform_work_id) 가 이미 있으면 아무것도 하지 않고 빈 값을 돌려준다
     * (유니크 위반 예외로 트랜잭션을 깨지 않기 위해 ON CONFLICT DO NOTHING).
     */
    public Optional<Long> insertIfAbsent(long workId, String platformCode, String platformWorkId, IdScope idScope,
            String url, ListingKind listingKind, String languageCode, String ageRatingRaw, AgeClass ageClass,
            Instant verifiedAt, long verifiedBy, String note) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO work_platform_listings
                    (work_id, platform_code, platform_work_id, id_scope, url, listing_kind, language_code, status,
                     age_rating_raw, age_class, first_verified_at, last_verified_at, verified_by, note)
                VALUES (:workId, :platformCode, :platformWorkId, :idScope, :url, :listingKind, :languageCode, 'ACTIVE',
                        :ageRatingRaw, :ageClass, :verifiedAt, :verifiedAt, :verifiedBy, :note)
                ON CONFLICT ON CONSTRAINT uk_listings_platform_work DO NOTHING
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("workId", workId).addValue("platformCode", platformCode)
                .addValue("platformWorkId", platformWorkId).addValue("idScope", idScope.name())
                .addValue("url", url).addValue("listingKind", listingKind.name())
                .addValue("languageCode", languageCode).addValue("ageRatingRaw", ageRatingRaw)
                .addValue("ageClass", ageClass.name()).addValue("verifiedAt", Timestamp.from(verifiedAt))
                .addValue("verifiedBy", verifiedBy).addValue("note", note), Long.class);
        return ids.stream().findFirst();
    }

    /** 바꿀 수 있는 필드 전체 교체. 플랫폼·외부 작품 ID·작품은 바꾸지 않는다 */
    public int update(long id, IdScope idScope, String url, ListingKind listingKind, String languageCode,
            ListingStatus status, Long movedToListingId, String ageRatingRaw, AgeClass ageClass,
            Instant lastVerifiedAt, long verifiedBy, String note) {
        return jdbc.update("""
                UPDATE work_platform_listings
                SET id_scope = :idScope, url = :url, listing_kind = :listingKind, language_code = :languageCode,
                    status = :status, moved_to_listing_id = :movedTo, age_rating_raw = :ageRatingRaw,
                    age_class = :ageClass, last_verified_at = :lastVerifiedAt, verified_by = :verifiedBy,
                    note = :note, updated_at = GREATEST(updated_at, now())
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("idScope", idScope.name()).addValue("url", url)
                .addValue("listingKind", listingKind.name()).addValue("languageCode", languageCode)
                .addValue("status", status.name()).addValue("movedTo", movedToListingId)
                .addValue("ageRatingRaw", ageRatingRaw).addValue("ageClass", ageClass.name())
                .addValue("lastVerifiedAt", Timestamp.from(lastVerifiedAt)).addValue("verifiedBy", verifiedBy)
                .addValue("note", note));
    }

    /**
     * 이전 대상 평탄화: from 을 가리키던 같은 작품의 MOVED 연결이 to 를 가리키게 한다.
     * from 이 MOVED 로 바뀔 때 함께 호출해 "MOVED 연결의 대상은 MOVED 가 아니다"를 유지한다. 바뀐 연결 id 를 돌려준다(변경 기록용).
     */
    public List<Long> repointMovedTo(long workId, long fromId, long toId) {
        return jdbc.queryForList("""
                UPDATE work_platform_listings
                SET moved_to_listing_id = :toId, updated_at = GREATEST(updated_at, now())
                WHERE work_id = :workId AND moved_to_listing_id = :fromId
                RETURNING id
                """, Map.of("workId", workId, "fromId", fromId, "toId", toId), Long.class);
    }

    private static ListingRow map(ResultSet rs) throws SQLException {
        long moved = rs.getLong("moved_to_listing_id");
        Long movedTo = rs.wasNull() ? null : moved;
        return new ListingRow(
                rs.getLong("id"),
                rs.getLong("work_id"),
                rs.getString("platform_code"),
                rs.getString("platform_work_id"),
                IdScope.valueOf(rs.getString("id_scope")),
                rs.getString("url"),
                ListingKind.valueOf(rs.getString("listing_kind")),
                rs.getString("language_code"),
                ListingStatus.valueOf(rs.getString("status")),
                movedTo,
                rs.getString("age_rating_raw"),
                AgeClass.valueOf(rs.getString("age_class")),
                PlatformRepository.instant(rs, "first_verified_at"),
                PlatformRepository.instant(rs, "last_verified_at"),
                rs.getLong("verified_by"),
                rs.getString("note"),
                PlatformRepository.instant(rs, "created_at"),
                PlatformRepository.instant(rs, "updated_at"));
    }
}
