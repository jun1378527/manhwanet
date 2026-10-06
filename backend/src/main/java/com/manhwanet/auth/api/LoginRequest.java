package com.manhwanet.auth.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 로그인 요청. 비밀번호는 공백만으로도 구성될 수 있으므로 NotBlank 가 아니라 NotEmpty 로 검사하고, 자르거나 trim 하지 않는다.
 * Size(max) 는 해시 계산 비용을 제한하는 원시 입력 상한이다.
 */
public record LoginRequest(
        @NotNull(message = "이메일은 필수입니다.") @Size(max = 320, message = "이메일이 너무 깁니다.") String email,
        @NotEmpty(message = "비밀번호는 필수입니다.") @Size(max = 1024, message = "비밀번호가 너무 깁니다.") String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + "]";
    }
}
