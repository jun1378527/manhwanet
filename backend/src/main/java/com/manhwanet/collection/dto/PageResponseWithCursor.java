package com.manhwanet.collection.dto;

import java.util.List;

/** id 내림차순 목록. 다음 쪽은 nextBeforeId 를 beforeId 로 보낸다(없으면 null) */
public record PageResponseWithCursor<T>(List<T> items, Long nextBeforeId) {
}
