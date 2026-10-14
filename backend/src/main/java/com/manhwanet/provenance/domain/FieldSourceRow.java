package com.manhwanet.provenance.domain;

import java.time.Instant;

public record FieldSourceRow(
        long id,
        long workId,
        SourceField field,
        Long listingId,
        String targetRef,
        SourceType sourceType,
        String sourceRef,
        String sourceUrl,
        FieldSourceStatus status,
        Instant verifiedAt,
        long verifiedBy,
        String note,
        Instant createdAt,
        Instant updatedAt) {
}
