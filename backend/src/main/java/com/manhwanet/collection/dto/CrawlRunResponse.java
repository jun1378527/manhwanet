package com.manhwanet.collection.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.manhwanet.collection.domain.RunRow;
import java.time.Instant;
import java.util.Map;

/** 수집 실행. taskCounts·failureSampleCount 는 상세 조회에서만 채운다 */
public record CrawlRunResponse(
        long id,
        String platformCode,
        String runKind,
        String status,
        String stopReason,
        String adapterVersion,
        String robotsSha256,
        String leaseOwner,
        Instant leaseUntil,
        boolean leaseValid,
        Instant startedAt,
        Instant stopRequestedAt,
        Instant finishedAt,
        JsonNode stats,
        Map<String, Long> taskCounts,
        Long failureSampleCount) {

    public static CrawlRunResponse from(RunRow r, JsonNode stats, Map<String, Long> taskCounts, Long samples) {
        return new CrawlRunResponse(r.id(), r.platformCode(), r.runKind().name(), r.status().name(),
                r.stopReason() == null ? null : r.stopReason().name(), r.adapterVersion(), r.robotsSha256(),
                r.leaseOwner(), r.leaseUntil(), r.leaseValid(), r.startedAt(), r.stopRequestedAt(), r.finishedAt(), stats,
                taskCounts, samples);
    }
}
