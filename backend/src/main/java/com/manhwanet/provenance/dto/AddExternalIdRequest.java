package com.manhwanet.provenance.dto;

import com.manhwanet.review.dto.JsonInteger;
import java.time.Instant;

/**
 * 외부 ID 추가. POST /api/v1/admin/works/{workId}/external-ids, /api/v1/admin/creators/{creatorId}/external-ids
 * version 은 작품(또는 작가) version 이다. 성공하면 1 오른다. reason 은 선택.
 */
public record AddExternalIdRequest(
        JsonInteger version,
        String source,
        String externalId,
        String sourceUrl,
        Instant verifiedAt,
        String note,
        String reason) {

    public ExternalIdInput toInput() {
        return new ExternalIdInput(source, externalId, sourceUrl, verifiedAt, note);
    }
}
