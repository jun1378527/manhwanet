package com.manhwanet.rating.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 공개 평균 계산. DB가 돌려준 정수 COUNT·SUM(score_half)만으로 정확하게 계산한다.
 */
public final class RatingAverage {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private RatingAverage() {
    }

    /**
     * @param count         별점 수
     * @param scoreHalfSum  score_half 합계
     * @return 평균 점수(소수 첫째 자리, HALF_UP). count 가 0이면 null
     */
    public static BigDecimal of(long count, long scoreHalfSum) {
        if (count < 0 || scoreHalfSum < 0) {
            throw new IllegalArgumentException("negative aggregate: count=" + count + ", sum=" + scoreHalfSum);
        }
        if (count == 0) {
            return null;
        }
        return BigDecimal.valueOf(scoreHalfSum)
                .divide(BigDecimal.valueOf(count).multiply(TWO), 1, RoundingMode.HALF_UP);
    }
}
