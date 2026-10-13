package com.manhwanet.platform.dto;

import com.manhwanet.platform.domain.AgeClass;
import com.manhwanet.platform.domain.IdScope;
import com.manhwanet.platform.domain.ListingKind;
import com.manhwanet.platform.domain.ListingRow;
import com.manhwanet.platform.domain.ListingStatus;
import java.time.Instant;

public record ListingResponse(
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

    public static ListingResponse from(ListingRow row) {
        return new ListingResponse(row.id(), row.workId(), row.platformCode(), row.platformWorkId(), row.idScope(),
                row.url(), row.listingKind(), row.languageCode(), row.status(), row.movedToListingId(),
                row.ageRatingRaw(), row.ageClass(), row.firstVerifiedAt(), row.lastVerifiedAt(), row.verifiedBy(),
                row.note(), row.createdAt(), row.updatedAt());
    }
}
