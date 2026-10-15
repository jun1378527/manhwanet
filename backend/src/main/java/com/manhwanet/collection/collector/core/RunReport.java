package com.manhwanet.collection.collector.core;

import com.manhwanet.collection.domain.RunStatus;
import java.util.Map;

/** 실행 한 번의 결과. halt 는 멈춘 이유(끝까지 돌았으면 null), status 는 DB 의 실행 상태(잠금을 잃었으면 다른 프로세스가 본 상태) */
public record RunReport(long runId, RunStatus status, Halt halt, Map<String, Object> stats) {
}
