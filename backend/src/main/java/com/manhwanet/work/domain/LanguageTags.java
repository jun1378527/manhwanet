package com.manhwanet.work.domain;

import java.util.IllformedLocaleException;
import java.util.Locale;

/**
 * BCP 47 언어 태그 검증·정규화. (docs/work-creator-spec.md 3.3)
 *
 * <p>형식(well-formed)만 검증하며 IANA 레지스트리 등록 여부는 검사하지 않는다.
 * 정규화는 Locale#toLanguageTag() 결과를 따른다. (예: JA → ja, ko-kr → ko-KR, iw → he)
 */
public final class LanguageTags {

    /** RFC 5646 4.4.1 권고 저장 길이 */
    public static final int MAX_LENGTH = 35;

    private LanguageTags() {
    }

    /**
     * @return 정규화된 언어 태그
     * @throws IllegalArgumentException 형식이 잘못되었거나 지원 범위를 벗어난 경우 (메시지는 사용자 안내용)
     */
    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("언어 코드는 필수입니다.");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("언어 코드는 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
        Locale locale;
        try {
            locale = new Locale.Builder().setLanguageTag(value).build();
        } catch (IllformedLocaleException e) {
            throw new IllegalArgumentException("BCP 47 형식의 언어 코드가 아닙니다. (예: ko, ja, en-US)");
        }
        if (locale.getLanguage().isEmpty()) {
            throw new IllegalArgumentException("기본 언어가 지정된 언어 코드여야 합니다. (und, x-... 불가)");
        }
        String normalized = locale.toLanguageTag();
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("언어 코드는 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
        return normalized;
    }
}
