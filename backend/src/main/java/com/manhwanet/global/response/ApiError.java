package com.manhwanet.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.manhwanet.global.error.ErrorCode;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, List<FieldError> fieldErrors) {

    public ApiError {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public static ApiError of(ErrorCode errorCode, String message, List<FieldError> fieldErrors) {
        return new ApiError(errorCode.name(), message, fieldErrors);
    }

    public record FieldError(String field, String reason) {
    }
}
