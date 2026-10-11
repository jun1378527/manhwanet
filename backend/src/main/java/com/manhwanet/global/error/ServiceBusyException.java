package com.manhwanet.global.error;

/**
 * 일시적 과부하로 지금은 처리하지 않음(503). 응답에 Retry-After(초) 헤더를 붙인다.
 * 요청은 처리되지 않았으므로 상태가 바뀌지 않는다.
 */
public class ServiceBusyException extends RetryLaterException {

    public ServiceBusyException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode, retryAfterSeconds);
    }
}
