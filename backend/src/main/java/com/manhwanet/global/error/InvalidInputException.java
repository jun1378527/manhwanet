package com.manhwanet.global.error;

import com.manhwanet.global.response.ApiError;
import java.util.List;

/**
 * 서비스 계층에서 검증한 입력 오류. 필드별 오류와 함께 400 INVALID_INPUT 으로 응답한다.
 */
public class InvalidInputException extends BusinessException {

    private final List<ApiError.FieldError> fieldErrors;

    public InvalidInputException(List<ApiError.FieldError> fieldErrors) {
        super(ErrorCode.INVALID_INPUT);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public static InvalidInputException of(String field, String reason) {
        return new InvalidInputException(List.of(new ApiError.FieldError(field, reason)));
    }

    public List<ApiError.FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
