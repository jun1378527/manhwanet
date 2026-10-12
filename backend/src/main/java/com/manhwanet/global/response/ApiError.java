package com.manhwanet.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.manhwanet.global.error.ErrorCode;
import java.util.List;

/**
 * 오류 응답. currentVersion 은 버전 충돌(409) 응답에만 담기며, 그 밖에는 직렬화되지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, List<FieldError> fieldErrors, Long currentVersion) {

    public ApiError {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public static ApiError of(ErrorCode errorCode, String message, List<FieldError> fieldErrors) {
        return new ApiError(errorCode.name(), message, fieldErrors, null);
    }

    public static ApiError versionConflict(ErrorCode errorCode, String message, long currentVersion) {
        return new ApiError(errorCode.name(), message, List.of(), currentVersion);
    }

    public record FieldError(String field, String reason) {
    }
}
