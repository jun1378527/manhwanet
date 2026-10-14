package com.manhwanet.provenance.domain;

/** 근거 종류. STAFF: 직접 작성, LICENSED: 허락받은 글, PLATFORM_PAGE_MANUAL: 사람이 플랫폼 페이지를 확인 */
public enum SourceType {
    PLATFORM_PAGE_MANUAL, WIKIDATA, MADB, NDL, KMAS, PUBLISHER_PAGE, STAFF, LICENSED
}
