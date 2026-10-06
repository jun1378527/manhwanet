package com.manhwanet.work.dto;

import com.manhwanet.work.domain.WorkFormat;
import java.time.Instant;
import java.util.List;

public record WorkDetailResponse(
        Long id,
        WorkFormat workFormat,
        String originCountry,
        String description,
        PrimaryTitleResponse primaryTitle,
        List<WorkTitleResponse> titles,
        List<CreditResponse> creators,
        Instant createdAt,
        Instant updatedAt) {
}
