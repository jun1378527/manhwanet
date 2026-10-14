package com.manhwanet.provenance.application;

import com.manhwanet.provenance.domain.LinkType;
import java.time.Instant;

/** 검증을 마친 새 공식 링크. url 은 정규화된 값, field 는 오류를 붙일 요청 필드 경로 */
public record LinkDraft(LinkType linkType, String url, String languageCode, Instant verifiedAt, String note,
        String field) {
}
