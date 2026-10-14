package com.manhwanet.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.audit.domain.ChangeAction;
import com.manhwanet.audit.domain.ChangeEntityType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리 변경 기록 쓰기 (#40, docs/work-platform-admin-spec.md §3.3 admin_change_log).
 * <ul>
 *   <li>바뀐 내용과 같은 트랜잭션에서만 쓴다(MANDATORY). 변경이 롤백되면 기록도 함께 사라지고, 기록이 실패하면 변경도 롤백된다.</li>
 *   <li>내용이 같아 아무것도 바뀌지 않은 요청은 기록하지 않는다(호출 측 책임).</li>
 *   <li>before·after 는 JSON 으로 저장한다. 관리자는 사용자 ID 만 남기고 이메일·비밀번호 등은 넣지 않는다.</li>
 * </ul>
 */
@Component
public class AdminChangeLog {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AdminChangeLog(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 대상과 함께 묶어 볼 작품·작가 ID(workId·creatorId)는 없으면 null */
    @Transactional(propagation = Propagation.MANDATORY)
    public long record(ChangeEntityType type, String entityId, ChangeAction action, Long workId, Long creatorId,
            Object before, Object after, String reason, long actorId) {
        if (action == ChangeAction.CREATE ? before != null : before == null) {
            throw new IllegalArgumentException("CREATE must not have before, others must: " + action);
        }
        if (after == null) {
            throw new IllegalArgumentException("after is required");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO admin_change_log (entity_type, entity_id, action, work_id, creator_id, before_json, after_json,
                    reason, actor_id)
                VALUES (:type, :entityId, :action, :workId, :creatorId, CAST(:before AS jsonb), CAST(:after AS jsonb),
                    :reason, :actorId)
                RETURNING id""",
                new MapSqlParameterSource()
                        .addValue("type", type.name())
                        .addValue("entityId", entityId)
                        .addValue("action", action.name())
                        .addValue("workId", workId)
                        .addValue("creatorId", creatorId)
                        .addValue("before", before == null ? null : json(before))
                        .addValue("after", json(after))
                        .addValue("reason", reason)
                        .addValue("actorId", actorId),
                Long.class);
        if (id == null) {
            throw new IllegalStateException("change log id not returned");
        }
        return id;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize change log value", e);
        }
    }
}
