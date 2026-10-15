package com.manhwanet.collection.api;

import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.collection.application.CollectionAdminService;
import com.manhwanet.collection.application.CrawlRunService;
import com.manhwanet.collection.dto.CollectionPolicyResponse;
import com.manhwanet.collection.dto.CrawlRunResponse;
import com.manhwanet.collection.dto.PageResponseWithCursor;
import com.manhwanet.collection.dto.StagingListingResponse;
import com.manhwanet.collection.dto.StopCrawlRunRequest;
import com.manhwanet.collection.dto.UpdateCollectionPolicyRequest;
import com.manhwanet.global.admin.AdminPrincipal;
import com.manhwanet.global.response.ApiResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수집 설정·실행·staging 관리자 API (#45). ADMIN + CSRF(WebSecurityConfig: /api/v1/admin/**).
 * <ul>
 *   <li>GET /api/v1/admin/collection-policies, GET·PUT /api/v1/admin/collection-policies/{platformCode}</li>
 *   <li>GET /api/v1/admin/crawl-runs?platformCode=&limit=&beforeId=, GET /api/v1/admin/crawl-runs/{runId},
 *       POST /api/v1/admin/crawl-runs/{runId}/stop</li>
 *   <li>GET /api/v1/admin/staging-listings?platformCode=&reviewStatus=&detailStatus=&limit=&beforeId=,
 *       GET /api/v1/admin/staging-listings/{id}</li>
 * </ul>
 * 실행 시작·작업·수집 결과 반영은 수집기(#46)가 서비스로 직접 하며 HTTP 로 열지 않는다. staging 을 공개 작품으로 등록하는 API 는 없다(#51).
 */
@RestController
@RequestMapping("/admin")
public class CollectionAdminController {

    private final CollectionAdminService adminService;
    private final CrawlRunService runService;

    public CollectionAdminController(CollectionAdminService adminService, CrawlRunService runService) {
        this.adminService = adminService;
        this.runService = runService;
    }

    @GetMapping("/collection-policies")
    public ApiResponse<List<CollectionPolicyResponse>> policies() {
        return ApiResponse.success(adminService.getPolicies());
    }

    @GetMapping("/collection-policies/{platformCode}")
    public ApiResponse<CollectionPolicyResponse> policy(@PathVariable String platformCode) {
        return ApiResponse.success(adminService.getPolicy(platformCode));
    }

    @PutMapping("/collection-policies/{platformCode}")
    public ApiResponse<CollectionPolicyResponse> updatePolicy(@PathVariable String platformCode,
            @RequestBody UpdateCollectionPolicyRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(adminService.updatePolicy(platformCode, request, AdminPrincipal.id(principal)));
    }

    @GetMapping("/crawl-runs")
    public ApiResponse<PageResponseWithCursor<CrawlRunResponse>> runs(@RequestParam(required = false) String platformCode,
            @RequestParam(required = false) String limit, @RequestParam(required = false) String beforeId) {
        return ApiResponse.success(adminService.getRuns(platformCode, limit, beforeId));
    }

    @GetMapping("/crawl-runs/{runId}")
    public ApiResponse<CrawlRunResponse> run(@PathVariable long runId) {
        return ApiResponse.success(adminService.getRun(runId));
    }

    @PostMapping("/crawl-runs/{runId}/stop")
    public ApiResponse<CrawlRunResponse> stop(@PathVariable long runId, @RequestBody StopCrawlRunRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        runService.requestStop(runId, request.reason(), AdminPrincipal.id(principal));
        return ApiResponse.success(adminService.getRun(runId));
    }

    @GetMapping("/staging-listings")
    public ApiResponse<PageResponseWithCursor<StagingListingResponse>> stagingListings(
            @RequestParam(required = false) String platformCode, @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) String detailStatus, @RequestParam(required = false) String limit,
            @RequestParam(required = false) String beforeId) {
        return ApiResponse.success(adminService.getStagingListings(platformCode, reviewStatus, detailStatus, limit, beforeId));
    }

    @GetMapping("/staging-listings/{id}")
    public ApiResponse<StagingListingResponse> stagingListing(@PathVariable long id) {
        return ApiResponse.success(adminService.getStagingListing(id));
    }
}
