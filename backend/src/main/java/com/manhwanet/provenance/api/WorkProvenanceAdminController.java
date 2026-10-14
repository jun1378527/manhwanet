package com.manhwanet.provenance.api;

import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.global.admin.AdminPrincipal;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.provenance.application.WorkProvenanceService;
import com.manhwanet.provenance.dto.AddExternalIdRequest;
import com.manhwanet.provenance.dto.AddFieldSourcesRequest;
import com.manhwanet.provenance.dto.AddLinkRequest;
import com.manhwanet.provenance.dto.ExternalIdMutationResponse;
import com.manhwanet.provenance.dto.FieldSourcesMutationResponse;
import com.manhwanet.provenance.dto.LinkMutationResponse;
import com.manhwanet.provenance.dto.UpdateExternalIdRequest;
import com.manhwanet.provenance.dto.UpdateFieldSourceRequest;
import com.manhwanet.provenance.dto.UpdateLinkRequest;
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
 * 작품 외부 ID·공식 링크·필드별 근거 관리자 API (#40, 명세 §5.4). ADMIN + CSRF.
 * <ul>
 *   <li>POST /api/v1/admin/works/{workId}/external-ids (201), PATCH .../external-ids/{id}</li>
 *   <li>POST /api/v1/admin/works/{workId}/links (201), PATCH .../links/{id}</li>
 *   <li>POST /api/v1/admin/works/{workId}/field-sources (201), PATCH .../field-sources/{id}</li>
 * </ul>
 * 목록은 관리자 작품 상세(GET /api/v1/admin/works/{workId})에 함께 담긴다. 삭제 API 는 없다(상태로 비활성화).
 */
@RestController
@RequestMapping("/admin/works/{workId}")
public class WorkProvenanceAdminController {

    private final WorkProvenanceService service;

    public WorkProvenanceAdminController(WorkProvenanceService service) {
        this.service = service;
    }

    @PostMapping("/external-ids")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExternalIdMutationResponse> addExternalId(@PathVariable long workId,
            @RequestBody AddExternalIdRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(service.addExternalId(workId, request, AdminPrincipal.id(principal)));
    }

    @PatchMapping("/external-ids/{externalIdId}")
    public ApiResponse<ExternalIdMutationResponse> updateExternalId(@PathVariable long workId,
            @PathVariable long externalIdId, @RequestBody UpdateExternalIdRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(service.updateExternalId(workId, externalIdId, request,
                AdminPrincipal.id(principal)));
    }

    @PostMapping("/links")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LinkMutationResponse> addLink(@PathVariable long workId, @RequestBody AddLinkRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(service.addLink(workId, request, AdminPrincipal.id(principal)));
    }

    @PatchMapping("/links/{linkId}")
    public ApiResponse<LinkMutationResponse> updateLink(@PathVariable long workId, @PathVariable long linkId,
            @RequestBody UpdateLinkRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(service.updateLink(workId, linkId, request, AdminPrincipal.id(principal)));
    }

    @PostMapping("/field-sources")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FieldSourcesMutationResponse> addFieldSources(@PathVariable long workId,
            @RequestBody AddFieldSourcesRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(service.addFieldSources(workId, request, AdminPrincipal.id(principal)));
    }

    @PatchMapping("/field-sources/{sourceId}")
    public ApiResponse<FieldSourcesMutationResponse> updateFieldSource(@PathVariable long workId,
            @PathVariable long sourceId, @RequestBody UpdateFieldSourceRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(service.updateFieldSource(workId, sourceId, request, AdminPrincipal.id(principal)));
    }
}
