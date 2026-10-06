package com.manhwanet.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 등록(관리자) API를 명시적으로 활성화한 통합 테스트 설정.
 * local 프로필(등록 조건) + test 프로필(테스트 DB)을 함께 사용하며, test 를 마지막에 두어 테스트 DB 설정이 우선한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "manhwanet.admin-api.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
public @interface AdminApiIntegrationTest {
}
