package com.manhwanet.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountFieldsTest {

    @Test
    void emailIsStrippedAndLowercasedWithRootLocale() {
        assertThat(EmailAddresses.normalizeAndValidate("  Reader.One@Example.COM　")).isEqualTo("reader.one@example.com");
        // Locale.ROOT: 터키어 로캘의 점 없는 i 변환이 일어나지 않는다.
        assertThat(EmailAddresses.normalize("TITLE@EXAMPLE.COM")).isEqualTo("title@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "no-at-sign", "a@b", "a@@b.com", "a b@c.com", "@example.com"})
    void rejectsMalformedEmails(String raw) {
        assertThatThrownBy(() -> EmailAddresses.normalizeAndValidate(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooLongEmail() {
        String email = "a".repeat(64) + "@" + "b".repeat(185) + ".com";
        assertThat(email.length()).isEqualTo(254);
        assertThat(EmailAddresses.normalizeAndValidate(email)).isEqualTo(email);
        assertThatThrownBy(() -> EmailAddresses.normalizeAndValidate("a" + email.substring(1).replace("@b", "@bb")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nicknameIsStrippedAndLimitedTo2To30CodePoints() {
        assertThat(Nicknames.normalizeAndValidate("  독자  ")).isEqualTo("독자");
        assertThatThrownBy(() -> Nicknames.normalizeAndValidate(" 독 ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Nicknames.normalizeAndValidate("가".repeat(30))).hasSize(30);
        assertThatThrownBy(() -> Nicknames.normalizeAndValidate("가".repeat(31))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Nicknames.normalizeAndValidate("😀😀")).isEqualTo("😀😀");
    }
}
