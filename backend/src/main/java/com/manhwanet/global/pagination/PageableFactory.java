package com.manhwanet.global.pagination;

import com.manhwanet.global.error.InvalidInputException;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 요청 파라미터(page, size, sort)를 검증된 Pageable 로 변환한다.
 * page·size 범위는 Controller 파라미터 제약(@Min, @Max)으로 먼저 검증한다.
 *
 * <p>지원 범위(#20): offset = page × size 가 {@link #MAX_OFFSET}(= {@link Integer#MAX_VALUE}) 이하여야 한다.
 * JPA 의 {@code Query#setFirstResult(int)} 가 int 를 받으므로 Spring Data JPA 가
 * {@code PageableUtils.getOffsetAsInteger} 에서 그보다 큰 offset 을 예외(500)로 거절하기 때문이다.
 * 범위를 넘는 page 는 Repository 를 부르기 전에 400 INVALID_INPUT(fieldErrors[page])으로 거절한다.
 * 값을 줄이거나 바꾸지 않는다. page 자체는 Controller 의 int 파라미터라 int 범위를 넘으면 바인딩 단계에서 400 이다.
 *
 * <p>sort 형식: "속성" 또는 "속성,방향" 하나. 허용 속성은 createdAt, id.
 * createdAt 정렬에는 같은 방향의 id 를 보조 기준으로 추가한다.
 * 기본값: createdAt DESC, id DESC.
 */
public final class PageableFactory {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    /** 지원하는 최대 offset(page × size). JPA setFirstResult(int) 의 한계 */
    public static final long MAX_OFFSET = Integer.MAX_VALUE;

    private static final String CREATED_AT = "createdAt";
    private static final String ID = "id";
    private static final Set<String> SORTABLE_PROPERTIES = Set.of(CREATED_AT, ID);
    private static final String SORT_FIELD = "sort";

    private PageableFactory() {
    }

    public static Pageable create(int page, int size, String sort) {
        validate(page, size, MAX_SIZE);
        return PageRequest.of(page, size, parseSort(sort));
    }

    /**
     * 정렬을 요청으로 받지 않는 목록용(예: 리뷰 목록). page·size·offset 범위를 {@link #create}와 같은 규칙으로 검증하고
     * 주어진 고정 정렬을 쓴다.
     */
    public static Pageable createWithFixedSort(int page, int size, int maxSize, Sort sort) {
        validate(page, size, maxSize);
        return PageRequest.of(page, size, sort);
    }

    /** 이 size 에서 허용하는 가장 큰 page (offset = page × size ≤ MAX_OFFSET) */
    public static int maxPage(int size) {
        return (int) (MAX_OFFSET / size);
    }

    /**
     * page ≥ 0, 1 ≤ size ≤ maxSize, page × size ≤ MAX_OFFSET.
     * 곱셈은 long 으로 계산해 int 오버플로 없이 비교한다. (page ≤ int 최대, size ≤ int 최대이므로 long 곱은 넘치지 않는다)
     */
    private static void validate(int page, int size, int maxSize) {
        if (page < 0) {
            throw InvalidInputException.of("page", "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > maxSize) {
            throw InvalidInputException.of("size", "size는 1 이상 " + maxSize + " 이하여야 합니다.");
        }
        if ((long) page * size > MAX_OFFSET) {
            throw InvalidInputException.of("page",
                    "page는 0 이상 " + maxPage(size) + " 이하여야 합니다. (size " + size + " 기준, page × size ≤ " + MAX_OFFSET + ")");
        }
    }

    static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return byProperty(CREATED_AT, Sort.Direction.DESC);
        }
        String[] tokens = sort.split(",", -1);
        if (tokens.length > 2) {
            throw invalidSort("정렬은 '속성' 또는 '속성,방향' 형식으로 하나만 지정할 수 있습니다.");
        }
        String property = tokens[0].strip();
        if (!SORTABLE_PROPERTIES.contains(property)) {
            throw invalidSort("허용되지 않은 정렬 기준입니다. (허용: createdAt, id)");
        }
        Sort.Direction direction = tokens.length == 2 ? parseDirection(tokens[1]) : Sort.Direction.ASC;
        return byProperty(property, direction);
    }

    private static Sort.Direction parseDirection(String token) {
        return switch (token.strip().toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw invalidSort("정렬 방향은 asc 또는 desc 여야 합니다.");
        };
    }

    private static Sort byProperty(String property, Sort.Direction direction) {
        Sort sort = Sort.by(direction, property);
        if (CREATED_AT.equals(property)) {
            sort = sort.and(Sort.by(direction, ID));
        }
        return sort;
    }

    private static InvalidInputException invalidSort(String reason) {
        return InvalidInputException.of(SORT_FIELD, reason);
    }
}
