package com.manhwanet.collection.domain;

import java.time.Instant;

/** 수집 실행. leaseValid 는 조회 시점(DB now())에 잠금이 살아 있는지 */
public record RunRow(
        long id,
        String platformCode,
        RunKind runKind,
        RunStatus status,
        StopReason stopReason,
        String robotsSha256,
        String adapterVersion,
        String leaseOwner,
        Instant leaseUntil,
        boolean leaseValid,
        Instant startedAt,
        Instant stopRequestedAt,
        Instant finishedAt,
        String statsJson) {

    /** owner 가 지금 잠금을 쥐고 있는지 */
    public boolean heldBy(String owner) {
        return leaseValid && owner.equals(leaseOwner);
    }
}
