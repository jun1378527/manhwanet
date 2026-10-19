package com.manhwanet.work.dto;

import com.manhwanet.platform.dto.PublicPlatformLinkResponse;
import com.manhwanet.work.domain.WorkFormat;
import java.time.Instant;
import java.util.List;

public record WorkDetailResponse(
        Long id,
        WorkFormat workFormat,
        String originCountry,
        String description,
        PrimaryTitleResponse primaryTitle,
        List<WorkTitleResponse> titles,
        List<CreditResponse> creators,
        RatingSummaryResponse rating,
        Instant createdAt,
        Instant updatedAt,
        /** 공개 리뷰 수(기존 필드 뒤에 덧붙임) */
        long reviewCount,
        /** 공개 가능한 플랫폼 바로가기(현재 상태·등급·URL 규칙을 다시 확인한 연결만) */
        List<PublicPlatformLinkResponse> platformLinks) {
}
