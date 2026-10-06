package com.manhwanet.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 가입 요청. role 등 정의되지 않은 필드는 무시되며 가입 계정은 항상 USER 다.
 * 정규화가 필요한 규칙(이메일 형식, 닉네임·비밀번호 길이, 차단 목록)은 UserService 에서 검증한다.
 * Size(max) 는 정규화 비용을 제한하기 위한 원시 입력 상한이다.
 */
public record SignupRequest(
        @NotNull(message = "이메일은 필수입니다.") @Size(max = 320, message = "이메일이 너무 깁니다.") String email,
        @NotNull(message = "비밀번호는 필수입니다.") @Size(max = 1024, message = "비밀번호가 너무 깁니다.") String password,
        @NotNull(message = "닉네임은 필수입니다.") @Size(max = 200, message = "닉네임이 너무 깁니다.") String nickname) {

    @Override
    public String toString() {
        return "SignupRequest[email=" + email + ", nickname=" + nickname + "]";
    }
}
