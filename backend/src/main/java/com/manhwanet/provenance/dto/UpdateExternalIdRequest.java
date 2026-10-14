package com.manhwanet.provenance.dto;

import com.manhwanet.review.dto.JsonInteger;
import java.time.Instant;

/**
 * 외부 ID 상태 변경. PATCH .../external-ids/{id}
 * 출처·ID·출처 URL 은 바꾸지 않는다(바뀌면 새로 추가하고 이전 것을 SUPERSEDED·INVALID 로).
 * verifiedAt 을 보내면 다시 확인한 시각으로 바꾸고 확인자도 요청자로 바뀐다. note 를 생략(null)하면 기존 메모를 유지한다.
 * reason 은 필수.
 */
public record UpdateExternalIdRequest(JsonInteger version, String status, Instant verifiedAt, String note,
        String reason) {
}
