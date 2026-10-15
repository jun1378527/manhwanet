package com.manhwanet.collection.domain;

/** 실행 중단·차단 사유 */
public enum StopReason {
    BLOCKED_RATE, BLOCKED_ACCESS, ROBOTS_UNAVAILABLE, CIRCUIT_OPEN, OPERATOR_STOP, ERROR
}
