package com.manhwanet.auth.api;

import com.manhwanet.auth.application.AuthService;
import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.user.application.UserService;
import com.manhwanet.user.dto.SignupRequest;
import com.manhwanet.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API. 로그아웃(POST /api/v1/auth/logout)은 Spring Security LogoutFilter 가 처리한다.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final AuthenticationSessionHandler sessionHandler;

    public AuthController(AuthService authService, UserService userService,
            AuthenticationSessionHandler sessionHandler) {
        this.authService = authService;
        this.userService = userService;
        this.sessionHandler = sessionHandler;
    }

    /**
     * CSRF 토큰 쿠키(XSRF-TOKEN)를 발급한다. 토큰 값은 쿠키로만 전달하고 본문은 없다.
     * 클라이언트는 쿠키 값을 X-XSRF-TOKEN 헤더에 그대로 넣어 변경 요청을 보낸다.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken csrfToken) {
        csrfToken.getToken();
        return ResponseEntity.noContent().build();
    }

    /** 가입 후 자동 로그인. 가입 계정은 항상 USER 다. */
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<UserResponse>> signup(@Valid @RequestBody SignupRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        UserResponse user = userService.signup(request);
        sessionHandler.establish(authService.authenticated(user), httpRequest, httpResponse);
        return ResponseEntity.created(URI.create("/api/v1/auth/me")).body(ApiResponse.success(user));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<UserResponse>> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = authService.authenticate(request.email(), request.password());
        sessionHandler.establish(authentication, httpRequest, httpResponse);
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success(userService.getUser(principal.id())));
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.success(userService.getUser(principal.id()));
    }
}
