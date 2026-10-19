package com.manhwanet.work.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 공개 작품 찾기 SQL(읽기 전용).
 *
 * <p>도메인 규칙의 예외(명세 work-creator-spec "작품 찾기"): 검색·필터·정렬·건수를 전체 공개 작품에 대해 DB 한 번에
 * 계산하려고 작가(creators)·플랫폼 연결(work_platform_listings, platforms)·별점(work_ratings)·리뷰(work_reviews) 테이블을 SQL 로 읽는다.
 * 다른 도메인의 Repository·Entity 는 참조하지 않고, 쓰기는 하지 않는다.
 *
 * <p>중복 방지: 제목·작가·플랫폼 조건은 모두 EXISTS 로 걸어 작품 행이 늘어나지 않는다. 정렬용 조인은 작품당 한 행만 붙는
 * 대표 제목(작품당 하나, 부분 유니크 인덱스)과 작품별 집계(별점·리뷰·최근 활동, 작품당 한 행)뿐이다.
 */
@Repository
public class WorkSearchRepository {

    /** 플랫폼 필터와 선택지에 넣는 연결 상태: 지금 그 플랫폼에서 볼 수 있는 연결만 */
    public static final String LISTED_STATUS = "ACTIVE";

    private static final String RATING_STATS = """
            SELECT work_id, COUNT(*) AS rating_count,
                   ROUND(SUM(score_half)::numeric / (COUNT(*) * 2), 1) AS rating_average
            FROM work_ratings
            GROUP BY work_id
            """;

    private static final String REVIEW_STATS = """
            SELECT work_id, COUNT(*) AS review_count
            FROM work_reviews
            GROUP BY work_id
            """;

    /** 최근 활동 이용자 수: 기간 안에 별점·리뷰를 남기거나 고친 이용자(별점과 리뷰를 모두 남겨도 1) */
    private static final String RECENT_ACTIVITY = """
            SELECT work_id, COUNT(DISTINCT user_id) AS active_users
            FROM (SELECT work_id, user_id FROM work_ratings WHERE updated_at >= :activitySince
                  UNION ALL
                  SELECT work_id, user_id FROM work_reviews WHERE updated_at >= :activitySince) a
            GROUP BY work_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public WorkSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 조건. 값이 null 이면 그 조건을 걸지 않는다 */
    public record Filter(String likePattern, String workFormat, String platformCode) {
    }

    public enum SortKey {
        RECENT("w.created_at DESC, w.id DESC"),
        POPULAR("COALESCE(ra.active_users, 0) DESC, COALESCE(rs.rating_count, 0) DESC, w.created_at DESC, w.id DESC"),
        RATING("rs.rating_average DESC NULLS LAST, rs.rating_count DESC NULLS LAST, w.created_at DESC, w.id DESC"),
        RATING_COUNT("COALESCE(rs.rating_count, 0) DESC, rs.rating_average DESC NULLS LAST, w.created_at DESC, w.id DESC"),
        REVIEW_COUNT("COALESCE(rv.review_count, 0) DESC, COALESCE(rs.rating_count, 0) DESC, w.created_at DESC, w.id DESC"),
        TITLE("lower(pt.title) COLLATE \"C\" ASC, pt.title COLLATE \"C\" ASC, w.id ASC");

        private final String orderBy;

        SortKey(String orderBy) {
            this.orderBy = orderBy;
        }
    }

    public long count(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT COUNT(*) FROM works w WHERE " + where(filter, params);
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    /**
     * 정렬·페이지를 적용한 작품 ID(순서 유지).
     *
     * @param activitySince 인기순이 세는 최근 활동의 시작 시각(그 외 정렬에서는 쓰지 않는다)
     */
    public List<Long> findIds(Filter filter, SortKey sort, Instant activitySince, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params);
        StringBuilder sql = new StringBuilder("SELECT w.id FROM works w ");
        if (sort == SortKey.RATING || sort == SortKey.RATING_COUNT || sort == SortKey.REVIEW_COUNT || sort == SortKey.POPULAR) {
            sql.append("LEFT JOIN (").append(RATING_STATS).append(") rs ON rs.work_id = w.id ");
        }
        if (sort == SortKey.REVIEW_COUNT) {
            sql.append("LEFT JOIN (").append(REVIEW_STATS).append(") rv ON rv.work_id = w.id ");
        }
        if (sort == SortKey.POPULAR) {
            sql.append("LEFT JOIN (").append(RECENT_ACTIVITY).append(") ra ON ra.work_id = w.id ");
            params.addValue("activitySince", Timestamp.from(activitySince));
        }
        if (sort == SortKey.TITLE) {
            sql.append("LEFT JOIN work_titles pt ON pt.work_id = w.id AND pt.is_primary ");
        }
        sql.append("WHERE ").append(where)
                .append(" ORDER BY ").append(sort.orderBy)
                .append(" LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.queryForList(sql.toString(), params, Long.class);
    }

    /** 공개 작품에 지금 연결(ACTIVE)이 있는 플랫폼과 그 공개 작품 수(작품 기준, 연결이 여럿이어도 1). 이름순 */
    public List<PlatformOption> findListedPlatforms() {
        return jdbc.query("""
                SELECT p.code, p.name, COUNT(DISTINCT l.work_id) AS work_count
                FROM work_platform_listings l
                JOIN works w ON w.id = l.work_id AND w.visibility = 'PUBLIC'
                JOIN platforms p ON p.code = l.platform_code
                WHERE l.status = :status
                GROUP BY p.code, p.name
                ORDER BY p.name COLLATE "C", p.code
                """, new MapSqlParameterSource("status", LISTED_STATUS),
                (rs, n) -> new PlatformOption(rs.getString("code"), rs.getString("name"), rs.getLong("work_count")));
    }

    public record PlatformOption(String code, String name, long workCount) {
    }

    private static String where(Filter filter, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        clauses.add("w.visibility = 'PUBLIC'");
        if (filter.workFormat() != null) {
            clauses.add("w.work_format = :format");
            params.addValue("format", filter.workFormat());
        }
        if (filter.platformCode() != null) {
            clauses.add("""
                    EXISTS (SELECT 1 FROM work_platform_listings l
                            WHERE l.work_id = w.id AND l.platform_code = :platform AND l.status = :listedStatus)""");
            params.addValue("platform", filter.platformCode()).addValue("listedStatus", LISTED_STATUS);
        }
        if (filter.likePattern() != null) {
            clauses.add("""
                    (EXISTS (SELECT 1 FROM work_titles t
                             WHERE t.work_id = w.id AND t.title ILIKE :pattern ESCAPE '\\')
                     OR EXISTS (SELECT 1 FROM work_creators wc JOIN creators c ON c.id = wc.creator_id
                                WHERE wc.work_id = w.id
                                  AND (c.name ILIKE :pattern ESCAPE '\\' OR c.original_name ILIKE :pattern ESCAPE '\\')))""");
            params.addValue("pattern", filter.likePattern());
        }
        return String.join(" AND ", clauses);
    }
}
