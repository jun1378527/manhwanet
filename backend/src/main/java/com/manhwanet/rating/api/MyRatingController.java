package com.manhwanet.rating.api;

import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.rating.application.RatingService;
import com.manhwanet.rating.dto.MyRatingResponse;
import com.manhwanet.rating.dto.RatingRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 별점 API. GET·PUT·DELETE /api/v1/me/ratings/{workId}
 * - 로그인 필요(보안 규칙의 기본 거부), PUT·DELETE 는 CSRF 필요
 * - 사용자 ID는 세션의 인증 정보에서만 얻는다. 요청의 사용자 ID는 받지 않는다.
 * - 본문이 비었거나 JSON null 이어도 score 누락과 같은 필드 오류(400)로 안내하도록 본문을 필수로 두지 않는다.
 */
@RestController
@RequestMapping("/me/ratings")
public class MyRatingController {

    private final RatingService ratingService;

    public MyRatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    @GetMapping("/{workId}")
    public ApiResponse<MyRatingResponse> getMyRating(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long workId) {
        return ApiResponse.success(ratingService.getMyRating(userId(principal), workId));
    }

    @PutMapping("/{workId}")
    public ApiResponse<MyRatingResponse> putMyRating(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long workId,
            @RequestBody(required = false) RatingRequest request) {
        return ApiResponse.success(ratingService.putMyRating(userId(principal), workId, request));
    }

    @DeleteMapping("/{workId}")
    public ResponseEntity<Void> deleteMyRating(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long workId) {
        ratingService.deleteMyRating(userId(principal), workId);
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
