package com.manhwanet.collection.application;

import java.util.List;

/**
 * 수집 결과 반영 결과. created: 새 식별 키, contentChanged: 내용 해시가 바뀜, changedFields: 이번 반영에서 바뀐 필드.
 * resolvedByAlias: 별칭으로 기존 행을 찾음.
 */
public record IngestResult(long stagingId, boolean created, boolean contentChanged, List<String> changedFields,
        boolean resolvedByAlias) {
}
