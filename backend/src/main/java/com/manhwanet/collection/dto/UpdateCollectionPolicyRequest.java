package com.manhwanet.collection.dto;

import com.manhwanet.review.dto.JsonInteger;

/**
 * 플랫폼 수집 설정 전체 교체. PUT /api/v1/admin/collection-policies/{platformCode}
 * version 이 같을 때만 반영한다. reason(변경 사유)은 필수. 외부 근거(basisKind·basisNote)는 선택이며 실행 조건이 아니다.
 */
public record UpdateCollectionPolicyRequest(
        JsonInteger version,
        String collectionPolicy,
        String synopsisPolicy,
        String coverPolicy,
        String note,
        String basisKind,
        String basisNote,
        JsonInteger rateIntervalMs,
        JsonInteger dailyRequestCap,
        String reason) {
}
