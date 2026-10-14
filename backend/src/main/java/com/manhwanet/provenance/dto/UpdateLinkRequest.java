package com.manhwanet.provenance.dto;

import com.manhwanet.review.dto.JsonInteger;
import java.time.Instant;

/**
 * 공식 링크 상태 변경. PATCH /api/v1/admin/works/{workId}/links/{linkId}
 * 종류·URL·언어는 바꾸지 않는다. verifiedAt·note 는 생략하면 유지. reason 필수.
 */
public record UpdateLinkRequest(JsonInteger version, String status, Instant verifiedAt, String note, String reason) {
}
