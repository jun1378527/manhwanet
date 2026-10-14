package com.manhwanet.provenance.dto;

import com.manhwanet.provenance.domain.ExternalIdRow;
import com.manhwanet.provenance.domain.ExternalIdSource;
import com.manhwanet.provenance.domain.ExternalIdStatus;
import java.time.Instant;

public record ExternalIdResponse(
        long id,
        ExternalIdSource source,
        String externalId,
        String sourceUrl,
        ExternalIdStatus status,
        Instant verifiedAt,
        long verifiedBy,
        String note,
        Instant createdAt,
        Instant updatedAt) {

    public static ExternalIdResponse from(ExternalIdRow row) {
        return new ExternalIdResponse(row.id(), row.source(), row.externalId(), row.sourceUrl(), row.status(),
                row.verifiedAt(), row.verifiedBy(), row.note(), row.createdAt(), row.updatedAt());
    }
}
