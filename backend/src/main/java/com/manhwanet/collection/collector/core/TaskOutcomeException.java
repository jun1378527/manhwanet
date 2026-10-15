package com.manhwanet.collection.collector.core;

import com.manhwanet.collection.domain.TaskError;

/** 이 작업만 실패로 기록하고 실행은 계속한다. retryAfterSeconds 가 있으면 그 뒤 다시 시도한다 */
public final class TaskOutcomeException extends RuntimeException {

    private final TaskError error;
    private final Integer httpStatus;
    private final Integer retryAfterSeconds;

    public TaskOutcomeException(TaskError error, Integer httpStatus, Integer retryAfterSeconds, String message) {
        super(message, null, false, false);
        this.error = error;
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public TaskError error() {
        return error;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
