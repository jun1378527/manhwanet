package com.manhwanet.collection.collector.adapter;

import java.util.List;

/** 사이트맵·목록 작업 결과: 더 볼 발견 경로(다음 쪽·하위 사이트맵)와 작품 참조 */
public record Discovery(List<TaskRequest> next, List<DiscoveredRef> refs) {

    public Discovery {
        next = next == null ? List.of() : List.copyOf(next);
        refs = refs == null ? List.of() : List.copyOf(refs);
    }
}
