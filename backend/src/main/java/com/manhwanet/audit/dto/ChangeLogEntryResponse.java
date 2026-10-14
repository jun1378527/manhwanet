package com.manhwanet.audit.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** 관리 변경 기록 한 줄. before 는 CREATE 이면 null */
public record ChangeLogEntryResponse(
        long id,
        String entityType,
        String entityId,
        String action,
        Long workId,
        Long creatorId,
        JsonNode before,
        JsonNode after,
        String reason,
        long actorId,
        Instant createdAt) {
}
