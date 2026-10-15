package com.manhwanet.collection.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 수집 대기 항목(관리자 전용, 비공개). 목록 조회에서는 credits·aliases·discoveries·changes·synopsisText 를 비운다.
 * ageClass 는 명시 신호가 없거나 충돌하면 UNKNOWN 이다.
 */
public record StagingListingResponse(
        long id,
        String platformCode,
        String platformWorkId,
        String idScope,
        String listingKind,
        String languageCode,
        String url,
        String titleRaw,
        String titleClean,
        JsonNode genresRaw,
        String ageRatingRaw,
        String ageClass,
        JsonNode ageSignals,
        String serialStatusRaw,
        String serialStatus,
        String coverUrl,
        String synopsisText,
        String synopsisSha256,
        Integer synopsisLength,
        JsonNode extra,
        JsonNode fieldSources,
        String detailStatus,
        String contentHash,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant lastChangedAt,
        long firstRunId,
        long lastRunId,
        String lastFailureReason,
        Instant lastFailureAt,
        Long lastFailureRunId,
        int consecutiveFailures,
        String reviewStatus,
        Long linkedListingId,
        List<Map<String, Object>> credits,
        List<String> aliases,
        List<Map<String, Object>> discoveries,
        List<Map<String, Object>> changes) {
}
