package com.manhwanet.collection.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 작업 큐 (V9 crawl_tasks·crawl_failure_samples) */
@Repository
public class CrawlTaskRepository {

    private static final String COLUMNS = "id, run_id, task_kind, url, url_hash, status, attempts, next_attempt_at, "
            + "last_http_status, last_error, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbc;

    public CrawlTaskRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 실행 안에서 같은 (종류, URL 해시) 작업은 한 번만 만든다. 이미 있으면 빈 값 */
    public Optional<Long> insertIfAbsent(long runId, TaskKind kind, String url, String urlHash) {
        return jdbc.queryForList("""
                INSERT INTO crawl_tasks (run_id, task_kind, url, url_hash) VALUES (:run, :kind, :url, :hash)
                ON CONFLICT ON CONSTRAINT uk_crawl_tasks DO NOTHING RETURNING id""",
                new MapSqlParameterSource().addValue("run", runId).addValue("kind", kind.name()).addValue("url", url)
                        .addValue("hash", urlHash),
                Long.class).stream().findFirst();
    }

    public Optional<TaskRow> find(long runId, TaskKind kind, String urlHash) {
        return jdbc.query("SELECT " + COLUMNS + " FROM crawl_tasks WHERE run_id = :run AND task_kind = :kind AND url_hash = :hash",
                new MapSqlParameterSource().addValue("run", runId).addValue("kind", kind.name()).addValue("hash", urlHash),
                (rs, n) -> map(rs)).stream().findFirst();
    }

    public Optional<TaskRow> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM crawl_tasks WHERE id = :id", new MapSqlParameterSource("id", id),
                (rs, n) -> map(rs)).stream().findFirst();
    }

    public Optional<TaskRow> lockById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM crawl_tasks WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), (rs, n) -> map(rs)).stream().findFirst();
    }

    /**
     * 실행할 작업을 가져간다. 대기 시각이 지난 PENDING 을 id 순으로 골라 RUNNING 으로 바꾸고 시도 횟수를 올린다.
     * 다른 트랜잭션이 잡은 행은 건너뛴다(SKIP LOCKED) — 같은 작업이 두 번 나가지 않는다.
     */
    public List<TaskRow> claim(long runId, int limit) {
        return jdbc.query("""
                UPDATE crawl_tasks SET status = 'RUNNING', attempts = attempts + 1, updated_at = now()
                WHERE id IN (
                    SELECT id FROM crawl_tasks
                    WHERE run_id = :run AND status = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED)
                """ + "RETURNING " + COLUMNS,
                new MapSqlParameterSource().addValue("run", runId).addValue("limit", limit), (rs, n) -> map(rs))
                .stream().sorted(java.util.Comparator.comparingLong(TaskRow::id)).toList();
    }

    public void finish(long id, TaskStatus status, Integer httpStatus, TaskError error) {
        int updated = jdbc.update("""
                UPDATE crawl_tasks SET status = :status, last_http_status = :http, last_error = :error, updated_at = now()
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status.name())
                        .addValue("http", httpStatus, Types.SMALLINT)
                        .addValue("error", error == null ? null : error.name(), Types.VARCHAR));
        if (updated != 1) {
            throw new IllegalStateException("crawl task was not updated: " + id);
        }
    }

    /** 다시 시도할 수 있게 PENDING 으로 되돌리고 다음 시도 시각을 정한다 */
    public void retryLater(long id, int delaySeconds, Integer httpStatus, TaskError error) {
        int updated = jdbc.update("""
                UPDATE crawl_tasks SET status = 'PENDING', next_attempt_at = now() + make_interval(secs => :delay),
                    last_http_status = :http, last_error = :error, updated_at = now()
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", id).addValue("delay", delaySeconds)
                        .addValue("http", httpStatus, Types.SMALLINT).addValue("error", error.name()));
        if (updated != 1) {
            throw new IllegalStateException("crawl task was not updated: " + id);
        }
    }

    /**
     * #46: 아직 때가 되지 않은 PENDING 작업 중 가장 이른 것까지 남은 밀리초(DB now() 기준, 0 이상).
     * PENDING 작업이 없으면 빈 값
     */
    public Optional<Long> nextPendingDelayMillis(long runId) {
        return jdbc.queryForList("""
                SELECT CAST(GREATEST(0, EXTRACT(EPOCH FROM (m - now())) * 1000) AS BIGINT)
                FROM (SELECT MIN(next_attempt_at) AS m FROM crawl_tasks WHERE run_id = :run AND status = 'PENDING') x
                WHERE m IS NOT NULL""",
                new MapSqlParameterSource("run", runId), Long.class).stream().findFirst();
    }

    /** 잠금을 넘겨받을 때: 끝나지 않은 RUNNING 작업을 PENDING 으로 되돌린다(시도 횟수는 그대로) */
    public int resetRunning(long runId) {
        return jdbc.update("UPDATE crawl_tasks SET status = 'PENDING', updated_at = now() WHERE run_id = :run AND status = 'RUNNING'",
                new MapSqlParameterSource("run", runId));
    }

    /** 중단·실패로 닫을 때: 남은 작업을 SKIPPED 로 닫는다 */
    public int skipOpen(long runId) {
        return jdbc.update("""
                UPDATE crawl_tasks SET status = 'SKIPPED', last_error = COALESCE(last_error, 'OPERATOR_STOP'), updated_at = now()
                WHERE run_id = :run AND status IN ('PENDING', 'RUNNING')""", new MapSqlParameterSource("run", runId));
    }

    public Map<TaskStatus, Long> countByStatus(long runId) {
        Map<TaskStatus, Long> counts = new EnumMap<>(TaskStatus.class);
        for (TaskStatus status : TaskStatus.values()) {
            counts.put(status, 0L);
        }
        jdbc.query("SELECT status, COUNT(*) AS n FROM crawl_tasks WHERE run_id = :run GROUP BY status",
                new MapSqlParameterSource("run", runId),
                rs -> {
                    counts.put(TaskStatus.valueOf(rs.getString("status")), rs.getLong("n"));
                });
        return counts;
    }

    public long countSamples(long runId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM crawl_failure_samples s JOIN crawl_tasks t ON t.id = s.task_id WHERE t.run_id = :run""",
                new MapSqlParameterSource("run", runId), Long.class);
        return n == null ? 0 : n;
    }

    public void insertSample(long taskId, Integer httpStatus, TaskError error, byte[] bodyGzip) {
        jdbc.update("INSERT INTO crawl_failure_samples (task_id, http_status, error, body_gzip) VALUES (:task, :http, :error, :body)",
                new MapSqlParameterSource().addValue("task", taskId).addValue("http", httpStatus, Types.SMALLINT)
                        .addValue("error", error.name()).addValue("body", bodyGzip, Types.BINARY));
    }

    private static TaskRow map(ResultSet rs) throws SQLException {
        String error = rs.getString("last_error");
        return new TaskRow(
                rs.getLong("id"),
                rs.getLong("run_id"),
                TaskKind.valueOf(rs.getString("task_kind")),
                rs.getString("url"),
                rs.getString("url_hash"),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getObject("last_http_status", Integer.class),
                error == null ? null : TaskError.valueOf(error),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
