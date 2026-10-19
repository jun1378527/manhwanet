package com.manhwanet.activity.api;

import com.manhwanet.activity.dto.MyActivitySummaryResponse;
import com.manhwanet.auth.api.ExpectedUser;
import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.rating.application.RatingService;
import com.manhwanet.review.application.ReviewService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 활동 요약 GET /api/v1/me/activity. 로그인 필요(보안 규칙의 기본 거부), 사용자는 세션에서만 얻는다.
 * 별점·리뷰 도메인의 공개 서비스로 각자 수를 센다(다른 도메인 Repository 를 직접 쓰지 않음).
 */
@RestController
@RequestMapping("/me/activity")
public class MyActivityController {

    private final RatingService ratingService;
    private final ReviewService reviewService;

    public MyActivityController(RatingService ratingService, ReviewService reviewService) {
        this.ratingService = ratingService;
        this.reviewService = reviewService;
    }

    @GetMapping
    public ApiResponse<MyActivitySummaryResponse> getSummary(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(name = ExpectedUser.HEADER, required = false) String expectedUserId) {
        Long userId = ExpectedUser.verify(principal, expectedUserId, false);
        return ApiResponse.success(new MyActivitySummaryResponse(ratingService.countMine(userId), reviewService.countMine(userId)));
    }
}
