/**
 * 브라우저에서 인증 API를 호출한다. 항상 같은 출처 `/api/v1/**` (Next.js 프록시)로만 요청한다.
 * - 응답은 parseApiBody(무손실 파서)로 읽어 사용자 id 정밀도를 유지한다.
 * - CSRF: 변경 요청 직전에 XSRF-TOKEN 쿠키를 읽어 X-XSRF-TOKEN 헤더로 보낸다. 토큰을 메모리·저장소에 따로 두지 않으므로,
 *   로그인·가입·로그아웃 응답이 쿠키를 교체·삭제하면 다음 요청은 자동으로 새 값을 쓴다.
 *   쿠키가 없으면 GET /auth/csrf 로 받고, INVALID_CSRF_TOKEN 이면 토큰을 다시 받아 한 번만 재시도한다.
 * - 비밀번호·토큰은 URL, localStorage, 로그에 남기지 않는다.
 */
import { parseApiBody } from "../api/parse-json";
import type { FieldError } from "../api/types";
import { CSRF_COOKIE_NAME, CSRF_HEADER_NAME, readCookieValue } from "./cookies";
import { type CurrentUser, type SessionState, toCurrentUser } from "./types";

const API_PREFIX = "/api/v1";
const REQUEST_TIMEOUT_MS = 15_000;

export type ApiFailure = {
  kind: "error";
  status: number;
  code: string | undefined;
  message: string | undefined;
  fieldErrors: FieldError[];
  retryAfterSeconds: number | null;
};

export type ApiCallResult<T> =
  | { kind: "ok"; status: number; data: T }
  | ApiFailure
  /** 연결 실패, 시간 초과, 502·503·504, JSON 이 아닌 응답 */
  | { kind: "unavailable" };

type ParsedBody = {
  success?: unknown;
  data?: unknown;
  error?: { code?: unknown; message?: unknown; fieldErrors?: unknown };
};

function csrfToken(): string | null {
  return readCookieValue(document.cookie, CSRF_COOKIE_NAME);
}

async function send(method: string, path: string, body: unknown, withCsrf: boolean): Promise<Response> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (withCsrf) {
    const token = csrfToken();
    if (token !== null) {
      headers[CSRF_HEADER_NAME] = token;
    }
  }
  return fetch(`${API_PREFIX}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: "same-origin",
    cache: "no-store",
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
}

/** XSRF-TOKEN 쿠키를 받는다. 성공 여부만 돌려준다. */
async function fetchCsrfToken(): Promise<boolean> {
  try {
    const response = await send("GET", "/auth/csrf", undefined, false);
    return response.ok && csrfToken() !== null;
  } catch {
    return false;
  }
}

async function toResult<T>(response: Response, readData: (data: unknown) => T | null): Promise<ApiCallResult<T>> {
  if (response.status === 502 || response.status === 503 || response.status === 504) {
    return { kind: "unavailable" };
  }

  let parsed: ParsedBody | undefined;
  if (response.status !== 204) {
    const text = await response.text();
    if (text.length > 0) {
      try {
        parsed = parseApiBody(text) as ParsedBody;
      } catch {
        return { kind: "unavailable" };
      }
    }
  }

  if (response.ok) {
    const data = readData(parsed?.data);
    if (data === null) {
      return { kind: "unavailable" };
    }
    return { kind: "ok", status: response.status, data };
  }

  if (parsed === undefined || typeof parsed !== "object" || parsed === null) {
    return { kind: "unavailable" };
  }
  const error = parsed.error ?? {};
  const fieldErrors = Array.isArray(error.fieldErrors)
    ? (error.fieldErrors as unknown[]).filter(
        (item): item is FieldError =>
          typeof item === "object" &&
          item !== null &&
          typeof (item as FieldError).field === "string" &&
          typeof (item as FieldError).reason === "string",
      )
    : [];
  return {
    kind: "error",
    status: response.status,
    code: typeof error.code === "string" ? error.code : undefined,
    message: typeof error.message === "string" ? error.message : undefined,
    fieldErrors,
    retryAfterSeconds: parseRetryAfter(response.headers.get("retry-after")),
  };
}

/** Retry-After 초 값. 없거나 형식이 다르면 null. */
export function parseRetryAfter(value: string | null): number | null {
  if (value === null || !/^\d{1,9}$/.test(value.trim())) {
    return null;
  }
  return Number(value.trim());
}

/**
 * CSRF 토큰이 필요한 변경 요청. 토큰이 없으면 먼저 받고, INVALID_CSRF_TOKEN 이면 한 번만 다시 받아 재시도한다.
 */
async function mutate<T>(path: string, body: unknown, readData: (data: unknown) => T | null): Promise<ApiCallResult<T>> {
  try {
    if (csrfToken() === null && !(await fetchCsrfToken())) {
      return { kind: "unavailable" };
    }
    const first = await toResult(await send("POST", path, body, true), readData);
    if (first.kind !== "error" || first.code !== "INVALID_CSRF_TOKEN") {
      return first;
    }
    if (!(await fetchCsrfToken())) {
      return first;
    }
    return await toResult(await send("POST", path, body, true), readData);
  } catch {
    return { kind: "unavailable" };
  }
}

const readUser = (data: unknown) => toCurrentUser(data);
const readNothing = () => true as const;

export function login(input: { email: string; password: string }): Promise<ApiCallResult<CurrentUser>> {
  return mutate("/auth/login", input, readUser);
}

export function signup(input: { email: string; nickname: string; password: string }): Promise<ApiCallResult<CurrentUser>> {
  return mutate("/auth/signup", input, readUser);
}

export function logout(): Promise<ApiCallResult<true>> {
  return mutate("/auth/logout", undefined, readNothing);
}

/** 현재 로그인 상태. 401 은 anonymous, 연결 실패·그 밖의 응답은 unavailable. */
export async function fetchSession(): Promise<SessionState> {
  try {
    const result = await toResult(await send("GET", "/auth/me", undefined, false), readUser);
    if (result.kind === "ok") {
      return { status: "authenticated", user: result.data };
    }
    if (result.kind === "error" && result.status === 401) {
      return { status: "anonymous" };
    }
    return { status: "unavailable" };
  } catch {
    return { status: "unavailable" };
  }
}
