package com.manhwanet.collection.domain;

import java.time.Instant;

public record PolicyRow(
        String platformCode,
        CollectionPolicy collectionPolicy,
        SynopsisPolicy synopsisPolicy,
        CoverPolicy coverPolicy,
        String note,
        BasisKind basisKind,
        String basisNote,
        Long decidedBy,
        Instant decidedAt,
        int rateIntervalMs,
        int dailyRequestCap,
        long version,
        Instant updatedAt) {
}
