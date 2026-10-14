package com.manhwanet.creator.dto;

import com.manhwanet.provenance.dto.ExternalIdInput;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 작가 등록 요청. 공백 제거 후 길이 검증은 CreatorService 에서 한다.
 * externalIds 는 선택이며 작가와 한 트랜잭션에서 저장한다(같은 출처·ID 가 이미 있으면 409, 등록 전체 롤백). reason 은 선택(#40).
 */
public record CreateCreatorRequest(
        @NotBlank(message = "작가 이름은 필수입니다.") String name,
        String originalName,
        List<ExternalIdInput> externalIds,
        String reason) {
}
