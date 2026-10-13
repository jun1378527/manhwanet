package com.manhwanet.global.error;

/**
 * 같은 플랫폼의 같은 외부 작품 ID 가 이미 연결되어 있음(409). 응답에 기존 작품·연결 ID 를 담는다(관리자 API 전용).
 */
public class ListingAlreadyLinkedException extends BusinessException {

    private final long existingWorkId;
    private final long existingListingId;

    public ListingAlreadyLinkedException(long existingWorkId, long existingListingId) {
        super(ErrorCode.PLATFORM_LISTING_ALREADY_LINKED);
        this.existingWorkId = existingWorkId;
        this.existingListingId = existingListingId;
    }

    public long getExistingWorkId() {
        return existingWorkId;
    }

    public long getExistingListingId() {
        return existingListingId;
    }
}
