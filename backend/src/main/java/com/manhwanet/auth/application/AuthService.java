package com.manhwanet.auth.application;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.TooManyRequestsException;
import com.manhwanet.user.domain.EmailAddresses;
import com.manhwanet.user.domain.Passwords;
import com.manhwanet.user.dto.UserResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 로그인 인증과 실패 제한. 세션 저장(HTTP)은 api 계층의 AuthenticationSessionHandler 가 맡는다.
 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final LoginAttemptLimiter loginAttemptLimiter;

    public AuthService(AuthenticationManager authenticationManager, LoginAttemptLimiter loginAttemptLimiter) {
        this.authenticationManager = authenticationManager;
        this.loginAttemptLimiter = loginAttemptLimiter;
    }

    /**
     * 이메일·비밀번호를 검증한다. 존재하지 않는 이메일과 틀린 비밀번호는 같은 INVALID_CREDENTIALS 로 응답한다.
     *
     * @throws TooManyRequestsException 실패 제한에 걸린 경우 (비밀번호를 검증하지 않음)
     * @throws BusinessException        INVALID_CREDENTIALS
     */
    public Authentication authenticate(String rawEmail, String rawPassword) {
        String key = EmailAddresses.normalize(rawEmail);
        LoginAttemptLimiter.Decision decision = loginAttemptLimiter.tryAcquire(key);
        if (!decision.permitted()) {
            throw new TooManyRequestsException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS, decision.retryAfterSeconds());
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(key, Passwords.normalize(rawPassword)));
            loginAttemptLimiter.recordSuccess(key);
            return authentication;
        } catch (BadCredentialsException e) {
            loginAttemptLimiter.recordFailure(key);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        } catch (RuntimeException e) {
            loginAttemptLimiter.release(key);
            throw e;
        }
    }

    /** 가입 직후 자동 로그인용 인증 객체 (비밀번호 재검증 없이 방금 만든 계정으로 인증) */
    public Authentication authenticated(UserResponse user) {
        AuthenticatedUser principal = new AuthenticatedUser(user.id(), user.email(), user.role(), null);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
