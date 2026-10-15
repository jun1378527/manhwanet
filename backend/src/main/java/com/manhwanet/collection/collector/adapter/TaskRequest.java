package com.manhwanet.collection.collector.adapter;

import com.manhwanet.collection.domain.TaskKind;

/** 큐에 넣을 작업(발견 경로·목록 다음 쪽·상세). url 은 이 플랫폼 허용 호스트의 https 주소여야 한다 */
public record TaskRequest(TaskKind kind, String url) {
}
