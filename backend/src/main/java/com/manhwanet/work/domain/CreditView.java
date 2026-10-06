package com.manhwanet.work.domain;

/**
 * 목록 조회용 크레딧 projection.
 */
public record CreditView(Long workId, Long creatorId, CreatorRole role) {
}
