package com.manhwanet.rating.api;

import com.manhwanet.global.pagination.PageableFactory;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.rating.application.RatingRankingService;
import com.manhwanet.work.dto.WorkSummaryResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 작품 별점 순위. GET /api/v1/works/rankings?order=rating|ratingCount&page=0&size=20
 * 보안 규칙의 GET /api/v1/works/** 공개 규칙에 해당한다(익명 허용). 응답 모양은 작품 목록(GET /works)과 같다.
 * 별점이 하나 이상 있는 공개 작품만 들어가며, totalElements 는 순위에 들어가는 작품 수다.
 */
@RestController
@RequestMapping("/works/rankings")
public class WorkRankingController {

    public static final int MAX_SIZE = 50;

    private final RatingRankingService rankingService;

    public WorkRankingController(RatingRankingService rankingService) {
        this.rankingService = rankingService;
    }

    @GetMapping
    public ApiResponse<PageResponse<WorkSummaryResponse>> rank(
            @RequestParam(required = false) String order,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size는 1 이상 50 이하여야 합니다.")
            @Max(value = MAX_SIZE, message = "size는 1 이상 50 이하여야 합니다.") int size) {
        RatingRankingService.Order parsed = RatingRankingService.Order.parse(order);
        return ApiResponse.success(rankingService.rank(parsed,
                PageableFactory.createWithFixedSort(page, size, MAX_SIZE, Sort.unsorted())));
    }
}
