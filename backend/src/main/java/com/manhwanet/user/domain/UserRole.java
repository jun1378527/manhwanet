package com.manhwanet.user.domain;

/**
 * 사용자 권한. 가입으로 생성되는 계정은 항상 USER 이며, ADMIN 은 별도 생성 명령으로만 만든다.
 */
public enum UserRole {
    USER,
    ADMIN;

    /** Spring Security 권한 이름 (ROLE_ 접두사) */
    public String authority() {
        return "ROLE_" + name();
    }
}
