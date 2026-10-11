package com.manhwanet.global.error;

/**
 * 요청 제한(429). 응답에 Retry-After(초) 헤더를 붙인다.
 */
public class TooManyRequestsException extends RetryLaterException {

    public TooManyRequestsException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode, retryAfterSeconds);
    }
}
