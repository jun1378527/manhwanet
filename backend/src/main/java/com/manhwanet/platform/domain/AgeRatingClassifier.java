package com.manhwanet.platform.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원래 이용등급 표기를 분류한다. 알아보지 못하면 빈 값을 돌려주고, 호출 측이 UNKNOWN 으로 둔다.
 * <ul>
 *   <li>숫자 연령 N세(뒤에 미만·이하·이상이 붙지 않을 때): N &lt; 18 이면 GENERAL, N &ge; 18 이면 ADULT</li>
 *   <li>"전체"(전체 이용가·전체연령가) 또는 "ALL": GENERAL</li>
 *   <li>"청소년 이용불가", "19금", "청불", "18+", "19+", "R18": ADULT</li>
 *   <li>"성인"이 들어 있다는 이유만으로 ADULT 가 되지 않는다. "18세 미만", "미성인", "성인 아님"은 자동 분류하지 않는다</li>
 * </ul>
 * 성인 신호와 일반 신호가 함께 있으면 판단하지 않는다(빈 값).
 * "19금"은 공개 목록에서 19세 이용가를 줄여 쓰는 표기고, "청불"은 "청소년 이용불가"의 약칭이다.
 * 앞뒤에 다른 한글이 붙은 경우는 약칭으로 보지 않는다.
 */
public final class AgeRatingClassifier {

    private static final Pattern AGE_NUMBER = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*세(?!\\s*(?:미만|이하|이상))");
    private static final Pattern ADULT_WORDS = Pattern.compile(
            "청소년\\s*이용\\s*불가|(?<![가-힣])19\\s*금(?![가-힣])|(?<![가-힣])청불(?![가-힣])|\\b1[89]\\s*\\+|\\bR\\s*-?\\s*18\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERAL_WORDS = Pattern.compile("전체|\\bALL\\b", Pattern.CASE_INSENSITIVE);

    private AgeRatingClassifier() {
    }

    public static Optional<AgeClass> classify(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        boolean adult = ADULT_WORDS.matcher(raw).find();
        boolean general = GENERAL_WORDS.matcher(raw).find();
        Matcher number = AGE_NUMBER.matcher(raw);
        while (number.find()) {
            int age = Integer.parseInt(number.group(1));
            if (age >= 18) {
                adult = true;
            } else {
                general = true;
            }
        }
        if (adult == general) {
            return Optional.empty();
        }
        return Optional.of(adult ? AgeClass.ADULT : AgeClass.GENERAL);
    }
}
