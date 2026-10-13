package com.manhwanet.global.error;

import com.manhwanet.global.response.ApiError;
import com.manhwanet.global.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 예외를 공통 응답 형식(ApiResponse)으로 변환한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.debug("Business exception: code={}, message={}", e.getErrorCode(), e.getMessage());
        return toResponse(e.getErrorCode(), e.getMessage(), List.of());
    }

    /** 관리자 수정의 버전 충돌(409). 현재 버전을 함께 보낸다. */
    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleVersionConflict(VersionConflictException e) {
        log.debug("Version conflict: code={}, currentVersion={}", e.getErrorCode(), e.getCurrentVersion());
        return ResponseEntity
                .status(e.getErrorCode().getStatus())
                .body(ApiResponse.failure(ApiError.versionConflict(
                        e.getErrorCode(), e.getMessage(), e.getCurrentVersion())));
    }

    /** 플랫폼 작품 중복 연결(409). 기존 작품·연결 ID 를 함께 보낸다(관리자 API 에서만 발생). */
    @ExceptionHandler(ListingAlreadyLinkedException.class)
    public ResponseEntity<ApiResponse<Void>> handleListingAlreadyLinked(ListingAlreadyLinkedException e) {
        return ResponseEntity
                .status(e.getErrorCode().getStatus())
                .body(ApiResponse.failure(ApiError.alreadyLinked(
                        e.getErrorCode(), e.getMessage(), e.getExistingWorkId(), e.getExistingListingId())));
    }

    /** 요청 제한(429)·일시적 과부하(503). Retry-After 헤더를 함께 보낸다. */
    @ExceptionHandler(RetryLaterException.class)
    public ResponseEntity<ApiResponse<Void>> handleRetryLater(RetryLaterException e) {
        return ResponseEntity
                .status(e.getErrorCode().getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(ApiResponse.failure(ApiError.of(e.getErrorCode(), e.getMessage(), List.of())));
    }

    /** 서비스 계층 입력 검증 실패 (정규화가 필요한 규칙, 존재하지 않는 참조 등) */
    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidInput(InvalidInputException e) {
        return toResponse(ErrorCode.INVALID_INPUT, e.getFieldErrors());
    }

    /** @RequestBody @Valid 검증 실패 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        List<ApiError.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return toResponse(ErrorCode.INVALID_INPUT, fieldErrors);
    }

    /** @RequestParam, @PathVariable 등 메서드 파라미터 제약 조건 검증 실패 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidation(HandlerMethodValidationException e) {
        List<ApiError.FieldError> fieldErrors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ApiError.FieldError(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        return toResponse(ErrorCode.INVALID_INPUT, fieldErrors);
    }

    /** @Validated 가 선언된 Bean 의 메서드 검증 실패 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        List<ApiError.FieldError> fieldErrors = e.getConstraintViolations().stream()
                .map(violation -> new ApiError.FieldError(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()))
                .toList();
        return toResponse(ErrorCode.INVALID_INPUT, fieldErrors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return toResponse(ErrorCode.INVALID_INPUT,
                List.of(new ApiError.FieldError(e.getName(), "요청 값의 타입이 올바르지 않습니다.")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        return toResponse(ErrorCode.INVALID_INPUT,
                List.of(new ApiError.FieldError(e.getParameterName(), "필수 파라미터가 누락되었습니다.")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return toResponse(ErrorCode.INVALID_INPUT, "요청 본문을 읽을 수 없습니다.", List.of());
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
        return toResponse(ErrorCode.RESOURCE_NOT_FOUND, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return toResponse(ErrorCode.METHOD_NOT_ALLOWED, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return toResponse(ErrorCode.UNSUPPORTED_MEDIA_TYPE, List.of());
    }

    /** 처리되지 않은 예외. 내부 정보는 응답에 노출하지 않고 로그로만 남긴다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unexpected exception", e);
        return toResponse(ErrorCode.INTERNAL_SERVER_ERROR, List.of());
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode, List<ApiError.FieldError> fieldErrors) {
        return toResponse(errorCode, errorCode.getMessage(), fieldErrors);
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(
            ErrorCode errorCode, String message, List<ApiError.FieldError> fieldErrors) {
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.failure(ApiError.of(errorCode, message, fieldErrors)));
    }
}
