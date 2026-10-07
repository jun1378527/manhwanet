/**
 * 백엔드 응답 타입. backend 의 DTO(record)와 1:1로 대응한다.
 * - ApiResponse / ApiError / PageResponse: com.manhwanet.global.response
 * - Work*: com.manhwanet.work.dto
 */

export type FieldError = {
  field: string;
  reason: string;
};

export type ApiError = {
  code: string;
  message: string;
  fieldErrors?: FieldError[];
};

export type ApiResponse<T> =
  | { success: true; data: T }
  | { success: false; error: ApiError };

export type PageResponse<T> = {
  content: T[];
  /** 0부터 시작 */
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
};

export type WorkFormat = "WEBTOON" | "PAGE_COMIC";

export type CreatorRole = "STORY" | "ART" | "ORIGINAL_STORY";

export type PrimaryTitle = {
  title: string;
  /** BCP 47 언어 태그 (예: ko, ja, en-US) */
  languageCode: string;
};

export type WorkTitle = PrimaryTitle & {
  primary: boolean;
};

export type Credit = {
  /** 백엔드 Long. JSON 숫자 정밀도 손실을 피하려고 원문 십진수 문자열로 둔다. */
  creatorId: string;
  name: string;
  role: CreatorRole;
};

/**
 * 공개 별점 집계. backend com.manhwanet.work.dto.RatingSummaryResponse
 * - average: 0.5~5.0, 소수 첫째 자리(HALF_UP). 참여자가 없으면 null
 * - count: 현재 별점이 있는 사용자 수
 */
export type RatingSummary = {
  average: number | null;
  count: number;
};

export type WorkSummary = {
  /** 백엔드 Long. JSON 숫자 정밀도 손실을 피하려고 원문 십진수 문자열로 둔다. */
  id: string;
  workFormat: WorkFormat;
  originCountry: string | null;
  primaryTitle: PrimaryTitle;
  /** STORY → ART → ORIGINAL_STORY, 같은 역할은 creatorId 오름차순으로 정렬되어 온다. */
  creators: Credit[];
  rating: RatingSummary;
  createdAt: string;
};

export type WorkDetail = {
  /** 백엔드 Long. JSON 숫자 정밀도 손실을 피하려고 원문 십진수 문자열로 둔다. */
  id: string;
  workFormat: WorkFormat;
  originCountry: string | null;
  description: string | null;
  primaryTitle: PrimaryTitle;
  /** 대표 제목이 먼저, 나머지는 등록 순서로 온다. */
  titles: WorkTitle[];
  creators: Credit[];
  rating: RatingSummary;
  createdAt: string;
  updatedAt: string;
};
