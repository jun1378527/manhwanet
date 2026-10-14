package com.manhwanet.provenance.dto;

import java.util.List;

/**
 * 외부 ID 변경 결과. ownerType 은 WORK·CREATOR, ownerVersion 은 바뀐 뒤 작품(작가) version.
 * warnings: EXTERNAL_ID_FORMAT_UNVERIFIED(형식 규칙을 확인하지 못한 출처)
 */
public record ExternalIdMutationResponse(String ownerType, long ownerId, long ownerVersion,
        ExternalIdResponse externalId, List<String> warnings) {
}
