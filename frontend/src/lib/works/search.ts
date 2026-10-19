/**
 * 작품 찾기(/works) 화면 상태와 URL.
 *
 * 화면 URL: /works?q=&format=&platform=&sort=&page=
 * - q: 작품명·작가명 검색어. 제어 문자를 공백으로 바꾸고 NFC 정규화 → 앞뒤 공백 제거 → 연속 공백 하나로. 최대 100자
 * - format: WEBTOON | PAGE_COMIC (없으면 전체)
 * - platform: 플랫폼 코드(없으면 전체)
 * - sort: popular(인기순) | ratingCount(별많은순) | reviewCount(리뷰많은순) | rating(높은평점순). 없으면 최신등록순(recent)
 * - page: 1부터. 1이면 생략
 * 기본값은 URL 에서 생략하고 파라미터 순서는 q → format → platform → sort → page 로 고정한다.
 *
 * 백엔드: GET /api/v1/works?page(0부터)&size&q&format&platform&order. 조건이 하나도 없으면 order 도 보내지 않아
 * 기존 목록 호출과 같다.
 */
import type { WorkFormat } from "@/lib/api/types";
import { parseListPage } from "@/lib/works/params";

export const WORKS_PATH = "/works";
export const MAX_QUERY_LENGTH = 100;

/** 화면에 보이는 정렬 순서(왼쪽부터). 기본값은 최신등록순(recent) */
export const WORK_SORTS = ["popular", "ratingCount", "reviewCount", "rating", "recent"] as const;
export type WorkSort = (typeof WORK_SORTS)[number];

export const WORK_SORT_LABELS: Record<WorkSort, string> = {
  popular: "인기순",
  ratingCount: "별많은순",
  reviewCount: "리뷰많은순",
  rating: "높은평점순",
  recent: "최신등록순",
};

/** 인기순이 세는 최근 활동 기간(백엔드 WorkSearchCriteria.POPULAR_WINDOW_DAYS 와 같음) */
export const POPULAR_WINDOW_DAYS = 30;

/** 정렬마다 무엇을 기준으로 줄 세우는지 화면 안내(명세와 같음). 결과 수는 정렬과 관계없이 같다 */
export const WORK_SORT_NOTES: Record<WorkSort, string> = {
  popular: `최근 ${POPULAR_WINDOW_DAYS}일 동안 별점·리뷰를 작성하거나 수정한 독자 수가 많은 순이에요. 같으면 별점 수가 많은 작품이 먼저예요.`,
  ratingCount: "별점을 남긴 독자가 많은 순이에요. 별점이 없는 작품도 맨 뒤에 최신등록순으로 함께 보여 드려요.",
  reviewCount: "리뷰가 많은 순이에요. 같으면 별점 수가 많은 작품이 먼저이고, 리뷰가 없는 작품도 맨 뒤에 함께 보여 드려요.",
  rating: "평균 별점이 높은 순이에요. 같으면 별점 수가 많은 작품이 먼저이고, 별점이 없는 작품도 맨 뒤에 함께 보여 드려요.",
  recent: "사이트에 새로 등록된 작품부터 보여 드려요.",
};

export const WORK_FORMATS: readonly WorkFormat[] = ["WEBTOON", "PAGE_COMIC"];

export type WorksQuery = {
  /** 정규화한 검색어. 없으면 "" */
  q: string;
  format: WorkFormat | null;
  platform: string | null;
  sort: WorkSort;
  /** 1부터 */
  page: number;
};

export const DEFAULT_WORKS_QUERY: WorksQuery = { q: "", format: null, platform: null, sort: "recent", page: 1 };

type SearchParamValue = string | string[] | undefined;
export type SearchParamsRecord = Record<string, SearchParamValue>;

const PLATFORM_CODE = /^[A-Z][A-Z0-9_]{1,29}$/;
// 제어 문자, 줄·문단 구분자
const CONTROL = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/g;

