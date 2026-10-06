import "server-only";

import { cache } from "react";

import { apiGet, type ApiResult } from "./client";
import type { PageResponse, WorkDetail, WorkSummary } from "./types";

/** 목록 한 페이지의 작품 수 (백엔드 기본값과 같음) */
export const WORKS_PAGE_SIZE = 20;

/**
 * 작품 목록. apiPage 는 백엔드 기준 0부터 시작하는 페이지 번호다.
 * 정렬은 백엔드 기본값(createdAt DESC, id DESC)을 사용한다.
 */
export function getWorks(apiPage: number): Promise<ApiResult<PageResponse<WorkSummary>>> {
  const query = new URLSearchParams({ page: String(apiPage), size: String(WORKS_PAGE_SIZE) });
  return apiGet<PageResponse<WorkSummary>>(`/works?${query}`);
}

/**
 * 작품 상세. 같은 요청 안의 generateMetadata 와 page 가 한 번만 호출하도록 React cache 로 감싼다.
 * (요청 사이에는 캐시하지 않는다)
 */
export const getWork = cache((id: string): Promise<ApiResult<WorkDetail>> => {
  return apiGet<WorkDetail>(`/works/${encodeURIComponent(id)}`);
});
