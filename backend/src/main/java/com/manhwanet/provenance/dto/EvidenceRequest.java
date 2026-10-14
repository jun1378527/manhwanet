package com.manhwanet.provenance.dto;

import com.manhwanet.review.dto.JsonInteger;
import java.time.Instant;

/**
 * 필드별 근거 입력 (#40). 작품 등록·수정·연결 등록·근거 추가에서 같이 쓴다.
 * <ul>
 *   <li>field: TITLE·CREDIT·WORK_FORMAT·ORIGIN_COUNTRY·DESCRIPTION·LISTING. 연결 요청 안에서는 생략하면 LISTING</li>
 *   <li>listingId: LISTING 근거를 근거 추가 API 로 따로 보낼 때만(같은 작품의 연결)</li>
 *   <li>targetRef: 어느 값의 근거인지 사람이 읽는 설명(예: "ko:유미의 세포들", "creator=5 ART"), 선택</li>
 *   <li>sourceType: PLATFORM_PAGE_MANUAL·WIKIDATA·MADB·NDL·KMAS·PUBLISHER_PAGE·STAFF·LICENSED</li>
 *   <li>sourceRef·sourceUrl: STAFF 외에는 둘 중 하나 이상, LICENSED 는 sourceRef(허락 근거) 필수. URL 은 가져오지 않는다</li>
 * </ul>
 */
public record EvidenceRequest(
        String field,
        JsonInteger listingId,
        String targetRef,
        String sourceType,
        String sourceRef,
        String sourceUrl,
        Instant verifiedAt,
        String note) {
}
