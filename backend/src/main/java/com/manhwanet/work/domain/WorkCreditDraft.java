package com.manhwanet.work.domain;

/**
 * 작품 생성 시 전달하는 작가 연결 값.
 */
public record WorkCreditDraft(Long creatorId, CreatorRole role) {
}
