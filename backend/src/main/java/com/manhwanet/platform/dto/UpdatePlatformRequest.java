package com.manhwanet.platform.dto;

import com.manhwanet.review.dto.JsonInteger;
import java.time.LocalDate;
import java.util.List;

/**
 * 플랫폼 규칙 수정(전체 교체). PUT /api/v1/admin/platforms/{code}
 * 코드(code)는 바꿀 수 없다. 운영 상태는 이 요청이 아니라 상태 변경 요청(POST .../status)으로만 바꾼다.
 * workUrlPattern 을 생략하거나 null 로 보내면 호스트 검사만 한다.
 */
public record UpdatePlatformRequest(
        JsonInteger version,
        String name,
        String operatorName,
        String defaultIdScope,
        String workUrlPattern,
        String urlRuleBasis,
        LocalDate urlRuleCheckedOn,
        LocalDate termsCheckedOn,
        List<String> allowedHosts) {
}
