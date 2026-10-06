package com.manhwanet.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.response.ApiError;
import com.manhwanet.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * 보안 필터 단계의 인증·인가 실패를 공통 ApiResponse 형식으로 쓴다.
 * 필터에서 난 예외는 @RestControllerAdvice 에 도달하지 않으므로 여기서 직접 응답한다.
 * <ul>
 *   <li>인증 필요(익명) → 401 UNAUTHORIZED</li>
 *   <li>CSRF 토큰 누락·불일치 → 403 INVALID_CSRF_TOKEN</li>
 *   <li>권한 부족 → 403 FORBIDDEN</li>
 * </ul>
 */
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonSecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        write(response, ErrorCode.UNAUTHORIZED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        write(response, accessDeniedException instanceof CsrfException ? ErrorCode.INVALID_CSRF_TOKEN : ErrorCode.FORBIDDEN);
    }

    private void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.failure(ApiError.of(errorCode, errorCode.getMessage(), List.of())));
    }
}
