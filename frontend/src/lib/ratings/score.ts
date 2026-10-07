/**
 * 별점 점수 규칙과 표시. 백엔드 확정 명세(docs/rating-design-draft.md)와 같게 둔다.
 * - 0.5~5.0점, 0.5 간격(10단계). 0점 없음. 평가하지 않음 = 내 별점 score 가 null
 * - 공개 평균은 백엔드가 소수 첫째 자리(HALF_UP)로 계산해 준다. 화면은 다시 계산하지 않고 한 자리로만 표기한다.
 */
import type { RatingSummary } from "../api/types";

/** 선택지: 0.5, 1.0, …, 5.0 (반 점 단위 정수 1~10) */
export const RATING_SCORE_HALVES = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] as const;

export const NO_RATINGS_TEXT = "아직 평가가 없습니다";

export type MyRating = {
  /** 백엔드 Long. 무손실 파서로 원문 십진수 문자열을 유지한다. */
  workId: string;
  /** 0.5~5.0. 평가하지 않았으면 null */
  score: number | null;
  createdAt: string | null;
  updatedAt: string | null;
};

/** 반 점 단위 정수(1~10) → 점수(0.5~5.0) */
export function scoreFromHalf(half: number): number {
  return half / 2;
}

/** 점수가 백엔드 규칙(0.5~5.0, 0.5 단위)에 맞는지. 맞으면 반 점 단위 정수, 아니면 null */
export function toScoreHalf(score: number): number | null {
  if (!Number.isFinite(score)) {
    return null;
  }
  const half = score * 2;
  return Number.isInteger(half) && half >= 1 && half <= 10 ? half : null;
}

/** 점수 표기: 항상 소수 한 자리 (4 → "4.0", 4.5 → "4.5") */
export function formatScore(score: number): string {
  return score.toFixed(1);
}

/** 공개 집계 문장 (스크린 리더용 전체 문장) */
export function describeSummary(summary: RatingSummary): string {
  if (summary.count === 0 || summary.average === null) {
    return NO_RATINGS_TEXT;
  }
  return `평균 별점 5점 만점에 ${formatScore(summary.average)}점, ${summary.count.toLocaleString("ko-KR")}명 참여`;
}

/** 응답 data 가 공개 집계 모양인지 확인한다. */
export function toRatingSummary(data: unknown): RatingSummary | null {
  if (typeof data !== "object" || data === null) {
    return null;
  }
  const { average, count } = data as Record<string, unknown>;
  if (typeof count !== "number" || !Number.isInteger(count) || count < 0) {
    return null;
  }
  if (count === 0) {
    return average === null ? { average: null, count: 0 } : null;
  }
  if (typeof average !== "number" || average < 0.5 || average > 5) {
    return null;
  }
  return { average, count };
}

/** 응답 data 가 내 별점 모양인지 확인한다. */
export function toMyRating(data: unknown): MyRating | null {
  if (typeof data !== "object" || data === null) {
    return null;
  }
  const { workId, score, createdAt, updatedAt } = data as Record<string, unknown>;
  if (typeof workId !== "string" || !/^[1-9]\d*$/.test(workId)) {
    return null;
  }
  if (score === null) {
    return createdAt === null && updatedAt === null ? { workId, score: null, createdAt: null, updatedAt: null } : null;
  }
  if (typeof score !== "number" || toScoreHalf(score) === null) {
    return null;
  }
  if (typeof createdAt !== "string" || typeof updatedAt !== "string") {
    return null;
  }
  return { workId, score, createdAt, updatedAt };
}
