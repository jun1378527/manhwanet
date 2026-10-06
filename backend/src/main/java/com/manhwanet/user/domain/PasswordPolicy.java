package com.manhwanet.user.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 정책 (docs/auth-design-draft.md 6장).
 * <ul>
 *   <li>NFKC 정규화 후 15~128 코드 포인트</li>
 *   <li>문자 종류 조합·주기적 변경을 강제하지 않는다</li>
 *   <li>흔한 비밀번호, 이메일·닉네임·서비스명과 같은 비밀번호(대소문자 무시)를 거부한다</li>
 * </ul>
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 15;
    public static final int MAX_LENGTH = 128;

    /** 서비스명 (NFKC + 소문자 기준) */
    static final Set<String> SERVICE_NAMES = Set.of("manhwanet", "만화넷", "manhwa net", "manhwa-net");

    private final CommonPasswords commonPasswords;

    public PasswordPolicy(CommonPasswords commonPasswords) {
        this.commonPasswords = commonPasswords;
    }

    /**
     * @param normalizedPassword {@link Passwords#normalize} 결과
     * @param normalizedEmail    정규화된 이메일 (없으면 null)
     * @param nickname           정규화된 닉네임 (없으면 null)
     * @return 위반 사유. 통과하면 empty
     */
    public Optional<String> violation(String normalizedPassword, String normalizedEmail, String nickname) {
        if (normalizedPassword == null || normalizedPassword.isEmpty()) {
            return Optional.of("비밀번호는 필수입니다.");
        }
        int length = Passwords.codePointLength(normalizedPassword);
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return Optional.of("비밀번호는 " + MIN_LENGTH + "자 이상 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
        String lower = normalizedPassword.toLowerCase(Locale.ROOT);
        if (normalizedEmail != null && lower.equals(normalizedEmail)) {
            return Optional.of("이메일과 같은 비밀번호는 사용할 수 없습니다.");
        }
        if (nickname != null && lower.equals(Passwords.normalize(nickname).toLowerCase(Locale.ROOT))) {
            return Optional.of("닉네임과 같은 비밀번호는 사용할 수 없습니다.");
        }
        if (SERVICE_NAMES.contains(lower)) {
            return Optional.of("서비스 이름과 같은 비밀번호는 사용할 수 없습니다.");
        }
        if (commonPasswords.contains(normalizedPassword)) {
            return Optional.of("너무 흔한 비밀번호입니다. 다른 비밀번호를 사용해 주세요.");
        }
        return Optional.empty();
    }
}
