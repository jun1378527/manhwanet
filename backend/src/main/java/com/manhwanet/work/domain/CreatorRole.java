package com.manhwanet.work.domain;

/**
 * 작품에서 작가가 맡은 역할.
 * 선언 순서가 크레딧 표시 순서(STORY → ART → ORIGINAL_STORY)다. 순서를 바꾸면 표시 순서가 바뀐다.
 */
public enum CreatorRole {
    /** 글 */
    STORY,
    /** 그림 */
    ART,
    /** 원작 */
    ORIGINAL_STORY
}
