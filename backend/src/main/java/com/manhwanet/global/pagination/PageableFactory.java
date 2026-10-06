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
 * <p>sort 형식: "속성" 또는 "속성,방향" 하나. 허용 속성은 createdAt, id.
 * createdAt 정렬에는 같은 방향의 id 를 보조 기준으로 추가한다.
 * 기본값: createdAt DESC, id DESC.
 */
public final class PageableFactory {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private static final String CREATED_AT = "createdAt";
    private static final String ID = "id";
    private static final Set<String> SORTABLE_PROPERTIES = Set.of(CREATED_AT, ID);
    private static final String SORT_FIELD = "sort";

    private PageableFactory() {
    }

    public static Pageable create(int page, int size, String sort) {
        if (page < 0) {
            throw InvalidInputException.of("page", "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw InvalidInputException.of("size", "size는 1 이상 " + MAX_SIZE + " 이하여야 합니다.");
        }
        return PageRequest.of(page, size, parseSort(sort));
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
