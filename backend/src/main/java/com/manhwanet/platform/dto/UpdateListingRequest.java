package com.manhwanet.platform.dto;

import com.manhwanet.review.dto.JsonInteger;
import java.time.Instant;

/**
 * 작품 플랫폼 연결 수정(바꿀 수 있는 필드 전체 교체). PUT /api/v1/admin/works/{workId}/listings/{listingId}
 * 플랫폼·외부 작품 ID 는 바꿀 수 없다(바뀌었으면 새 연결 + 이전). version 은 작품 version 이다.
 * status = MOVED 이면 movedToListingId 가 필요하고, 그 밖의 상태에서는 보내지 않는다.
 * lastVerifiedAt 을 생략하면 기존 확인 시각을 유지한다.
 */
public record UpdateListingRequest(
        JsonInteger version,
        String url,
        String idScope,
        String listingKind,
        String languageCode,
        String ageRatingRaw,
        String ageClass,
        String status,
        JsonInteger movedToListingId,
        Instant lastVerifiedAt,
        String note) {
}
