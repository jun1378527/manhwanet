package com.manhwanet.user.application;

import com.manhwanet.user.domain.UserRole;

/**
 * 인증(auth 도메인)에 공개하는 로그인 정보. password_hash 를 포함하므로 응답이나 로그에 쓰지 않는다.
 */
public record UserCredentials(Long id, String email, String passwordHash, UserRole role) {

    @Override
    public String toString() {
        return "UserCredentials[id=" + id + ", role=" + role + "]";
    }
}
