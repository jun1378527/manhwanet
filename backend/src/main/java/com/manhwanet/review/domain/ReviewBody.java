package com.manhwanet.review.domain;

/**
 * 리뷰 본문 정규화·검증 (docs/review-design-draft.md §1.2).
 *
 * <ol>
 *   <li>원시 입력 상한(UTF-16 길이) — 정규화 비용 제한</li>
 *   <li>줄바꿈 통일: CRLF·CR → LF</li>
 *   <li>금지 문자: LF·TAB 을 뺀 C0 제어 문자, DEL, C1 제어 문자. 공백 제거보다 먼저 검사해 앞뒤의 제어 문자도 거절한다</li>
 *   <li>앞뒤 공백 제거 ({@link String#strip()}, 유니코드 공백 포함)</li>
 *   <li>길이: 10~2,000 유니코드 코드 포인트</li>
 * </ol>
 * 유니코드 정규화(NFC 등)는 하지 않는다. 본문 가운데의 공백·빈 줄·탭은 그대로 둔다.
 */
public final class ReviewBody {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 2_000;
    /** 정규화 전 원시 입력 상한 (UTF-16 코드 단위) */
    public static final int MAX_RAW_LENGTH = 10_000;

    public static final String REQUIRED = "리뷰 내용을 입력해 주세요.";
    public static final String NOT_TEXT = "리뷰 내용은 문자열이어야 합니다.";
    public static final String LENGTH = "리뷰는 " + MIN_LENGTH + "자 이상 2,000자 이하로 입력해 주세요.";
    public static final String FORBIDDEN_CHARACTER = "사용할 수 없는 문자가 포함되어 있습니다.";

    private ReviewBody() {
    }

    /**
     * @param raw 요청의 원문 문자열 (null 이면 누락)
     * @return 저장할 정규화된 본문
     * @throws IllegalArgumentException 규칙 위반. 메시지는 사용자에게 보여 줄 필드 오류다
     */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException(REQUIRED);
        }
        if (raw.length() > MAX_RAW_LENGTH) {
            throw new IllegalArgumentException(LENGTH);
        }
        String unified = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (containsForbiddenCharacter(unified)) {
            throw new IllegalArgumentException(FORBIDDEN_CHARACTER);
        }
        String body = unified.strip();
        if (body.isEmpty()) {
            throw new IllegalArgumentException(REQUIRED);
        }
        int length = body.codePointCount(0, body.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException(LENGTH);
        }
        return body;
    }

    /** 코드 포인트 길이 */
    public static int length(String body) {
        return body.codePointCount(0, body.length());
    }

    static boolean containsForbiddenCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t') {
                continue;
            }
            // C0(U+0000~U+001F), DEL(U+007F), C1(U+0080~U+009F). 서로게이트는 이 범위에 없으므로 char 단위로 충분하다.
            if (c <= 0x1F || (c >= 0x7F && c <= 0x9F)) {
                return true;
            }
        }
        return false;
    }
}
