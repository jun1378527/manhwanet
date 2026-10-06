package com.manhwanet.creator.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 작가 등록 요청. 공백 제거 후 길이 검증은 CreatorService 에서 한다.
 */
public record CreateCreatorRequest(
        @NotBlank(message = "작가 이름은 필수입니다.") String name,
        String originalName) {
}
