package com.manhwanet.creator.application;

/**
 * 다른 도메인(work)에 공개하는 작가 요약 정보.
 */
public record CreatorSummary(Long id, String name, String originalName) {
}
