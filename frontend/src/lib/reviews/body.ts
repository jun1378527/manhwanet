/**
 * 리뷰 본문 규칙 (docs/review-design-draft.md §1.2). 백엔드 ReviewBody 와 같은 순서·같은 문구로 안내한다.
 * 화면은 입력 중 글자 수 안내와 저장 전 검사에 쓰고, 최종 판단은 백엔드가 한다.
 *
 * 1. 원시 입력 UTF-16 길이 10,000 초과 → 길이 오류
 * 2. 줄바꿈 통일: CRLF·CR → LF
 * 3. LF·TAB 을 뺀 C0 제어 문자, DEL, C1 제어 문자 → 거절 (공백 제거보다 먼저)
 * 4. 앞뒤 공백 제거: Java String.strip()(Character.isWhitespace)과 같은 문자 집합. JavaScript trim() 과 다르다
 *    (trim 은 U+00A0·U+FEFF 도 지우지만 Java 는 지우지 않는다)
 * 5. 길이: 10~2,000 유니코드 코드 포인트. 유니코드 정규화(NFC 등)는 하지 않는다
 */

export const REVIEW_MIN_LENGTH = 10;
export const REVIEW_MAX_LENGTH = 2_000;
export const REVIEW_MAX_RAW_LENGTH = 10_000;

export const BODY_REQUIRED = "리뷰 내용을 입력해 주세요.";
export const BODY_LENGTH = "리뷰는 10자 이상 2,000자 이하로 입력해 주세요.";
export const BODY_FORBIDDEN_CHARACTER = "사용할 수 없는 문자가 포함되어 있습니다.";

export type BodyCheck = { ok: true; body: string; length: number } | { ok: false; reason: string; length: number };

/** Java Character.isWhitespace 와 같은 판정 */
export function isJavaWhitespace(codeUnit: number): boolean {
  return (
    (codeUnit >= 0x09 && codeUnit <= 0x0d) ||
    (codeUnit >= 0x1c && codeUnit <= 0x20) ||
    codeUnit === 0x1680 ||
    (codeUnit >= 0x2000 && codeUnit <= 0x2006) ||
    (codeUnit >= 0x2008 && codeUnit <= 0x200a) ||
    codeUnit === 0x2028 ||
    codeUnit === 0x2029 ||
    codeUnit === 0x205f ||
    codeUnit === 0x3000
  );
}

/** Java String.strip() 과 같은 앞뒤 공백 제거 (공백 문자는 모두 BMP 이므로 UTF-16 단위로 충분하다) */
export function javaStrip(text: string): string {
  let start = 0;
  let end = text.length;
  while (start < end && isJavaWhitespace(text.charCodeAt(start))) {
    start++;
  }
  while (end > start && isJavaWhitespace(text.charCodeAt(end - 1))) {
    end--;
  }
  return text.slice(start, end);
}

/** 유니코드 코드 포인트 수 (Java codePointCount 와 같음: 짝 없는 서로게이트는 1개로 센다) */
export function codePointLength(text: string): number {
  return Array.from(text).length;
}

export function unifyLineBreaks(text: string): string {
  return text.replace(/\r\n?/g, "\n");
}

export function containsForbiddenCharacter(text: string): boolean {
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c === 0x0a || c === 0x09) {
      continue;
    }
    if (c <= 0x1f || (c >= 0x7f && c <= 0x9f)) {
      return true;
    }
  }
  return false;
}

/** 저장 전 검사. length 는 안내용 글자 수(줄바꿈 통일·앞뒤 공백 제거 후 코드 포인트) */
export function checkReviewBody(raw: string): BodyCheck {
  const unified = unifyLineBreaks(raw);
  const body = javaStrip(unified);
  const length = codePointLength(body);
  if (raw.length > REVIEW_MAX_RAW_LENGTH) {
    return { ok: false, reason: BODY_LENGTH, length };
  }
  if (containsForbiddenCharacter(unified)) {
    return { ok: false, reason: BODY_FORBIDDEN_CHARACTER, length };
  }
  if (body.length === 0) {
    return { ok: false, reason: BODY_REQUIRED, length };
  }
  if (length < REVIEW_MIN_LENGTH || length > REVIEW_MAX_LENGTH) {
    return { ok: false, reason: BODY_LENGTH, length };
  }
  return { ok: true, body, length };
}
