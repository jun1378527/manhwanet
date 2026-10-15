package com.manhwanet.collection.domain;

/** 작업 실패 사유 */
public enum TaskError {
    TIMEOUT, CONNECTION, HTTP_5XX, RATE_LIMITED, BLOCKED_ACCESS, GATED, NOT_FOUND, PARSE_FAILED, SKIPPED_ROBOTS, SKIPPED_ADULT, OPERATOR_STOP
}
