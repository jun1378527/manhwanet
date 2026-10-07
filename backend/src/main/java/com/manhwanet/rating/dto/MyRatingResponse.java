package com.manhwanet.rating.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 내 별점. 평가하지 않았으면 score·createdAt·updatedAt 이 null 이다.
 *
 * @param score 0.5~5.0 (scale 1)
 */
public record MyRatingResponse(Long workId, BigDecimal score, Instant createdAt, Instant updatedAt) {

    public static MyRatingResponse unrated(Long workId) {
        return new MyRatingResponse(workId, null, null, null);
    }
}
