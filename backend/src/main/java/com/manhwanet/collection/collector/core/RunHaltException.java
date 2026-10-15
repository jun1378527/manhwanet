package com.manhwanet.collection.collector.core;

import com.manhwanet.collection.domain.TaskError;

/**
 * 플랫폼 실행을 멈춘다. taskError 가 있으면 진행 중이던 작업을 그 사유로 먼저 기록한다
 * (retryAfterSeconds 가 있으면 재개 뒤 다시 시도할 수 있게 PENDING 으로).
 * 어댑터는 이 예외를 잡지 않는다. 잡아도 수집기는 실행을 멈춘다.
 */
public final class RunHaltException extends RuntimeException {

    private final Halt halt;
    private final TaskError taskError;
    private final Integer httpStatus;
    private final Integer retryAfterSeconds;

    public RunHaltException(Halt halt, String message) {
        this(halt, message, null, null, null);
    }

    public RunHaltException(Halt halt, String message, TaskError taskError, Integer httpStatus, Integer retryAfterSeconds) {
        super(message, null, false, false);
        this.halt = halt;
        this.taskError = taskError;
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Halt halt() {
        return halt;
    }

    public TaskError taskError() {
        return taskError;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
