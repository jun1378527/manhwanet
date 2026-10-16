package com.manhwanet.collection.review;

import com.manhwanet.global.admin.AdminPrincipal;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.auth.application.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * staging 검수 API (#51). ADMIN + CSRF(WebSecurityConfig: /api/v1/admin/**).
 * <ul>
 *   <li>GET  /api/v1/admin/staging-listings/{id}/review — 항목·수집 revision·저장된 후보·지난 결정</li>
 *   <li>POST /api/v1/admin/staging-listings/{id}/candidates — 후보 다시 만들기(행이 늘지 않음)</li>
 *   <li>POST /api/v1/admin/staging-listings/{id}/review — 결정(LINK_EXISTING·CREATE_WORK·HOLD·REJECT)</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/staging-listings")
public class StagingReviewAdminController {

    private final StagingReviewService service;

    public StagingReviewAdminController(StagingReviewService service) {
        this.service = service;
    }

    @GetMapping("/{id}/review")
    public ApiResponse<StagingReviewView> view(@PathVariable long id) {
        return ApiResponse.success(service.view(id));
    }

    @PostMapping("/{id}/candidates")
    public ApiResponse<StagingReviewView> candidates(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(service.regenerateCandidates(id, AdminPrincipal.id(principal)));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<StagingReviewView> review(@PathVariable long id, @RequestBody StagingReviewRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(service.review(id, request, AdminPrincipal.id(principal)));
    }
}
