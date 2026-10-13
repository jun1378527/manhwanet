package com.manhwanet.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.manhwanet.global.error.ErrorCode;
import java.util.List;

/**
 * 오류 응답. 다음 필드는 해당 오류에만 담기고, 그 밖에는 직렬화되지 않는다(null 생략).
 * <ul>
 *   <li>currentVersion: 버전 충돌(409)의 현재 버전</li>
 *   <li>existingWorkId·existingListingId: 플랫폼 작품 중복 연결(409 PLATFORM_LISTING_ALREADY_LINKED, 관리자 API 만)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, List<FieldError> fieldErrors, Long currentVersion,
        Long existingWorkId, Long existingListingId) {

    public ApiError {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public static ApiError of(ErrorCode errorCode, String message, List<FieldError> fieldErrors) {
        return new ApiError(errorCode.name(), message, fieldErrors, null, null, null);
    }

    public static ApiError versionConflict(ErrorCode errorCode, String message, long currentVersion) {
        return new ApiError(errorCode.name(), message, List.of(), currentVersion, null, null);
    }

    public static ApiError alreadyLinked(ErrorCode errorCode, String message, long existingWorkId,
            long existingListingId) {
        return new ApiError(errorCode.name(), message, List.of(), null, existingWorkId, existingListingId);
    }

    public record FieldError(String field, String reason) {
    }
}
