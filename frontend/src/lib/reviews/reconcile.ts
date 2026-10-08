/**
 * 결과를 모르는 변경(연결 실패·시간 초과·5xx)과 충돌 뒤에 다시 읽은 최신 리뷰를 해석한다.
 * 어떤 경우에도 변경 요청을 자동으로 다시 보내지 않는다. 화면은 이 결과에 따라 안내만 바꾼다.
 */
import type { MyReview } from "./model";

export type Content = { body: string; containsSpoiler: boolean };

/**
 * - applied: 최신 리뷰가 보내려던 내용과 같다(요청이 반영된 것으로 본다)
 * - not-applied: 최신 리뷰가 요청 전 기준과 같다(반영되지 않았다)
 * - diverged: 둘 다 아니다(다른 곳에서 바뀌었다). 사용자가 최신 내용을 확인하고 고르게 한다
 */
export type Reconciled = "applied" | "not-applied" | "diverged";

export function sameReview(a: MyReview | null, b: MyReview | null): boolean {
  if (a === null || b === null) {
    return a === b;
  }
  return a.id === b.id && a.version === b.version;
}

/**
 * 작성·수정 뒤 판단.
 * @param base 요청을 보낼 때 기준으로 삼은 리뷰 (작성이면 null)
 * @param intended 정규화된(줄바꿈 통일·앞뒤 공백 제거) 보낼 내용
 */
export function reconcileWrite(base: MyReview | null, intended: Content, latest: MyReview | null): Reconciled {
  if (latest !== null && latest.body === intended.body && latest.containsSpoiler === intended.containsSpoiler) {
    return "applied";
  }
  return sameReview(base, latest) ? "not-applied" : "diverged";
}

/** 삭제 뒤 판단 */
export function reconcileDelete(base: MyReview, latest: MyReview | null): Reconciled {
  if (latest === null) {
    return "applied";
  }
  return sameReview(base, latest) ? "not-applied" : "diverged";
}
