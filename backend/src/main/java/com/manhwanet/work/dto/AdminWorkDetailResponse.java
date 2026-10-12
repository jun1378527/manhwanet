package com.manhwanet.work.dto;

import com.manhwanet.work.domain.WorkFormat;
import java.time.Instant;
import java.util.List;

/**
 * 관리자용 작품 상세. 공개 상세와 같은 필드에 수정용 version 을 더한다.
 * 수정 요청은 이 version 을 그대로 보내야 한다.
 */
public record AdminWorkDetailResponse(
        Long id,
        long version,
        WorkFormat workFormat,
        String originCountry,
        String description,
        PrimaryTitleResponse primaryTitle,
        List<WorkTitleResponse> titles,
        List<CreditResponse> creators,
        RatingSummaryResponse rating,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminWorkDetailResponse of(long version, WorkDetailResponse detail) {
        return new AdminWorkDetailResponse(
                detail.id(),
                version,
                detail.workFormat(),
                detail.originCountry(),
                detail.description(),
                detail.primaryTitle(),
                detail.titles(),
                detail.creators(),
                detail.rating(),
                detail.createdAt(),
                detail.updatedAt());
    }
}
