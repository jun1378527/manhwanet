/**
 * 로그인·가입 후 돌아갈 주소(returnTo). 같은 사이트의 내부 경로만 허용한다.
 */

export const DEFAULT_RETURN_TO = "/works";

const MAX_RETURN_TO_LENGTH = 2048;
const PROBE_ORIGIN = "http://return-to.invalid";
/** 돌아가면 다시 로그인 화면이 되거나 페이지가 아닌 경로 */
const EXCLUDED_PREFIXES = ["/login", "/signup", "/api/"];

type SearchParamValue = string | string[] | undefined;

/**
 * returnTo 값을 검사해 안전한 내부 경로를 돌려준다. 허용하지 않는 값이면 `/works`.
 * - `/` 로 시작하는 경로만 허용. `//host`, `/\host`, 스킴(`https:`, `javascript:`) 거부
 * - 제어 문자·역슬래시가 있으면 거부 (브라우저가 `\` 를 `/` 로 바꾸는 경우 대비)
 * - URL 해석 결과가 같은 출처인지 다시 확인하고, 정규화된 경로·쿼리·해시만 사용
 * - `/login`, `/signup`, `/api/` 아래는 기본값으로 바꾼다
 */
export function safeReturnTo(value: SearchParamValue | null): string {
  if (typeof value !== "string" || value.length === 0 || value.length > MAX_RETURN_TO_LENGTH) {
    return DEFAULT_RETURN_TO;
  }
  if (!value.startsWith("/") || value.startsWith("//") || /[\\\u0000-\u001f\u007f]/.test(value)) {
    return DEFAULT_RETURN_TO;
  }

  let url: URL;
  try {
    url = new URL(value, PROBE_ORIGIN);
  } catch {
    return DEFAULT_RETURN_TO;
  }
  if (url.origin !== PROBE_ORIGIN || url.pathname.startsWith("//")) {
    return DEFAULT_RETURN_TO;
  }

  const path = url.pathname;
  const excluded = EXCLUDED_PREFIXES.some((prefix) =>
    prefix.endsWith("/") ? path.startsWith(prefix) : path === prefix || path.startsWith(`${prefix}/`),
  );
  if (excluded) {
    return DEFAULT_RETURN_TO;
  }
  return `${path}${url.search}${url.hash}`;
}

function withReturnTo(base: string, returnTo: string, extra?: Record<string, string>): string {
  const params = new URLSearchParams();
  const safe = safeReturnTo(returnTo);
  if (safe !== DEFAULT_RETURN_TO) {
    params.set("returnTo", safe);
  }
  for (const [key, value] of Object.entries(extra ?? {})) {
    params.set(key, value);
  }
  const query = params.toString();
  return query ? `${base}?${query}` : base;
}

export function loginHref(returnTo: string, options?: { expired?: boolean }): string {
  return withReturnTo("/login", returnTo, options?.expired ? { reason: "expired" } : undefined);
}

export function signupHref(returnTo: string): string {
  return withReturnTo("/signup", returnTo);
}

/**
 * 로그인·가입 화면에 보여 줄 "돌아갈 곳" 이름. 주소를 그대로 보이지 않고 화면 종류만 알려 준다.
 * value 는 safeReturnTo 로 검증한 내부 경로여야 한다.
 */
export function describeReturnTo(value: string): string {
  const path = value.split(/[?#]/, 1)[0];
  if (path === "/") return "대문";
  if (path === "/works") return value === "/works" ? "작품 목록" : "보던 작품 찾기 결과";
  if (/^\/works\/[^/]+$/.test(path)) return "보던 작품 화면";
  return "보던 화면";
}
