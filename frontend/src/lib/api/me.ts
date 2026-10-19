import "server-only";

import { headers } from "next/headers";

import { SESSION_COOKIE_NAMES, pickCookieHeader } from "@/lib/auth/cookies";
import {
  type ActivitySummary,
  ME_PAGE_SIZE,
  type MeTab,
  type MyRatingItem,
  type MyReviewItem,
  type Page,
  toActivitySummary,
  toMyRatingItem,
  toMyReviewItem,
  toPage,
} from "@/lib/me/model";

import { apiBaseUrl } from "./client";
import { parseApiBody } from "./parse-json";

const TIMEOUT_MS = 4_000;

/**
 * 서버 렌더링용 내 활동 조회 결과.
 * - anonymous: 세션 쿠키가 없거나 401
 * - switched: 화면을 그리는 사이 세션 사용자가 바뀜(409 USER_MISMATCH). 이전 사용자의 기록을 보여 주지 않는다
 * - unavailable: 연결 실패·시간 초과·예상하지 못한 응답
 */
export type MeResult<T> =
  | { status: "loaded"; data: T }
  | { status: "anonymous" }
  | { status: "switched" }
  | { status: "unavailable" };

/**
 * 세션 쿠키만 고정된 API_BASE_URL 로 전달하고 `no-store` 로 요청한다. 사용자별 응답이므로 캐시하거나 요청 사이에 공유하지 않는다.
 * X-Expected-User-Id 로 이 화면을 그리는 사용자(세션 확인 결과)를 보내, 그 사이 계정이 바뀌었으면 서버가 409 로 거절하게 한다.
 */
async function meGet<T>(path: string, expectedUserId: string, read: (data: unknown) => T | null): Promise<MeResult<T>> {
  const cookie = pickCookieHeader((await headers()).get("cookie"), SESSION_COOKIE_NAMES);
  if (cookie === null) {
    return { status: "anonymous" };
  }
  let response: Response;
  try {
    response = await fetch(`${apiBaseUrl()}${path}`, {
      cache: "no-store",
      headers: { Accept: "application/json", Cookie: cookie, "X-Expected-User-Id": expectedUserId },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (error) {
    console.error(`[me] GET ${path.split("?")[0]} failed: ${error instanceof Error ? error.name : "unknown error"}`);
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
  const ok = response.ok && typeof body === "object" && body !== null && (body as { success?: unknown }).success === true;
  if (!ok) {
    const code = (body as { error?: { code?: unknown } } | undefined)?.error?.code;
    if (response.status === 409 && code === "USER_MISMATCH") {
      return { status: "switched" };
    }
    console.error(`[me] GET ${path.split("?")[0]} returned ${response.status}`);
    return { status: "unavailable" };
  }
  const data = read((body as { data?: unknown }).data);
  if (data === null) {
    console.error(`[me] GET ${path.split("?")[0]} returned an unexpected shape`);
    return { status: "unavailable" };
  }
  return { status: "loaded", data };
}

export function getMyActivitySummary(expectedUserId: string): Promise<MeResult<ActivitySummary>> {
  return meGet("/me/activity", expectedUserId, toActivitySummary);
}

export function getMyActivityPage(
  tab: MeTab,
  page: number,
  expectedUserId: string,
): Promise<MeResult<Page<MyRatingItem> | Page<MyReviewItem>>> {
  const query = new URLSearchParams({ page: String(page - 1), size: String(ME_PAGE_SIZE) });
  return tab === "ratings"
    ? meGet(`/me/ratings?${query}`, expectedUserId, (d) => toPage(d, toMyRatingItem))
    : meGet(`/me/reviews?${query}`, expectedUserId, (d) => toPage(d, toMyReviewItem));
}
