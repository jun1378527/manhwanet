package com.manhwanet.platform.dto;

import com.manhwanet.platform.domain.IdScope;
import com.manhwanet.platform.domain.PlatformRow;
import com.manhwanet.platform.domain.PlatformStatus;
import com.manhwanet.platform.domain.StatusBasis;
import com.manhwanet.platform.domain.UrlRuleBasis;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PlatformResponse(
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
        List<String> allowedHosts,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<String> warnings) {

    public static PlatformResponse from(PlatformRow row, List<String> warnings) {
        return new PlatformResponse(row.code(), row.name(), row.operatorName(), row.status(), row.statusBasis(),
                row.mergedInto(), row.statusEffectiveOn(), row.statusEvidenceUrl(), row.defaultIdScope(),
                row.workUrlPattern(), row.urlRuleBasis(), row.urlRuleCheckedOn(), row.termsCheckedOn(),
                row.allowedHosts(), row.version(), row.createdAt(), row.updatedAt(), List.copyOf(warnings));
    }
}
