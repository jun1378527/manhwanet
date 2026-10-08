import "server-only";

import { headers } from "next/headers";

import { apiBaseUrl } from "../api/client";
import { parseApiBody } from "../api/parse-json";
import { SESSION_COOKIE_NAMES, pickCookieHeader } from "../auth/cookies";
import { type MyReview, type PublicReviewPage, REVIEWS_PAGE_SIZE, toMyReviewResult, toPublicReviewPage } from "./model";

const TIMEOUT_MS = 3_000;

/**
 * 서버 렌더링용 내 리뷰 상태.
 * - anonymous: 세션 쿠키가 없거나 401
 * - unavailable: 연결 실패·시간 초과·예상하지 못한 응답. 비로그인으로 바꾸지 않는다.
 */
export type MyReviewState =
  | { status: "loaded"; review: MyReview | null }
  | { status: "anonymous" }
  | { status: "unavailable" };

/** 서버 렌더링용 공개 목록 상태 */
export type ReviewPageState = { status: "loaded"; page: PublicReviewPage } | { status: "unavailable" };

async function readData(response: Response): Promise<unknown> {
  try {
    const body = parseApiBody(await response.text()) as { success?: unknown; data?: unknown };
    return response.ok && typeof body === "object" && body !== null && body.success === true ? body.data : undefined;
  } catch {
    return undefined;
  }
}

/**
 * GET /me/reviews/{workId}. 세션 쿠키만 전달하고 `no-store` 로 요청한다.
 * 사용자별 응답이므로 캐시하거나 요청 사이에 공유하지 않는다.
 */
export async function getMyReview(workId: string): Promise<MyReviewState> {
  const cookie = pickCookieHeader((await headers()).get("cookie"), SESSION_COOKIE_NAMES);
  if (cookie === null) {
    return { status: "anonymous" };
  }
  let response: Response;
  try {
    response = await fetch(`${apiBaseUrl()}/me/reviews/${encodeURIComponent(workId)}`, {
      cache: "no-store",
      headers: { Accept: "application/json", Cookie: cookie },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (error) {
    console.error(`[review] GET /me/reviews failed: ${error instanceof Error ? error.name : "unknown error"}`);
    return { status: "unavailable" };
  }
  if (response.status === 401) {
    return { status: "anonymous" };
  }
  const result = toMyReviewResult(await readData(response));
  if (result === null || result.workId !== workId) {
    console.error(`[review] GET /me/reviews returned ${response.status}`);
    return { status: "unavailable" };
  }
  return { status: "loaded", review: result.review };
}

/**
 * GET /works/{workId}/reviews. 공개 응답이므로 쿠키를 보내지 않는다(로그인 여부와 관계없이 같은 응답).
 * @param apiPage 0부터 시작
 */
export async function getReviewPage(workId: string, apiPage: number): Promise<ReviewPageState> {
  const query = new URLSearchParams({ page: String(apiPage), size: String(REVIEWS_PAGE_SIZE) });
  let response: Response;
  try {
    response = await fetch(`${apiBaseUrl()}/works/${encodeURIComponent(workId)}/reviews?${query}`, {
      cache: "no-store",
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (error) {
    console.error(`[review] GET /works/{id}/reviews failed: ${error instanceof Error ? error.name : "unknown error"}`);
    return { status: "unavailable" };
  }
  const page = toPublicReviewPage(await readData(response));
  if (page === null) {
    console.error(`[review] GET /works/{id}/reviews returned ${response.status}`);
    return { status: "unavailable" };
  }
  return { status: "loaded", page };
}
