/**
 * 브라우저에서 인증 API를 호출한다. 요청·CSRF·응답 처리는 공통 클라이언트(../api/browser)를 쓴다.
 * - 응답은 parseApiBody(무손실 파서)로 읽어 사용자 id 정밀도를 유지한다.
 * - 비밀번호·토큰은 URL, localStorage, 로그에 남기지 않는다.
 */
import { type ApiCallResult, apiRequest, readNothing } from "../api/browser";
import { type CurrentUser, type SessionState, toCurrentUser } from "./types";

export { type ApiCallResult, type ApiFailure, parseRetryAfter } from "../api/browser";

const readUser = (data: unknown) => toCurrentUser(data);

export function login(input: { email: string; password: string }): Promise<ApiCallResult<CurrentUser>> {
  return apiRequest("POST", "/auth/login", readUser, input);
}

export function signup(input: { email: string; nickname: string; password: string }): Promise<ApiCallResult<CurrentUser>> {
  return apiRequest("POST", "/auth/signup", readUser, input);
}

export function logout(): Promise<ApiCallResult<true>> {
  return apiRequest("POST", "/auth/logout", readNothing);
}

/** 현재 로그인 상태. 401 은 anonymous, 연결 실패·그 밖의 응답은 unavailable. */
export async function fetchSession(): Promise<SessionState> {
  const result = await apiRequest("GET", "/auth/me", readUser);
  if (result.kind === "ok") {
    return { status: "authenticated", user: result.data };
  }
  if (result.kind === "error" && result.status === 401) {
    return { status: "anonymous" };
  }
  return { status: "unavailable" };
}
