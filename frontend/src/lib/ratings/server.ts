import "server-only";

import { headers } from "next/headers";

import { apiBaseUrl } from "../api/client";
import { parseApiBody } from "../api/parse-json";
import { SESSION_COOKIE_NAMES, pickCookieHeader } from "../auth/cookies";
import { type MyRating, toMyRating } from "./score";

const TIMEOUT_MS = 3_000;

/**
 * 서버 렌더링용 내 별점 상태.
 * - anonymous: 세션 쿠키가 없거나 401 (세션 만료 포함)
 * - unavailable: 연결 실패·시간 초과·예상하지 못한 응답. 비로그인으로 바꾸지 않는다.
 */
export type MyRatingState =
  | { status: "loaded"; rating: MyRating }
  | { status: "anonymous" }
  | { status: "unavailable" };

/**
 * GET /me/ratings/{workId} 를 고정된 API_BASE_URL 로 호출한다.
 * 세션 쿠키만 전달하고 `no-store` 로 요청한다. 사용자별 응답이므로 캐시하거나 요청 사이에 공유하지 않는다.
 */
export async function getMyRating(workId: string): Promise<MyRatingState> {
  const cookie = pickCookieHeader((await headers()).get("cookie"), SESSION_COOKIE_NAMES);
  if (cookie === null) {
    return { status: "anonymous" };
  }

  let response: Response;
  try {
    response = await fetch(`${apiBaseUrl()}/me/ratings/${encodeURIComponent(workId)}`, {
      cache: "no-store",
      headers: { Accept: "application/json", Cookie: cookie },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (error) {
    console.error(`[rating] GET /me/ratings failed: ${error instanceof Error ? error.name : "unknown error"}`);
    return { status: "unavailable" };
  }

  if (response.status === 401) {
    return { status: "anonymous" };
  }

  let body: unknown;
  try {
    body = parseApiBody(await response.text());
  } catch {
    body = undefined;
  }
  const rating =
    response.ok && typeof body === "object" && body !== null && (body as { success?: unknown }).success === true
      ? toMyRating((body as { data?: unknown }).data)
      : null;
  if (rating === null || rating.workId !== workId) {
    console.error(`[rating] GET /me/ratings returned ${response.status}`);
    return { status: "unavailable" };
  }
  return { status: "loaded", rating };
}
