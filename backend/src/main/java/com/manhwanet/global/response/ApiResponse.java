package com.manhwanet.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 API가 공통으로 사용하는 응답 형식.
 * 성공 시 {@code data}, 실패 시 {@code error}만 포함된다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> failure(ApiError error) {
        return new ApiResponse<>(false, null, error);
    }
}
