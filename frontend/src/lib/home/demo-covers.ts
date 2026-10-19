/**
 * 가상 데모 작품 전용 표지. 표지 API 가 없으므로 일반 작품에는 "표지 없음" 디자인을 쓴다.
 * 데모 데이터(backend/src/demo DemoCatalog)의 정확한 대표 제목과 언어가 일치할 때만 연결한다.
 * 비슷한 제목이나 "[DEMO]" 접두어만으로는 붙이지 않는다(제목을 보고 임의 표지를 고르지 않음).
 */
import type { PrimaryTitle } from "@/lib/api/types";

export type DemoCoverId = "star-garden" | "moon-bookshop" | "clock-tower";

const DEMO_COVERS: ReadonlyMap<string, DemoCoverId> = new Map([
  ["[DEMO] 가상의 별빛 정원", "star-garden"],
  ["[DEMO] 가상의 달빛 서점", "moon-bookshop"],
  ["[DEMO] 가상의 시계탑 소녀", "clock-tower"],
]);

export function demoCoverFor(title: PrimaryTitle): DemoCoverId | null {
  if (title.languageCode !== "ko") {
    return null;
  }
  return DEMO_COVERS.get(title.title) ?? null;
}
