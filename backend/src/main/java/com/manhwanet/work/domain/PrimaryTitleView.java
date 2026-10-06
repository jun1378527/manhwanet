package com.manhwanet.work.domain;

/**
 * 목록 조회용 대표 제목 projection.
 */
public record PrimaryTitleView(Long workId, String title, String languageCode) {
}
