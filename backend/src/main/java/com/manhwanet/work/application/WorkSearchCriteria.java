package com.manhwanet.work.application;

import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.work.domain.WorkFormat;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 공개 작품 찾기 조건(GET /api/v1/works 의 q·format·platform·order).
 *
 * <p>검색어: 제어 문자를 공백으로 바꾸고 NFC 로 정규화한 뒤 앞뒤 공백을 지우고 연속 공백을 하나로 줄인다.
 * 남은 글자가 없으면 검색어가 없는 것으로 본다. 정규화 후 {@value #MAX_QUERY_LENGTH}자(코드 포인트)를 넘으면 400.
 *
 * @param query    정규화한 검색어. 없으면 null
 * @param format   작품 형식. 없으면 null(전체)
 * @param platform 플랫폼 코드. 없으면 null(전체)
 * @param order    정렬
 */
public record WorkSearchCriteria(String query, WorkFormat format, String platform, Order order) {

    public static final int MAX_QUERY_LENGTH = 100;
    /** 인기순이 보는 최근 활동 기간(일) */
    public static final int POPULAR_WINDOW_DAYS = 30;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");
    private static final Pattern SPACES = Pattern.compile("(?U)\\s+");
    private static final Pattern PLATFORM_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");

    public enum Order {
        /** 최신등록순: created_at DESC, id DESC */
        RECENT,
        /**
         * 인기순: 최근 {@value WorkSearchCriteria#POPULAR_WINDOW_DAYS}일 동안 별점이나 리뷰를 남기거나 고친 이용자 수(한 사람은 1) DESC,
         * 전체 별점 수 DESC, created_at DESC, id DESC. 활동 없는 작품은 0 으로 뒤에 온다
         */
        POPULAR,
        /** 높은평점순: 평균(소수 첫째 자리 반올림) DESC, 별점 수 DESC, created_at DESC, id DESC. 별점 없는 작품은 맨 뒤 */
        RATING,
        /** 별많은순: 별점 수 DESC, 평균 DESC, created_at DESC, id DESC. 별점 없는 작품은 맨 뒤 */
        RATING_COUNT,
        /** 리뷰많은순: 리뷰 수 DESC, 별점 수 DESC, created_at DESC, id DESC. 리뷰 없는 작품은 맨 뒤 */
        REVIEW_COUNT,
        /** 제목순: 대표 제목(소문자, 코드 포인트 순) ASC, 대표 제목 ASC, id ASC */
        TITLE;

        static Order parse(String value) {
            if (value == null || value.isBlank()) {
                return RECENT;
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "recent" -> RECENT;
                case "popular" -> POPULAR;
                case "rating" -> RATING;
                case "ratingcount" -> RATING_COUNT;
                case "reviewcount" -> REVIEW_COUNT;
                case "title" -> TITLE;
                default -> throw InvalidInputException.of("order",
                        "order는 recent, popular, rating, ratingCount, reviewCount, title 중 하나여야 합니다.");
            };
        }
    }

    /** 요청 파라미터에서 조건을 만든다. 형식이 잘못된 값은 해당 필드의 400 INVALID_INPUT */
    public static WorkSearchCriteria of(String q, String format, String platform, String order) {
        return new WorkSearchCriteria(normalizeQuery(q), parseFormat(format), parsePlatform(platform), Order.parse(order));
    }

    /** 검색 조건이 하나라도 있는지(아무것도 없으면 기존 목록 경로를 그대로 쓴다) */
    public static boolean anyPresent(String q, String format, String platform, String order) {
        return normalizeQuery(q) != null || hasText(format) || hasText(platform) || hasText(order);
    }

    static String normalizeQuery(String q) {
        if (q == null) {
            return null;
        }
        String cleaned = CONTROL.matcher(q).replaceAll(" ");
        cleaned = Normalizer.normalize(cleaned, Normalizer.Form.NFC);
        cleaned = SPACES.matcher(cleaned).replaceAll(" ").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_QUERY_LENGTH) {
            throw InvalidInputException.of("q", "검색어는 " + MAX_QUERY_LENGTH + "자 이하여야 합니다.");
        }
        return cleaned;
    }

    private static WorkFormat parseFormat(String format) {
        if (!hasText(format)) {
            return null;
        }
        try {
            return WorkFormat.valueOf(format.strip());
        } catch (IllegalArgumentException e) {
            throw InvalidInputException.of("format", "format은 WEBTOON 또는 PAGE_COMIC 이어야 합니다.");
        }
    }

    private static String parsePlatform(String platform) {
        if (!hasText(platform)) {
            return null;
        }
        String code = platform.strip();
        if (!PLATFORM_CODE.matcher(code).matches()) {
            throw InvalidInputException.of("platform", "platform은 플랫폼 코드(영문 대문자·숫자·밑줄 2~30자)여야 합니다.");
        }
        return code;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** ILIKE 부분 일치 패턴. 와일드카드(%, _)와 escape 문자(\)는 글자 그대로 찾도록 escape 한다 */
    public String likePattern() {
        if (query == null) {
            return null;
        }
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
