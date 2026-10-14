package com.manhwanet.provenance.dto;

import java.time.Instant;

/** 등록 요청에 함께 넣는 외부 ID (작품·작가 등록, #40 §5.6) */
public record ExternalIdInput(String source, String externalId, String sourceUrl, Instant verifiedAt, String note) {
}
