/**
 * 인증 쿠키 이름과 Cookie 헤더 필터.
 * 이름은 backend application*.yml 과 AuthenticationConfig 의 설정과 같다.
 */

/** 운영(`__Host-` 접두사, Secure)과 local(HTTP 개발) 세션 쿠키 이름 */
export const SESSION_COOKIE_NAMES: ReadonlySet<string> = new Set([
  "__Host-MANHWANET_SESSION",
  "MANHWANET_SESSION",
]);

/** CookieCsrfTokenRepository 의 쿠키 이름. HttpOnly 가 아니어서 브라우저 스크립트가 읽는다. */
export const CSRF_COOKIE_NAME = "XSRF-TOKEN";

/** 변경 요청에 CSRF 쿠키 값을 그대로 담는 헤더 이름 */
export const CSRF_HEADER_NAME = "X-XSRF-TOKEN";

/** Next.js 프록시가 백엔드로 넘기는 쿠키: 세션 쿠키와 CSRF 쿠키 */
export const PROXIED_COOKIE_NAMES: ReadonlySet<string> = new Set([...SESSION_COOKIE_NAMES, CSRF_COOKIE_NAME]);

/**
 * Cookie 헤더에서 허용한 이름의 쿠키만 원문 그대로 남긴다.
 * 값은 디코딩·재인코딩하지 않는다. (세션 ID와 CSRF 토큰을 바꾸지 않기 위해)
 * 남는 쿠키가 없으면 null.
 */
export function pickCookieHeader(header: string | null | undefined, allowed: ReadonlySet<string>): string | null {
  if (!header) {
    return null;
  }
  const kept: string[] = [];
  for (const part of header.split(";")) {
    const pair = part.trim();
    const separator = pair.indexOf("=");
    if (separator <= 0) {
      continue;
    }
    const name = pair.slice(0, separator).trim();
    if (allowed.has(name)) {
      kept.push(`${name}=${pair.slice(separator + 1).trim()}`);
    }
  }
  return kept.length > 0 ? kept.join("; ") : null;
}

/** document.cookie 형식 문자열에서 쿠키 하나의 값을 읽는다. (브라우저 전용 호출부에서 사용) */
export function readCookieValue(cookieString: string, name: string): string | null {
  for (const part of cookieString.split(";")) {
    const pair = part.trim();
    const separator = pair.indexOf("=");
    if (separator > 0 && pair.slice(0, separator) === name) {
      const value = pair.slice(separator + 1);
      return value.length > 0 ? value : null;
    }
  }
  return null;
}
