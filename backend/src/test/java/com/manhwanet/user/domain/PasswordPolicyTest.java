package com.manhwanet.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

    private static final CommonPasswords COMMON = new CommonPasswords();
    private final PasswordPolicy policy = new PasswordPolicy(COMMON);

    private static String repeat(String unit, int count) {
        return unit.repeat(count);
    }

    @Test
    void commonPasswordListIsLoaded() {
        assertThat(COMMON.size()).isEqualTo(10_898);
    }

    @Test
    void lengthBoundariesAreCountedInCodePoints() {
        assertThat(policy.violation(repeat("가", 14), null, null)).isPresent();
        assertThat(policy.violation(repeat("가", 15), null, null)).isEmpty();
        assertThat(policy.violation(repeat("가", 128), null, null)).isEmpty();
        assertThat(policy.violation(repeat("가", 129), null, null)).isPresent();

        // 서로게이트 쌍(이모지)은 UTF-16 길이 2 지만 코드 포인트 1개로 센다.
        String emoji15 = repeat("😀", 15);
        assertThat(emoji15.length()).isEqualTo(30);
        assertThat(policy.violation(emoji15, null, null)).isEmpty();
        assertThat(policy.violation(repeat("😀", 129), null, null)).isPresent();
    }

    @Test
    void doesNotTrimOrRequireCharacterClasses() {
        // 앞뒤 공백도 비밀번호의 일부로 센다. (13자 + 공백 2개 = 15자)
        assertThat(policy.violation(" abcdefghijklm ", null, null)).isEmpty();
        assertThat(policy.violation("abcdefghijklm", null, null)).isPresent();
        // 문자 종류 조합을 요구하지 않는다.
        assertThat(policy.violation("correct horse battery", null, null)).isEmpty();
    }

    @Test
    void normalizesWithNfkc() {
        // 전각 문자는 NFKC 에서 반각으로 바뀐다.
        assertThat(Passwords.normalize("ＡＢＣ")).isEqualTo("ABC");
        // 조합형 한글(첫가끝)과 완성형은 같은 문자열이 된다.
        assertThat(Passwords.normalize("가")).isEqualTo("가");
    }

    @ParameterizedTest
    @ValueSource(strings = {"qwertyuiopasdfgh", "QWERTYUIOPASDFGH", "000000000000000"})
    void rejectsCommonPasswordsCaseInsensitively(String password) {
        assertThat(policy.violation(password, null, null)).hasValueSatisfying(
                reason -> assertThat(reason).contains("흔한"));
    }

    @Test
    void rejectsPasswordEqualToEmailNicknameOrServiceName() {
        assertThat(policy.violation("Reader.One@Example.com", "reader.one@example.com", null))
                .hasValueSatisfying(reason -> assertThat(reason).contains("이메일"));
        assertThat(policy.violation("MyLongNickname123", null, "mylongnickname123"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("닉네임"));
        assertThat(PasswordPolicy.SERVICE_NAMES).contains("manhwanet", "만화넷");
    }
}
