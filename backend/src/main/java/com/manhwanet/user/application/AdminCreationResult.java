package com.manhwanet.user.application;

public enum AdminCreationResult {
    /** 새 ADMIN 계정을 만들었다. */
    CREATED,
    /** 같은 이메일의 ADMIN 이 이미 있어 아무것도 바꾸지 않았다. */
    ALREADY_ADMIN
}
