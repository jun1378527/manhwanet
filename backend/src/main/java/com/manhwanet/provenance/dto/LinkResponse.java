package com.manhwanet.provenance.dto;

import com.manhwanet.provenance.domain.LinkStatus;
import com.manhwanet.provenance.domain.LinkType;
import com.manhwanet.provenance.domain.WorkLinkRow;
import java.time.Instant;

public record LinkResponse(
        long id,
        LinkType linkType,
        String url,
        String languageCode,
        LinkStatus status,
        Instant verifiedAt,
        long verifiedBy,
        String note,
        Instant createdAt,
        Instant updatedAt) {

    public static LinkResponse from(WorkLinkRow row) {
        return new LinkResponse(row.id(), row.linkType(), row.url(), row.languageCode(), row.status(),
                row.verifiedAt(), row.verifiedBy(), row.note(), row.createdAt(), row.updatedAt());
    }
}
