package com.manhwanet.user.domain;

/**
 * 닉네임: 앞뒤 공백 제거 후 2~30 코드 포인트. 중복을 허용한다.
 */
public final class Nicknames {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 30;

    private Nicknames() {
    }

    public static String normalizeAndValidate(String raw) {
        String nickname = raw == null ? "" : raw.strip();
        int length = nickname.codePointCount(0, nickname.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "닉네임은 앞뒤 공백을 제외하고 " + MIN_LENGTH + "자 이상 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
        return nickname;
    }
}
