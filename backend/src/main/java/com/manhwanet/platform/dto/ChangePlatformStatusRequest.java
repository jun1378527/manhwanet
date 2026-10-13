package com.manhwanet.platform.dto;

import com.manhwanet.review.dto.JsonInteger;
import java.time.LocalDate;

/**
 * 플랫폼 운영 상태 변경. POST /api/v1/admin/platforms/{code}/status
 * 변경과 이력 저장은 한 트랜잭션이다. evidenceUrl 은 저장만 하고 서버가 가져오지 않는다.
 */
public record ChangePlatformStatusRequest(
        JsonInteger version,
        String toStatus,
        String statusBasis,
        String mergedInto,
        LocalDate effectiveOn,
        String evidenceUrl,
        String note) {
}
