package com.manhwanet.provenance.api;

import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.global.admin.AdminPrincipal;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.provenance.application.CreatorExternalIdService;
import com.manhwanet.provenance.dto.AddExternalIdRequest;
import com.manhwanet.provenance.dto.ExternalIdMutationResponse;
import com.manhwanet.provenance.dto.UpdateExternalIdRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작가 외부 ID 관리자 API (#40). POST /api/v1/admin/creators/{creatorId}/external-ids (201),
 * PATCH .../external-ids/{id}. 작가 version 을 쓴다. 목록은 관리자 작가 상세에 담긴다.
 */
@RestController
@RequestMapping("/admin/creators/{creatorId}/external-ids")
public class CreatorExternalIdAdminController {

    private final CreatorExternalIdService service;

    public CreatorExternalIdAdminController(CreatorExternalIdService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExternalIdMutationResponse> add(@PathVariable long creatorId,
            @RequestBody AddExternalIdRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(service.add(creatorId, request, AdminPrincipal.id(principal)));
    }

    @PatchMapping("/{externalIdId}")
    public ApiResponse<ExternalIdMutationResponse> update(@PathVariable long creatorId,
            @PathVariable long externalIdId, @RequestBody UpdateExternalIdRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(service.update(creatorId, externalIdId, request, AdminPrincipal.id(principal)));
    }
}
