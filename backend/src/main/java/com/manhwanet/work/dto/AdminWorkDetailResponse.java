package com.manhwanet.work.dto;

import com.manhwanet.platform.dto.ListingResponse;
import com.manhwanet.provenance.dto.ExternalIdResponse;
import com.manhwanet.provenance.dto.FieldSourceResponse;
import com.manhwanet.provenance.dto.LinkResponse;
import com.manhwanet.work.domain.WorkFormat;
import com.manhwanet.work.domain.WorkVisibility;
import java.time.Instant;
import java.util.List;

/**
 * 관리자용 작품 상세. 공개 상세와 같은 필드에 수정용 version·공개 상태(visibility, #41)와, 플랫폼 연결·외부 ID·공식 링크·필드별 근거 전체를 더한다(#40).
 * 수정 요청은 이 version 을 그대로 보내야 한다. 비활성(종료·무효·철회) 행도 상태와 함께 모두 담는다.
 */
public record AdminWorkDetailResponse(
        Long id,
        long version,
        WorkVisibility visibility,
        WorkFormat workFormat,
        String originCountry,
        String description,
        PrimaryTitleResponse primaryTitle,
        List<WorkTitleResponse> titles,
        List<CreditResponse> creators,
        RatingSummaryResponse rating,
        List<ListingResponse> listings,
        List<ExternalIdResponse> externalIds,
        List<LinkResponse> links,
        List<FieldSourceResponse> fieldSources,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminWorkDetailResponse of(long version, WorkVisibility visibility, WorkDetailResponse detail, List<ListingResponse> listings,
            List<ExternalIdResponse> externalIds, List<LinkResponse> links, List<FieldSourceResponse> fieldSources) {
        return new AdminWorkDetailResponse(
                detail.id(),
                version,
                visibility,
                detail.workFormat(),
                detail.originCountry(),
                detail.description(),
                detail.primaryTitle(),
                detail.titles(),
                detail.creators(),
                detail.rating(),
                listings,
                externalIds,
                links,
                fieldSources,
                detail.createdAt(),
                detail.updatedAt());
    }
}
