package com.manhwanet.platform.dto;

import java.util.List;

/**
 * 연결 변경 결과. workVersion 은 변경 뒤 작품 version(다음 요청에 그대로 보낸다).
 * movedFrom 은 이전(move) 요청에서 MOVED 로 바뀐 기존 연결이다.
 * warnings: PLATFORM_MERGING(통합 중인 플랫폼), PLATFORM_STATUS_UNKNOWN(운영 상태 미확인), URL_RULE_UNVERIFIED(작품 URL 형식 미확인)
 */
public record ListingMutationResponse(
        long workId,
        long workVersion,
        ListingResponse listing,
        ListingResponse movedFrom,
        List<String> warnings) {
}
