/**
 * 브라우저에서 리뷰 API를 호출한다. 같은 출처 프록시·CSRF 처리는 공통 클라이언트(../api/browser)를 쓴다.
 * - 내 리뷰: GET·POST·PUT·DELETE /api/v1/me/reviews/{workId}
 *   PUT 은 본문에, DELETE 는 query 에 reviewId·version 을 보낸다. DELETE 성공은 204(본문 없음, 파싱하지 않음)
 * - 내 리뷰 요청에는 화면을 렌더링한 사용자 ID(viewerId)를 X-Expected-User-Id 헤더로 보낸다(#22).
 *   서버가 같은 요청의 세션 사용자와 비교해 다르면 409 USER_MISMATCH 로 거절한다. 작성자는 서버가 세션에서 정한다.
 * - 공개 목록: GET /api/v1/works/{workId}/reviews?page&size
 */
import { type ApiCallResult, SerializedJson, apiRequest, readNothing } from "../api/browser";
import { type MyReviewResult, type PublicReviewPage, REVIEWS_PAGE_SIZE, isLongId, toMyReviewResult, toPublicReviewPage } from "./model";
import { type ReviewTarget, createRequestJson, deleteQuery, updateRequestJson } from "./serialize";

function myReviewPath(workId: string): string {
  return `/me/reviews/${encodeURIComponent(workId)}`;
}

/** 예상 사용자 ID 는 1 이상 Long 범위의 십진수 문자열이어야 한다. 아니면 요청을 만들지 않는다 */
function expectedUser(viewerId: string): { expectedUserId: string } {
  if (!isLongId(viewerId)) {
    throw new Error("invalid expected user id");
  }
  return { expectedUserId: viewerId };
}

export async function fetchMyReview(workId: string, viewerId: string): Promise<ApiCallResult<MyReviewResult>> {
  return apiRequest("GET", myReviewPath(workId), toMyReviewResult, undefined, expectedUser(viewerId));
}

export async function createMyReview(
  workId: string,
  viewerId: string,
  body: string,
  containsSpoiler: boolean,
): Promise<ApiCallResult<MyReviewResult>> {
  return apiRequest(
    "POST",
    myReviewPath(workId),
    toMyReviewResult,
    new SerializedJson(createRequestJson(body, containsSpoiler)),
    expectedUser(viewerId),
  );
}

/** 수정. target 이 현재 리뷰와 다르면 백엔드가 409 REVIEW_VERSION_CONFLICT 로 거절한다 */
export async function updateMyReview(
  workId: string,
  viewerId: string,
  target: ReviewTarget,
  body: string,
  containsSpoiler: boolean,
): Promise<ApiCallResult<MyReviewResult>> {
  return apiRequest(
    "PUT",
    myReviewPath(workId),
    toMyReviewResult,
    new SerializedJson(updateRequestJson(target, body, containsSpoiler)),
    expectedUser(viewerId),
  );
}

/** 삭제. 현재 리뷰가 없으면 204, target 과 다르면 409 */
export async function deleteMyReview(
  workId: string,
  viewerId: string,
  target: ReviewTarget,
): Promise<ApiCallResult<true>> {
  return apiRequest("DELETE", `${myReviewPath(workId)}?${deleteQuery(target)}`, readNothing, undefined, expectedUser(viewerId));
}

/** @param apiPage 0부터 시작 */
export function fetchReviewPage(workId: string, apiPage: number): Promise<ApiCallResult<PublicReviewPage>> {
  const query = new URLSearchParams({ page: String(apiPage), size: String(REVIEWS_PAGE_SIZE) });
  return apiRequest("GET", `/works/${encodeURIComponent(workId)}/reviews?${query}`, toPublicReviewPage);
}
