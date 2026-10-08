package com.manhwanet.review.dto;

/**
 * 리뷰 작성 요청. 사용자 ID·reviewId·version 등 다른 필드는 받지 않는다(알 수 없는 필드는 무시된다).
 *
 * @param body            JSON 문자열 (누락·null 이면 null)
 * @param containsSpoiler JSON 불리언 (누락·null 이면 null)
 */
public record CreateReviewRequest(JsonText body, JsonFlag containsSpoiler) {
}
