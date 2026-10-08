package com.manhwanet.review.api;

import com.manhwanet.auth.api.ExpectedUser;
import com.manhwanet.auth.application.AuthenticatedUser;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 리뷰 API. GET·POST·PUT·DELETE /api/v1/me/reviews/{workId}
 * - 로그인 필요(보안 규칙의 기본 거부), POST·PUT·DELETE 는 CSRF 필요
 * - 사용자 ID는 세션의 인증 정보에서만 얻는다. 요청의 사용자 ID는 받지 않는다.
 * - PUT 은 본문, DELETE 는 query 로 reviewId·version 을 받는다. DELETE 는 요청 본문을 쓰지 않는다.
 * - 본문이 비었거나 JSON null 이어도 필드 오류(400)로 안내하도록 본문을 필수로 두지 않는다.
 * - POST·PUT·DELETE 는 헤더 X-Expected-User-Id(화면이 예상한 사용자 ID, 십진수 문자열)가 필수이고,
 *   이 요청의 세션 사용자와 다르면 409 USER_MISMATCH 로 아무것도 바꾸지 않는다(#22). GET 은 헤더가 있을 때만 확인한다.
 *   이 값은 일치 확인에만 쓰며 작성자로 쓰지 않는다. 확인은 다른 입력 검증보다 먼저 한다.
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
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(name = ExpectedUser.HEADER, required = false) String expectedUserId,
            @PathVariable Long workId) {
        return ApiResponse.success(reviewService.getMyReview(ExpectedUser.verify(principal, expectedUserId, false), workId));
    }

    @PostMapping("/{workId}")
    public ResponseEntity<ApiResponse<MyReviewResponse>> createMyReview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(name = ExpectedUser.HEADER, required = false) String expectedUserId,
            @PathVariable Long workId,
            @RequestBody(required = false) CreateReviewRequest request) {
        Long userId = ExpectedUser.verify(principal, expectedUserId, true);
        MyReviewResponse response = reviewService.create(userId, workId, request);
        return ResponseEntity.created(URI.create("/api/v1/me/reviews/" + workId)).body(ApiResponse.success(response));
    }

    @PutMapping("/{workId}")
    public ApiResponse<MyReviewResponse> updateMyReview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(name = ExpectedUser.HEADER, required = false) String expectedUserId,
            @PathVariable Long workId,
            @RequestBody(required = false) UpdateReviewRequest request) {
        Long userId = ExpectedUser.verify(principal, expectedUserId, true);
        return ApiResponse.success(reviewService.update(userId, workId, request));
    }

    @DeleteMapping("/{workId}")
    public ResponseEntity<Void> deleteMyReview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(name = ExpectedUser.HEADER, required = false) String expectedUserId,
            @PathVariable Long workId,
            @RequestParam Long reviewId,
            @RequestParam Long version) {
        Long userId = ExpectedUser.verify(principal, expectedUserId, true);
        reviewService.delete(userId, workId, reviewId, version);
        return ResponseEntity.noContent().build();
    }
}
