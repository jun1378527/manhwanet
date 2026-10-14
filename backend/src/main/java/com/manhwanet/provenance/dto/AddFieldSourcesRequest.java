package com.manhwanet.provenance.dto;

import com.manhwanet.review.dto.JsonInteger;
import java.util.List;

/** 근거만 보강. POST /api/v1/admin/works/{workId}/field-sources (작품 version, 근거 1~50개, reason 선택) */
public record AddFieldSourcesRequest(JsonInteger version, List<EvidenceRequest> evidence, String reason) {
}
