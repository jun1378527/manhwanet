package com.manhwanet.platform.domain;

import java.time.Instant;

/** work_platform_listings 한 행 */
public record ListingRow(
        Long id,
        Long workId,
        String platformCode,
        String platformWorkId,
        IdScope idScope,
        String url,
        ListingKind listingKind,
        String languageCode,
        ListingStatus status,
        Long movedToListingId,
        String ageRatingRaw,
        AgeClass ageClass,
        Instant firstVerifiedAt,
        Instant lastVerifiedAt,
        Long verifiedBy,
        String note,
        Instant createdAt,
        Instant updatedAt) {
}
