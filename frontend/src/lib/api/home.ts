import "server-only";

import { HOME_REVIEWS_SIZE, HOME_WORKS_SIZE, type RankingOrder, type RecentReview, toRecentReviews } from "@/lib/home/model";

import { apiGet, type ApiResult } from "./client";
import type { PageResponse, WorkSummary } from "./types";

/**
 * 별점 순위 첫 페이지. 전체 공개 작품 중 별점이 있는 작품을 백엔드가 정렬한다(현재 불러온 목록만 정렬하지 않음).
 * totalElements 는 순위에 들어가는 작품 수다.
 */
export function getWorkRanking(order: RankingOrder): Promise<ApiResult<PageResponse<WorkSummary>>> {
  const query = new URLSearchParams({ order, page: "0", size: String(HOME_WORKS_SIZE) });
  return apiGet<PageResponse<WorkSummary>>(`/works/rankings?${query}`);
}

/** 사이트 등록 시각 최신순(작품 목록 기본 정렬 createdAt DESC, id DESC) */
export function getRecentWorks(): Promise<ApiResult<PageResponse<WorkSummary>>> {
  const query = new URLSearchParams({ page: "0", size: String(HOME_WORKS_SIZE) });
  return apiGet<PageResponse<WorkSummary>>(`/works?${query}`);
}

/** 최근 공개 리뷰. 응답 모양이 다르면 unavailable 로 본다 */
export async function getRecentReviews(): Promise<ApiResult<RecentReview[]>> {
  const result = await apiGet<unknown>(`/reviews/recent?size=${HOME_REVIEWS_SIZE}`);
  if (!result.ok) {
    return result;
  }
  const reviews = toRecentReviews(result.data);
  if (reviews === null) {
    console.error("[api] GET /reviews/recent returned an unexpected shape");
    return { ok: false, reason: "unavailable" };
  }
  return { ok: true, data: reviews };
}
