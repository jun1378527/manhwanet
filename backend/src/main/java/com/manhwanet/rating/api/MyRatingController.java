package com.manhwanet.rating.api;

import com.manhwanet.auth.api.ExpectedUser;
import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.global.pagination.PageableFactory;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.rating.application.RatingService;
import com.manhwanet.rating.dto.MyRatingItemResponse;
import com.manhwanet.rating.dto.MyRatingResponse;
import com.manhwanet.rating.dto.RatingRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 별점 API. GET·PUT·DELETE /api/v1/me/ratings/{workId}
 * - 로그인 필요(보안 규칙의 기본 거부), PUT·DELETE 는 CSRF 필요
 * - 사용자 ID는 세션의 인증 정보에서만 얻는다. 요청의 사용자 ID는 받지 않는다.
 * - PUT·DELETE 는 헤더 X-Expected-User-Id(화면이 예상한 사용자 ID, 십진수 문자열)가 필수이고,
 *   이 요청의 세션 사용자와 다르면 409 USER_MISMATCH 로 아무것도 바꾸지 않는다. GET 은 헤더가 있을 때만 확인한다.
 *   이 값은 일치 확인에만 쓰며 별점 소유자로 쓰지 않는다.
 * - 본문이 비었거나 JSON null 이어도 score 누락과 같은 필드 오류(400)로 안내하도록 본문을 필수로 두지 않는다.
 */
@RestController
@RequestMapping("/me/ratings")
public class MyRatingController {

    public static final int DEFAULT_LIST_SIZE = 20;
    public static final int MAX_LIST_SIZE = 50;
    /** 마지막으로 저장한 시각 DESC, 같으면 id DESC(고정) */
    static final Sort LIST_ORDER = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));

    private final RatingService ratingService;

    public MyRatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    /**
     * 내 별점 목록 GET /api/v1/me/ratings?page&size (size 1~50, 기본 20). 본인 기록만, 사용자는 세션에서.
     * X-Expected-User-Id 가 있으면 세션 사용자와 같은지 확인한다(다르면 409 USER_MISMATCH).
     */
    @GetMapping
    public ApiResponse<PageResponse<MyRatingItemResponse>> getMyRatings(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(name = ExpectedUser.HEADER, required = false) String expectedUserId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "" + DEFAULT_LIST_SIZE)
            @Min(value = 1, message = "size는 1 이상 50 이하여야 합니다.")
            @Max(value = MAX_LIST_SIZE, message = "size는 1 이상 50 이하여야 합니다.") int size) {
        Long userId = ExpectedUser.verify(principal, expectedUserId, false);
        return ApiResponse.success(ratingService.getMyRatings(userId,
                PageableFactory.createWithFixedSort(page, size, MAX_LIST_SIZE, LIST_ORDER)));
    }

    @GetMapping("/{workId}")
    public ApiResponse<MyRatingResponse> getMyRating(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(name = ExpectedUser.HEADER, required = false) String expectedUserId,
            @PathVariable Long workId) {
        return ApiResponse.success(
                ratingService.getMyRating(ExpectedUser.verify(principal, expectedUserId, false), workId));
    }

    @PutMapping("/{workId}")
    public ApiResponse<MyRatingResponse> putMyRating(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(name = ExpectedUser.HEADER, required = false) String expectedUserId,
            @PathVariable Long workId,
            @RequestBody(required = false) RatingRequest request) {
        return ApiResponse.success(
                ratingService.putMyRating(ExpectedUser.verify(principal, expectedUserId, true), workId, request));
    }

    @DeleteMapping("/{workId}")
    public ResponseEntity<Void> deleteMyRating(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(name = ExpectedUser.HEADER, required = false) String expectedUserId,
            @PathVariable Long workId) {
        ratingService.deleteMyRating(ExpectedUser.verify(principal, expectedUserId, true), workId);
        return ResponseEntity.noContent().build();
    }
}
