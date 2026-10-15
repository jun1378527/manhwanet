package com.manhwanet.collection.domain;

/** staging 검수 상태. 수집은 NEW 로 만들기만 하고 바꾸지 않는다(검수는 #51) */
public enum ReviewStatus {
    NEW, CANDIDATES, IN_REVIEW, LINKED, NEW_WORK, REJECTED, ON_HOLD, MERGED_ALIAS
}
