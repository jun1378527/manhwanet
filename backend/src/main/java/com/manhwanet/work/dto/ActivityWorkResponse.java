package com.manhwanet.work.dto;

import com.manhwanet.work.domain.WorkFormat;
import java.util.List;

/**
 * 내 활동 목록에 붙이는 공개 작품 정보(작품명·형식·작가). 공개(PUBLIC) 작품에만 만든다.
 * 숨김 작품은 이 값을 만들지 않으므로 제목·작가가 내 활동 응답으로 나가지 않는다.
 */
public record ActivityWorkResponse(
        Long id,
        WorkFormat workFormat,
        PrimaryTitleResponse primaryTitle,
        List<CreditResponse> creators) {

    public ActivityWorkResponse {
        creators = List.copyOf(creators);
    }

    public static ActivityWorkResponse from(WorkSummaryResponse summary) {
        return new ActivityWorkResponse(summary.id(), summary.workFormat(), summary.primaryTitle(), summary.creators());
    }
}
