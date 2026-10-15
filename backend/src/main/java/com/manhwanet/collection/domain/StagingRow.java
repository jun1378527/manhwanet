package com.manhwanet.collection.domain;

import java.time.Instant;

/** staging 한 행. JSON 열(genres·signals·extra·fieldSources)은 원문 문자열 */
public record StagingRow(
        long id,
        String platformCode,
        String platformWorkId,
        String idScope,
        String listingKind,
        String languageCode,
        String url,
        String titleRaw,
        String titleClean,
        String genresJson,
        String ageRatingRaw,
        String ageClass,
        String ageSignalsJson,
        String serialStatusRaw,
        SerialStatus serialStatus,
        String coverUrl,
        String synopsisText,
        String synopsisSha256,
        Integer synopsisLength,
        String extraJson,
        String fieldSourcesJson,
        DetailStatus detailStatus,
        String contentHash,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant lastChangedAt,
        long firstRunId,
        long lastRunId,
        DetailStatus lastFailureReason,
        Instant lastFailureAt,
        Long lastFailureRunId,
        int consecutiveFailures,
        ReviewStatus reviewStatus,
        Long linkedListingId,
        Instant createdAt,
        Instant updatedAt) {
}
