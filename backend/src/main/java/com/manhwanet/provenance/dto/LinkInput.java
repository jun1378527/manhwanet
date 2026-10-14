package com.manhwanet.provenance.dto;

import java.time.Instant;

/** 등록 요청에 함께 넣는 공식 링크 (#40 §5.6) */
public record LinkInput(String linkType, String url, String languageCode, Instant verifiedAt, String note) {
}
