package com.manhwanet.collection.domain;

import java.util.EnumSet;
import java.util.Set;

/** 실행 상태 묶음 */
public final class RunStatusRules {

    /** 플랫폼당 하나만 있을 수 있는 활성 상태(부분 유니크 인덱스 uk_crawl_runs_active 와 같음) */
    public static final Set<RunStatus> ACTIVE = EnumSet.of(RunStatus.RUNNING, RunStatus.PAUSED, RunStatus.STOP_REQUESTED,
            RunStatus.BLOCKED);
    /** 다시 열지 않는 종료 상태 */
    public static final Set<RunStatus> FINISHED = EnumSet.of(RunStatus.STOPPED, RunStatus.COMPLETED, RunStatus.FAILED);

    private RunStatusRules() {
    }
}
