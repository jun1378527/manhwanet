package com.manhwanet.platform.api;

import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.platform.application.WorkListingService;
import com.manhwanet.platform.dto.CreateListingRequest;
import com.manhwanet.platform.dto.ListingLookupResponse;
import com.manhwanet.platform.dto.ListingMutationResponse;
import com.manhwanet.platform.dto.MoveListingRequest;
import com.manhwanet.platform.dto.UpdateListingRequest;
import com.manhwanet.platform.dto.WorkListingsResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작품 플랫폼 연결 관리자 API (#39). ADMIN 권한과 쿠키 CSRF 토큰이 필요하다. 삭제 API 는 두지 않는다.
 * <ul>
 *   <li>GET /api/v1/admin/works/{workId}/listings</li>
 *   <li>POST /api/v1/admin/works/{workId}/listings: 연결 등록(작품 version)</li>
 *   <li>PUT /api/v1/admin/works/{workId}/listings/{listingId}: 연결 수정·종료(ENDED)·이전(MOVED)(작품 version)</li>
 *   <li>POST /api/v1/admin/works/{workId}/listings/{listingId}/move: 새 연결로 이전(작품 version)</li>
 *   <li>GET /api/v1/admin/listings/lookup?platformCode=&amp;platformWorkId=: 이미 연결됐는지 확인</li>
 * </ul>
 */
@RestController
public class WorkListingAdminController {

    private final WorkListingService workListingService;

    public WorkListingAdminController(WorkListingService workListingService) {
        this.workListingService = workListingService;
    }

    @GetMapping("/admin/works/{workId}/listings")
    public ApiResponse<WorkListingsResponse> list(@PathVariable long workId) {
        return ApiResponse.success(workListingService.getListings(workId));
    }

    @PostMapping("/admin/works/{workId}/listings")
    public ResponseEntity<ApiResponse<ListingMutationResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long workId,
            @RequestBody CreateListingRequest request) {
        ListingMutationResponse response = workListingService.create(workId, request, AdminPrincipal.id(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PutMapping("/admin/works/{workId}/listings/{listingId}")
    public ApiResponse<ListingMutationResponse> update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long workId,
            @PathVariable long listingId,
            @RequestBody UpdateListingRequest request) {
        return ApiResponse.success(workListingService.update(workId, listingId, request, AdminPrincipal.id(principal)));
    }

    @PostMapping("/admin/works/{workId}/listings/{listingId}/move")
    public ResponseEntity<ApiResponse<ListingMutationResponse>> move(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long workId,
            @PathVariable long listingId,
            @RequestBody MoveListingRequest request) {
        ListingMutationResponse response = workListingService.move(workId, listingId, request,
                AdminPrincipal.id(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/admin/listings/lookup")
    public ApiResponse<ListingLookupResponse> lookup(@RequestParam String platformCode,
            @RequestParam String platformWorkId) {
        return ApiResponse.success(workListingService.lookup(platformCode, platformWorkId));
    }
}
