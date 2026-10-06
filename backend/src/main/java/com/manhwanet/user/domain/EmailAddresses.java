package com.manhwanet.user.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 이메일 정규화·형식 검증. 앞뒤 공백 제거(String.strip) 후 Locale.ROOT 소문자로 바꾼다.
 */
public final class EmailAddresses {

    /** RFC 5321 경로 길이 제한에 따른 실무 최대 길이 */
    public static final int MAX_LENGTH = 254;

    /** 로컬 파트 1~64자, '@' 1개, 도메인에 '.' 포함, 공백 없음 (메일 소유 확인은 하지 않는다) */
    private static final Pattern FORMAT = Pattern.compile("^[^@\\s]{1,64}@[^@\\s.]+(\\.[^@\\s.]+)+$");

    private EmailAddresses() {
    }

    /** 형식 검증 없이 정규화만 한다. (로그인·실패 제한 키에 사용) */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * @return 정규화된 이메일
     * @throws IllegalArgumentException 형식이 잘못된 경우 (메시지는 사용자 안내용)
     */
    public static String normalizeAndValidate(String raw) {
        String email = normalize(raw);
        if (email.isEmpty()) {
            throw new IllegalArgumentException("이메일은 필수입니다.");
        }
        if (email.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("이메일은 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (!FORMAT.matcher(email).matches()) {
            throw new IllegalArgumentException("이메일 형식이 올바르지 않습니다.");
        }
        return email;
    }
}
