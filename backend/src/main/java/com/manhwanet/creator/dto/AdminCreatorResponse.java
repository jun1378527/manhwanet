package com.manhwanet.creator.dto;

import java.time.Instant;

/**
 * 관리자용 작가 상세. 수정용 version 과, 이 작가를 크레딧에 둔 작품 수를 더한다.
 * 작가 이름을 바꾸면 linkedWorkCount 개 작품의 크레딧 표시가 함께 바뀐다.
 */
public record AdminCreatorResponse(
        Long id,
        long version,
        String name,
        String originalName,
        long linkedWorkCount,
        Instant createdAt,
        Instant updatedAt) {
}
