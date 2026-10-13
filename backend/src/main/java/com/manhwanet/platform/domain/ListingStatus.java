package com.manhwanet.platform.domain;

/**
 * 연결 상태. 삭제하지 않고 상태로 관리한다. MOVED 는 이전 대상 연결을 가리킨다.
 */
public enum ListingStatus {
    ACTIVE, ENDED, MOVED, UNKNOWN
}
