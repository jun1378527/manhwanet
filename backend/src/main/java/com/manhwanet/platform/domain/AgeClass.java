package com.manhwanet.platform.domain;

/**
 * 이용등급 분류. 18세 미만 등급은 GENERAL, 성인 등급은 ADULT, 미확인은 UNKNOWN(일반으로 처리하지 않음).
 */
public enum AgeClass {
    GENERAL, ADULT, UNKNOWN
}
