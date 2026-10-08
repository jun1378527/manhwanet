package com.manhwanet.review.dto;

import com.manhwanet.review.domain.WorkReview;
import java.time.Instant;

/** 내 리뷰. 수정·삭제 요청에 그대로 쓸 id 와 version 을 포함한다. */
public record ReviewResponse(
        Long id,
        String body,
        boolean containsSpoiler,
        long version,
        Instant createdAt,
        Instant updatedAt,
        boolean edited) {

    public static ReviewResponse from(WorkReview review) {
        return new ReviewResponse(review.getId(), review.getBody(), review.isContainsSpoiler(), review.getVersion(),
                review.getCreatedAt(), review.getUpdatedAt(), review.isEdited());
    }
}
