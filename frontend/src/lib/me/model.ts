/**
 * 내 활동(/me) 화면 상태와 응답 모양.
 *
 * URL: /me?tab=reviews&page=N
 * - tab: ratings(기본, 생략) | reviews
 * - page: 1부터. 1이면 생략
 * 탭을 바꾸면 그 탭의 첫 페이지로 간다. 잘못된 값·중복 값은 정규 주소로 보낸다.
 *
 * 백엔드: GET /api/v1/me/ratings | /me/reviews ?page(0부터)&size, GET /api/v1/me/activity
 */
import type { Credit, PrimaryTitle, WorkFormat } from "@/lib/api/types";
import { parseListPage } from "@/lib/works/params";

export const ME_PATH = "/me";
export const ME_PAGE_SIZE = 20;
export const ME_TABS = ["ratings", "reviews"] as const;
export type MeTab = (typeof ME_TABS)[number];
export const ME_TAB_LABELS: Record<MeTab, string> = { ratings: "내 별점", reviews: "내 리뷰" };

export type MeQuery = { tab: MeTab; page: number };
export const DEFAULT_ME_QUERY: MeQuery = { tab: "ratings", page: 1 };

type SearchParamValue = string | string[] | undefined;

/** URL 파라미터 해석. changed 면 정규 주소로 보내야 한다(기본값을 적은 tab=ratings·page=1 은 그대로 둔다) */
export function parseMeQuery(params: Record<string, SearchParamValue>): { query: MeQuery; changed: boolean } {
  let changed = false;
  let tab: MeTab = "ratings";
  const rawTab = params.tab;
  if (rawTab !== undefined) {
    if (typeof rawTab === "string" && (ME_TABS as readonly string[]).includes(rawTab)) {
      tab = rawTab as MeTab;
    } else {
      changed = true;
    }
  }
  let page = 1;
  if (params.page !== undefined) {
    const parsed = parseListPage(params.page);
    if (parsed === null) {
      changed = true;
    } else {
      page = parsed;
    }
  }
  return { query: { tab, page }, changed };
}

export function meHref(query: MeQuery): string {
  const params = new URLSearchParams();
  if (query.tab !== "ratings") params.set("tab", query.tab);
  if (query.page > 1) params.set("page", String(query.page));
  const text = params.toString();
  return text ? `${ME_PATH}?${text}` : ME_PATH;
}

// ---------- 응답 ----------

export type ActivityWork = {
  /** 백엔드 Long(원문 십진수 문자열) */
  id: string;
  workFormat: WorkFormat;
  primaryTitle: PrimaryTitle;
  creators: Credit[];
};

export type MyRatingItem = {
  workId: string;
  /** false 면 작품이 지금 공개되지 않아 work 가 없다(내 기록은 보존) */
  available: boolean;
  work: ActivityWork | null;
  score: number;
  createdAt: string;
  updatedAt: string;
};

export type MyReviewItem = {
  id: string;
  workId: string;
  available: boolean;
  work: ActivityWork | null;
  excerpt: string;
  excerptTruncated: boolean;
  containsSpoiler: boolean;
  createdAt: string;
  updatedAt: string;
  edited: boolean;
};

export type ActivitySummary = { ratingCount: number; reviewCount: number };

export type Page<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number };

const ID = /^[1-9]\d{0,18}$/;
const isObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null;
const isTime = (v: unknown): v is string => typeof v === "string" && !Number.isNaN(Date.parse(v));
const isCount = (v: unknown): v is number => typeof v === "number" && Number.isSafeInteger(v) && v >= 0;

function toWork(value: unknown): ActivityWork | null {
  if (!isObject(value) || typeof value.id !== "string" || !ID.test(value.id)) return null;
  if (value.workFormat !== "WEBTOON" && value.workFormat !== "PAGE_COMIC") return null;
  const title = value.primaryTitle;
  if (!isObject(title) || typeof title.title !== "string" || typeof title.languageCode !== "string") return null;
  if (!Array.isArray(value.creators)) return null;
  const creators: Credit[] = [];
  for (const c of value.creators) {
    if (!isObject(c) || typeof c.creatorId !== "string" || typeof c.name !== "string") return null;
    if (c.role !== "STORY" && c.role !== "ART" && c.role !== "ORIGINAL_STORY") return null;
    creators.push({ creatorId: c.creatorId, name: c.name, role: c.role });
  }
  return { id: value.id, workFormat: value.workFormat, primaryTitle: { title: title.title, languageCode: title.languageCode }, creators };
}

/** 공개 여부와 작품 정보가 서로 맞는지 확인한다(공개면 작품 정보와 ID 가 있어야 하고, 아니면 작품 정보가 없어야 함) */
function workPart(value: Record<string, unknown>): { workId: string; available: boolean; work: ActivityWork | null } | null {
  if (typeof value.workId !== "string" || !ID.test(value.workId) || typeof value.available !== "boolean") return null;
  if (value.available) {
    const work = toWork(value.work);
    return work && work.id === value.workId ? { workId: value.workId, available: true, work } : null;
  }
  return value.work === null || value.work === undefined ? { workId: value.workId, available: false, work: null } : null;
}

export function toMyRatingItem(value: unknown): MyRatingItem | null {
  if (!isObject(value)) return null;
  const part = workPart(value);
  const score = value.score;
  if (!part || typeof score !== "number" || score < 0.5 || score > 5 || !isTime(value.createdAt) || !isTime(value.updatedAt)) {
    return null;
  }
  return { ...part, score, createdAt: value.createdAt, updatedAt: value.updatedAt };
}

export function toMyReviewItem(value: unknown): MyReviewItem | null {
  if (!isObject(value) || typeof value.id !== "string" || !ID.test(value.id)) return null;
  const part = workPart(value);
  if (
    !part ||
    typeof value.excerpt !== "string" ||
    typeof value.excerptTruncated !== "boolean" ||
    typeof value.containsSpoiler !== "boolean" ||
    typeof value.edited !== "boolean" ||
    !isTime(value.createdAt) ||
    !isTime(value.updatedAt)
  ) {
    return null;
  }
  return {
    id: value.id,
    ...part,
    excerpt: value.excerpt,
    excerptTruncated: value.excerptTruncated,
    containsSpoiler: value.containsSpoiler,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    edited: value.edited,
  };
}

/** 페이지 응답. 항목 하나라도 모양이 틀리면 전체를 받지 않는다(일부만 보여 주며 오해하지 않게) */
export function toPage<T>(data: unknown, item: (v: unknown) => T | null): Page<T> | null {
  if (!isObject(data) || !Array.isArray(data.content)) return null;
  if (!isCount(data.page) || !isCount(data.size) || !isCount(data.totalElements) || !isCount(data.totalPages)) return null;
  const content: T[] = [];
  for (const raw of data.content) {
    const parsed = item(raw);
    if (parsed === null) return null;
    content.push(parsed);
  }
  return { content, page: data.page, size: data.size, totalElements: data.totalElements, totalPages: data.totalPages };
}

export function toActivitySummary(data: unknown): ActivitySummary | null {
  if (!isObject(data) || !isCount(data.ratingCount) || !isCount(data.reviewCount)) return null;
  return { ratingCount: data.ratingCount, reviewCount: data.reviewCount };
}

/** 리뷰를 수정·삭제하러 가는 주소: 작품 상세의 내 리뷰 영역 */
export function reviewEditHref(workId: string): string {
  return `/works/${workId}#my-review`;
}
