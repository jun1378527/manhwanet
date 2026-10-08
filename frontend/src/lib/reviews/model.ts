/**
 * 리뷰 응답 타입과 모양 검사. backend com.manhwanet.review.dto 와 대응한다.
 * - id·workId·version 은 백엔드 Long 이며 무손실 파서(parseApiBody)가 원문 십진수 문자열로 둔다.
 * - 시각은 UTC ISO-8601 문자열이다. 화면은 Asia/Seoul 로 표시한다(./time).
 */
import type { PageResponse } from "../api/types";

/** 내 리뷰 (ReviewResponse) */
export type MyReview = {
  id: string;
  body: string;
  containsSpoiler: boolean;
  /** 수정·삭제 요청에 그대로 보낼 버전 (십진수 문자열) */
  version: string;
  createdAt: string;
  updatedAt: string;
  edited: boolean;
};

/** GET·POST·PUT /me/reviews/{workId} 의 data */
export type MyReviewResult = {
  workId: string;
  /** 작성하지 않았으면 null */
  review: MyReview | null;
};

/** 공개 목록의 리뷰 (PublicReviewResponse). 작성자는 닉네임만, version 없음 */
export type PublicReview = {
  id: string;
  /** 사용자를 찾지 못하면 null → 화면은 "알 수 없는 사용자" */
  authorNickname: string | null;
  body: string;
  containsSpoiler: boolean;
  createdAt: string;
  updatedAt: string;
  edited: boolean;
};

export type PublicReviewPage = PageResponse<PublicReview>;

/** 공개 목록 한 페이지의 리뷰 수 (백엔드 기본값과 같음) */
export const REVIEWS_PAGE_SIZE = 10;

const LONG_MAX = BigInt("9223372036854775807");

/** 1 이상 Long 범위의 십진수 문자열 (선행 0 없음) */
export function isLongId(value: unknown): value is string {
  return typeof value === "string" && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= LONG_MAX;
}

/** 0 이상 Long 범위의 십진수 문자열 (선행 0 없음) */
export function isLongVersion(value: unknown): value is string {
  return typeof value === "string" && /^(0|[1-9]\d{0,18})$/.test(value) && BigInt(value) <= LONG_MAX;
}

function isTimestamp(value: unknown): value is string {
  return typeof value === "string" && !Number.isNaN(Date.parse(value));
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

export function toMyReview(data: unknown): MyReview | null {
  const value = asRecord(data);
  if (
    value === null ||
    !isLongId(value.id) ||
    typeof value.body !== "string" ||
    typeof value.containsSpoiler !== "boolean" ||
    !isLongVersion(value.version) ||
    !isTimestamp(value.createdAt) ||
    !isTimestamp(value.updatedAt) ||
    typeof value.edited !== "boolean"
  ) {
    return null;
  }
  return {
    id: value.id,
    body: value.body,
    containsSpoiler: value.containsSpoiler,
    version: value.version,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    edited: value.edited,
  };
}

export function toMyReviewResult(data: unknown): MyReviewResult | null {
  const value = asRecord(data);
  if (value === null || !isLongId(value.workId)) {
    return null;
  }
  if (value.review === null) {
    return { workId: value.workId, review: null };
  }
  const review = toMyReview(value.review);
  return review === null ? null : { workId: value.workId, review };
}

export function toPublicReview(data: unknown): PublicReview | null {
  const value = asRecord(data);
  const author = asRecord(value?.author);
  if (
    value === null ||
    author === null ||
    (author.nickname !== null && typeof author.nickname !== "string") ||
    !isLongId(value.id) ||
    typeof value.body !== "string" ||
    typeof value.containsSpoiler !== "boolean" ||
    !isTimestamp(value.createdAt) ||
    !isTimestamp(value.updatedAt) ||
    typeof value.edited !== "boolean"
  ) {
    return null;
  }
  return {
    id: value.id,
    authorNickname: author.nickname as string | null,
    body: value.body,
    containsSpoiler: value.containsSpoiler,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    edited: value.edited,
  };
}

export function toPublicReviewPage(data: unknown): PublicReviewPage | null {
  const value = asRecord(data);
  if (
    value === null ||
    !Array.isArray(value.content) ||
    !Number.isInteger(value.page) ||
    !Number.isInteger(value.size) ||
    !Number.isInteger(value.totalElements) ||
    !Number.isInteger(value.totalPages) ||
    typeof value.hasNext !== "boolean"
  ) {
    return null;
  }
  const content: PublicReview[] = [];
  for (const item of value.content) {
    const review = toPublicReview(item);
    if (review === null) {
      return null;
    }
    content.push(review);
  }
  return {
    content,
    page: value.page as number,
    size: value.size as number,
    totalElements: value.totalElements as number,
    totalPages: value.totalPages as number,
    hasNext: value.hasNext,
  };
}

/** 두 리뷰 내용(본문·스포일러)이 같은지 */
export function sameContent(a: { body: string; containsSpoiler: boolean }, b: { body: string; containsSpoiler: boolean }) {
  return a.body === b.body && a.containsSpoiler === b.containsSpoiler;
}
