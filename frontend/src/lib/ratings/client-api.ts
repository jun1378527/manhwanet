/**
 * 브라우저에서 별점 API를 호출한다. 같은 출처 프록시·CSRF 처리는 공통 클라이언트(../api/browser)를 쓴다.
 * - 내 별점: GET·PUT·DELETE /api/v1/me/ratings/{workId} (DELETE 성공은 204, 본문 없음)
 * - 화면을 렌더링한 사용자 ID를 X-Expected-User-Id 로 보낸다. 서버가 같은 요청의 세션 사용자와 다르면
 *   409 USER_MISMATCH 로 거절한다. 별점 소유자는 서버가 세션에서 정한다.
 * - 공개 집계: GET /api/v1/works/{workId} 의 data.rating
 */
import { type ApiCallResult, apiRequest, readNothing } from "../api/browser";
import type { RatingSummary } from "../api/types";
import { type MyRating, toMyRating, toRatingSummary } from "./score";

const LONG_MAX = BigInt("9223372036854775807");

function isExpectedUserId(value: string): boolean {
  return /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= LONG_MAX;
}

function myRatingPath(workId: string): string {
  return `/me/ratings/${encodeURIComponent(workId)}`;
}

/** 예상 사용자 ID 는 1 이상 Long 범위의 십진수 문자열이어야 한다. 아니면 요청을 만들지 않는다 */
function expectedUser(viewerId: string): { expectedUserId: string } {
  if (!isExpectedUserId(viewerId)) {
    throw new Error("invalid expected user id");
  }
  return { expectedUserId: viewerId };
}

export function fetchMyRating(workId: string, viewerId: string): Promise<ApiCallResult<MyRating>> {
  return apiRequest("GET", myRatingPath(workId), toMyRating, undefined, expectedUser(viewerId));
}

/** 등록·수정. score 는 JSON 숫자로 보낸다(문자열은 백엔드가 거절). */
export function saveMyRating(workId: string, viewerId: string, score: number): Promise<ApiCallResult<MyRating>> {
  return apiRequest("PUT", myRatingPath(workId), toMyRating, { score }, expectedUser(viewerId));
}

export function deleteMyRating(workId: string, viewerId: string): Promise<ApiCallResult<true>> {
  return apiRequest("DELETE", myRatingPath(workId), readNothing, undefined, expectedUser(viewerId));
}

export function fetchRatingSummary(workId: string): Promise<ApiCallResult<RatingSummary>> {
  return apiRequest("GET", `/works/${encodeURIComponent(workId)}`, (data) =>
    typeof data === "object" && data !== null ? toRatingSummary((data as { rating?: unknown }).rating) : null,
  );
}