/** 검색어 정규화(백엔드 WorkSearchCriteria 와 같은 규칙). 100자를 넘으면 100자에서 자른다 */
export function normalizeQuery(value: string): string {
  const cleaned = value.replace(CONTROL, " ").normalize("NFC").trim().replace(/\s+/g, " ");
  const chars = Array.from(cleaned);
  return chars.length > MAX_QUERY_LENGTH ? chars.slice(0, MAX_QUERY_LENGTH).join("").trimEnd() : cleaned;
}

export function isWorkFormat(value: string): value is WorkFormat {
  return (WORK_FORMATS as readonly string[]).includes(value);
}

export function isPlatformCode(value: string): boolean {
  return PLATFORM_CODE.test(value);
}

export type ParsedWorksQuery = {
  query: WorksQuery;
  /** 잘못된 값·중복 값·정규화로 바뀐 검색어가 있었다(정규 주소로 보내야 한다) */
  changed: boolean;
};

/**
 * URL 검색 파라미터를 화면 상태로 해석한다. 잘못된 값은 버리고(기본값) changed 를 켠다.
 * 기본값을 직접 적은 값(page=1, sort=recent, 빈 q·format·platform)은 바꾸지 않은 것으로 본다.
 * 모르는 파라미터는 무시한다.
 */
export function parseWorksQuery(params: SearchParamsRecord): ParsedWorksQuery {
  let changed = false;
  const one = (key: string): string | undefined => {
    const value = params[key];
    if (Array.isArray(value)) {
      changed = true;
      return undefined;
    }
    return value;
  };

  let q = "";
  const rawQ = one("q");
  if (rawQ !== undefined) {
    q = normalizeQuery(rawQ);
    if (q !== rawQ && rawQ !== "") {
      changed = true;
    }
  }

  let format: WorkFormat | null = null;
  const rawFormat = one("format");
  if (rawFormat) {
    if (isWorkFormat(rawFormat)) {
      format = rawFormat;
    } else {
      changed = true;
    }
  }

  let platform: string | null = null;
  const rawPlatform = one("platform");
  if (rawPlatform) {
    if (isPlatformCode(rawPlatform)) {
      platform = rawPlatform;
    } else {
      changed = true;
    }
  }

  let sort: WorkSort = "recent";
  const rawSort = one("sort");
  if (rawSort) {
    if ((WORK_SORTS as readonly string[]).includes(rawSort)) {
      sort = rawSort as WorkSort;
    } else {
      changed = true;
    }
  }

  let page = 1;
  const rawPage = params.page;
  if (rawPage !== undefined) {
    const parsed = parseListPage(rawPage);
    if (parsed === null) {
      changed = true;
    } else {
      page = parsed;
    }
  }

  return { query: { q, format, platform, sort, page }, changed };
}

/** URLSearchParams(브라우저·서버 공통)를 레코드로. 같은 키가 여러 번 나오면 배열 */
export function searchParamsToRecord(params: URLSearchParams): SearchParamsRecord {
  const record: Record<string, string | string[]> = {};
  for (const [key, value] of params) {
    const prev = record[key];
    record[key] = prev === undefined ? value : Array.isArray(prev) ? [...prev, value] : [prev, value];
  }
  return record;
}

/** 화면 상태 → 정규 /works 주소 */
export function worksSearchHref(query: WorksQuery): string {
  const params = new URLSearchParams();
  if (query.q) params.set("q", query.q);
  if (query.format) params.set("format", query.format);
  if (query.platform) params.set("platform", query.platform);
  if (query.sort !== "recent") params.set("sort", query.sort);
  if (query.page > 1) params.set("page", String(query.page));
  const text = params.toString();
  return text ? `${WORKS_PATH}?${text}` : WORKS_PATH;
}

/** 조건을 바꾼다. 페이지 외의 조건이 바뀌면 첫 페이지로 간다 */
export function changeWorksQuery(query: WorksQuery, patch: Partial<Omit<WorksQuery, "page">>): WorksQuery {
  const next = { ...query, ...patch };
  if (patch.q !== undefined) {
    next.q = normalizeQuery(patch.q);
  }
  return { ...next, page: 1 };
}

