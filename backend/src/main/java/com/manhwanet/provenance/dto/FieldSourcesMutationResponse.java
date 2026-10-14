package com.manhwanet.provenance.dto;

import java.util.List;

/** 근거 추가·상태 변경 결과. fieldSources 는 이번에 추가·변경한 근거 */
public record FieldSourcesMutationResponse(long workId, long workVersion, List<FieldSourceResponse> fieldSources) {
}
