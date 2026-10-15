package com.manhwanet.collection.application;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;

/** 같은 플랫폼에 활성 실행이 이미 있음. 수집기는 새 실행을 만들지 않고 activeRunId 를 이어 간다(§7.4) */
public class CrawlRunActiveException extends BusinessException {

    private final long activeRunId;

    public CrawlRunActiveException(long activeRunId) {
        super(ErrorCode.CRAWL_RUN_ACTIVE, ErrorCode.CRAWL_RUN_ACTIVE.getMessage() + " (활성 실행 " + activeRunId + ")");
        this.activeRunId = activeRunId;
    }

    public long getActiveRunId() {
        return activeRunId;
    }
}
