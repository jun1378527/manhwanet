package com.manhwanet.collection.collector.core;

import com.manhwanet.collection.domain.RunKind;

/**
 * 어댑터가 HTTP 를 요청하는 유일한 통로(설계 §5.2). robots·허용 호스트·목적지 IP·요청 간격·하루 상한·재시도·차단 처리가
 * 모든 플랫폼에 똑같이 적용된다.
 * <p>{@link #get} 은 2xx 만 돌려준다. 그 밖에는 {@link TaskOutcomeException}(이 작업만 실패) 또는
 * {@link RunHaltException}(실행 정지)을 던진다. 어댑터는 이 예외들을 잡지 않는다.
 */
public interface FetchContext {

    String platformCode();

    RunKind runKind();

    FetchResponse get(String url);
}
