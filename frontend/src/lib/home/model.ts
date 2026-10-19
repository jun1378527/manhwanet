/**
 * 홈 화면 API 응답 검증·변환. 브라우저·서버 어디서나 쓸 수 있도록 Next.js 에 의존하지 않는다.
 * - 최근 공개 리뷰: GET /reviews/recent (backend RecentReviewResponse)
 * - 별점 순위: GET /works/rankings (응답 모양은 작품 목록과 같음)
 */
import type { PrimaryTitle } from "@/lib/api/types";
import { isLongId } from "@/lib/reviews/model";

export type RankingOrder = "rating" | "ratingCount";
export type HomeWorkTab = RankingOrder | "recent";

export const HOME_WORKS_SIZE = 10;
export const HOME_REVIEWS_SIZE = 6;

export type RecentReview = {
  id: string;
  work: { id: string; primaryTitle: PrimaryTitle };
  /** 사용자를 찾지 못하면 null → "알 수 없는 사용자" */
  authorNickname: string | null;
  body: string;
  containsSpoiler: boolean;
  createdAt: string;
  edited: boolean;
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function isTimestamp(value: unknown): value is string {
  return typeof value === "string" && !Number.isNaN(Date.parse(value));
}

export function toRecentReview(data: unknown): RecentReview | null {
  const value = asRecord(data);
  const work = asRecord(value?.work);
  const title = asRecord(work?.primaryTitle);
  const author = asRecord(value?.author);
  if (
    value === null ||
    work === null ||
    title === null ||
    author === null ||
    !isLongId(value.id) ||
    !isLongId(work.id) ||
    typeof title.title !== "string" ||
    typeof title.languageCode !== "string" ||
    (author.nickname !== null && typeof author.nickname !== "string") ||
    typeof value.body !== "string" ||
    typeof value.containsSpoiler !== "boolean" ||
    !isTimestamp(value.createdAt) ||
    typeof value.edited !== "boolean"
  ) {
    return null;
  }
  return {
    id: value.id,
    work: { id: work.id, primaryTitle: { title: title.title, languageCode: title.languageCode } },
    authorNickname: author.nickname as string | null,
    body: value.body,
    containsSpoiler: value.containsSpoiler,
    createdAt: value.createdAt,
    edited: value.edited,
  };
}

/** 목록 전체가 올바를 때만 쓴다(한 건이라도 모양이 다르면 null → 오류 상태) */
/**
 * 화면에 넘기는 최근 리뷰. 스포일러 리뷰는 본문을 빼고(null) 넘겨, 펼치기 전에는 페이지 데이터·DOM 어디에도 없게 한다.
 * 펼칠 때 GET /reviews/{id} 로 본문을 받는다.
 */
export type HomeReview = Omit<RecentReview, "body"> & { body: string | null };

export function withholdSpoilerBody(review: RecentReview): HomeReview {
  return review.containsSpoiler ? { ...review, body: null } : review;
}

export function toRecentReviews(data: unknown): RecentReview[] | null {
  if (!Array.isArray(data)) {
    return null;
  }
  const reviews = data.map(toRecentReview);
  return reviews.every((r): r is RecentReview => r !== null) ? reviews : null;
}

const DATE_FORMATTER = new Intl.DateTimeFormat("en-US", {
  timeZone: "Asia/Seoul",
  month: "numeric",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

const DAY_FORMATTER = new Intl.DateTimeFormat("en-US", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "numeric",
  day: "numeric",
});

function parts(formatter: Intl.DateTimeFormat, iso: string): Record<string, string> {
  const result: Record<string, string> = {};
  for (const part of formatter.formatToParts(new Date(iso))) {
    result[part.type] = part.value;
  }
  return result;
}

/** 리뷰 작성 시각 "10월 17일 12:30" (Asia/Seoul, 서버·브라우저 결과 같음) */
export function formatReviewTime(iso: string): string {
  const p = parts(DATE_FORMATTER, iso);
  return `${p.month}월 ${p.day}일 ${p.hour}:${p.minute}`;
}

/** 사이트 등록일 "2026.10.17" (Asia/Seoul) */
export function formatRegisteredDate(iso: string): string {
  const p = parts(DAY_FORMATTER, iso);
  return `${p.year}.${p.month.padStart(2, "0")}.${p.day.padStart(2, "0")}`;
}

/** 시각 요소의 dateTime 값(UTC ISO) */
export function isoDateTime(iso: string): string {
  return new Date(iso).toISOString();
}
