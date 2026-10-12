package com.manhwanet.work.api;

import com.manhwanet.global.config.WebConfig;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.AdminWorkDetailResponse;
import com.manhwanet.work.dto.CreateWorkRequest;
import com.manhwanet.work.dto.UpdateWorkRequest;
import com.manhwanet.work.dto.WorkDetailResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 작품 관리자 API. ADMIN 권한이 필요하다 (WebSecurityConfig: /api/v1/admin/** → hasRole("ADMIN")).
 * <ul>
 *   <li>POST /api/v1/admin/works: 등록</li>
 *   <li>GET /api/v1/admin/works/{workId}: 관리자용 상세(version 포함)</li>
 *   <li>PUT /api/v1/admin/works/{workId}: 핵심 정보 전체 교체(version 일치 시에만, 다르면 409)</li>
 * </ul>
 * 삭제 API 는 두지 않는다(별점·리뷰 보존, docs/work-platform-admin-spec.md §7).
 */
@RestController
@RequestMapping("/admin/works")
public class WorkAdminController {

    private final WorkService workService;

    public WorkAdminController(WorkService workService) {
        this.workService = workService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WorkDetailResponse>> register(@Valid @RequestBody CreateWorkRequest request) {
        WorkDetailResponse response = workService.register(request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(WebConfig.API_PREFIX + "/works/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(ApiResponse.success(response));
    }

    @GetMapping("/{workId}")
    public ApiResponse<AdminWorkDetailResponse> get(@PathVariable Long workId) {
        return ApiResponse.success(workService.getAdminWork(workId));
    }

    @PutMapping("/{workId}")
    public ApiResponse<AdminWorkDetailResponse> update(
            @PathVariable Long workId, @Valid @RequestBody UpdateWorkRequest request) {
        return ApiResponse.success(workService.update(workId, request));
    }
}
