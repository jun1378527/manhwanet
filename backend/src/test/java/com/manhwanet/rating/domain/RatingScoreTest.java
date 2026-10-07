package com.manhwanet.rating.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RatingScoreTest {

    @ParameterizedTest
    @CsvSource({
            "0.5, 1", "1, 2", "1.0, 2", "2.5, 5", "4.5, 9", "4.50, 9", "5, 10", "5.0, 10", "5.000, 10",
            "1E+0, 2", "45E-1, 9", "0.50000000000000000000, 1"})
    void acceptsHalfStepsFromHalfToFive(String score, int expectedScoreHalf) {
        assertThat(RatingScore.toScoreHalf(new BigDecimal(score))).isEqualTo((short) expectedScoreHalf);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0", "0.0", "-0.5", "-1", "0.4", "0.49999999999999999999", "5.5", "5.01", "6", "10",
            "4.25", "4.4", "4.51", "4.50000000000000000001", "4.49999999999999999999", "1E+1", "1E+1000", "1E-1000"})
    void rejectsZeroOutOfRangeAndNonHalfSteps(String score) {
        assertThatThrownBy(() -> RatingScore.toScoreHalf(new BigDecimal(score)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(RatingScore.OUT_OF_RANGE);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> RatingScore.toScoreHalf(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doubleWouldRoundButBigDecimalDoesNot() {
        // double 로 읽으면 4.5 가 되는 값이다. BigDecimal 원문 비교로 거절해야 한다.
        assertThat(Double.parseDouble("4.50000000000000000001")).isEqualTo(4.5);
        assertThatThrownBy(() -> RatingScore.toScoreHalf(new BigDecimal("4.50000000000000000001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertsScoreHalfBackWithScaleOne() {
        assertThat(RatingScore.fromScoreHalf(1)).isEqualTo(new BigDecimal("0.5"));
        assertThat(RatingScore.fromScoreHalf(9)).isEqualTo(new BigDecimal("4.5"));
        assertThat(RatingScore.fromScoreHalf(10)).isEqualTo(new BigDecimal("5.0"));
        assertThat(RatingScore.fromScoreHalf(2).toPlainString()).isEqualTo("1.0");
        assertThatThrownBy(() -> RatingScore.fromScoreHalf(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RatingScore.fromScoreHalf(11)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsEveryStep() {
        for (int half = RatingScore.MIN_SCORE_HALF; half <= RatingScore.MAX_SCORE_HALF; half++) {
            assertThat(RatingScore.toScoreHalf(RatingScore.fromScoreHalf(half))).isEqualTo((short) half);
        }
    }
}
