package com.manhwanet.global.response;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 페이지 응답 공통 형식. page 는 0부터 시작한다.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public PageResponse {
        content = List.copyOf(content);
    }

    public static <T> PageResponse<T> of(Page<?> page, List<T> content) {
        return new PageResponse<>(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
