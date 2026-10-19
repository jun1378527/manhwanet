package com.manhwanet.activity.dto;

/**
 * 내 활동 요약. 숨김 작품에 남긴 내 기록도 센다(기록은 보존 정책).
 *
 * @param ratingCount 내 별점 수
 * @param reviewCount 내 리뷰 수
 */
public record MyActivitySummaryResponse(long ratingCount, long reviewCount) {
}
