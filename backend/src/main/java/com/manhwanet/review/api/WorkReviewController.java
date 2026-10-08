package com.manhwanet.review.api;

import com.manhwanet.global.pagination.PageableFactory;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.review.application.ReviewQueryService;
import com.manhwanet.review.dto.PublicReviewResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작품별 공개 리뷰 목록. GET /api/v1/works/{workId}/reviews
 * 보안 규칙의 GET /api/v1/works/** 공개 규칙에 해당한다(익명 허용). 응답은 로그인 여부와 관계없이 같다.
 * 정렬은 작성 시각 DESC, id DESC 로 고정하며 sort 파라미터는 받지 않는다.
 */
@RestController
@RequestMapping("/works/{workId}/reviews")
public class WorkReviewController {

    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 50;
    static final Sort ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ReviewQueryService reviewQueryService;

    public WorkReviewController(ReviewQueryService reviewQueryService) {
        this.reviewQueryService = reviewQueryService;
    }

    @GetMapping
    public ApiResponse<PageResponse<PublicReviewResponse>> getWorkReviews(
            @PathVariable Long workId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE)
            @Min(value = 1, message = "size는 1 이상 50 이하여야 합니다.")
            @Max(value = MAX_SIZE, message = "size는 1 이상 50 이하여야 합니다.") int size) {
        return ApiResponse.success(reviewQueryService.getWorkReviews(
                workId, PageableFactory.createWithFixedSort(page, size, MAX_SIZE, ORDER)));
    }
}
