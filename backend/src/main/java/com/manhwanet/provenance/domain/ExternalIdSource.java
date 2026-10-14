package com.manhwanet.provenance.domain;

/** 외부 ID 출처. WIKIDATA 는 Q 번호, 나머지는 형식을 확인하지 않아 보이는 ASCII 만 검사한다 */
public enum ExternalIdSource {
    WIKIDATA, MADB, NDL, KMAS
}
