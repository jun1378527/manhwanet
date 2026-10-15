package com.manhwanet.collection.domain;

import java.time.Instant;

public record TaskRow(
        long id,
        long runId,
        TaskKind taskKind,
        String url,
        String urlHash,
        TaskStatus status,
        int attempts,
        Instant nextAttemptAt,
        Integer lastHttpStatus,
        TaskError lastError,
        Instant createdAt,
        Instant updatedAt) {
}
