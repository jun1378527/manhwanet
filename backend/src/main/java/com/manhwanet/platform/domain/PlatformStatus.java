package com.manhwanet.platform.domain;

/**
 * 플랫폼 운영 상태. 종료(CLOSED)·통합(MERGING)은 공식 확인(OFFICIAL)일 때만 쓴다. 보도·미확인은 UNKNOWN.
 */
public enum PlatformStatus {
    ACTIVE, MERGING, CLOSED, UNKNOWN
}
