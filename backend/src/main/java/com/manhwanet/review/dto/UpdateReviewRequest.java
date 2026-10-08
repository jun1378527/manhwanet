package com.manhwanet.review.dto;

/**
 * 리뷰 수정(전체 교체) 요청. reviewId 와 version 이 현재 리뷰와 모두 같을 때만 반영한다.
 * 사용자 ID 등 다른 필드는 받지 않는다(알 수 없는 필드는 무시된다).
 */
public record UpdateReviewRequest(JsonInteger reviewId, JsonInteger version, JsonText body, JsonFlag containsSpoiler) {
}
