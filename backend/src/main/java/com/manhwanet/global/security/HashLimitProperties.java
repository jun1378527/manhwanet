package com.manhwanet.global.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 비밀번호 해시(Argon2id) 공통 부하 제한. 기본값은 잠정값이다(docs/login-limit-design-draft.md §2.2·§5.2).
 *
 * @param maxConcurrent 동시에 진행할 해시 연산 수(P). 1 이상. CPU 개수로 자동 결정하지 않는다
 * @param maxWaiting    자리를 기다릴 수 있는 요청 수(Q). 0 이상. 0이면 자리가 없을 때 바로 거절한다
 * @param maxWait       한 요청의 최대 대기(W). 0보다 크고 1초 이하
 */
@ConfigurationProperties("manhwanet.auth.hash-limit")
public record HashLimitProperties(
        @DefaultValue("2") int maxConcurrent,
        @DefaultValue("4") int maxWaiting,
        @DefaultValue("250ms") Duration maxWait) {

    static final Duration MAX_WAIT_UPPER_BOUND = Duration.ofSeconds(1);

    public HashLimitProperties {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("manhwanet.auth.hash-limit.max-concurrent must be >= 1");
        }
        if (maxWaiting < 0) {
            throw new IllegalArgumentException("manhwanet.auth.hash-limit.max-waiting must be >= 0");
        }
        if (maxWait == null || maxWait.isNegative() || maxWait.isZero() || maxWait.compareTo(MAX_WAIT_UPPER_BOUND) > 0) {
            throw new IllegalArgumentException("manhwanet.auth.hash-limit.max-wait must be > 0 and <= 1s");
        }
    }
}
