package com.manhwanet.rating.dto;

import com.manhwanet.work.dto.ActivityWorkResponse;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 내 별점 목록 한 줄.
 *
 * @param available 작품이 지금 공개 상태인지. false 면 work 는 null 이다(숨김 작품의 내 기록은 보존하되 작품 정보는 내보내지 않음)
 * @param work      공개 작품 정보. 공개가 아니면 null
 * @param score     저장한 점수 0.5~5.0 (scale 1)
 */
public record MyRatingItemResponse(
        Long workId,
        boolean available,
        ActivityWorkResponse work,
        BigDecimal score,
        Instant createdAt,
        Instant updatedAt) {
}
