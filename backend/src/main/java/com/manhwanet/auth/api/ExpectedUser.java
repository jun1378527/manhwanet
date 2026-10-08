package com.manhwanet.auth.api;

import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.InvalidInputException;
import java.util.regex.Pattern;

/**
 * 화면이 예상한 사용자와 이 요청의 인증 사용자가 같은지 확인한다.
 * 리뷰(#22)와 별점이 같은 검사를 쓴다.
 *
 * <ul>
 *   <li>헤더 {@value #HEADER}: 화면을 렌더링할 때의 로그인 사용자 ID. 1 이상 Long 범위의 십진수 <b>문자열</b>
 *       (선행 0·부호·공백·소수·지수 표기 없음). 헤더 값이라 JSON 숫자 정밀도 문제가 없다.</li>
 *   <li>값은 <b>일치 확인에만</b> 쓴다. 작성자·변경 대상은 계속 세션의 {@link AuthenticatedUser#id()}로만 정한다.</li>
 *   <li>확인은 같은 요청의 인증 정보(SecurityContext)로 한다. 요청 처리 중 세션 사용자는 바뀌지 않으므로
 *       확인을 통과한 요청은 같은 사용자에게만 반영된다.</li>
 *   <li>다르면 409 {@code USER_MISMATCH}이고 아무것도 바꾸지 않는다.</li>
 * </ul>
 */
public final class ExpectedUser {

    public static final String HEADER = "X-Expected-User-Id";
    public static final String FIELD = "expectedUserId";
    public static final String REQUIRED = "요청한 화면의 사용자 ID(X-Expected-User-Id)가 필요합니다.";
    public static final String INVALID = "사용자 ID는 1 이상의 정수를 나타내는 문자열이어야 합니다.";

    private static final Pattern DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");

    private ExpectedUser() {
    }

    /**
     * @param required 변경 요청은 true. 조회는 값이 있을 때만 확인한다
     * @return 세션 사용자 ID
     */
    public static Long verify(AuthenticatedUser principal, String header, boolean required) {
        // 보안 규칙상 익명은 여기까지 오지 않지만, 규칙이 바뀌어도 익명 요청을 처리하지 않도록 한 번 더 막는다.
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (header == null) {
            if (required) {
                throw InvalidInputException.of(FIELD, REQUIRED);
            }
            return principal.id();
        }
        long expected;
        try {
            if (!DECIMAL.matcher(header).matches()) {
                throw new NumberFormatException();
            }
            expected = Long.parseLong(header);
        } catch (NumberFormatException e) {
            throw InvalidInputException.of(FIELD, INVALID);
        }
        if (principal.id() == null || principal.id() != expected) {
            throw new BusinessException(ErrorCode.USER_MISMATCH);
        }
        return principal.id();
    }
}
