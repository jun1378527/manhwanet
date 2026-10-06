package com.manhwanet.user.cli;

/**
 * 관리자 비밀번호 입력 방법. 명령행 인자로는 받지 않는다.
 */
@FunctionalInterface
public interface AdminPasswordSource {

    /** @return 입력된 비밀번호. 입력 수단이 없으면 IllegalStateException */
    String read();
}
