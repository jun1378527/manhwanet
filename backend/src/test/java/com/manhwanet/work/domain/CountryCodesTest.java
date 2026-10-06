package com.manhwanet.work.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CountryCodesTest {

    @ParameterizedTest
    @CsvSource({"kr, KR", "JP, JP", "' jp ', JP", "Us, US"})
    void normalizesToUpperCase(String raw, String expected) {
        assertThat(CountryCodes.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "K", "KOR", "K1", "ZZ", "한국"})
    void rejectsInvalidCodes(String raw) {
        assertThatThrownBy(() -> CountryCodes.normalize(raw)).isInstanceOf(IllegalArgumentException.class);
    }
}
