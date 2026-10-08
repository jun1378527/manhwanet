/**
 * 리뷰 요청 실패를 화면 처리 종류로 바꾼다.
 */
import type { ApiCallResult } from "../api/browser";
import { CSRF_FAILED_MESSAGE } from "../auth/messages";

export type ReviewFailure =
  /** 화면을 렌더링한 사용자와 현재 로그인 사용자가 다름(USER_MISMATCH). 아무것도 바뀌지 않았다.
   *  재시도하지 않고 이전 사용자의 글·충돌 상태를 지운 뒤 새 사용자 기준으로 다시 그린다 */
  | { kind: "account-switched" }
  /** 세션 만료·비로그인: 작성 중인 글은 유지하고 로그인 안내 */
  | { kind: "session-expired" }
  /** 연결 실패·시간 초과·5xx: 반영 여부를 모른다. 자동으로 다시 보내지 않고 현재 리뷰만 다시 조회한다 */
  | { kind: "unknown-outcome" }
  /** 이미 리뷰가 있음 (POST) */
  | { kind: "already-exists" }
  /** 현재 리뷰가 요청한 reviewId·version 과 다름 (PUT·DELETE) */
  | { kind: "version-conflict" }
  /** 수정하려는 리뷰가 없음 (PUT) */
  | { kind: "review-missing" }
  /** 입력 오류: 필드별 안내 (body·containsSpoiler 외 필드는 일반 안내) */
  | { kind: "field"; body: string | null; spoiler: string | null; other: string | null }
  | { kind: "message"; message: string };

export const REVIEW_SESSION_EXPIRED_MESSAGE =
  "로그인이 만료되었습니다. 작성 중인 글은 이 화면에 그대로 있지만, 로그인 화면으로 이동하면 사라집니다. 필요하면 먼저 복사해 두세요.";
export const REVIEW_UNKNOWN_OUTCOME_MESSAGE =
  "서버 응답을 받지 못해 반영 여부를 확인하지 못했습니다. 아래 현재 리뷰를 확인해 주세요.";
export const REVIEW_UNREACHABLE_MESSAGE =
  "서버에 연결할 수 없어 반영 여부를 확인하지 못했습니다. 작성 중인 글은 그대로 있습니다. 잠시 후 \"다시 불러오기\"로 확인해 주세요.";
export const REVIEW_REFRESH_FAILED_MESSAGE = "최신 리뷰 정보를 불러오지 못했습니다.";
export const REVIEW_SESSION_CHECK_FAILED_MESSAGE =
  "로그인 상태를 확인하지 못해 요청을 보내지 않았습니다. 작성 중인 글은 그대로 있습니다. 잠시 후 다시 시도해 주세요.";

export function reviewFailure(result: Exclude<ApiCallResult<unknown>, { kind: "ok" }>): ReviewFailure {
  // 연결 실패와 5xx 는 반영 여부를 모른다. 변경을 다시 보내지 않고 내 리뷰를 다시 읽어 판단한다(#25).
  if (result.kind === "unavailable" || result.status >= 500) {
    return { kind: "unknown-outcome" };
  }
  switch (result.code) {
    case "USER_MISMATCH":
      return { kind: "account-switched" };
    case "UNAUTHORIZED":
      return { kind: "session-expired" };
    case "INVALID_CSRF_TOKEN":
      return { kind: "message", message: CSRF_FAILED_MESSAGE };
    case "WORK_NOT_FOUND":
      return { kind: "message", message: "작품을 찾을 수 없습니다. 삭제되었을 수 있습니다." };
    case "REVIEW_ALREADY_EXISTS":
      return { kind: "already-exists" };
    case "REVIEW_VERSION_CONFLICT":
      return { kind: "version-conflict" };
    case "REVIEW_NOT_FOUND":
      return { kind: "review-missing" };
    case "INVALID_INPUT": {
      const reason = (field: string) => result.fieldErrors.find((error) => error.field === field)?.reason ?? null;
      const body = reason("body");
      const spoiler = reason("containsSpoiler");
      const other =
        body === null && spoiler === null
          ? (result.fieldErrors[0]?.reason ?? result.message ?? "입력값을 확인해 주세요.")
          : null;
      return { kind: "field", body, spoiler, other };
    }
    default:
      if (result.status === 401) {
        return { kind: "session-expired" };
      }
      return { kind: "message", message: result.message ?? "요청을 처리하지 못했습니다." };
  }
}
