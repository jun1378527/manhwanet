package com.manhwanet.work.domain;

/**
 * 작품 생성 시 전달하는 제목 값. title·languageCode 는 정규화된 값이어야 한다.
 */
public record WorkTitleDraft(String title, String languageCode, boolean primary) {
}
