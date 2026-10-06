package com.manhwanet.user.domain;

import java.text.Normalizer;

/**
 * 비밀번호 유니코드 정규화.
 *
 * <p>NIST SP 800-63B-4 권고에 따라 해시·검증·정책 검사 전에 NFKC 로 정규화한다.
 * 가입·로그인·관리자 생성 모두 같은 정규화를 거치므로 입력 방식(조합형/완성형, 전각/반각)이 달라도 같은 비밀번호로 취급된다.
 * 앞뒤 공백 제거(trim)나 길이 초과분 잘라내기는 하지 않는다.
 */
public final class Passwords {

    private Passwords() {
    }

    public static String normalize(String raw) {
        return raw == null ? null : Normalizer.normalize(raw, Normalizer.Form.NFKC);
    }

    public static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }
}
