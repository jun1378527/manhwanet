package com.manhwanet.auth.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 로그인 실패 제한 (개발 단계 기본값: 이메일 키당 15분 내 5회 실패 → 15분 거절, 최대 10,000개 키).
 */
@ConfigurationProperties("manhwanet.auth.login-limit")
public record LoginLimitProperties(
        @DefaultValue("5") int maxFailures,
        @DefaultValue("15m") Duration window,
        @DefaultValue("15m") Duration lockDuration,
        @DefaultValue("10000") int maxEntries) {

    public LoginLimitProperties {
        if (maxFailures < 1 || maxEntries < 1 || window.isNegative() || window.isZero()
                || lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("invalid login limit properties");
        }
    }
}
