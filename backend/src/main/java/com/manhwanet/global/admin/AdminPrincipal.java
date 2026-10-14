package com.manhwanet.global.admin;

import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;

/** 변경 기록에 남길 관리자 ID. 로그인 세션 사용자가 아니면(예: 다른 인증 방식) 401 로 거절한다 */
public final class AdminPrincipal {

    private AdminPrincipal() {
    }

    public static long id(AuthenticatedUser principal) {
        if (principal == null || principal.id() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return principal.id();
    }
}
