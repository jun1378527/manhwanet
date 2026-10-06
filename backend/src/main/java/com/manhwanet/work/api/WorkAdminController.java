package com.manhwanet.work.api;

import com.manhwanet.global.config.ConditionalOnAdminApiEnabled;
import com.manhwanet.global.config.WebConfig;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.CreateWorkRequest;
import com.manhwanet.work.dto.WorkDetailResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 작품 등록 API. POST /api/v1/admin/works
 * 인증 구현 전에는 local 프로필 + manhwanet.admin-api.enabled=true 일 때만 등록된다.
 */
@RestController
@RequestMapping("/admin/works")
@ConditionalOnAdminApiEnabled
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
}
