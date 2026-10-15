package com.manhwanet.collection.domain;

/** 소개문 처리. NOT_COLLECTED 면 staging 에 저장하지 않는다. 공개 표시는 별도 단계 */
public enum SynopsisPolicy {
    NOT_COLLECTED, COLLECT_PRIVATE, DISPLAY
}
