import type { Credit, CreatorRole, WorkFormat } from "@/lib/api/types";

export const WORK_FORMAT_LABELS: Record<WorkFormat, string> = {
  WEBTOON: "웹툰",
  PAGE_COMIC: "페이지 만화",
};

/** 표시 순서는 백엔드 크레딧 정렬(STORY → ART → ORIGINAL_STORY)과 같다. */
export const CREATOR_ROLE_LABELS: Record<CreatorRole, string> = {
  STORY: "글",
  ART: "그림",
  ORIGINAL_STORY: "원작",
};

const ROLE_ORDER: CreatorRole[] = ["STORY", "ART", "ORIGINAL_STORY"];

export type CreditGroup = {
  role: CreatorRole;
  label: string;
  names: string[];
};

/** 역할별로 작가 이름을 묶는다. 같은 역할 안의 순서는 API 응답 순서(creatorId 오름차순)를 유지한다. */
export function groupCredits(credits: Credit[]): CreditGroup[] {
  return ROLE_ORDER.map((role) => ({
    role,
    label: CREATOR_ROLE_LABELS[role],
    names: credits.filter((credit) => credit.role === role).map((credit) => credit.name),
  })).filter((group) => group.names.length > 0);
}

const regionNames = createDisplayNames("region");
const languageNames = createDisplayNames("language");

/** 국가 코드를 "대한민국 (KR)" 형태로 표시한다. 이름을 알 수 없으면 코드만 표시한다. */
export function countryLabel(code: string): string {
  const name = safeOf(regionNames, code);
  return name && name !== code ? `${name} (${code})` : code;
}

/** 언어 태그를 "영어 (en)" 형태로 표시한다. */
export function languageLabel(tag: string): string {
  const name = safeOf(languageNames, tag);
  return name && name !== tag ? `${name} (${tag})` : tag;
}

function createDisplayNames(type: "region" | "language"): Intl.DisplayNames | null {
  try {
    return new Intl.DisplayNames(["ko"], { type, fallback: "code" });
  } catch {
    return null;
  }
}

function safeOf(names: Intl.DisplayNames | null, code: string): string | undefined {
  try {
    return names?.of(code);
  } catch {
    return undefined;
  }
}
