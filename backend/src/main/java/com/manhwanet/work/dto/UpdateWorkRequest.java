package com.manhwanet.work.dto;

import com.manhwanet.provenance.dto.EvidenceRequest;
import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.work.dto.CreateWorkRequest.CreditRequest;
import com.manhwanet.work.dto.CreateWorkRequest.TitleRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 작품 핵심 정보 수정(전체 교체) 요청. PUT /api/v1/admin/works/{workId}
 * version 이 현재 작품 version 과 같을 때만 반영한다. 나머지 필드의 규칙은 등록(CreateWorkRequest)과 같다.
 * description 을 생략하거나 null 로 보내면 줄거리를 비운다(전체 교체).
 * version 은 JSON 정수만 받는다(문자열·소수 표기는 400).
 * #40: reason(수정 사유) 필수. 실제로 바뀐 제목·크레딧에는 TITLE·CREDIT 근거, 바뀐 줄거리(비우는 경우 제외)에는
 * DESCRIPTION(STAFF·LICENSED) 근거가 필요하다. 바뀐 것이 없으면 근거·기록을 남기지 않는다.
 */
public record UpdateWorkRequest(
        JsonInteger version,

        @NotNull(message = "작품 형식은 필수입니다.")
        @Pattern(regexp = "WEBTOON|PAGE_COMIC", message = "작품 형식은 WEBTOON 또는 PAGE_COMIC 이어야 합니다.")
        String workFormat,

        String originCountry,

        String description,

        @NotNull(message = "제목은 1개 이상 필요합니다.")
        @Size(min = 1, max = 20, message = "제목은 1개 이상 20개 이하여야 합니다.")
        List<@NotNull(message = "제목 항목은 비어 있을 수 없습니다.") @Valid TitleRequest> titles,

        @NotNull(message = "작가 연결은 1개 이상 필요합니다.")
        @Size(min = 1, max = 20, message = "작가 연결은 1개 이상 20개 이하여야 합니다.")
        List<@NotNull(message = "작가 연결 항목은 비어 있을 수 없습니다.") @Valid CreditRequest> creators,

        List<EvidenceRequest> evidence,

        String reason) {
}
