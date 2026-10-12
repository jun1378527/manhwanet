package com.manhwanet.global.error;

/**
 * 관리자 수정의 버전 충돌(409). 응답 error.currentVersion 에 현재 버전을 담아
 * 관리자가 최신 상태를 다시 읽게 한다. 이 예외가 나면 아무것도 바뀌지 않는다.
 */
public class VersionConflictException extends BusinessException {

    private final long currentVersion;

    public VersionConflictException(ErrorCode errorCode, long currentVersion) {
        super(errorCode);
        this.currentVersion = currentVersion;
    }

    public long getCurrentVersion() {
        return currentVersion;
    }
}
