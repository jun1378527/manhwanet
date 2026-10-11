package com.manhwanet.auth.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 이메일 키별 로그인 실패 제한(docs/login-limit-design-draft.md §2).
 *
 * @param maxFailures  기간 안 실패 허용 횟수. 이 횟수에 이르면 잠근다 [확정 5]
 * @param window       실패를 세는 슬라이딩 기간 [확정 15분]
 * @param lockDuration 잠금 시간 [확정 15분]
 * @param maxEntries   서버 메모리에 둘 키 수 상한. 초기값 46,000은 512MiB·키당 922B 측정에 맞춘 값이다.
 *                     활성 항목은 상한 때문에 지우지 않으며, 가득 차면 신규 이메일의 로그인만 503 으로 거절한다
 */
@ConfigurationProperties("manhwanet.auth.login-limit")
public record LoginLimitProperties(
        @DefaultValue("5") int maxFailures,
        @DefaultValue("15m") Duration window,
        @DefaultValue("15m") Duration lockDuration,
        @DefaultValue("46000") int maxEntries) {

    public LoginLimitProperties {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("manhwanet.auth.login-limit.max-failures must be >= 1");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("manhwanet.auth.login-limit.max-entries must be >= 1");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("manhwanet.auth.login-limit.window must be > 0");
        }
        if (lockDuration == null || lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("manhwanet.auth.login-limit.lock-duration must be > 0");
        }
    }
}
