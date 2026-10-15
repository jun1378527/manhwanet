package com.manhwanet.collection.domain;

/** staging 상세 수집 상태. OK 외에는 실패·미확인이며 이전 내용을 지우지 않는다 */
public enum DetailStatus {
    PENDING, OK, GATED, SKIPPED_ADULT, NOT_FOUND, PARSE_FAILED, FAILED
}
