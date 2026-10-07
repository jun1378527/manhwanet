package com.manhwanet.work.dto;

import com.manhwanet.work.domain.WorkFormat;
import java.time.Instant;
import java.util.List;

public record WorkSummaryResponse(
        Long id,
        WorkFormat workFormat,
        String originCountry,
        PrimaryTitleResponse primaryTitle,
        List<CreditResponse> creators,
        RatingSummaryResponse rating,
        Instant createdAt) {
}
