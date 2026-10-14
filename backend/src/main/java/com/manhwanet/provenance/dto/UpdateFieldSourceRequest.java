package com.manhwanet.provenance.dto;

import com.manhwanet.review.dto.JsonInteger;

/** 근거 상태 변경(틀린 근거 철회 등). PATCH .../field-sources/{id}. note 는 생략하면 유지, reason 필수 */
public record UpdateFieldSourceRequest(JsonInteger version, String status, String note, String reason) {
}
