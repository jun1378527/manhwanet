package com.manhwanet.platform.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** platforms 한 행과 허용 호스트 */
public record PlatformRow(
        String code,
        String name,
        String operatorName,
        PlatformStatus status,
        StatusBasis statusBasis,
        String mergedInto,
        LocalDate statusEffectiveOn,
        String statusEvidenceUrl,
        IdScope defaultIdScope,
        String workUrlPattern,
        UrlRuleBasis urlRuleBasis,
        LocalDate urlRuleCheckedOn,
        LocalDate termsCheckedOn,
        long version,
        List<String> allowedHosts,
        Instant createdAt,
        Instant updatedAt) {
}
