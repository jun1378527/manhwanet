package com.manhwanet.collection.collector.core;

import java.util.ArrayDeque;

/**
 * 회로 차단기(설계 §7.3): 연속 5회 실패, 또는 최근 최대 100건(20건 이상 모였을 때) 중 실패가 20%를 넘으면 연다.
 * 실패로 세는 것은 타임아웃·연결 오류·5xx·응답 형식 오류다(404·GATED·robots 건너뜀은 세지 않음).
 * 실행 한 번(프로세스 하나) 안에서만 센다. 사람이 원인을 확인하고 재개하면 새로 센다.
 */
public final class CircuitBreaker {

    static final int CONSECUTIVE = 5;
    static final int WINDOW = 100;
    static final int MIN_SAMPLES = 20;

    private final ArrayDeque<Boolean> window = new ArrayDeque<>(WINDOW);
    private int consecutive;
    private int failures;

    /** 결과를 기록하고, 이 기록으로 차단기가 열려야 하면 true */
    public synchronized boolean record(boolean failure) {
        if (window.size() == WINDOW && window.removeFirst()) {
            failures--;
        }
        window.addLast(failure);
        if (failure) {
            failures++;
            consecutive++;
        } else {
            consecutive = 0;
        }
        return consecutive >= CONSECUTIVE || (window.size() >= MIN_SAMPLES && failures * 5 > window.size());
    }

    public synchronized int consecutiveFailures() {
        return consecutive;
    }
}
