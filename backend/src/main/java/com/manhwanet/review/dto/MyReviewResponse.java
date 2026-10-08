package com.manhwanet.review.dto;

/**
 * 내 리뷰 조회·작성·수정 응답.
 *
 * @param review 작성하지 않았으면 null
 */
public record MyReviewResponse(Long workId, ReviewResponse review) {

    public static MyReviewResponse none(Long workId) {
        return new MyReviewResponse(workId, null);
    }
}
