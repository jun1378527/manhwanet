import "server-only";

import { parseApiBody } from "./parse-json";
import type { ApiResponse } from "./types";

/**
 * 서버(Next.js Server Component)에서만 백엔드 API를 호출한다.
 * 브라우저가 백엔드에 직접 요청하지 않으므로 백엔드 CORS 설정이 필요 없다.
 *
 * 캐시 정책: 모든 조회는 `cache: "no-store"`로 요청마다 백엔드에서 새로 읽는다.
 * 작품·작가가 등록되면 다음 요청부터 바로 보이며, 빌드 시점 데이터가 남지 않는다.
 */

/** 백엔드 기본 주소. local 프로필 백엔드는 127.0.0.1 에만 바인딩되므로 IPv4 주소를 기본값으로 쓴다. */
const DEFAULT_API_BASE_URL = "http://127.0.0.1:8080/api/v1";
const REQUEST_TIMEOUT_MS = 5_000;

export function apiBaseUrl(): string {
  return (process.env.API_BASE_URL ?? DEFAULT_API_BASE_URL).replace(/\/+$/, "");
}

export type ApiResult<T> =
  | { ok: true; data: T }
  | { ok: false; reason: "not-found" | "unavailable"; status?: number; code?: string };

/**
 * GET 요청을 보내고 결과를 값으로 돌려준다. (예상 가능한 실패는 throw 하지 않는다)
 * - 404: not-found
 * - 연결 실패, 시간 초과, 5xx, 형식이 다른 응답, 그 밖의 오류: unavailable
 */
export async function apiGet<T>(path: string): Promise<ApiResult<T>> {
  let response: Response;
  try {
    response = await fetch(`${apiBaseUrl()}${path}`, {
      cache: "no-store",
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch (error) {
    console.error(`[api] GET ${path} failed: ${describe(error)}`);
    return { ok: false, reason: "unavailable" };
  }

  let body: ApiResponse<T> | undefined;
  try {
    body = parseApiBody(await response.text()) as ApiResponse<T>;
  } catch {
    body = undefined;
  }

  if (response.ok && body?.success === true) {
    return { ok: true, data: body.data };
  }

  const code = body && body.success === false ? body.error.code : undefined;
  if (response.status === 404) {
    return { ok: false, reason: "not-found", status: 404, code };
  }
  console.error(`[api] GET ${path} returned ${response.status}${code ? ` (${code})` : ""}`);
  return { ok: false, reason: "unavailable", status: response.status, code };
}

function describe(error: unknown): string {
  if (error instanceof Error) {
    const cause = (error as Error & { cause?: unknown }).cause;
    return cause instanceof Error ? `${error.name}: ${cause.message}` : `${error.name}: ${error.message}`;
  }
  return String(error);
}
