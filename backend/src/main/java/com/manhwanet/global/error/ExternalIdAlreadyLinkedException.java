package com.manhwanet.global.error;

/**
 * 같은 출처의 같은 외부 ID 가 이미 연결됨(409 EXTERNAL_ID_ALREADY_LINKED, #40).
 * 작품 외부 ID 면 existingWorkId, 작가 외부 ID 면 existingCreatorId 를 담는다.
 */
public class ExternalIdAlreadyLinkedException extends BusinessException {

    private final Long existingWorkId;
    private final Long existingCreatorId;
    private final long existingExternalIdId;

    public ExternalIdAlreadyLinkedException(Long existingWorkId, Long existingCreatorId, long existingExternalIdId) {
        super(ErrorCode.EXTERNAL_ID_ALREADY_LINKED);
        this.existingWorkId = existingWorkId;
        this.existingCreatorId = existingCreatorId;
        this.existingExternalIdId = existingExternalIdId;
    }

    public Long getExistingWorkId() {
        return existingWorkId;
    }

    public Long getExistingCreatorId() {
        return existingCreatorId;
    }

    public long getExistingExternalIdId() {
        return existingExternalIdId;
    }
}
