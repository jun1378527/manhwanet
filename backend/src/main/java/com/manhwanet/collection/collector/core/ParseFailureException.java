package com.manhwanet.collection.collector.core;

/** 어댑터가 응답을 해석하지 못함. body 는 실패 표본으로 남긴다(민감해 보이는 값은 가린 뒤) */
public final class ParseFailureException extends RuntimeException {

    private final transient byte[] body;

    public ParseFailureException(String message, byte[] body) {
        super(message, null, false, false);
        this.body = body;
    }

    public byte[] body() {
        return body;
    }
}
