package com.manhwanet.provenance.dto;

import com.manhwanet.review.dto.JsonInteger;
import java.time.Instant;

/** 공식 링크 추가. POST /api/v1/admin/works/{workId}/links (작품 version, reason 선택) */
public record AddLinkRequest(
        JsonInteger version,
        String linkType,
        String url,
        String languageCode,
        Instant verifiedAt,
        String note,
        String reason) {

    public LinkInput toInput() {
        return new LinkInput(linkType, url, languageCode, verifiedAt, note);
    }
}
