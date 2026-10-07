/**
 * 브라우저에서 같은 출처 `/api/v1/**`(Next.js 프록시)를 호출하는 공통 클라이언트.
 * - 응답은 parseApiBody(무손실 파서)로 읽는다. 204 등 본문이 없는 응답은 파싱하지 않는다.
 * - 변경 요청(POST·PUT·PATCH·DELETE)은 직전에 XSRF-TOKEN 쿠키를 읽어 X-XSRF-TOKEN 헤더로 보낸다.
 *   쿠키가 없으면 GET /auth/csrf 로 받고, INVALID_CSRF_TOKEN 이면 토큰을 다시 받아 한 번만 재시도한다.
 *   토큰을 메모리·저장소에 따로 두지 않으므로, 서버가 쿠키를 교체·삭제하면 다음 요청은 자동으로 새 값을 쓴다.
 * - 변경 요청은 CSRF 재시도 외에는 자동으로 다시 보내지 않는다. (연결 실패·시간 초과 시 반영 여부를 알 수 없음)
 * - 사용자별 응답이 브라우저 캐시에 남지 않도록 `cache: "no-store"` 로 요청한다.
 */
import { CSRF_COOKIE_NAME, CSRF_HEADER_NAME, readCookieValue } from "../auth/cookies";
import { parseApiBody } from "./parse-json";
import type { FieldError } from "./types";

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

export type HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

/** 응답 data 를 검사해 원하는 타입으로 바꾼다. 모양이 다르면 null (unavailable 로 처리) */
export type DataReader<T> = (data: unknown) => T | null;

type ParsedBody = {
  success?: unknown;
  data?: unknown;
  error?: { code?: unknown; message?: unknown; fieldErrors?: unknown };
};

/** 본문이 없는 성공 응답(204 등)용 */
export const readNothing: DataReader<true> = () => true;

const NO_BODY_STATUSES = new Set([204, 205, 304]);

function csrfToken(): string | null {
  return readCookieValue(document.cookie, CSRF_COOKIE_NAME);
}

function isMutation(method: HttpMethod): boolean {
  return method !== "GET";
}

async function send(method: HttpMethod, path: string, body: unknown, withCsrf: boolean): Promise<Response> {
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

/** Retry-After 초 값. 없거나 형식이 다르면 null. */
export function parseRetryAfter(value: string | null): number | null {
  if (value === null || !/^\d{1,9}$/.test(value.trim())) {
    return null;
  }
  return Number(value.trim());
}

/** 응답을 결과 값으로 바꾼다. 204·205·304 는 본문을 읽거나 파싱하지 않는다. */
export async function toResult<T>(response: Response, readData: DataReader<T>): Promise<ApiCallResult<T>> {
  if (response.status === 502 || response.status === 503 || response.status === 504) {
    return { kind: "unavailable" };
  }

  let parsed: ParsedBody | undefined;
  if (!NO_BODY_STATUSES.has(response.status)) {
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

/**
 * 같은 출처 API 요청. GET 은 CSRF 없이, 그 밖의 메서드는 CSRF 토큰을 붙여 보낸다.
 * 변경 요청이 INVALID_CSRF_TOKEN 으로 거절되면 토큰을 다시 받아 한 번만 재시도한다.
 * (CSRF 거절은 서버가 요청을 처리하지 않았다는 뜻이므로 재시도해도 중복 반영되지 않는다)
 */
export async function apiRequest<T>(
  method: HttpMethod,
  path: string,
  readData: DataReader<T>,
  body?: unknown,
): Promise<ApiCallResult<T>> {
  try {
    if (!isMutation(method)) {
      return await toResult(await send(method, path, undefined, false), readData);
    }
    if (csrfToken() === null && !(await fetchCsrfToken())) {
      return { kind: "unavailable" };
    }
    const first = await toResult(await send(method, path, body, true), readData);
    if (first.kind !== "error" || first.code !== "INVALID_CSRF_TOKEN") {
      return first;
    }
    if (!(await fetchCsrfToken())) {
      return first;
    }
    return await toResult(await send(method, path, body, true), readData);
  } catch {
    return { kind: "unavailable" };
  }
}
