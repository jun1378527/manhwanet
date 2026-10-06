package com.manhwanet.work.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LanguageTagsTest {

    @ParameterizedTest
    @CsvSource({
            "ko, ko",
            "JA, ja",
            "' ko-kr ', ko-KR",
            "ja-latn, ja-Latn",
            "zh-hant-tw, zh-Hant-TW",
            "en-US-x-private, en-US-x-private",
            "zh-yue-HK, yue-HK",
            "iw, he"
    })
    void normalizesWellFormedTags(String raw, String expected) {
        assertThat(LanguageTags.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "en_US", "12", "ko--KR", "und", "x-private", "toolongsubtag"})
    void rejectsIllFormedOrUnsupportedTags(String raw) {
        assertThatThrownBy(() -> LanguageTags.normalize(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US-x-aaaaaaaa-bbbbbbbb-cccccccc-dd"})
    void rejectsTagsLongerThan35Characters(String raw) {
        assertThat(raw.length()).isGreaterThan(LanguageTags.MAX_LENGTH);
        assertThatThrownBy(() -> LanguageTags.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("35");
    }
}
