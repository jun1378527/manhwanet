package com.manhwanet.audit.domain;

/** 관리 변경 기록의 동작. CREATE 외에는 이전 값과 사유가 반드시 있다(DB CHECK) */
public enum ChangeAction {
    CREATE, UPDATE, STATUS_CHANGE, MOVE
}
