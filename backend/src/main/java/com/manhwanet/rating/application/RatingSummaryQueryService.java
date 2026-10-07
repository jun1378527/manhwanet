package com.manhwanet.rating.application;

import com.manhwanet.rating.domain.RatingAverage;
import com.manhwanet.rating.domain.WorkRatingRepository;
import com.manhwanet.rating.domain.WorkRatingStats;
import com.manhwanet.work.application.WorkRatingSummaryQuery;
import com.manhwanet.work.dto.RatingSummaryResponse;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작품 목록·상세의 공개 별점 집계. 작품 ID 묶음마다 집계 SQL 1개를 실행한다.
 * WorkService 가 이 빈에 의존하므로, 빈 순환을 피하려고 WorkService 에는 의존하지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class RatingSummaryQueryService implements WorkRatingSummaryQuery {

    private final WorkRatingRepository workRatingRepository;

    public RatingSummaryQueryService(WorkRatingRepository workRatingRepository) {
        this.workRatingRepository = workRatingRepository;
    }

    @Override
    public Map<Long, RatingSummaryResponse> findByWorkIds(Collection<Long> workIds) {
        if (workIds.isEmpty()) {
            return Map.of();
        }
        return workRatingRepository.summarize(workIds).stream()
                .collect(Collectors.toMap(WorkRatingStats::getWorkId, RatingSummaryQueryService::toSummary));
    }

    private static RatingSummaryResponse toSummary(WorkRatingStats stats) {
        long count = stats.getRatingCount();
        long sum = stats.getScoreHalfSum();
        return new RatingSummaryResponse(RatingAverage.of(count, sum), count);
    }
}
