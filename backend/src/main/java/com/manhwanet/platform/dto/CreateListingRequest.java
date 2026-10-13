package com.manhwanet.platform.dto;

import com.manhwanet.review.dto.JsonInteger;
import java.time.Instant;

/**
 * 작품 플랫폼 연결 등록. POST /api/v1/admin/works/{workId}/listings
 * version 은 작품 version 이다(작품당 버전 하나, 명세 S-3). 성공하면 작품 version 이 1 오른다.
 */
public record CreateListingRequest(
        JsonInteger version,
        String platformCode,
        String platformWorkId,
        String url,
        String idScope,
        String listingKind,
        String languageCode,
        String ageRatingRaw,
        String ageClass,
        Instant verifiedAt,
        String note) {

    public ListingInput toInput() {
        return new ListingInput(platformCode, platformWorkId, url, idScope, listingKind, languageCode,
                ageRatingRaw, ageClass, verifiedAt, note);
    }
}
