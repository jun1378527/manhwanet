package com.manhwanet.platform.dto;

import com.manhwanet.platform.domain.PlatformStatus;
import com.manhwanet.platform.domain.StatusBasis;
import com.manhwanet.platform.domain.StatusHistoryRow;
import java.time.Instant;
import java.time.LocalDate;

public record StatusHistoryResponse(
        Long id,
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

    public static StatusHistoryResponse from(StatusHistoryRow row) {
        return new StatusHistoryResponse(row.id(), row.fromStatus(), row.fromBasis(), row.toStatus(), row.toBasis(),
                row.mergedInto(), row.effectiveOn(), row.evidenceUrl(), row.note(), row.changedBy(), row.changedAt());
    }
}
