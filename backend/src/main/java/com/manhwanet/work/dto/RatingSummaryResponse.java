package com.manhwanet.work.dto;

import java.math.BigDecimal;

/**
 * 작품의 공개 별점 집계.
 *
 * @param average 현재 별점들의 평균 (0.5~5.0, 소수 첫째 자리 HALF_UP). 참여자가 없으면 null
 * @param count   현재 별점이 있는 사용자 수
 */
public record RatingSummaryResponse(BigDecimal average, long count) {

    public static final RatingSummaryResponse EMPTY = new RatingSummaryResponse(null, 0);
}
