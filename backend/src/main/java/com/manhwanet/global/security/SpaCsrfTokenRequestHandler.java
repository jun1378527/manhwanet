package com.manhwanet.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * Spring Security 6.5 공식 문서의 Single-Page Application CSRF 구성.
 * https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html
 * <ul>
 *   <li>헤더(X-XSRF-TOKEN)로 온 토큰: 쿠키의 원래 값과 비교 (plain)</li>
 *   <li>요청 파라미터 등으로 온 토큰: BREACH 대응 XOR 인코딩 값으로 해석</li>
 *   <li>handle 에서 토큰을 즉시 불러와 지연 생성된 토큰도 쿠키로 내려가게 한다</li>
 * </ul>
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        this.xor.handle(request, response, csrfToken);
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? this.plain : this.xor).resolveCsrfTokenValue(request, csrfToken);
    }
}
