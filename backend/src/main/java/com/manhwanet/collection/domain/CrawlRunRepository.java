package com.manhwanet.collection.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 수집 실행 (V9 crawl_runs). 시각 비교는 모두 DB now() 로 한다 */
@Repository
public class CrawlRunRepository {

    private static final String COLUMNS = "id, platform_code, run_kind, status, stop_reason, robots_sha256, adapter_version, "
            + "lease_owner, lease_until, (lease_until IS NOT NULL AND lease_until > now()) AS lease_valid, started_at, "
            + "stop_requested_at, finished_at, stats::text AS stats";

    private final NamedParameterJdbcTemplate jdbc;

    public CrawlRunRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 활성 실행이 없을 때만 새 실행을 만든다(부분 유니크 인덱스). 이미 있으면 빈 값.
     * 동시에 두 요청이 오면 한쪽은 다른 쪽 트랜잭션이 끝날 때까지 기다린 뒤 결과를 따른다.
     */
    public Optional<Long> insertIfNoActive(String platformCode, RunKind kind, String adapterVersion, String owner,
            int leaseSeconds, String robotsSha256) {
        return jdbc.queryForList("""
                INSERT INTO crawl_runs (platform_code, run_kind, status, adapter_version, lease_owner, lease_until, robots_sha256)
                VALUES (:platform, :kind, 'RUNNING', :adapter, :owner, now() + make_interval(secs => :lease), :robots)
                ON CONFLICT (platform_code) WHERE status IN ('RUNNING', 'PAUSED', 'STOP_REQUESTED', 'BLOCKED') DO NOTHING
                RETURNING id""",
                new MapSqlParameterSource()
                        .addValue("platform", platformCode)
                        .addValue("kind", kind.name())
                        .addValue("adapter", adapterVersion)
                        .addValue("owner", owner)
                        .addValue("lease", leaseSeconds)
                        .addValue("robots", robotsSha256, java.sql.Types.VARCHAR),
                Long.class).stream().findFirst();
    }

    public Optional<RunRow> findById(long id) {
        return one("SELECT " + COLUMNS + " FROM crawl_runs WHERE id = :id", id);
    }

