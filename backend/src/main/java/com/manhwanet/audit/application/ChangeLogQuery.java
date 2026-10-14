package com.manhwanet.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.audit.domain.ChangeEntityType;
import com.manhwanet.audit.dto.ChangeLogEntryResponse;
import com.manhwanet.audit.dto.ChangeLogPageResponse;
import com.manhwanet.global.admin.AdminInputs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리 변경 기록 조회. 필터는 정확히 하나: (entityType + entityId) 또는 workId 또는 creatorId.
 * workId 는 그 작품과 작품의 연결·외부 ID·링크·근거 기록을, creatorId 는 작가와 작가 외부 ID 기록을 모은다.
 * 최신순(id 내림차순), limit 1~100(기본 50), beforeId 로 다음 쪽을 읽는다.
 */
@Service
@Transactional(readOnly = true)
public class ChangeLogQuery {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ChangeLogQuery(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ChangeLogPageResponse find(String entityType, String entityId, String workId, String creatorId,
            String limit, String beforeId) {
        AdminInputs in = new AdminInputs();
        ChangeEntityType type = in.enumValue(entityType, ChangeEntityType.class, "entityType", false);
        String id = in.text(entityId, "entityId", 50, false);
        Long work = positiveLong(workId, "workId", in);
        Long creator = positiveLong(creatorId, "creatorId", in);
        Long before = positiveLong(beforeId, "beforeId", in);
        Long size = positiveLong(limit, "limit", in);
        if (size != null && size > MAX_LIMIT) {
            in.add("limit", "limit 은 1 이상 " + MAX_LIMIT + " 이하여야 합니다.");
        }
        int filters = (entityType != null || entityId != null ? 1 : 0) + (workId != null ? 1 : 0)
                + (creatorId != null ? 1 : 0);
        if (filters != 1) {
            in.add("filter", "entityType+entityId, workId, creatorId 중 하나만 지정해야 합니다.");
        } else if ((entityType != null || entityId != null) && (entityType == null || entityId == null)) {
            in.add("entityId", "entityType 과 entityId 는 함께 지정해야 합니다.");
        }
        in.throwIfAny();
        int pageSize = size == null ? DEFAULT_LIMIT : size.intValue();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("before", before == null ? Long.MAX_VALUE : before)
                .addValue("limit", pageSize + 1);
        String where;
        if (type != null) {
            where = "entity_type = :type AND entity_id = :entityId";
            params.addValue("type", type.name()).addValue("entityId",
                    type == ChangeEntityType.PLATFORM ? id.toUpperCase(Locale.ROOT) : id);
        } else if (work != null) {
            where = "work_id = :workId";
            params.addValue("workId", work);
        } else {
            where = "creator_id = :creatorId";
            params.addValue("creatorId", creator);
        }
        List<ChangeLogEntryResponse> rows = jdbc.query("""
                SELECT id, entity_type, entity_id, action, work_id, creator_id, before_json::text AS before_json,
                       after_json::text AS after_json, reason, actor_id, created_at
                FROM admin_change_log
                WHERE %s AND id < :before
                ORDER BY id DESC
                LIMIT :limit""".formatted(where), params, (rs, n) -> map(rs));
        Long next = null;
        if (rows.size() > pageSize) {
            rows = rows.subList(0, pageSize);
            next = rows.get(rows.size() - 1).id();
        }
        return new ChangeLogPageResponse(List.copyOf(rows), next);
    }

    private ChangeLogEntryResponse map(ResultSet rs) throws SQLException {
        return new ChangeLogEntryResponse(
                rs.getLong("id"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("action"),
                (Long) rs.getObject("work_id", Long.class),
                (Long) rs.getObject("creator_id", Long.class),
                node(rs.getString("before_json")),
                node(rs.getString("after_json")),
                rs.getString("reason"),
                rs.getLong("actor_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    private JsonNode node(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored change log json is invalid", e);
        }
    }

    /** 쿼리 파라미터 정수: 양수만, long 범위를 넘거나 숫자가 아니면 400 */
    private static Long positiveLong(String raw, String field, AdminInputs in) {
        if (raw == null) {
            return null;
        }
        if (!raw.matches("[0-9]{1,19}")) {
            in.add(field, field + " 은 1 이상의 정수여야 합니다.");
            return null;
        }
        try {
            long value = Long.parseLong(raw);
            if (value < 1) {
                in.add(field, field + " 은 1 이상의 정수여야 합니다.");
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            in.add(field, field + " 이 너무 큽니다.");
            return null;
        }
    }
}
