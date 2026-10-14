package com.manhwanet.audit.api;

import com.manhwanet.audit.application.ChangeLogQuery;
import com.manhwanet.audit.dto.ChangeLogPageResponse;
import com.manhwanet.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리 변경 기록 조회 (#40). ADMIN 만(WebSecurityConfig: /api/v1/admin/**).
 * GET /api/v1/admin/change-log?entityType=&entityId= | ?workId= | ?creatorId= [&limit=&beforeId=]
 * 기록은 추가만 하고 고치거나 지우는 API 는 없다.
 */
@RestController
@RequestMapping("/admin/change-log")
public class ChangeLogAdminController {

    private final ChangeLogQuery changeLogQuery;

    public ChangeLogAdminController(ChangeLogQuery changeLogQuery) {
        this.changeLogQuery = changeLogQuery;
    }

    @GetMapping
    public ApiResponse<ChangeLogPageResponse> find(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String workId,
            @RequestParam(required = false) String creatorId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String beforeId) {
        return ApiResponse.success(changeLogQuery.find(entityType, entityId, workId, creatorId, limit, beforeId));
    }
}
