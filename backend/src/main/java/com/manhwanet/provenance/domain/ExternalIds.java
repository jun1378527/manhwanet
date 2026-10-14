package com.manhwanet.provenance.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** 외부 ID 형식. 출처별로 확인한 형식만 좁히고, 확인하지 못한 출처는 보이는 ASCII 1~100자만 검사한다 */
public final class ExternalIds {

    public static final int MAX_LENGTH = 100;
    private static final Pattern WIKIDATA = Pattern.compile("^Q[1-9][0-9]{0,11}$");
    private static final Pattern ASCII = Pattern.compile("^[!-~]{1,100}$");

    private ExternalIds() {
    }

    /** 정규화한 ID. 형식이 틀리면 null. Wikidata 는 소문자 q 를 대문자로 바꾼다 */
    public static String normalize(ExternalIdSource source, String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (source == ExternalIdSource.WIKIDATA) {
            value = value.toUpperCase(Locale.ROOT);
            return WIKIDATA.matcher(value).matches() ? value : null;
        }
        return ASCII.matcher(value).matches() ? value : null;
    }

    /** 형식 규칙을 확인하지 못한 출처(경고 대상) */
    public static boolean formatUnverified(ExternalIdSource source) {
        return source != ExternalIdSource.WIKIDATA;
    }
}
