package com.manhwanet.creator.api;

import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.dto.AdminCreatorResponse;
import com.manhwanet.creator.dto.CreateCreatorRequest;
import com.manhwanet.creator.dto.CreatorResponse;
import com.manhwanet.creator.dto.UpdateCreatorRequest;
import com.manhwanet.global.config.WebConfig;
import com.manhwanet.global.response.ApiResponse;
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
 * 작가 관리자 API. ADMIN 권한이 필요하다 (WebSecurityConfig: /api/v1/admin/** → hasRole("ADMIN")).
 * <ul>
 *   <li>POST /api/v1/admin/creators: 등록</li>
 *   <li>GET /api/v1/admin/creators/{creatorId}: 관리자용 상세(version, 연결된 작품 수)</li>
 *   <li>PUT /api/v1/admin/creators/{creatorId}: 이름 전체 교체(version 일치 시에만, 다르면 409)</li>
 * </ul>
 * 삭제·병합 API 는 두지 않는다.
 */
@RestController
@RequestMapping("/admin/creators")
public class CreatorAdminController {

    private final CreatorService creatorService;

    public CreatorAdminController(CreatorService creatorService) {
        this.creatorService = creatorService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreatorResponse>> register(@Valid @RequestBody CreateCreatorRequest request) {
        CreatorResponse response = creatorService.register(request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(WebConfig.API_PREFIX + "/creators/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(ApiResponse.success(response));
    }

    @GetMapping("/{creatorId}")
    public ApiResponse<AdminCreatorResponse> get(@PathVariable Long creatorId) {
        return ApiResponse.success(creatorService.getAdminCreator(creatorId));
    }

    @PutMapping("/{creatorId}")
    public ApiResponse<AdminCreatorResponse> update(
            @PathVariable Long creatorId, @RequestBody UpdateCreatorRequest request) {
        return ApiResponse.success(creatorService.update(creatorId, request));
    }
}
