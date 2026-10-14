package com.manhwanet.work.dto;

import com.manhwanet.platform.dto.ListingInput;
import com.manhwanet.provenance.dto.EvidenceRequest;
import com.manhwanet.provenance.dto.ExternalIdInput;
import com.manhwanet.provenance.dto.LinkInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 작품 등록 요청.
 * 형식·필수값은 Bean Validation 으로, 정규화가 필요한 규칙(공백 제거 후 길이, 언어·국가 코드,
 * 대표 제목 개수, 중복, 작가 존재)은 WorkService 에서 검증한다.
 * #40(명세 §5.6): evidence(필드별 근거)는 TITLE·CREDIT 각 1개 이상, 줄거리가 있으면 DESCRIPTION(STAFF·LICENSED) 1개 이상 필수.
 * listings·externalIds·links 는 선택이며 작품과 한 트랜잭션에서 저장한다. 연결·외부 ID 가 이미 다른 작품에 있으면 409 로
 * 등록 전체가 롤백되므로, 이런 키를 함께 보낸 등록은 재시도해도 작품이 두 번 생기지 않는다. reason 은 선택.
 */
public record CreateWorkRequest(
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

        List<ListingInput> listings,

        List<ExternalIdInput> externalIds,

        List<LinkInput> links,

        List<EvidenceRequest> evidence,

        String reason) {

    public record TitleRequest(
            @NotBlank(message = "제목은 필수입니다.") String title,
            @NotBlank(message = "언어 코드는 필수입니다.") String languageCode,
            boolean primary) {
    }

    public record CreditRequest(
            @NotNull(message = "작가 ID는 필수입니다.")
            @Positive(message = "작가 ID는 양수여야 합니다.")
            Long creatorId,

            @NotNull(message = "작가 역할은 필수입니다.")
            @Pattern(regexp = "STORY|ART|ORIGINAL_STORY", message = "작가 역할은 STORY, ART, ORIGINAL_STORY 중 하나여야 합니다.")
            String role) {
    }
}
