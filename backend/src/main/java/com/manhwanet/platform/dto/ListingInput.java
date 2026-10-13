package com.manhwanet.platform.dto;

import java.time.Instant;

/**
 * 새 플랫폼 연결 입력. 등록(POST .../listings)과 이전(POST .../move 의 newListing)에서 같이 쓴다.
 * ageClass 를 생략하면 ageRatingRaw 로 분류하고, 분류할 수 없거나 표기가 없으면 UNKNOWN 이다.
 */
public record ListingInput(
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
}
