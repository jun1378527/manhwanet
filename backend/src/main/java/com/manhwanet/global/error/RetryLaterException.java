package com.manhwanet.global.error;

/**
 * 나중에 다시 시도하면 처리될 수 있는 거절. 응답에 Retry-After(초) 헤더를 붙인다.
 * 하위 클래스: 계정별 제한 429({@link TooManyRequestsException}), 일시적 과부하 503({@link ServiceBusyException}).
 */
public abstract class RetryLaterException extends BusinessException {

    private final long retryAfterSeconds;

    protected RetryLaterException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
