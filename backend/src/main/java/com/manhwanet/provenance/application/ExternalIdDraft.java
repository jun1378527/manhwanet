package com.manhwanet.provenance.application;

import com.manhwanet.provenance.domain.ExternalIdSource;
import java.time.Instant;

/** 검증을 마친 새 외부 ID */
public record ExternalIdDraft(ExternalIdSource source, String externalId, String sourceUrl, Instant verifiedAt,
        String note) {
}
