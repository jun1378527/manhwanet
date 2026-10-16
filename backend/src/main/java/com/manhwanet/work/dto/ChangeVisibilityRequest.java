package com.manhwanet.work.dto;

import com.manhwanet.review.dto.JsonInteger;

/** 작품 공개 상태 변경(#41). POST /api/v1/admin/works/{workId}/visibility — {version, visibility: PUBLIC|HIDDEN, reason} */
public record ChangeVisibilityRequest(JsonInteger version, String visibility, String reason) {
}
