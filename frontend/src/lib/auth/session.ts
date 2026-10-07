import "server-only";

import { headers } from "next/headers";
import { cache } from "react";

import { apiBaseUrl } from "../api/client";
import { parseApiBody } from "../api/parse-json";
import { SESSION_COOKIE_NAMES, pickCookieHeader } from "./cookies";
import { type SessionState, toCurrentUser } from "./types";

const SESSION_TIMEOUT_MS = 3_000;

/**
 * 서버 렌더링 중 현재 사용자를 백엔드 GET /auth/me 로 확인한다.
 * - 요청의 세션 쿠키만 고정된 API_BASE_URL 로 전달한다. (CSRF 쿠키 등 다른 쿠키는 보내지 않음)
 * - 세션 쿠키가 없으면 백엔드를 호출하지 않고 anonymous.
 * - `cache: "no-store"` 이고, React cache 는 한 요청 안에서만 결과를 공유한다. (사용자 사이 공유 없음)
 * - 연결 실패·시간 초과·5xx·형식 오류는 unavailable 이다. 로그아웃 상태로 바꾸지 않는다.
 */
export const getSession = cache(async (): Promise<SessionState> => {
  const cookie = pickCookieHeader((await headers()).get("cookie"), SESSION_COOKIE_NAMES);
  if (cookie === null) {
    return { status: "anonymous" };
  }

  let response: Response;
  try {
    response = await fetch(`${apiBaseUrl()}/auth/me`, {
      cache: "no-store",
      headers: { Accept: "application/json", Cookie: cookie },
      signal: AbortSignal.timeout(SESSION_TIMEOUT_MS),
    });
  } catch (error) {
    console.error(`[auth] GET /auth/me failed: ${error instanceof Error ? error.name : "unknown error"}`);
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
  const user =
    response.ok && typeof body === "object" && body !== null && (body as { success?: unknown }).success === true
      ? toCurrentUser((body as { data?: unknown }).data)
      : null;
  if (user === null) {
    console.error(`[auth] GET /auth/me returned ${response.status}`);
    return { status: "unavailable" };
  }
  return { status: "authenticated", user };
});
