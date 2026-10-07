package com.manhwanet.rating.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RatingAverageTest {

    @ParameterizedTest(name = "count={0}, sum={1} -> {2}")
    @CsvSource({
            // 정확한 평균 → 소수 첫째 자리 HALF_UP
            "1, 9, 4.5",      // 4.5
            "2, 17, 4.3",     // 4.25 → 4.3 (HALF_EVEN 이면 4.2)
            "4, 9, 1.1",      // 1.125 → 1.1
            "4, 7, 0.9",      // 0.875 → 0.9
            "8, 5, 0.3",      // 0.3125 → 0.3
            "4, 37, 4.6",     // 4.625 → 4.6
            "3, 10, 1.7",     // 1.666… → 1.7
            "3, 20, 3.3",     // 3.333… → 3.3
            "2, 20, 5.0",     // 5.0
            "2, 2, 0.5"})     // 0.5
    void averagesWithHalfUp(long count, long sum, String expected) {
        assertThat(RatingAverage.of(count, sum)).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void zeroParticipantsHaveNoAverage() {
        assertThat(RatingAverage.of(0, 0)).isNull();
    }

    @Test
    void largeTotalsStayExact() {
        long count = 1_000_000_000L;
        // 모두 4.5점(9) → 평균 4.5, double 누적 오차 없음
        assertThat(RatingAverage.of(count, 9L * count)).isEqualTo(new BigDecimal("4.5"));
        // 한 명만 0.5점(1), 나머지 5.0점 → 4.9999999955 → 5.0
        assertThat(RatingAverage.of(count, 10L * count - 9)).isEqualTo(new BigDecimal("5.0"));
    }

    @Test
    void rejectsNegativeAggregates() {
        assertThatThrownBy(() -> RatingAverage.of(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RatingAverage.of(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
