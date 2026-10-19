package com.manhwanet.review.api;

import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.review.application.ReviewQueryService;
import com.manhwanet.review.dto.RecentReviewResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 홈의 공개 리뷰 조회. 익명 허용(보안 규칙에 GET /api/v1/reviews/* 만 공개로 추가).
 * - GET /api/v1/reviews/recent?size=6: 최신 size 건(1~20, 기본 6). 페이지 이동 없음
 * - GET /api/v1/reviews/{reviewId}: 공개 리뷰 한 건
 * 정렬은 작성 시각 DESC, id DESC 로 고정한다. 숨김 작품의 리뷰는 넣지 않는다.
 */
@RestController
@RequestMapping("/reviews")
public class RecentReviewController {

    public static final int DEFAULT_SIZE = 6;
    public static final int MAX_SIZE = 20;

    private final ReviewQueryService reviewQueryService;

    public RecentReviewController(ReviewQueryService reviewQueryService) {
        this.reviewQueryService = reviewQueryService;
    }

    @GetMapping("/recent")
    public ApiResponse<List<RecentReviewResponse>> recent(
            @RequestParam(defaultValue = "" + DEFAULT_SIZE)
            @Min(value = 1, message = "size는 1 이상 20 이하여야 합니다.")
            @Max(value = MAX_SIZE, message = "size는 1 이상 20 이하여야 합니다.") int size) {
        return ApiResponse.success(reviewQueryService.getRecentReviews(size));
    }

    /**
     * 공개 리뷰 한 건. GET /api/v1/reviews/{reviewId}
     * 홈에서 접어 둔 스포일러 리뷰를 펼칠 때 본문을 받는다. 숨김 작품의 리뷰·없는 리뷰는 404 REVIEW_NOT_FOUND.
     */
    @GetMapping("/{reviewId}")
    public ApiResponse<RecentReviewResponse> one(@PathVariable Long reviewId) {
        return ApiResponse.success(reviewQueryService.getPublicReview(reviewId));
    }
}
