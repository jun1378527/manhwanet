package com.manhwanet.review.application;

import com.manhwanet.review.domain.WorkReviewRepository;
import com.manhwanet.review.domain.WorkReviewRepository.WorkReviewCount;
import com.manhwanet.work.application.WorkReviewCountQuery;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작품 목록·상세의 공개 리뷰 수. 작품 ID 묶음마다 집계 SQL 1개를 실행한다.
 * WorkService 가 이 빈에 의존하므로, 빈 순환을 피하려고 WorkService 에는 의존하지 않는다.
 * 리뷰에는 공개·숨김 구분이 없어 작품의 모든 리뷰가 공개 리뷰다(작품이 숨김이면 작품 쪽에서 걸러진다).
 */
@Service
@Transactional(readOnly = true)
public class ReviewCountQueryService implements WorkReviewCountQuery {

    private final WorkReviewRepository workReviewRepository;

    public ReviewCountQueryService(WorkReviewRepository workReviewRepository) {
        this.workReviewRepository = workReviewRepository;
    }

    @Override
    public Map<Long, Long> countByWorkIds(Collection<Long> workIds) {
        if (workIds.isEmpty()) {
            return Map.of();
        }
        return workReviewRepository.countByWorkIds(workIds).stream()
                .collect(Collectors.toMap(WorkReviewCount::getWorkId, WorkReviewCount::getReviewCount));
    }
}
