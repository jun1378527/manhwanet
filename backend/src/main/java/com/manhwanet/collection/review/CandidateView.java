package com.manhwanet.collection.review;

import java.util.List;

/**
 * 검수 후보 한 건. 기존 작품 후보(workId)이거나 다른 수집 대기 항목 후보(otherStagingId) 중 하나.
 * score 는 정렬용이며 자동 병합에 쓰지 않는다.
 */
public record CandidateView(
        long id,
        Long workId,
        String workTitle,
        Long otherStagingId,
        String otherStagingTitle,
        String otherStagingPlatformCode,
        double score,
        List<String> reasons,
        List<String> warnings,
        String status) {
}
