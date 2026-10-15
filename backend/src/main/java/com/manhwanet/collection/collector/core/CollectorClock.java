package com.manhwanet.collection.collector.core;

/** 요청 간격·대기 계산용 시계. 운영은 시스템 시계, 테스트는 가상 시계로 바꾼다(네트워크 시간 제한에는 쓰지 않음) */
public interface CollectorClock {

    long nanoTime();

    void sleep(long millis) throws InterruptedException;

    static CollectorClock system() {
        return new CollectorClock() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public void sleep(long millis) throws InterruptedException {
                Thread.sleep(millis);
            }
        };
    }
}
