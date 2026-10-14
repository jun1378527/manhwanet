package com.manhwanet.global.error;

/**
 * 같은 출처의 같은 외부 ID 가 이미 연결됨(409 EXTERNAL_ID_ALREADY_LINKED, #40).
 * 작품 외부 ID 면 existingWorkId, 작가 외부 ID 면 existingCreatorId 를 담는다.
 * 충돌 직후 그 행이 INVALID 가 되어 연결을 특정할 수 없으면 세 ID 가 모두 없다(#60).
 * Retry-After 는 붙이지 않는다. 이 헤더가 재전송을 일으키지는 않으며, 공통 클라이언트는
 * INVALID_CSRF_TOKEN 일 때만 변경 요청을 한 번 다시 보낸다.
 */
public class ExternalIdAlreadyLinkedException extends BusinessException {

    private static final String VANISHED_MESSAGE =
            "이 외부 ID의 연결 상태가 확인 중에 바뀌었습니다. 이번 변경은 반영되지 않았습니다. "
                    + "자동으로 다시 보내지 말고 최신 내용을 확인한 뒤 필요하면 다시 요청해 주세요.";

    private final Long existingWorkId;
    private final Long existingCreatorId;
    private final Long existingExternalIdId;

    public ExternalIdAlreadyLinkedException(Long existingWorkId, Long existingCreatorId, Long existingExternalIdId) {
        super(ErrorCode.EXTERNAL_ID_ALREADY_LINKED, message(existingWorkId, existingCreatorId, existingExternalIdId));
        this.existingWorkId = existingWorkId;
        this.existingCreatorId = existingCreatorId;
        this.existingExternalIdId = existingExternalIdId;
    }

    private static String message(Long existingWorkId, Long existingCreatorId, Long existingExternalIdId) {
        if (existingWorkId == null && existingCreatorId == null && existingExternalIdId == null) {
            return VANISHED_MESSAGE;
        }
        return ErrorCode.EXTERNAL_ID_ALREADY_LINKED.getMessage();
    }

    public Long getExistingWorkId() {
        return existingWorkId;
    }

    public Long getExistingCreatorId() {
        return existingCreatorId;
    }

    public Long getExistingExternalIdId() {
        return existingExternalIdId;
    }
}
