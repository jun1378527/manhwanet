/**
 * 브라우저에서 별점 API를 호출한다. 같은 출처 프록시·CSRF 처리는 공통 클라이언트(../api/browser)를 쓴다.
 * - 내 별점: GET·PUT·DELETE /api/v1/me/ratings/{workId} (DELETE 성공은 204, 본문 없음)
 * - 공개 집계: GET /api/v1/works/{workId} 의 data.rating
 */
import { type ApiCallResult, apiRequest, readNothing } from "../api/browser";
import type { RatingSummary } from "../api/types";
import { type MyRating, toMyRating, toRatingSummary } from "./score";

function myRatingPath(workId: string): string {
  return `/me/ratings/${encodeURIComponent(workId)}`;
}

export function fetchMyRating(workId: string): Promise<ApiCallResult<MyRating>> {
  return apiRequest("GET", myRatingPath(workId), toMyRating);
}

/** 등록·수정. score 는 JSON 숫자로 보낸다(문자열은 백엔드가 거절). */
export function saveMyRating(workId: string, score: number): Promise<ApiCallResult<MyRating>> {
  return apiRequest("PUT", myRatingPath(workId), toMyRating, { score });
}

export function deleteMyRating(workId: string): Promise<ApiCallResult<true>> {
  return apiRequest("DELETE", myRatingPath(workId), readNothing);
}

export function fetchRatingSummary(workId: string): Promise<ApiCallResult<RatingSummary>> {
  return apiRequest("GET", `/works/${encodeURIComponent(workId)}`, (data) =>
    typeof data === "object" && data !== null ? toRatingSummary((data as { rating?: unknown }).rating) : null,
  );
}
