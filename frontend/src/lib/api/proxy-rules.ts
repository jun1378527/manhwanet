/**
 * 같은 출처 API 프록시(`/api/v1/**` → API_BASE_URL)의 규칙.
 * 목적지는 항상 서버 설정 API_BASE_URL 아래 경로이며, 요청이 목적지를 고를 수 없다.
 * Route Handler(app/api/v1/[...path]/route.ts)가 사용하고, 단위 테스트를 위해 Next.js 의존 없이 둔다.
 */
import { PROXIED_COOKIE_NAMES, pickCookieHeader } from "../auth/cookies";

export const PROXY_PREFIX = "/api/v1";

/** 백엔드로 넘기는 요청 헤더 (Cookie 는 인증 쿠키만 따로 골라 넘긴다) */
const FORWARDED_REQUEST_HEADERS = ["accept", "accept-language", "content-type", "x-xsrf-token"] as const;

/** 브라우저로 돌려주는 응답 헤더 (Set-Cookie, Location 은 따로 처리한다) */
const FORWARDED_RESPONSE_HEADERS = ["content-type", "cache-control", "expires", "pragma", "retry-after"] as const;

/** 본문 상한. 인증·등록 API의 JSON 요청은 수 KB 이하다. */
export const MAX_REQUEST_BODY_BYTES = 64 * 1024;

const FORBIDDEN_PATH = /\/\/|\\|%2f|%5c|%00|[\u0000-\u001f\u007f]/i;

/**
 * 들어온 요청 URL을 백엔드 URL로 바꾼다. 규칙에 맞지 않으면 null.
 * - 경로는 `/api/v1/` 로 시작해야 하고, 그 뒤만 API_BASE_URL 뒤에 붙인다.
 * - `.`·`..` 세그먼트, 빈 세그먼트(`//`), 역슬래시, 인코딩된 슬래시는 거부한다.
 * - 결과가 API_BASE_URL 과 같은 출처·경로 아래인지 마지막에 다시 확인한다.
 */
export function resolveUpstreamUrl(apiBaseUrl: string, requestUrl: string): URL | null {
  let incoming: URL;
  let base: URL;
  try {
    incoming = new URL(requestUrl);
    base = new URL(apiBaseUrl.replace(/\/+$/, ""));
  } catch {
    return null;
  }

  const path = incoming.pathname;
  if (!path.startsWith(`${PROXY_PREFIX}/`)) {
    return null;
  }
  const rest = path.slice(PROXY_PREFIX.length);
  if (rest === "/" || FORBIDDEN_PATH.test(rest)) {
    return null;
  }
  for (const segment of rest.slice(1).split("/")) {
    const decoded = segment.replace(/%2e/gi, ".");
    if (decoded === "." || decoded === "..") {
      return null;
    }
  }

  let target: URL;
  try {
    target = new URL(`${base.origin}${base.pathname}${rest}${incoming.search}`);
  } catch {
    return null;
  }
  if (target.origin !== base.origin || !target.pathname.startsWith(`${base.pathname.replace(/\/$/, "")}/`)) {
    return null;
  }
  return target;
}

/** 백엔드로 보낼 요청 헤더. Host, Origin, 다른 쿠키 등은 넘기지 않는다. */
export function upstreamRequestHeaders(incoming: Headers): Headers {
  const headers = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = incoming.get(name);
    if (value !== null) {
      headers.set(name, value);
    }
  }
  const cookie = pickCookieHeader(incoming.get("cookie"), PROXIED_COOKIE_NAMES);
  if (cookie) {
    headers.set("cookie", cookie);
  }
  return headers;
}

/** Location 을 같은 출처로 해석할 때 쓰는 기준 출처. 실제 요청 출처가 아니다. */
const LOCATION_PROBE_ORIGIN = "http://location.invalid";

/** returnTo 와 같다. 역슬래시와 제어 문자는 URL 해석 전에 거절한다. */
const UNSAFE_LOCATION = /[\\\u0000-\u001f\u007f]/;

/**
 * 응답 Location 을 같은 출처 경로로 확인한다. 아니면 null.
 * returnTo 는 `/login`·`/signup`·`/api/` 를 기본 경로로 바꾸지만, 여기서는 백엔드가 보내는 `/api/v1/...` 를 유지한다.
 * - `/` 로 시작하는 경로만 본다. `//` 프로토콜 상대 URL, 스킴이 있는 절대 URL 은 제외한다.
 * - 역슬래시·제어 문자가 있으면 제외한다. `/\host` 는 URL 해석 시 다른 출처가 된다.
 * - URL 로 해석한 출처가 기준 출처와 같아야 한다. 결과의 경로·query·해시만 돌려준다.
 */
export function sameOriginLocation(value: string): string | null {
  if (!value.startsWith("/") || value.startsWith("//") || UNSAFE_LOCATION.test(value)) {
    return null;
  }
  let url: URL;
  try {
    url = new URL(value, LOCATION_PROBE_ORIGIN);
  } catch {
    return null;
  }
  if (url.origin !== LOCATION_PROBE_ORIGIN || !url.pathname.startsWith("/") || url.pathname.startsWith("//")) {
    return null;
  }
  return `${url.pathname}${url.search}${url.hash}`;
}

/**
 * 브라우저로 돌려줄 응답 헤더.
 * - Set-Cookie 는 여러 개를 각각 그대로 넘긴다. (세션·CSRF 쿠키 발급·교체·삭제)
 * - Location 은 같은 출처 경로일 때만 넘긴다. 백엔드 주소가 브라우저에 드러나지 않게 한다.
 * - 사용자별 응답이 공유 캐시에 남지 않도록 Cache-Control 이 없으면 `private, no-store` 를 붙인다.
 */
export function downstreamResponseHeaders(upstream: Headers): Headers {
  const headers = new Headers();
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const value = upstream.get(name);
    if (value !== null) {
      headers.set(name, value);
    }
  }
  for (const cookie of upstream.getSetCookie()) {
    headers.append("set-cookie", cookie);
  }
  const location = upstream.get("location");
  if (location !== null) {
    const safe = sameOriginLocation(location);
    if (safe !== null) {
      headers.set("location", safe);
    }
  }
  if (!headers.has("cache-control")) {
    headers.set("cache-control", "private, no-store");
  }
  return headers;
}

export function methodHasBody(method: string): boolean {
  return method !== "GET" && method !== "HEAD";
}

/**
 * 요청 본문을 상한까지만 읽는다. 상한을 넘으면 null.
 */
export async function readLimitedBody(body: ReadableStream<Uint8Array> | null, limit: number): Promise<Uint8Array | null> {
  if (body === null) {
    return new Uint8Array(0);
  }
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const result = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

/** 프록시가 직접 만드는 오류 응답. 백엔드 ApiResponse 형식과 같다. */
export function proxyErrorBody(code: string, message: string): string {
  return JSON.stringify({ success: false, error: { code, message } });
}
