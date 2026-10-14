package com.manhwanet.platform.dto;

import com.manhwanet.provenance.dto.EvidenceRequest;
import java.time.Instant;
import java.util.List;

/**
 * 새 플랫폼 연결 입력. 등록(POST .../listings), 이전(POST .../move 의 newListing), 작품 등록(listings[])에서 같이 쓴다.
 * ageClass 를 생략하면 ageRatingRaw 로 분류하고, 분류할 수 없거나 표기가 없으면 UNKNOWN 이다.
 * evidence: 이 연결의 근거(field 생략 시 LISTING) 1개 이상 필수(#40, 명세 §5.4 근거 필수 규칙).
 */
public record ListingInput(
        String platformCode,
        String platformWorkId,
        String url,
        String idScope,
        String listingKind,
        String languageCode,
        String ageRatingRaw,
        String ageClass,
        Instant verifiedAt,
        String note,
        List<EvidenceRequest> evidence) {
}
