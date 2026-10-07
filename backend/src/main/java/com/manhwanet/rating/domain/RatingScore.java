package com.manhwanet.rating.domain;

import java.math.BigDecimal;

/**
 * 별점 점수 규칙: 0.5~5.0점, 0.5 간격. DB에는 반 점 단위 정수(score_half 1~10)로 저장한다.
 * 0점은 없고, 평가하지 않음은 행이 없는 상태다.
 */
public final class RatingScore {

    public static final int MIN_SCORE_HALF = 1;
    public static final int MAX_SCORE_HALF = 10;

    static final String OUT_OF_RANGE = "별점은 0.5점부터 5.0점까지 0.5점 단위로 입력해 주세요.";

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal MIN = new BigDecimal("0.5");
    private static final BigDecimal MAX = new BigDecimal("5");

    private RatingScore() {
    }

    /**
     * API 점수를 반 점 단위 정수로 바꾼다. double 을 거치지 않으므로 4.50000000000000000001 같은 값도 거절한다.
     *
     * @throws IllegalArgumentException 범위 밖이거나 0.5의 배수가 아닌 경우 (메시지는 사용자 안내용)
     */
    public static short toScoreHalf(BigDecimal score) {
        if (score == null) {
            throw new IllegalArgumentException(OUT_OF_RANGE);
        }
        // 범위를 먼저 확인해 큰 지수(1E+1000 등)를 산술하지 않는다.
        if (score.compareTo(MIN) < 0 || score.compareTo(MAX) > 0) {
            throw new IllegalArgumentException(OUT_OF_RANGE);
        }
        try {
            return (short) score.multiply(TWO).intValueExact();
        } catch (ArithmeticException notHalfStep) {
            throw new IllegalArgumentException(OUT_OF_RANGE);
        }
    }

    /** 반 점 단위 정수를 API 점수(scale 1)로 바꾼다. */
    public static BigDecimal fromScoreHalf(int scoreHalf) {
        if (scoreHalf < MIN_SCORE_HALF || scoreHalf > MAX_SCORE_HALF) {
            throw new IllegalArgumentException("score_half out of range: " + scoreHalf);
        }
        return BigDecimal.valueOf(scoreHalf, 0).divide(TWO).setScale(1);
    }
}
