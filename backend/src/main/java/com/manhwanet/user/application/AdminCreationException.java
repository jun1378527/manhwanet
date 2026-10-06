package com.manhwanet.user.application;

/**
 * 관리자 생성 실패 (입력 오류, 같은 이메일의 일반 사용자 존재 등). 메시지에 비밀번호를 넣지 않는다.
 */
public class AdminCreationException extends RuntimeException {

    public AdminCreationException(String message) {
        super(message);
    }
}
