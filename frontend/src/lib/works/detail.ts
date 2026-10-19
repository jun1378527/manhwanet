/**
 * 작품 상세·목록의 공개 응답 확장(리뷰 수, 플랫폼 바로가기)과 표시 규칙.
 * 백엔드는 기존 필드를 그대로 두고 reviewCount·platformLinks 를 뒤에 덧붙였다. 이전 응답(필드 없음)도 받아들인다.
 */
import type { WorkDetail, WorkSummary } from "@/lib/api/types";

export type PlatformListingKind = "MAIN" | "SEASON" | "EDITION" | "TRANSLATION";

/** backend com.manhwanet.platform.dto.PublicPlatformLinkResponse */
export type PlatformLink = {
  platformCode: string;
  platformName: string;
  url: string;
  listingKind: PlatformListingKind;
  languageCode: string;
};

export type WorkSummaryWithReviews = WorkSummary & { reviewCount?: number };
export type WorkDetailWithLinks = WorkDetail & { reviewCount?: number; platformLinks?: PlatformLink[] };

const KIND_LABELS: Record<PlatformListingKind, string | null> = {
  MAIN: null,
  SEASON: "시즌",
  EDITION: "판본",
  TRANSLATION: "번역판",
};

/** 리뷰 수. 응답에 없거나 잘못된 값이면 null(화면에 표시하지 않음) */
export function reviewCountOf(work: { reviewCount?: unknown }): number | null {
  const value = work.reviewCount;
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

/**
 * 외부 링크로 써도 되는 주소인지 한 번 더 확인한다(서버가 플랫폼 규칙으로 검증한 값이지만 화면에서도 막는다).
 * https, 사용자 정보·포트 없음, 프래그먼트 없음, 제어 문자·공백 없음일 때만 정규화한 주소를 돌려준다.
 */
export function safePlatformHref(raw: unknown): string | null {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > 500 || /[\s\u0000-\u001f\u007f\\]/.test(raw)) {
    return null;
  }
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== "https:" || url.username || url.password || url.port || url.hash || !url.hostname.includes(".")) {
    return null;
  }
  return url.href;
}

const languageNames = (() => {
  try {
    return new Intl.DisplayNames(["ko"], { type: "language" });
  } catch {
    return null;
  }
})();

/** 바로가기 언어 표시. 예) "en" → "영어판". 이름을 알 수 없으면 언어 태그 그대로 */
export function editionLanguageLabel(tag: string): string {
  let name: string | undefined;
  try {
    name = languageNames?.of(tag);
  } catch {
    name = undefined;
  }
  return name && name !== tag ? `${name}판` : tag;
}

/** 화면에 보여 줄 바로가기: 주소가 안전한 것만, 같은 주소는 한 번만(서버 순서 유지) */
export function visiblePlatformLinks(links: unknown): (PlatformLink & { href: string; label: string })[] {
  if (!Array.isArray(links)) {
    return [];
  }
  const seen = new Set<string>();
  const labelCount = new Map<string, number>();
  const result: (PlatformLink & { href: string; label: string })[] = [];
  for (const link of links as PlatformLink[]) {
    if (!link || typeof link.platformName !== "string" || link.platformName.trim() === "") {
      continue;
    }
    const href = safePlatformHref(link.url);
    if (href === null || seen.has(href)) {
      continue;
    }
    seen.add(href);
    const kind = KIND_LABELS[link.listingKind] ?? null;
    const base = kind ? `${link.platformName} ${kind}` : link.platformName;
    // 같은 이름의 바로가기가 여럿이면(같은 플랫폼의 본편 연결 둘 등) 두 번째부터 번호를 붙여 구분한다
    const n = (labelCount.get(base) ?? 0) + 1;
    labelCount.set(base, n);
    result.push({ ...link, href, label: n > 1 ? `${base} ${n}` : base });
  }
  return result;
}
