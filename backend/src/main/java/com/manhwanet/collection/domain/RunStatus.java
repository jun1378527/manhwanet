package com.manhwanet.collection.domain;

/** 수집 실행 상태. 앞의 네 값은 활성(플랫폼당 하나) */
public enum RunStatus {
    RUNNING, PAUSED, STOP_REQUESTED, BLOCKED, STOPPED, COMPLETED, FAILED
}
