package com.manhwanet.global.error;

import org.springframework.http.HttpStatus;

/**
 * 공통 에러 코드. 도메인 전용 에러 코드는 해당 도메인을 구현할 때 추가한다.
 * API 응답의 error.code 에는 enum 이름이 그대로 사용된다.
 */
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type 입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없습니다."),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "이미 존재하는 데이터입니다."),

    // auth / security
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    INVALID_CSRF_TOKEN(HttpStatus.FORBIDDEN, "CSRF 토큰이 없거나 올바르지 않습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    TOO_MANY_LOGIN_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    /** 로그인·가입의 비밀번호 해시 과부하 또는 로그인 제한 저장소 포화(신규 이메일). Retry-After 로 회복 시점을 알린다 */
    AUTH_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "요청이 많아 지금은 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    // work
    WORK_NOT_FOUND(HttpStatus.NOT_FOUND, "작품을 찾을 수 없습니다."),
    /** 관리자 수정 요청의 version 이 현재 작품 version 과 다름. 아무것도 바꾸지 않는다 */
    WORK_VERSION_CONFLICT(HttpStatus.CONFLICT, "작품이 다른 곳에서 수정되었습니다. 최신 내용을 다시 불러온 뒤 수정해 주세요."),

    // platform (#39)
    PLATFORM_NOT_FOUND(HttpStatus.NOT_FOUND, "플랫폼을 찾을 수 없습니다."),
    /** 관리자 수정 요청의 version 이 현재 플랫폼 version 과 다름. 아무것도 바꾸지 않는다 */
    PLATFORM_VERSION_CONFLICT(HttpStatus.CONFLICT, "플랫폼 정보가 다른 곳에서 수정되었습니다. 최신 내용을 다시 불러온 뒤 수정해 주세요."),
    LISTING_NOT_FOUND(HttpStatus.NOT_FOUND, "플랫폼 연결을 찾을 수 없습니다."),
    /** 같은 플랫폼의 같은 외부 작품 ID 가 이미 (다른 또는 같은) 작품에 연결됨. error.existingWorkId 로 알린다 */
    PLATFORM_LISTING_ALREADY_LINKED(HttpStatus.CONFLICT, "이 플랫폼 작품은 이미 연결되어 있습니다(error.existingWorkId 확인)."),

    // provenance (#40)
    /** 같은 출처의 같은 외부 ID 가 이미 (다른 또는 같은) 작품·작가에 연결됨. error.existingWorkId·existingCreatorId 로 알린다 */
    EXTERNAL_ID_ALREADY_LINKED(HttpStatus.CONFLICT, "이 외부 ID 는 이미 연결되어 있습니다(error.existingWorkId 또는 existingCreatorId 확인)."),
    EXTERNAL_ID_NOT_FOUND(HttpStatus.NOT_FOUND, "외부 ID 를 찾을 수 없습니다."),
    WORK_LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "공식 링크를 찾을 수 없습니다."),
    /** 같은 작품에 같은 URL 의 공식 링크가 이미 있음(상태와 무관). 기존 링크의 상태를 바꿔 다시 쓴다 */
    WORK_LINK_ALREADY_EXISTS(HttpStatus.CONFLICT, "이 작품에 같은 URL 의 공식 링크가 이미 있습니다. 기존 링크의 상태를 바꿔 주세요."),
    FIELD_SOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "근거를 찾을 수 없습니다."),

    // collection (#45)
    /** 플랫폼 수집 설정이 이 실행을 허용하지 않음(DISABLED, 또는 PROBE 설정에서 PROBE 외 실행) */
    COLLECTION_NOT_ALLOWED(HttpStatus.CONFLICT, "이 플랫폼의 수집 설정이 이 실행을 허용하지 않습니다."),
    COLLECTION_POLICY_VERSION_CONFLICT(HttpStatus.CONFLICT, "수집 설정이 다른 곳에서 바뀌었습니다. 최신 내용을 다시 불러온 뒤 수정해 주세요."),
    /** 같은 플랫폼에 활성 실행이 이미 있음. 새 실행을 만들지 않고 그 실행을 이어 간다 */
    CRAWL_RUN_ACTIVE(HttpStatus.CONFLICT, "이 플랫폼에 이미 진행 중인 수집 실행이 있습니다."),
    CRAWL_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "수집 실행을 찾을 수 없습니다."),
    /** 실행 잠금을 쥐고 있지 않거나(만료·다른 프로세스가 넘겨받음) 실행 상태가 이 작업을 허용하지 않음 */
    CRAWL_RUN_LEASE_LOST(HttpStatus.CONFLICT, "이 수집 실행의 잠금을 쥐고 있지 않습니다."),
    CRAWL_RUN_STATE_CONFLICT(HttpStatus.CONFLICT, "수집 실행의 현재 상태에서는 할 수 없는 요청입니다."),
    CRAWL_TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "수집 작업을 찾을 수 없습니다."),
    STAGING_LISTING_NOT_FOUND(HttpStatus.NOT_FOUND, "수집 대기 항목을 찾을 수 없습니다."),
    /** 별칭 ID 가 이미 다른 staging 행을 가리키거나 다른 행의 대표 ID 임 */
    STAGING_ALIAS_CONFLICT(HttpStatus.CONFLICT, "이 별칭 ID 는 이미 다른 수집 대기 항목에 연결되어 있습니다."),
    /** #51: 검수자가 본 수집 revision 과 현재 revision 이 다름(검수 중 재수집). error.currentVersion 에 현재 revision */
    STAGING_REVISION_CONFLICT(HttpStatus.CONFLICT, "검수하는 동안 수집 내용이 바뀌었습니다. 최신 내용을 다시 확인한 뒤 결정해 주세요."),
    /** #51: 이미 기존 작품 연결·새 작품 등록으로 끝난 항목 */
    STAGING_ALREADY_REVIEWED(HttpStatus.CONFLICT, "이미 검수를 마친 수집 대기 항목입니다."),
    /** #51: 열린 중복 후보를 모두 판단하지 않았거나 후보 목록이 바뀜 */
    STAGING_REVIEW_CANDIDATES_UNRESOLVED(HttpStatus.CONFLICT, "판단하지 않은 중복 후보가 있습니다. 후보를 다시 불러와 모두 판단해 주세요."),
    /** #51: 상세를 확인하지 못한 항목 등 이 결정을 할 수 없는 상태 */
    STAGING_NOT_REVIEWABLE(HttpStatus.CONFLICT, "이 수집 대기 항목은 지금 상태에서 이 결정을 할 수 없습니다."),

    // creator
    CREATOR_NOT_FOUND(HttpStatus.NOT_FOUND, "작가를 찾을 수 없습니다."),
    /** 관리자 수정 요청의 version 이 현재 작가 version 과 다름. 아무것도 바꾸지 않는다 */
    CREATOR_VERSION_CONFLICT(HttpStatus.CONFLICT, "작가 정보가 다른 곳에서 수정되었습니다. 최신 내용을 다시 불러온 뒤 수정해 주세요."),

    // review
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다."),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 이 작품에 리뷰를 작성했습니다."),
    REVIEW_VERSION_CONFLICT(HttpStatus.CONFLICT, "리뷰가 다른 곳에서 수정되었거나 삭제 후 다시 작성되었습니다. 최신 내용을 확인해 주세요."),

    /** 화면이 예상한 사용자(X-Expected-User-Id)와 이 요청의 로그인 사용자가 다름. 아무것도 바꾸지 않는다 */
    USER_MISMATCH(HttpStatus.CONFLICT, "로그인한 사용자가 바뀌었습니다. 화면을 새로 불러온 뒤 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
