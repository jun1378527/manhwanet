package com.manhwanet.platform.dto;

import java.util.List;

/**
 * 상태 변경 결과. historyId 는 내용이 같아 바뀌지 않았으면 null 이다.
 * activeListingCount: 이 플랫폼의 ACTIVE 연결 수. 연결 상태는 자동으로 바꾸지 않으므로 관리자가 연결마다 처리한다.
 * activeListingIds: 처리할 ACTIVE 연결 id(오름차순, 최대 100개). 전체 수는 activeListingCount 로 확인하며, 목록이 그보다 짧을 수 있다.
 * platform.warnings: 통합 대상이 CLOSED 이면 MERGED_INTO_CLOSED, 이 플랫폼이 CLOSED 인데 다른 플랫폼이 여기를 가리키면 MERGE_SOURCES_REMAIN.
 */
public record PlatformStatusChangeResponse(PlatformResponse platform, Long historyId, long activeListingCount,
        List<Long> activeListingIds) {
}
