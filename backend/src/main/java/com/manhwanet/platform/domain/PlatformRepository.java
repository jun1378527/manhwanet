package com.manhwanet.platform.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * platforms·platform_hosts·platform_status_history SQL.
 * JPA 엔티티 없이 JDBC 로 다룬다(같은 트랜잭션 매니저·커넥션을 쓴다).
 */
@Repository
public class PlatformRepository {

    private static final String COLUMNS = """
            code, name, operator_name, status, status_basis, merged_into, status_effective_on, status_evidence_url,
            default_id_scope, work_url_pattern, url_rule_basis, url_rule_checked_on, terms_checked_on, version,
            created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PlatformRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PlatformRow> findAll() {
        List<PlatformRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM platforms ORDER BY code",
                (rs, n) -> map(rs, List.of()));
        Map<String, List<String>> hosts = hostsByPlatform(null);
        List<PlatformRow> result = new ArrayList<>();
        for (PlatformRow row : rows) {
            result.add(withHosts(row, hosts.getOrDefault(row.code(), List.of())));
        }
        return result;
    }

    public Optional<PlatformRow> findByCode(String code) {
        return single("SELECT " + COLUMNS + " FROM platforms WHERE code = :code", code);
    }

    /** 상태·규칙 변경용 잠금. FOR NO KEY UPDATE 라서 연결 추가의 FK(KEY SHARE)는 막지 않는다 */
    public Optional<PlatformRow> lockForUpdate(String code) {
        return single("SELECT " + COLUMNS + " FROM platforms WHERE code = :code FOR NO KEY UPDATE", code);
    }

    /** 연결 추가·변경용 공유 잠금. 같은 시점의 상태 변경(FOR NO KEY UPDATE)과 직렬화된다 */
    public Optional<PlatformRow> lockForShare(String code) {
        return single("SELECT " + COLUMNS + " FROM platforms WHERE code = :code FOR SHARE", code);
    }

    public boolean exists(String code) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platforms WHERE code = :code",
                Map.of("code", code), Integer.class);
        return count != null && count > 0;
    }

    /** code 에서 merged_into 를 따라간 코드 목록(자기 자신 제외). 순환이 있어도 멈춘다 */
    public List<String> mergedChain(String code) {
        return jdbc.queryForList("""
                WITH RECURSIVE chain(code, merged_into, depth) AS (
                    SELECT code, merged_into, 0 FROM platforms WHERE code = :code
                    UNION ALL
                    SELECT p.code, p.merged_into, c.depth + 1
                    FROM platforms p JOIN chain c ON p.code = c.merged_into
                    WHERE c.depth < 20
                )
                SELECT code FROM chain WHERE depth > 0 ORDER BY depth
                """, Map.of("code", code), String.class);
    }

    public int updateRules(String code, long expectedVersion, String name, String operatorName, IdScope defaultIdScope,
            String workUrlPattern, UrlRuleBasis urlRuleBasis, LocalDate urlRuleCheckedOn, LocalDate termsCheckedOn) {
        return jdbc.update("""
                UPDATE platforms
                SET name = :name, operator_name = :operatorName, default_id_scope = :scope,
                    work_url_pattern = :pattern, url_rule_basis = :ruleBasis, url_rule_checked_on = :ruleCheckedOn,
                    terms_checked_on = :termsCheckedOn,
                    version = version + 1, updated_at = GREATEST(updated_at, now())
                WHERE code = :code AND version = :expected
                """, new MapSqlParameterSource()
                .addValue("code", code).addValue("expected", expectedVersion)
                .addValue("name", name).addValue("operatorName", operatorName)
                .addValue("scope", defaultIdScope.name()).addValue("pattern", workUrlPattern)
                .addValue("ruleBasis", urlRuleBasis.name()).addValue("ruleCheckedOn", urlRuleCheckedOn)
                .addValue("termsCheckedOn", termsCheckedOn));
    }

    public void replaceHosts(String code, Collection<String> hosts) {
        jdbc.update("DELETE FROM platform_hosts WHERE platform_code = :code", Map.of("code", code));
        for (String host : hosts) {
            jdbc.update("INSERT INTO platform_hosts (platform_code, host) VALUES (:code, :host)",
                    Map.of("code", code, "host", host));
        }
    }

    public int updateStatus(String code, long expectedVersion, PlatformStatus status, StatusBasis basis,
            String mergedInto, LocalDate effectiveOn, String evidenceUrl) {
        return jdbc.update("""
                UPDATE platforms
                SET status = :status, status_basis = :basis, merged_into = :mergedInto,
                    status_effective_on = :effectiveOn, status_evidence_url = :evidenceUrl,
                    version = version + 1, updated_at = GREATEST(updated_at, now())
                WHERE code = :code AND version = :expected
                """, new MapSqlParameterSource()
                .addValue("code", code).addValue("expected", expectedVersion)
                .addValue("status", status.name()).addValue("basis", basis.name())
                .addValue("mergedInto", mergedInto).addValue("effectiveOn", effectiveOn)
                .addValue("evidenceUrl", evidenceUrl));
    }

    public long insertHistory(String code, PlatformRow from, PlatformStatus toStatus, StatusBasis toBasis,
            String mergedInto, LocalDate effectiveOn, String evidenceUrl, String note, long changedBy) {
        Long id = jdbc.queryForObject("""
                INSERT INTO platform_status_history
                    (platform_code, from_status, from_basis, to_status, to_basis, merged_into, effective_on,
                     evidence_url, note, changed_by)
                VALUES (:code, :fromStatus, :fromBasis, :toStatus, :toBasis, :mergedInto, :effectiveOn,
                        :evidenceUrl, :note, :changedBy)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("fromStatus", from.status().name()).addValue("fromBasis", from.statusBasis().name())
                .addValue("toStatus", toStatus.name()).addValue("toBasis", toBasis.name())
                .addValue("mergedInto", mergedInto).addValue("effectiveOn", effectiveOn)
                .addValue("evidenceUrl", evidenceUrl).addValue("note", note).addValue("changedBy", changedBy),
                Long.class);
        return id == null ? 0 : id;
    }

    /** 최신 상태 이력의 메모. 이력이 없으면 empty. 이력은 있고 메모만 없으면 note 가 null 이다 */
    public Optional<LatestNote> latestStatusNote(String code) {
        List<LatestNote> notes = jdbc.query("""
                SELECT note FROM platform_status_history
                WHERE platform_code = :code
                ORDER BY changed_at DESC, id DESC
                LIMIT 1
                """, Map.of("code", code), (rs, n) -> new LatestNote(rs.getString("note")));
        return notes.stream().findFirst();
    }

    public record LatestNote(String note) {
    }

    public List<StatusHistoryRow> findHistory(String code) {
        return jdbc.query("""
                SELECT id, platform_code, from_status, from_basis, to_status, to_basis, merged_into, effective_on,
                       evidence_url, note, changed_by, changed_at
                FROM platform_status_history
                WHERE platform_code = :code
                ORDER BY changed_at DESC, id DESC
                """, Map.of("code", code), (rs, n) -> new StatusHistoryRow(
                rs.getLong("id"),
                rs.getString("platform_code"),
                PlatformStatus.valueOf(rs.getString("from_status")),
                StatusBasis.valueOf(rs.getString("from_basis")),
                PlatformStatus.valueOf(rs.getString("to_status")),
                StatusBasis.valueOf(rs.getString("to_basis")),
                rs.getString("merged_into"),
                date(rs, "effective_on"),
                rs.getString("evidence_url"),
                rs.getString("note"),
                rs.getLong("changed_by"),
                instant(rs, "changed_at")));
    }

    public long countActiveListings(String code) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_platform_listings WHERE platform_code = :code AND status = 'ACTIVE'",
                Map.of("code", code), Long.class);
        return count == null ? 0 : count;
    }

    public List<Long> findActiveListingIds(String code, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM work_platform_listings WHERE platform_code = :code AND status = 'ACTIVE'
                ORDER BY id LIMIT :limit""", Map.of("code", code, "limit", limit), Long.class);
    }

    private Optional<PlatformRow> single(String sql, String code) {
        List<PlatformRow> rows = jdbc.query(sql, Map.of("code", code), (rs, n) -> map(rs, List.of()));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withHosts(rows.get(0), hostsByPlatform(code).getOrDefault(code, List.of())));
    }

    private Map<String, List<String>> hostsByPlatform(String code) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        String sql = code == null
                ? "SELECT platform_code, host FROM platform_hosts ORDER BY platform_code, host"
                : "SELECT platform_code, host FROM platform_hosts WHERE platform_code = :code ORDER BY host";
        jdbc.query(sql, code == null ? Map.of() : Map.of("code", code), rs -> {
            result.computeIfAbsent(rs.getString("platform_code"), k -> new ArrayList<>()).add(rs.getString("host"));
        });
        return result;
    }

    private static PlatformRow withHosts(PlatformRow row, List<String> hosts) {
        return new PlatformRow(row.code(), row.name(), row.operatorName(), row.status(), row.statusBasis(),
                row.mergedInto(), row.statusEffectiveOn(), row.statusEvidenceUrl(), row.defaultIdScope(),
                row.workUrlPattern(), row.urlRuleBasis(), row.urlRuleCheckedOn(), row.termsCheckedOn(), row.version(),
                List.copyOf(hosts), row.createdAt(), row.updatedAt());
    }

    private static PlatformRow map(ResultSet rs, List<String> hosts) throws SQLException {
        return new PlatformRow(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("operator_name"),
                PlatformStatus.valueOf(rs.getString("status")),
                StatusBasis.valueOf(rs.getString("status_basis")),
                rs.getString("merged_into"),
                date(rs, "status_effective_on"),
                rs.getString("status_evidence_url"),
                IdScope.valueOf(rs.getString("default_id_scope")),
                rs.getString("work_url_pattern"),
                UrlRuleBasis.valueOf(rs.getString("url_rule_basis")),
                date(rs, "url_rule_checked_on"),
                date(rs, "terms_checked_on"),
                rs.getLong("version"),
                hosts,
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
