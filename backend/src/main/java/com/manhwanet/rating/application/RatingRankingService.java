package com.manhwanet.rating.application;

import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.rating.domain.WorkRatingRepository;
import com.manhwanet.rating.domain.WorkRatingStats;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.WorkSummaryResponse;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전체 공개 작품에 대한 별점 순위(홈 작품 영역의 평점순·평가 많은 순).
 * 현재 페이지가 아니라 전체 대상에서 순서를 정하고 page·size 로 자른다. 별점이 없는 작품은 순위에 넣지 않는다.
 * 순위 조회와 표시 정보 조회가 같은 스냅샷을 보도록 REPEATABLE READ 로 읽는다.
 */
@Service
public class RatingRankingService {

    public enum Order {
        /** 평균 별점순 */
        RATING,
        /** 평가(별점) 많은 순 */
        RATING_COUNT;

        public static Order parse(String value) {
            if (value == null || value.isBlank()) {
                return RATING;
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "rating" -> RATING;
                case "ratingcount" -> RATING_COUNT;
                default -> throw InvalidInputException.of("order", "order는 rating 또는 ratingCount 여야 합니다.");
            };
        }
    }

    private final WorkRatingRepository workRatingRepository;
    private final WorkService workService;

    public RatingRankingService(WorkRatingRepository workRatingRepository, WorkService workService) {
        this.workRatingRepository = workRatingRepository;
        this.workService = workService;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<WorkSummaryResponse> rank(Order order, Pageable pageable) {
        long total = workRatingRepository.countRankedWorks();
        if (total == 0 || pageable.getOffset() >= total) {
            return PageResponse.of(new PageImpl<>(List.of(), pageable, total), List.of());
        }
        int limit = pageable.getPageSize();
        long offset = pageable.getOffset();
        List<WorkRatingStats> rows = order == Order.RATING
                ? workRatingRepository.rankByAverage(limit, offset)
                : workRatingRepository.rankByCount(limit, offset);
        List<WorkSummaryResponse> content = workService.getPublicSummaries(rows.stream().map(WorkRatingStats::getWorkId).toList());
        return PageResponse.of(new PageImpl<>(content, pageable, total), content);
    }
}
