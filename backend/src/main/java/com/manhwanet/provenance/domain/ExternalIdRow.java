package com.manhwanet.provenance.domain;

import java.time.Instant;

public record ExternalIdRow(
        long id,
        ExternalIdOwner owner,
        long ownerId,
        ExternalIdSource source,
        String externalId,
        String sourceUrl,
        ExternalIdStatus status,
        Instant verifiedAt,
        long verifiedBy,
        String note,
        Instant createdAt,
        Instant updatedAt) {
}