    public Optional<RunRow> findActive(String platformCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM crawl_runs WHERE platform_code = :platform AND status IN "
                        + "('RUNNING', 'PAUSED', 'STOP_REQUESTED', 'BLOCKED')",
                new MapSqlParameterSource("platform", platformCode), (rs, n) -> map(rs)).stream().findFirst();
    }

    /** 상태 전환용 행 잠금 */
    public Optional<RunRow> lockForUpdate(long id) {
        return one("SELECT " + COLUMNS + " FROM crawl_runs WHERE id = :id FOR NO KEY UPDATE", id);
    }

    /**
     * 수집 결과·작업 갱신용 공유 잠금. 상태 전환(잠금 넘겨받기·중단 확정)과 직렬화되어,
     * 잠금을 잃은 프로세스가 넘겨받은 뒤에 쓰지 못한다.
     */
    public Optional<RunRow> lockForShare(long id) {
        return one("SELECT " + COLUMNS + " FROM crawl_runs WHERE id = :id FOR SHARE", id);
    }

    public List<RunRow> findPage(String platformCode, long beforeId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("before", beforeId).addValue("limit", limit);
        String where = "id < :before";
        if (platformCode != null) {
            where += " AND platform_code = :platform";
            params.addValue("platform", platformCode);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM crawl_runs WHERE " + where + " ORDER BY id DESC LIMIT :limit",
                params, (rs, n) -> map(rs));
    }

    /** 잠금 갱신·넘겨받기. status 를 함께 바꾼다(STOP_REQUESTED 는 유지) */
    public void lease(long id, RunStatus status, String owner, int leaseSeconds) {
        update("""
                UPDATE crawl_runs SET status = :status, lease_owner = :owner,
                    lease_until = now() + make_interval(secs => :lease),
                    stop_reason = CASE WHEN :status = 'RUNNING' THEN NULL ELSE stop_reason END
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status.name())
                        .addValue("owner", owner).addValue("lease", leaseSeconds));
    }

    /** 활성 상태로 바꾸며 잠금을 놓는다(PAUSED·BLOCKED) */
    public void release(long id, RunStatus status, StopReason reason) {
        update("UPDATE crawl_runs SET status = :status, stop_reason = :reason, lease_owner = NULL, lease_until = NULL "
                        + "WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status.name())
                        .addValue("reason", reason == null ? null : reason.name(), java.sql.Types.VARCHAR));
    }

    public void requestStop(long id) {
        update("UPDATE crawl_runs SET status = 'STOP_REQUESTED', stop_requested_at = now(), stop_reason = 'OPERATOR_STOP' "
                + "WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    /** 종료 상태로 닫는다(잠금 해제, 끝난 시각, 통계) */
    public void finish(long id, RunStatus status, StopReason reason, String statsJson) {
        update("""
                UPDATE crawl_runs SET status = :status, stop_reason = COALESCE(CAST(:reason AS VARCHAR), stop_reason),
                    lease_owner = NULL, lease_until = NULL, finished_at = now(),
                    stats = COALESCE(CAST(:stats AS jsonb), stats)
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status.name())
                        .addValue("reason", reason == null ? null : reason.name(), java.sql.Types.VARCHAR)
                        .addValue("stats", statsJson, java.sql.Types.VARCHAR));
    }

    /** #46: 이 실행에서 받은 429 횟수를 하나 올리고 새 값을 돌려준다 */
    public int incrementRateLimitHits(long id) {
        return jdbc.queryForObject("UPDATE crawl_runs SET rate_limit_hits = rate_limit_hits + 1 WHERE id = :id "
                + "RETURNING rate_limit_hits", new MapSqlParameterSource("id", id), Integer.class);
    }

    /** #46: 이 실행에서 받은 429 횟수 */
    public int rateLimitHits(long id) {
        return jdbc.queryForObject("SELECT rate_limit_hits FROM crawl_runs WHERE id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
    }

    /** #46: 실행을 시작할 때 읽은 robots.txt 의 SHA-256 */
    public void updateRobots(long id, String robotsSha256) {
        update("UPDATE crawl_runs SET robots_sha256 = :robots WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("robots", robotsSha256, java.sql.Types.VARCHAR));
    }

    /** #46: 다음 요청은 지금부터 적어도 millis 뒤. 이미 더 늦은 시각이 있으면 유지한다 */
    public void postponeNextRequest(long id, long millis) {
        update("""
                UPDATE crawl_runs SET next_request_at = GREATEST(COALESCE(next_request_at, now()),
                    now() + (:millis * INTERVAL '1 millisecond'))
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", id).addValue("millis", millis));
    }

    /** #46: 다음 요청까지 남은 밀리초. 시각이 없거나 지났으면 0. 행 잠금은 잡지 않는다 */
    public long millisUntilNextRequest(long id) {
        Long millis = jdbc.queryForObject("""
                SELECT COALESCE(CEIL(EXTRACT(EPOCH FROM (next_request_at - now())) * 1000), 0)::bigint
                FROM crawl_runs WHERE id = :id""",
                new MapSqlParameterSource("id", id), Long.class);
        return millis == null || millis < 0 ? 0 : millis;
    }

    /** #46: 통계 JSON 객체를 기존 값에 합친다(같은 키는 새 값) */
    public void mergeStats(long id, String statsJson) {
        update("UPDATE crawl_runs SET stats = stats || CAST(:stats AS jsonb) WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("stats", statsJson, java.sql.Types.VARCHAR));
    }

    private void update(String sql, MapSqlParameterSource params) {
        if (jdbc.update(sql, params) != 1) {
            throw new IllegalStateException("crawl run was not updated: " + params.getValue("id"));
        }
    }

    private Optional<RunRow> one(String sql, long id) {
        return jdbc.query(sql, new MapSqlParameterSource("id", id), (rs, n) -> map(rs)).stream().findFirst();
    }

    private static RunRow map(ResultSet rs) throws SQLException {
        String reason = rs.getString("stop_reason");
        return new RunRow(
                rs.getLong("id"),
                rs.getString("platform_code"),
                RunKind.valueOf(rs.getString("run_kind")),
                RunStatus.valueOf(rs.getString("status")),
                reason == null ? null : StopReason.valueOf(reason),
                rs.getString("robots_sha256"),
                rs.getString("adapter_version"),
                rs.getString("lease_owner"),
                instant(rs.getTimestamp("lease_until")),
                rs.getBoolean("lease_valid"),
                rs.getTimestamp("started_at").toInstant(),
                instant(rs.getTimestamp("stop_requested_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("stats"));
    }

    static java.time.Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
