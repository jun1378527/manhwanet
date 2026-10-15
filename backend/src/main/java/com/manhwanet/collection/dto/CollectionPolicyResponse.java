package com.manhwanet.collection.dto;

import com.manhwanet.collection.domain.PolicyRow;
import java.time.Instant;
import java.util.List;

/** 수집 설정. activeRunId: 이 플랫폼의 활성 실행(없으면 null). warnings: ACTIVE_RUN_REMAINS(설정을 꺼도 활성 실행은 따로 중단해야 함) */
public record CollectionPolicyResponse(
        String platformCode,
        String collectionPolicy,
        String synopsisPolicy,
        String coverPolicy,
        String note,
        String basisKind,
        String basisNote,
        Long decidedBy,
        Instant decidedAt,
        int rateIntervalMs,
        int dailyRequestCap,
        long version,
        Instant updatedAt,
        Long activeRunId,
        List<String> warnings) {

    public static CollectionPolicyResponse from(PolicyRow row, Long activeRunId, List<String> warnings) {
        return new CollectionPolicyResponse(row.platformCode(), row.collectionPolicy().name(), row.synopsisPolicy().name(),
                row.coverPolicy().name(), row.note(), row.basisKind() == null ? null : row.basisKind().name(),
                row.basisNote(), row.decidedBy(), row.decidedAt(), row.rateIntervalMs(), row.dailyRequestCap(),
                row.version(), row.updatedAt(), activeRunId, warnings);
    }
}
