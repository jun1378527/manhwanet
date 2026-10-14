package com.manhwanet.provenance.domain;

import java.time.Instant;

public record WorkLinkRow(
        long id,
        long workId,
        LinkType linkType,
        String url,
        String languageCode,
        LinkStatus status,
        Instant verifiedAt,
        long verifiedBy,
        String note,
        Instant createdAt,
        Instant updatedAt) {
}
