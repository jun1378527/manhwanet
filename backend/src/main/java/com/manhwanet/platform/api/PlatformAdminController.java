package com.manhwanet.platform.api;

import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.global.admin.AdminPrincipal;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.platform.application.PlatformAdminService;
import com.manhwanet.platform.dto.ChangePlatformStatusRequest;
import com.manhwanet.platform.dto.PlatformResponse;
import com.manhwanet.platform.dto.PlatformStatusChangeResponse;
import com.manhwanet.platform.dto.StatusHistoryResponse;
import com.manhwanet.platform.dto.UpdatePlatformRequest;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 관리자 API (#39). ADMIN 권한이 필요하다 (WebSecurityConfig: /api/v1/admin/** → hasRole("ADMIN")).
 * 변경 요청은 쿠키 CSRF 토큰이 필요하다. 삭제 API 는 두지 않는다.
 * <ul>
 *   <li>GET /api/v1/admin/platforms</li>
 *   <li>GET /api/v1/admin/platforms/{code}</li>
 *   <li>PUT /api/v1/admin/platforms/{code}: 표시 이름·URL 규칙·허용 호스트 등 전체 교체(version)</li>
 *   <li>POST /api/v1/admin/platforms/{code}/status: 운영 상태 변경 + 이력(version)</li>
 *   <li>GET /api/v1/admin/platforms/{code}/status-history</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/platforms")
public class PlatformAdminController {

    private final PlatformAdminService platformAdminService;

    public PlatformAdminController(PlatformAdminService platformAdminService) {
        this.platformAdminService = platformAdminService;
    }

    @GetMapping
    public ApiResponse<List<PlatformResponse>> list() {
        return ApiResponse.success(platformAdminService.getPlatforms());
    }

    @GetMapping("/{code}")
    public ApiResponse<PlatformResponse> get(@PathVariable String code) {
        return ApiResponse.success(platformAdminService.getPlatform(code));
    }

    @PutMapping("/{code}")
    public ApiResponse<PlatformResponse> update(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String code, @RequestBody UpdatePlatformRequest request) {
        return ApiResponse.success(platformAdminService.updateRules(code, request, AdminPrincipal.id(principal)));
    }

    @PostMapping("/{code}/status")
    public ApiResponse<PlatformStatusChangeResponse> changeStatus(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String code,
            @RequestBody ChangePlatformStatusRequest request) {
        return ApiResponse.success(platformAdminService.changeStatus(code, request, AdminPrincipal.id(principal)));
    }

    @GetMapping("/{code}/status-history")
    public ApiResponse<List<StatusHistoryResponse>> history(@PathVariable String code) {
        return ApiResponse.success(platformAdminService.getStatusHistory(code));
    }
}
