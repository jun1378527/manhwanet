import "server-only";

import { cache } from "react";

import { type WorksQuery, worksApiQuery } from "@/lib/works/search";

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
 * 작품 찾기. 전체 공개 작품을 대상으로 백엔드가 검색·필터·정렬·페이지를 처리한다.
 * 조건·정렬이 모두 기본값이면 getWorks 와 같은 요청이다.
 */
export function searchWorks(query: WorksQuery): Promise<ApiResult<PageResponse<WorkSummary>>> {
  return apiGet<PageResponse<WorkSummary>>(`/works?${worksApiQuery(query, WORKS_PAGE_SIZE)}`);
}

/** 작품 찾기 필터 선택지. backend com.manhwanet.work.dto.WorkFilterOptionsResponse */
export type WorkFilterOptions = {
  /** 공개 작품에 지금(ACTIVE) 연결이 있는 플랫폼만, 이름순. workCount 는 연결된 공개 작품 수(작품 기준) */
  platforms: { code: string; name: string; workCount: number }[];
};

/** 작품 찾기 필터 선택지(공개 작품에 지금 연결이 있는 플랫폼) */
export function getWorkFilterOptions(): Promise<ApiResult<WorkFilterOptions>> {
  return apiGet<WorkFilterOptions>("/works/filters");
}

/**
 * 작품 상세. 같은 요청 안의 generateMetadata 와 page 가 한 번만 호출하도록 React cache 로 감싼다.
 * (요청 사이에는 캐시하지 않는다)
 */
export const getWork = cache((id: string): Promise<ApiResult<WorkDetail>> => {
  return apiGet<WorkDetail>(`/works/${encodeURIComponent(id)}`);
});
