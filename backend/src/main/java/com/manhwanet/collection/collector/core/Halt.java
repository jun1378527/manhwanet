package com.manhwanet.collection.collector.core;

/** 플랫폼 실행을 멈추는 이유 */
public enum Halt {
    /** 403·CAPTCHA·봇 확인·접근 거부 → BLOCKED(BLOCKED_ACCESS) */
    BLOCKED_ACCESS,
    /** 한 실행에서 429 두 번째 → BLOCKED(BLOCKED_RATE) */
    BLOCKED_RATE,
    /** robots.txt 를 읽지 못함 → BLOCKED(ROBOTS_UNAVAILABLE) */
    ROBOTS_UNAVAILABLE,
    /** 연속 실패·실패율 → BLOCKED(CIRCUIT_OPEN) */
    CIRCUIT_OPEN,
    /** 하루 요청 상한 → PAUSED */
    DAILY_CAP,
    /** 수집 설정이 이 실행을 더는 허용하지 않음 → PAUSED */
    POLICY_CHANGED,
    /** 관리자 중단 요청 → STOPPED(남은 작업 SKIPPED) */
    STOP_REQUESTED,
    /** 잠금을 잃음 → 아무것도 쓰지 않고 끝냄 */
    LEASE_LOST,
    /** 프로세스 인터럽트 → 아무것도 쓰지 않고 끝냄(잠금이 만료되면 다른 프로세스가 넘겨받음) */
    INTERRUPTED
}
