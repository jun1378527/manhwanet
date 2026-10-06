package com.manhwanet.creator.api;

import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.dto.CreateCreatorRequest;
import com.manhwanet.creator.dto.CreatorResponse;
import com.manhwanet.global.config.WebConfig;
import com.manhwanet.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 작가 등록 API. POST /api/v1/admin/creators
 * ADMIN 권한이 필요하다 (WebSecurityConfig: /api/v1/admin/** → hasRole("ADMIN")).
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
}
