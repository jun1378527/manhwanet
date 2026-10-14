package com.manhwanet.audit.dto;

import java.util.List;

/** 최신순 변경 기록. 다음 쪽은 nextBeforeId 를 beforeId 로 보내 읽는다(없으면 null) */
public record ChangeLogPageResponse(List<ChangeLogEntryResponse> entries, Long nextBeforeId) {
}
