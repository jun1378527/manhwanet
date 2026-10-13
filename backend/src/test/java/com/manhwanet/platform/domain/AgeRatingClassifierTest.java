package com.manhwanet.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AgeRatingClassifierTest {

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "전체 이용가, GENERAL",
            "전체연령가, GENERAL",
            "ALL, GENERAL",
            "7세 이용가, GENERAL",
            "12세, GENERAL",
            "15세 이용가, GENERAL",
            "17세, GENERAL",
            "18세 이용가, ADULT",
            "19세, ADULT",
            "19세 이용가, ADULT",
            "19금, ADULT",
            "19 금, ADULT",
            "청소년 이용불가, ADULT",
            "청소년이용불가, ADULT",
            "청불, ADULT",
            "19+, ADULT",
            "R-18, ADULT",
            "성인, NULL",
            "미성인, NULL",
            "비성인, NULL",
            "성인 아님, NULL",
            "18세 미만, NULL",
            "19세 미만, NULL",
            "18세미만이용가, NULL",
            "청소년 이용가, NULL",
            "독자 판단, NULL",
            "'', NULL",
            "'전체 이용가 (19세 버전 있음)', NULL",
    })
    void classifies(String raw, AgeClass expected) {
        assertThat(AgeRatingClassifier.classify(raw).orElse(null)).isEqualTo(expected);
    }
}
