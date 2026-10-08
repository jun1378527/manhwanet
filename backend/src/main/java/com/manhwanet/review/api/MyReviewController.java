package com.manhwanet.review.api;

import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.review.application.ReviewService;
import com.manhwanet.review.dto.CreateReviewRequest;
import com.manhwanet.review.dto.MyReviewResponse;
import com.manhwanet.review.dto.UpdateReviewRequest;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 리뷰 API. GET·POST·PUT·DELETE /api/v1/me/reviews/{workId}
 * - 로그인 필요(보안 규칙의 기본 거부), POST·PUT·DELETE 는 CSRF 필요
 * - 사용자 ID는 세션의 인증 정보에서만 얻는다. 요청의 사용자 ID는 받지 않는다.
 * - PUT 은 본문, DELETE 는 query 로 reviewId·version 을 받는다. DELETE 는 요청 본문을 쓰지 않는다.
 * - 본문이 비었거나 JSON null 이어도 필드 오류(400)로 안내하도록 본문을 필수로 두지 않는다.
 */
@RestController
@RequestMapping("/me/reviews")
public class MyReviewController {

    private final ReviewService reviewService;

    public MyReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/{workId}")
    public ApiResponse<MyReviewResponse> getMyReview(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long workId) {
        return ApiResponse.success(reviewService.getMyReview(userId(principal), workId));
    }

    @PostMapping("/{workId}")
    public ResponseEntity<ApiResponse<MyReviewResponse>> createMyReview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long workId,
            @RequestBody(required = false) CreateReviewRequest request) {
        MyReviewResponse response = reviewService.create(userId(principal), workId, request);
        return ResponseEntity.created(URI.create("/api/v1/me/reviews/" + workId)).body(ApiResponse.success(response));
    }

    @PutMapping("/{workId}")
    public ApiResponse<MyReviewResponse> updateMyReview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long workId,
            @RequestBody(required = false) UpdateReviewRequest request) {
        return ApiResponse.success(reviewService.update(userId(principal), workId, request));
    }

    @DeleteMapping("/{workId}")
    public ResponseEntity<Void> deleteMyReview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long workId,
            @RequestParam Long reviewId,
            @RequestParam Long version) {
        reviewService.delete(userId(principal), workId, reviewId, version);
        return ResponseEntity.noContent().build();
    }

    /** 보안 규칙상 익명은 여기까지 오지 않지만, 규칙이 바뀌어도 익명 요청을 처리하지 않도록 한 번 더 막는다. */
    private static Long userId(AuthenticatedUser principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return principal.id();
    }
}
