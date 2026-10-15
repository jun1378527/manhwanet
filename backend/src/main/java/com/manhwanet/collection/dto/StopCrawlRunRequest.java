package com.manhwanet.collection.dto;

/** 실행 중단 요청. POST /api/v1/admin/crawl-runs/{runId}/stop. reason 필수 */
public record StopCrawlRunRequest(String reason) {
}
