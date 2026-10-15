package com.manhwanet.collection.collector.net;

/** 전송 단계 실패. 메시지에는 쿼리 문자열·헤더 값을 넣지 않는다 */
public final class FetchException extends Exception {

    public enum Kind {
        /** 연결·응답 시간 초과 */
        TIMEOUT,
        /** 이름 해석 실패, 연결 거부·끊김, TLS 실패 */
        CONNECTION,
        /** https 가 아니거나 공개 주소가 아닌 목적지 */
        BLOCKED_DESTINATION,
        /** 응답 크기 상한 초과 */
        TOO_LARGE,
        /** 응답 형식 오류 */
        PROTOCOL
    }

    private final Kind kind;

    public FetchException(Kind kind, String message) {
        super(message, null, false, false);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
