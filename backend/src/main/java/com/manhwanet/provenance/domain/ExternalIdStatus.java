package com.manhwanet.provenance.domain;

/** 외부 ID 상태. SUPERSEDED: 출처에서 다른 ID 로 대체됨(자리 유지), INVALID: 잘못 연결(자리를 비움) */
public enum ExternalIdStatus {
    ACTIVE, SUPERSEDED, INVALID
}
