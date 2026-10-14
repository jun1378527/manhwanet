package com.manhwanet.provenance.dto;

import com.manhwanet.provenance.domain.FieldSourceRow;
import com.manhwanet.provenance.domain.FieldSourceStatus;
import com.manhwanet.provenance.domain.SourceField;
import com.manhwanet.provenance.domain.SourceType;
import java.time.Instant;

public record FieldSourceResponse(
        long id,
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

    public static FieldSourceResponse from(FieldSourceRow row) {
        return new FieldSourceResponse(row.id(), row.field(), row.listingId(), row.targetRef(), row.sourceType(),
                row.sourceRef(), row.sourceUrl(), row.status(), row.verifiedAt(), row.verifiedBy(), row.note(),
                row.createdAt(), row.updatedAt());
    }
}
