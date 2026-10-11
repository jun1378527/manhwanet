package com.manhwanet.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.auth.application.LoginLimitProperties;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;

/**
 * 인증에 쓰는 공용 Bean. 웹 서버가 없는 관리자 생성 명령(admin-cli)에서도 만들어지므로
 * HttpSecurity 에 의존하는 SecurityFilterChain 은 {@link WebSecurityConfig} 에 따로 둔다.
 */
@Configuration
@EnableConfigurationProperties({LoginLimitProperties.class, HashLimitProperties.class})
public class AuthenticationConfig {

    /** OWASP Password Storage Cheat Sheet 의 Argon2id 최소 설정: m=19 MiB, t=2, p=1 */
    static final int ARGON2_SALT_LENGTH = 16;
    static final int ARGON2_HASH_LENGTH = 32;
    static final int ARGON2_PARALLELISM = 1;
    static final int ARGON2_MEMORY_KIB = 19 * 1024;
    static final int ARGON2_ITERATIONS = 2;

    public static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    public static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

    /** 해시 앞에 {argon2} 접두사를 붙여 저장한다. 나중에 알고리즘을 바꿔도 기존 해시를 검증할 수 있다. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(
                ARGON2_SALT_LENGTH, ARGON2_HASH_LENGTH, ARGON2_PARALLELISM, ARGON2_MEMORY_KIB, ARGON2_ITERATIONS);
        return new DelegatingPasswordEncoder("argon2", Map.of("argon2", argon2));
    }

    /**
     * 사용자 없음·비밀번호 불일치를 모두 BadCredentialsException 으로 만든다(hideUserNotFoundExceptions 기본값).
     * 사용자가 없을 때도 더미 해시와 비교해 응답 시간 차이를 줄인다(DaoAuthenticationProvider 기본 동작).
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /** 필터 체인과 로그인 처리기가 같은 저장소를 쓴다. */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    /** SPA 방식 CSRF: JS 가 읽을 수 있는 쿠키(XSRF-TOKEN) + 요청 헤더(X-XSRF-TOKEN) */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(CSRF_COOKIE_NAME);
        repository.setHeaderName(CSRF_HEADER_NAME);
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.secure(secure).sameSite("Lax"));
        return repository;
    }

    /**
     * JSON 로그인·가입 자동 로그인 성공 시 적용하는 세션 전략.
     * <ol>
     *   <li>세션 고정 방지: 기존 세션이 있으면 세션 ID 변경 (ChangeSessionId)</li>
     *   <li>CSRF 토큰 교체: 이전 토큰 쿠키 삭제 후 새 토큰을 같은 응답에 발급</li>
     * </ol>
     */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(CookieCsrfTokenRepository csrfTokenRepository) {
        CsrfAuthenticationStrategy csrfStrategy = new CsrfAuthenticationStrategy(csrfTokenRepository);
        csrfStrategy.setRequestHandler(new SpaCsrfTokenRequestHandler());
        return new CompositeSessionAuthenticationStrategy(
                List.of(new ChangeSessionIdAuthenticationStrategy(), csrfStrategy));
    }

    @Bean
    public JsonSecurityErrorHandler jsonSecurityErrorHandler(ObjectMapper objectMapper) {
        return new JsonSecurityErrorHandler(objectMapper);
    }
}
