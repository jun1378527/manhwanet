/**
 * 가입·로그인 입력의 클라이언트 검사. 백엔드 규칙과 같게 두며, 최종 판정은 항상 백엔드가 한다.
 * - 이메일: backend EmailAddresses (String.strip + Locale.ROOT 소문자, 254자, 형식)
 * - 닉네임: backend Nicknames (strip 후 2~30 코드 포인트)
 * - 비밀번호: backend Passwords/PasswordPolicy (NFKC 정규화 후 15~128 코드 포인트, trim 없음)
 *   차단 목록, 이메일·닉네임·서비스명과 같은 비밀번호는 백엔드만 검사한다.
 */

export const EMAIL_MAX_LENGTH = 254;
export const NICKNAME_MIN_LENGTH = 2;
export const NICKNAME_MAX_LENGTH = 30;
export const PASSWORD_MIN_LENGTH = 15;
export const PASSWORD_MAX_LENGTH = 128;

/** Java Pattern 의 `\s`(ASCII 공백)와 같은 문자 집합을 쓴다. */
const EMAIL_FORMAT = /^[^@ \t\n\u000B\f\r]{1,64}@[^@ \t\n\u000B\f\r.]+(\.[^@ \t\n\u000B\f\r.]+)+$/;

export type FieldName = "email" | "nickname" | "password";
export type FieldErrors = Partial<Record<FieldName, string>>;

/** Java Character.isWhitespace 와 같은 판정 (String.strip 이 지우는 문자) */
function isJavaWhitespace(char: string): boolean {
  const code = char.codePointAt(0) ?? 0;
  if ((code >= 0x09 && code <= 0x0d) || (code >= 0x1c && code <= 0x1f)) {
    return true;
  }
  if (code === 0x00a0 || code === 0x2007 || code === 0x202f) {
    return false;
  }
  return /\p{Z}/u.test(char);
}

/** Java String.strip 과 같게 앞뒤 공백을 지운다. */
export function javaStrip(value: string): string {
  const chars = [...value];
  let start = 0;
  let end = chars.length;
  while (start < end && isJavaWhitespace(chars[start])) {
    start += 1;
  }
  while (end > start && isJavaWhitespace(chars[end - 1])) {
    end -= 1;
  }
  return chars.slice(start, end).join("");
}

export function codePointLength(value: string): number {
  return [...value].length;
}

/** 백엔드와 같은 기준의 비밀번호 길이: NFKC 정규화 후 코드 포인트 수 */
export function passwordLength(password: string): number {
  return codePointLength(password.normalize("NFKC"));
}

export function validateEmail(email: string): string | undefined {
  const normalized = javaStrip(email).toLowerCase();
  if (normalized.length === 0) {
    return "이메일을 입력해 주세요.";
  }
  if (normalized.length > EMAIL_MAX_LENGTH) {
    return `이메일은 ${EMAIL_MAX_LENGTH}자 이하여야 합니다.`;
  }
  if (!EMAIL_FORMAT.test(normalized)) {
    return "이메일 형식이 올바르지 않습니다. 예: reader@example.com";
  }
  return undefined;
}

export function validateNickname(nickname: string): string | undefined {
  const length = codePointLength(javaStrip(nickname));
  if (length === 0) {
    return "닉네임을 입력해 주세요.";
  }
  if (length < NICKNAME_MIN_LENGTH || length > NICKNAME_MAX_LENGTH) {
    return `닉네임은 앞뒤 공백을 제외하고 ${NICKNAME_MIN_LENGTH}자 이상 ${NICKNAME_MAX_LENGTH}자 이하여야 합니다.`;
  }
  return undefined;
}

export function validateNewPassword(password: string): string | undefined {
  if (password.length === 0) {
    return "비밀번호를 입력해 주세요.";
  }
  const length = passwordLength(password);
  if (length < PASSWORD_MIN_LENGTH || length > PASSWORD_MAX_LENGTH) {
    return `비밀번호는 ${PASSWORD_MIN_LENGTH}자 이상 ${PASSWORD_MAX_LENGTH}자 이하여야 합니다. (현재 ${length}자)`;
  }
  return undefined;
}

export function validateSignup(input: { email: string; nickname: string; password: string }): FieldErrors {
  const errors: FieldErrors = {};
  const email = validateEmail(input.email);
  const nickname = validateNickname(input.nickname);
  const password = validateNewPassword(input.password);
  if (email) errors.email = email;
  if (nickname) errors.nickname = nickname;
  if (password) errors.password = password;
  return errors;
}

/**
 * 로그인은 형식·길이를 검사하지 않고 비어 있는지만 본다.
 * (정책이 바뀌기 전 계정도 로그인할 수 있게 하고, 실패 이유를 드러내지 않기 위해)
 */
export function validateLogin(input: { email: string; password: string }): FieldErrors {
  const errors: FieldErrors = {};
  if (javaStrip(input.email).length === 0) {
    errors.email = "이메일을 입력해 주세요.";
  }
  if (input.password.length === 0) {
    errors.password = "비밀번호를 입력해 주세요.";
  }
  return errors;
}
