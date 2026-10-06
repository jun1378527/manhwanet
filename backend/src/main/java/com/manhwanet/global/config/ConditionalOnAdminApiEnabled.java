package com.manhwanet.global.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;

/**
 * 인증 구현 전 등록(관리자) API를 제한적으로 노출하기 위한 조건.
 * local 프로필 + manhwanet.admin-api.enabled=true 가 모두 충족될 때만 Bean을 등록한다.
 *
 * <p>관리자 인증을 대신하지 않는다. 운영·공유 환경에서 활성화하지 않는다.
 * auth 도메인 구현 시 /api/v1/admin/** 인증·인가로 대체한다. (docs/work-creator-spec.md 6.1)
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Profile("local")
@ConditionalOnProperty(prefix = "manhwanet.admin-api", name = "enabled", havingValue = "true")
public @interface ConditionalOnAdminApiEnabled {
}
