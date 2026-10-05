package com.manhwanet.global.error;

/**
 * 비즈니스 규칙 위반 등 의도적으로 클라이언트에 전달할 예외.
 * GlobalExceptionHandler 가 ErrorCode 에 맞는 HTTP 상태와 공통 응답으로 변환한다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
