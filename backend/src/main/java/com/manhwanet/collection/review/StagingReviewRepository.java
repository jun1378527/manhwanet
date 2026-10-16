package com.manhwanet.collection.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** #51 검수: 후보(V11 staging_match_candidates), 결정(V15 staging_review_decisions), 비교용 조회 */
@Repository
public class StagingReviewRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StagingReviewRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CandidateRow(long id, long stagingId, Long workId, Long otherStagingId, double score, String reasonsJson,
            String warningsJson, String status) {
    }

    public record TitleRow(long id, String title) {
    }

    public record StagingTitleRow(long id, String platformCode, String titleRaw, String titleClean, String ageClass) {
    }

    // ---------- 비교용 조회 ----------

    /** 모든 작품 제목(대표·대체). 후보 비교는 서비스에서 키로 한다(전체 검사, 규모가 커지면 #41 에서 색인) */
    public List<TitleRow> workTitles() {
        return jdbc.query("SELECT work_id, title FROM work_titles ORDER BY work_id, id",
                (rs, n) -> new TitleRow(rs.getLong(1), rs.getString(2)));
    }

    public Map<Long, String> primaryTitles(Collection<Long> workIds) {
        Map<Long, String> out = new HashMap<>();
        if (workIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT work_id, title FROM work_titles WHERE is_primary AND work_id IN (:ids)",
                new MapSqlParameterSource("ids", workIds), rs -> {
                    out.put(rs.getLong(1), rs.getString(2));
                });
        return out;
    }

    public Map<Long, List<String>> workCreatorNames(Collection<Long> workIds) {
        Map<Long, List<String>> out = new HashMap<>();
        if (workIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT wc.work_id, c.name FROM work_creators wc JOIN creators c ON c.id = wc.creator_id
                WHERE wc.work_id IN (:ids)""", new MapSqlParameterSource("ids", workIds), rs -> {
            out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
        });
        return out;
    }

    /** 작품별 ACTIVE 연결의 등급 */
    public Map<Long, List<String>> workAgeClasses(Collection<Long> workIds) {
        Map<Long, List<String>> out = new HashMap<>();
        if (workIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT work_id, age_class FROM work_platform_listings WHERE status = 'ACTIVE' AND work_id IN (:ids)",
                new MapSqlParameterSource("ids", workIds), rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return out;
    }

    public List<StagingTitleRow> otherStagingTitles(long stagingId) {
        return jdbc.query("""
                SELECT id, platform_code, title_raw, title_clean, age_class FROM staging_listings
                WHERE id <> :id AND (title_clean IS NOT NULL OR title_raw IS NOT NULL) ORDER BY id""",
                new MapSqlParameterSource("id", stagingId),
                (rs, n) -> new StagingTitleRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    public Map<Long, List<String>> stagingCreditNames(Collection<Long> stagingIds) {
        Map<Long, List<String>> out = new HashMap<>();
        if (stagingIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT staging_id, name_raw FROM staging_credits WHERE staging_id IN (:ids)",
                new MapSqlParameterSource("ids", stagingIds), rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return out;
    }

    /** 이름이 같은(대소문자·공백·기호 무시 전 단계: 소문자·앞뒤 공백) 기존 작가 ID. 동명이인 확인용 */
    public List<Long> creatorIdsByName(String name) {
        return jdbc.queryForList("SELECT id FROM creators WHERE lower(btrim(name)) = lower(btrim(:name)) ORDER BY id",
                new MapSqlParameterSource("name", name), Long.class);
    }

    public Optional<Long> workVersion(long workId) {
        return jdbc.queryForList("SELECT version FROM works WHERE id = :id", new MapSqlParameterSource("id", workId), Long.class)
                .stream().findFirst();
    }

    // ---------- 후보 ----------

    /**
     * 다시 만들어도 행은 늘지 않는다. 사람이 남긴 ACCEPTED·REJECTED 는 유지한다.
     * 자동 STALE 로 닫힌 후보가 다시 생성되면 OPEN 으로 되돌리고 판단자·판단 시각을 비운다(#66).
     */
    private static final String REFRESH_CANDIDATE = "score = EXCLUDED.score, reasons = EXCLUDED.reasons, "
            + "warnings = EXCLUDED.warnings, updated_at = now(), "
            + "status = CASE WHEN staging_match_candidates.status = 'REJECTED' "
            + "AND jsonb_exists(staging_match_candidates.warnings, 'STALE') THEN 'OPEN' "
            + "ELSE staging_match_candidates.status END, "
            + "decided_by = CASE WHEN staging_match_candidates.status = 'REJECTED' "
            + "AND jsonb_exists(staging_match_candidates.warnings, 'STALE') THEN NULL "
            + "ELSE staging_match_candidates.decided_by END, "
            + "decided_at = CASE WHEN staging_match_candidates.status = 'REJECTED' "
            + "AND jsonb_exists(staging_match_candidates.warnings, 'STALE') THEN NULL "
            + "ELSE staging_match_candidates.decided_at END";

    /** 기존 작품 후보. 같은 (항목, 작품)은 한 행(uk_smc_work) */
    public long upsertWorkCandidate(long stagingId, long workId, double score, String reasonsJson, String warningsJson) {
        return jdbc.queryForObject("INSERT INTO staging_match_candidates (staging_id, work_id, score, reasons, warnings) "
                + "VALUES (:s, :w, :score, CAST(:reasons AS jsonb), CAST(:warnings AS jsonb)) "
                + "ON CONFLICT (staging_id, work_id) WHERE work_id IS NOT NULL DO UPDATE SET " + REFRESH_CANDIDATE
                + " RETURNING id",
                new MapSqlParameterSource().addValue("s", stagingId).addValue("w", workId).addValue("score", score)
                        .addValue("reasons", reasonsJson).addValue("warnings", warningsJson), Long.class);
    }

    /** 다른 수집 대기 항목 후보. 쌍마다 한 행(uk_smc_pair)이라 두 항목이 같은 행을 본다 */
    public long upsertPairCandidate(long stagingId, long otherStagingId, double score, String reasonsJson, String warningsJson) {
        return jdbc.queryForObject("INSERT INTO staging_match_candidates (staging_id, other_staging_id, score, reasons, warnings) "
                + "VALUES (:s, :o, :score, CAST(:reasons AS jsonb), CAST(:warnings AS jsonb)) "
                + "ON CONFLICT ((LEAST(staging_id, other_staging_id)), (GREATEST(staging_id, other_staging_id))) "
                + "WHERE other_staging_id IS NOT NULL DO UPDATE SET " + REFRESH_CANDIDATE + " RETURNING id",
                new MapSqlParameterSource().addValue("s", stagingId).addValue("o", otherStagingId).addValue("score", score)
                        .addValue("reasons", reasonsJson).addValue("warnings", warningsJson), Long.class);
    }

    /** 이 항목의 후보(쌍 후보는 어느 쪽에서 만들었든 포함) */
    public List<CandidateRow> candidates(long stagingId) {
        return jdbc.query("""
                SELECT id, staging_id, work_id, other_staging_id, score, reasons::text, warnings::text, status
                FROM staging_match_candidates WHERE staging_id = :s OR other_staging_id = :s ORDER BY score DESC, id""",
                new MapSqlParameterSource("s", stagingId), (rs, n) -> candidate(rs));
    }

    /** 판단(OPEN → ACCEPTED·REJECTED). 이미 판단한 후보는 바꾸지 않는다 */
    public void decideCandidate(long id, String status, long actorId, String extraWarning) {
        jdbc.update("""
                UPDATE staging_match_candidates SET status = :status, decided_by = :actor, decided_at = now(), updated_at = now(),
                    warnings = CASE WHEN CAST(:extra AS VARCHAR) IS NULL THEN warnings
                        ELSE warnings || jsonb_build_array(CAST(:extra AS VARCHAR)) END
                WHERE id = :id AND status = 'OPEN'""",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status).addValue("actor", actorId)
                        .addValue("extra", extraWarning, Types.VARCHAR));
    }

    // ---------- 검수 상태·결정 ----------

    public void updateReview(long stagingId, String reviewStatus, Long linkedListingId) {
        int updated = jdbc.update("""
                UPDATE staging_listings SET review_status = :status, linked_listing_id = :listing, updated_at = GREATEST(updated_at, now())
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", stagingId).addValue("status", reviewStatus)
                        .addValue("listing", linkedListingId, Types.BIGINT));
        if (updated != 1) {
            throw new IllegalStateException("staging listing was not updated: " + stagingId);
        }
    }

    /** NEW 이고 열린 후보가 있으면 CANDIDATES 로 둔다(검수 대기열 표시용) */
    public void markCandidates(long stagingId) {
        jdbc.update("UPDATE staging_listings SET review_status = 'CANDIDATES', updated_at = GREATEST(updated_at, now()) "
                + "WHERE id = :id AND review_status = 'NEW'", new MapSqlParameterSource("id", stagingId));
    }

    public long insertDecision(long stagingId, String decision, long revision, String fromStatus, String toStatus, Long workId,
            Long listingId, String candidatesJson, String reason, long actorId) {
        return jdbc.queryForObject("""
                INSERT INTO staging_review_decisions
                    (staging_id, decision, staging_revision, from_status, to_status, work_id, listing_id, candidates, reason, decided_by)
                VALUES (:s, :d, :rev, :from, :to, :work, :listing, CAST(:candidates AS jsonb), :reason, :actor)
                RETURNING id""",
                new MapSqlParameterSource().addValue("s", stagingId).addValue("d", decision).addValue("rev", revision)
                        .addValue("from", fromStatus).addValue("to", toStatus).addValue("work", workId, Types.BIGINT)
                        .addValue("listing", listingId, Types.BIGINT).addValue("candidates", candidatesJson)
                        .addValue("reason", reason).addValue("actor", actorId), Long.class);
    }

    public List<StagingReviewView.Decision> decisions(long stagingId) {
        return jdbc.query("""
                SELECT id, decision, staging_revision, from_status, to_status, work_id, listing_id, reason, decided_by, decided_at
                FROM staging_review_decisions WHERE staging_id = :s ORDER BY id DESC LIMIT 50""",
                new MapSqlParameterSource("s", stagingId), (rs, n) -> new StagingReviewView.Decision(rs.getLong(1),
                        rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), (Long) rs.getObject(6, Long.class),
                        (Long) rs.getObject(7, Long.class), rs.getString(8), rs.getLong(9), rs.getTimestamp(10).toInstant()));
    }

    private static CandidateRow candidate(ResultSet rs) throws SQLException {
        return new CandidateRow(rs.getLong(1), rs.getLong(2), rs.getObject(3, Long.class), rs.getObject(4, Long.class),
                rs.getDouble(5), rs.getString(6), rs.getString(7), rs.getString(8));
    }
}
