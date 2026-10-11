package com.manhwanet.auth.application;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.ServiceBusyException;
import com.manhwanet.global.error.TooManyRequestsException;
import com.manhwanet.global.security.PasswordHashGate;
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
 *
 * <p>순서(docs/login-limit-design-draft.md §6.1): 계정 예약 → 해시 게이트 → 인증 → 결과 기록 → 게이트 자리 반환 →
 * 예약 닫기. 잠긴 키·한도 도달 키는 해시 없이 429 이고, 게이트 거절(503)은 실패로 세지 않는다.
 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final PasswordHashGate passwordHashGate;

    public AuthService(AuthenticationManager authenticationManager, LoginAttemptLimiter loginAttemptLimiter,
            PasswordHashGate passwordHashGate) {
        this.authenticationManager = authenticationManager;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.passwordHashGate = passwordHashGate;
    }

    /**
     * 이메일·비밀번호를 검증한다. 존재하지 않는 이메일과 틀린 비밀번호는 같은 INVALID_CREDENTIALS 로 응답한다.
     *
     * @throws TooManyRequestsException 429. 계정 잠금 또는 진행 중 시도 한도 (비밀번호를 검증하지 않음)
     * @throws ServiceBusyException     503 AUTH_BUSY. 해시 게이트 과부하 또는 제한 저장소 포화(신규 이메일).
     *                                  비밀번호를 검증하지 않았고 실패로 세지 않음
     * @throws BusinessException        INVALID_CREDENTIALS
     */
    public Authentication authenticate(String rawEmail, String rawPassword) {
        String key = EmailAddresses.normalize(rawEmail);
        // close(): 결과를 기록하지 못하고 끝나면(게이트 거절·인증 외 예외·Error) 실패로 세지 않고 예약을 반환한다.
        try (LoginAttemptLimiter.Reservation reservation = loginAttemptLimiter.reserve(key)) {
            PasswordHashGate.Permit permit = passwordHashGate.acquire();
            try {
                Authentication authentication = authenticationManager.authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated(key, Passwords.normalize(rawPassword)));
                reservation.success();
                return authentication;
            } catch (BadCredentialsException e) {
                reservation.failure();
                throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
            } finally {
                permit.close();
            }
        }
    }

    /** 가입 직후 자동 로그인용 인증 객체 (비밀번호 재검증 없이 방금 만든 계정으로 인증) */
    public Authentication authenticated(UserResponse user) {
        AuthenticatedUser principal = new AuthenticatedUser(user.id(), user.email(), user.role(), null);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
