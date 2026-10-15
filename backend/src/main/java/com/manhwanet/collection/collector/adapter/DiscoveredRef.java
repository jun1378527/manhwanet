package com.manhwanet.collection.collector.adapter;

import com.manhwanet.collection.application.CollectedListing;

/**
 * 사이트맵·목록에서 찾은 작품 참조.
 * <ul>
 *   <li>listOnly 가 null 이면 상세 작업(detailUrl)을 큐에 넣는다.</li>
 *   <li>listOnly 의 detailStatus 가 SKIPPED_ADULT 면(목록 단계 성인 신호) 상세를 요청하지 않고 목록 값만 staging 에 반영한다(설계 §6.3).</li>
 * </ul>
 */
public record DiscoveredRef(String detailUrl, CollectedListing listOnly) {

    public static DiscoveredRef detail(String detailUrl) {
        return new DiscoveredRef(detailUrl, null);
    }
}
