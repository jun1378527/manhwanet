package com.manhwanet.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 리뷰 본문 규칙 (docs/review-design-draft.md §1.2). 특수 문자는 소스에서 보이도록 모두 이스케이프로 적는다.
 */
class ReviewBodyTest {

    private static final String TEN = "가나다라마바사아자차";
    private static final String EMOJI = "\uD83D\uDE00"; // 😀 (서로게이트 쌍)
    private static final String IDEOGRAPHIC_SPACE = "\u3000";

    @Test
    void acceptsLengthBoundariesInCodePoints() {
        assertThat(ReviewBody.normalize(TEN)).isEqualTo(TEN);
        assertThat(ReviewBody.normalize("a".repeat(2000))).hasSize(2000);
        assertRejected("가나다라마바사아자", ReviewBody.LENGTH); // 9자
        assertRejected("a".repeat(2001), ReviewBody.LENGTH);
    }

    @Test
    void countsSupplementaryCharactersAsOneCodePoint() {
        String tenEmoji = EMOJI.repeat(10);
        assertThat(tenEmoji.length()).isEqualTo(20); // UTF-16 단위
        assertThat(ReviewBody.normalize(tenEmoji)).isEqualTo(tenEmoji);
        assertRejected(EMOJI.repeat(9), ReviewBody.LENGTH);

        String max = EMOJI.repeat(2000); // UTF-16 4,000단위
        assertThat(ReviewBody.length(ReviewBody.normalize(max))).isEqualTo(2000);
        assertRejected(EMOJI.repeat(2001), ReviewBody.LENGTH);
    }

    @Test
    void countsCombiningSequencesByCodePointWithoutNormalization() {
        // ZWJ 가족 이모지 1개 = 코드 포인트 7개 (사람 4 + ZWJ 3)
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66";
        assertThat(family.codePointCount(0, family.length())).isEqualTo(7);
        assertRejected(family, ReviewBody.LENGTH);
        assertThat(ReviewBody.normalize(family + "abc")).isEqualTo(family + "abc"); // 10

        // 조합형 é (e + U+0301) 는 NFC 로 바꾸지 않는다: 5 × 2 = 10 코드 포인트
        String decomposed = "e\u0301".repeat(5);
        assertThat(ReviewBody.normalize(decomposed)).isEqualTo(decomposed).hasSize(10);
        assertThat(ReviewBody.normalize("\u00E9".repeat(10))).isEqualTo("\u00E9".repeat(10));
    }

    @Test
    void stripsLeadingAndTrailingUnicodeWhitespaceBeforeCounting() {
        String padded = "  \t\n" + IDEOGRAPHIC_SPACE + TEN + IDEOGRAPHIC_SPACE + " \n\t ";
        assertThat(ReviewBody.normalize(padded)).isEqualTo(TEN);
        assertRejected("      가나다라마바사아자      ", ReviewBody.LENGTH); // 제거 후 9자
        // 앞뒤의 줄·문단 구분자(U+2028, U+2029)도 공백으로 제거된다
        assertThat(ReviewBody.normalize("\u2028" + TEN + "\u2029")).isEqualTo(TEN);
    }

    @Test
    void followsStringStripForNoBreakSpace() {
        // String.strip 은 줄바꿈 없는 공백(U+00A0)을 공백으로 보지 않는다 (작품 제목·닉네임 규칙과 같음)
        assertThat(ReviewBody.normalize(TEN + "\u00A0")).isEqualTo(TEN + "\u00A0");
    }

    @Test
    void keepsInnerWhitespaceNewlinesAndTabs() {
        String body = "첫 줄입니다.\n\n\t둘째  줄입니다." + IDEOGRAPHIC_SPACE + "끝";
        assertThat(ReviewBody.normalize(body)).isEqualTo(body);
    }

    @Test
    void unifiesLineBreaksToLf() {
        assertThat(ReviewBody.normalize("첫 줄입니다.\r\n둘째 줄\r셋째 줄\n넷째 줄"))
                .isEqualTo("첫 줄입니다.\n둘째 줄\n셋째 줄\n넷째 줄");
        // CRLF 는 LF 1자로 센다: 9자 + 줄바꿈 = 10자
        assertThat(ReviewBody.normalize("가나다라\r\n마바사아자")).isEqualTo("가나다라\n마바사아자");
        assertThat(ReviewBody.normalize("가나다라\r\n\r\n마바사아")).isEqualTo("가나다라\n\n마바사아");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   \t\n ", "\u3000\u3000", "\r\n\r\n", "\u2028"})
    void blankIsRequired(String raw) {
        assertRejected(raw, ReviewBody.REQUIRED);
    }

    @Test
    void nullIsRequired() {
        assertRejected(null, ReviewBody.REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(ints = {0x0000, 0x0001, 0x0008, 0x000B, 0x000C, 0x001B, 0x001C, 0x001F, 0x007F, 0x0080, 0x0085, 0x009F})
    void rejectsControlCharactersOtherThanLfAndTab(int codePoint) {
        String control = Character.toString(codePoint);
        assertRejected(TEN + control + TEN, ReviewBody.FORBIDDEN_CHARACTER);
        // 앞뒤에 있어도 공백 제거로 조용히 지우지 않고 거절한다 (U+000B, U+000C, U+001C~U+001F 는 Java 공백이기도 함)
        assertRejected(control + TEN, ReviewBody.FORBIDDEN_CHARACTER);
        assertRejected(TEN + control, ReviewBody.FORBIDDEN_CHARACTER);
    }

    @Test
    void controlCheckRunsBeforeLengthCheck() {
        assertRejected("\u0000", ReviewBody.FORBIDDEN_CHARACTER);
        assertRejected("a".repeat(2001) + "\u0000", ReviewBody.FORBIDDEN_CHARACTER);
    }

    @Test
    void allowsNonControlFormatCharactersSuchAsBidiOverrides() {
        String body = TEN + "\u2028\u202E\u2066" + TEN;
        assertThat(ReviewBody.normalize(body)).isEqualTo(body);
    }

    @Test
    void keepsHtmlCharactersAsTyped() {
        String body = "<script>alert('x')</script> &amp; <b>굵게</b>";
        assertThat(ReviewBody.normalize(body)).isEqualTo(body);
    }

    @Test
    void rejectsRawInputOverTheRawLimitBeforeNormalizing() {
        // 정확히 상한(UTF-16 10,000)이면 통과: 공백 9,990 + 10자
        assertThat(ReviewBody.normalize(" ".repeat(ReviewBody.MAX_RAW_LENGTH - 10) + TEN)).isEqualTo(TEN);
        assertRejected(" ".repeat(ReviewBody.MAX_RAW_LENGTH - 9) + TEN, ReviewBody.LENGTH);
    }

    private static void assertRejected(String raw, String expectedReason) {
        assertThatThrownBy(() -> ReviewBody.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedReason);
    }
}
