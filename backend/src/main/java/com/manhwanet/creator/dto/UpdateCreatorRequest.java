package com.manhwanet.creator.dto;

import com.manhwanet.review.dto.JsonInteger;

/**
 * 작가 수정(전체 교체) 요청. PUT /api/v1/admin/creators/{creatorId}
 * version 이 현재 작가 version 과 같을 때만 반영한다. originalName 을 생략하거나 null 로 보내면 원어 표기를 비운다.
 * 공백 제거 후 길이 검증은 CreatorService 에서 한다(등록과 같은 규칙). reason(수정 사유)은 필수(#40).
 */
public record UpdateCreatorRequest(JsonInteger version, String name, String originalName, String reason) {
}
