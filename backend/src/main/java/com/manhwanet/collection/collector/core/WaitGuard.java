package com.manhwanet.collection.collector.core;

/**
 * 대기 중 확인. checkpoint 는 잠금 연장(때가 되면)·중단 요청·잠금 상실·수집 설정 변경을 확인하고,
 * 멈춰야 하면 {@link RunHaltException} 을 던진다. await 는 짧게 나눠 자면서 사이마다 checkpoint 한다.
 * 둘 다 DB 트랜잭션 밖에서만 부른다.
 */
public interface WaitGuard {

    void checkpoint();

    void await(long millis);
}
