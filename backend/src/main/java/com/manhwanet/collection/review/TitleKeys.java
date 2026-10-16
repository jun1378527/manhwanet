package com.manhwanet.collection.review;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 후보 비교용 제목·이름 키(#51, 설계 §9.2). 키가 같다는 것은 "후보로 보여 줄 만하다"는 뜻이지 같은 작품이라는 뜻이 아니다.
 * <ul>
 *   <li>NFKC → 소문자 → 판본·시즌 표지 제거 → 문자·숫자 밖 모두 제거</li>
 *   <li>판본(EDITION_SUFFIX)·시즌(SEASON) 표지는 지우고 비교하되, 있으면 경고로 돌려준다(병합을 막는 신호)</li>
 * </ul>
 */
final class TitleKeys {

    static final String EDITION_SUFFIX = "EDITION_SUFFIX";
    static final String SEASON = "SEASON";

    private static final Pattern EDITION = Pattern.compile(
            "(\\[(개정판|단행본|완전판|컬러|합본|리마스터|무삭제|연재판|15세판|19세판)]|_nonadult|_na\\b|_15\\b|_19\\b)");
    private static final Pattern SEASONS = Pattern.compile("(시즌\\s*\\d+|season\\s*\\d+|\\d+\\s*부(?![가-힣])|외전)");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private TitleKeys() {
    }

    static String key(String title) {
        if (title == null) {
            return null;
        }
        String value = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        value = EDITION.matcher(value).replaceAll(" ");
        value = SEASONS.matcher(value).replaceAll(" ");
        value = NON_WORD.matcher(value).replaceAll("");
        return value.isEmpty() ? null : value;
    }

    static Set<String> markers(String title) {
        Set<String> out = new TreeSet<>();
        if (title == null) {
            return out;
        }
        String value = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (EDITION.matcher(value).find()) {
            out.add(EDITION_SUFFIX);
        }
        if (SEASONS.matcher(value).find()) {
            out.add(SEASON);
        }
        return out;
    }

    static String nameKey(String name) {
        if (name == null) {
            return null;
        }
        String value = NON_WORD.matcher(Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)).replaceAll("");
        return value.isEmpty() ? null : value;
    }
}