export function withPage(query: WorksQuery, page: number): WorksQuery {
  return { ...query, page };
}

/** 검색어·형식·플랫폼 중 하나라도 걸려 있는지 */
export function hasConditions(query: WorksQuery): boolean {
  return query.q !== "" || query.format !== null || query.platform !== null;
}

export function activeFilterCount(query: WorksQuery): number {
  return (query.format ? 1 : 0) + (query.platform ? 1 : 0);
}

export function isSameQuery(a: WorksQuery, b: WorksQuery): boolean {
  return worksSearchHref(a) === worksSearchHref(b);
}

/**
 * 백엔드 쿼리 문자열. 검색 조건·정렬이 모두 기본값이면 기존 목록 호출(page·size)과 같다.
 */
export function worksApiQuery(query: WorksQuery, size: number): string {
  const params = new URLSearchParams({ page: String(query.page - 1), size: String(size) });
  if (query.q) params.set("q", query.q);
  if (query.format) params.set("format", query.format);
  if (query.platform) params.set("platform", query.platform);
  if (query.sort !== "recent") params.set("order", query.sort);
  return params.toString();
}

// ---------- 상세 → 목록 복귀 ----------

const PROBE_ORIGIN = "http://works-back.invalid";
const MAX_BACK_LENGTH = 1024;

/**
 * 상세 화면의 ?back= 값(목록 주소)을 검사해 목록 상태로 돌려준다. 허용하지 않는 값이면 null.
 * - 같은 출처의 /works 경로만 허용한다. `//host`, `/\host`, 스킴, 제어 문자·역슬래시, 해시, 다른 경로는 거부
 * - 쿼리는 목록 파라미터 규칙으로 다시 해석해 잘못된 값은 버린다(모르는 파라미터도 버린다)
 */
export function parseWorksBack(value: SearchParamValue): WorksQuery | null {
  if (typeof value !== "string" || value.length === 0 || value.length > MAX_BACK_LENGTH) {
    return null;
  }
  if (!value.startsWith(`${WORKS_PATH}`) || value.startsWith("//") || /[\\\u0000-\u001f\u007f]/.test(value)) {
    return null;
  }
  let url: URL;
  try {
    url = new URL(value, PROBE_ORIGIN);
  } catch {
    return null;
  }
  if (url.origin !== PROBE_ORIGIN || url.pathname !== WORKS_PATH || url.hash !== "") {
    return null;
  }
  return parseWorksQuery(searchParamsToRecord(url.searchParams)).query;
}

/**
 * 상세 화면에서 돌아갈 목록 상태. ?back=(검증한 목록 주소)이 있으면 그것을, 없으면 이전 방식 ?from=(페이지 번호)을 쓴다.
 */
export function resolveReturnQuery(back: SearchParamValue, from: SearchParamValue): WorksQuery {
  const parsed = parseWorksBack(back);
  if (parsed) {
    return parsed;
  }
  const page = typeof from === "string" ? parseListPage(from) : null;
  return { ...DEFAULT_WORKS_QUERY, page: page ?? 1 };
}

/**
 * 작품 상세 주소. 목록 상태가 기본값이면 생략, 페이지만 다르면 이전과 같은 ?from=N, 그 밖에는 ?back=목록 주소.
 * reviewPage(공개 리뷰 페이지, 1부터)는 1이면 생략한다. 순서는 from/back → reviewPage.
 */
export function workDetailHrefFor(id: string, list: WorksQuery, reviewPage = 1): string {
  const params = new URLSearchParams();
  const onlyPage = isSameQuery({ ...list, page: 1 }, DEFAULT_WORKS_QUERY);
  if (onlyPage) {
    if (list.page > 1) params.set("from", String(list.page));
  } else {
    params.set("back", worksSearchHref(list));
  }
  if (reviewPage > 1) params.set("reviewPage", String(reviewPage));
  const text = params.toString();
  return text ? `/works/${id}?${text}` : `/works/${id}`;
}
