package com.manhwanet.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 등록(관리자) API 를 호출하는 통합 테스트 설정 (test 프로필, 테스트 DB).
 * 등록 API 는 ADMIN 권한이 필요하므로 요청은 {@link ApiFixtures} 가 ADMIN 사용자 + CSRF 토큰으로 보낸다.
 * (이전: local 프로필 + manhwanet.admin-api.enabled=true 로 Controller 를 조건부 등록 — 인증 구현으로 제거됨)
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public @interface AdminApiIntegrationTest {
}
