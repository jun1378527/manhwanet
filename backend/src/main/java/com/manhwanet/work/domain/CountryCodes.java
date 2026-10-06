package com.manhwanet.work.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ISO 3166-1 alpha-2 국가 코드 검증·대문자 정규화.
 */
public final class CountryCodes {

    private static final Pattern ALPHA_2 = Pattern.compile("[A-Za-z]{2}");
    private static final Set<String> ISO_ALPHA_2 = Set.copyOf(Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2));

    private CountryCodes() {
    }

    /**
     * @return 대문자로 정규화된 국가 코드
     * @throws IllegalArgumentException 형식이 잘못되었거나 ISO 3166-1 alpha-2 코드가 아닌 경우
     */
    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (!ALPHA_2.matcher(value).matches()) {
            throw new IllegalArgumentException("국가 코드는 ISO 3166-1 alpha-2 형식(영문 2자)이어야 합니다.");
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (!ISO_ALPHA_2.contains(upper)) {
            throw new IllegalArgumentException("ISO 3166-1 alpha-2 국가 코드가 아닙니다.");
        }
        return upper;
    }
}
