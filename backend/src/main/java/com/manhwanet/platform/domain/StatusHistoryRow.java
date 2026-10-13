package com.manhwanet.platform.domain;

import java.time.Instant;
import java.time.LocalDate;

/** platform_status_history 한 행 */
public record StatusHistoryRow(
        Long id,
        String platformCode,
        PlatformStatus fromStatus,
        StatusBasis fromBasis,
        PlatformStatus toStatus,
        StatusBasis toBasis,
        String mergedInto,
        LocalDate effectiveOn,
        String evidenceUrl,
        String note,
        Long changedBy,
        Instant changedAt) {
}
