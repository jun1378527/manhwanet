/**
 * 별점 변경 요청 실패를 화면 안내로 바꾼다.
 */
import type { ApiCallResult } from "../api/browser";
import { CSRF_FAILED_MESSAGE } from "../auth/messages";

export type RatingFailure =
  /** 화면의 사용자와 현재 세션 사용자가 다름. 아무것도 바뀌지 않았다. 이전 사용자의 별점 상태를 지운다 */
  | { kind: "account-switched" }
  /** 세션 만료·비로그인: 로그인 안내로 바꾼다 */
  | { kind: "session-expired" }
  /** 연결 실패·시간 초과: 변경이 반영됐는지 알 수 없다. 자동으로 다시 보내지 않고 현재 값을 다시 조회한다 */
  | { kind: "unknown-outcome" }
  /** 입력 오류: 라디오 그룹에 연결한다 */
  | { kind: "field"; message: string }
  | { kind: "message"; message: string };

export const SESSION_EXPIRED_MESSAGE = "로그인이 만료되었습니다. 다시 로그인하면 별점을 남길 수 있습니다.";
export const UNKNOWN_OUTCOME_MESSAGE =
  "서버 응답을 받지 못해 반영 여부를 확인하지 못했습니다. 아래에 표시된 현재 별점을 확인해 주세요.";
export const UNREACHABLE_MESSAGE =
  "서버에 연결할 수 없어 반영 여부를 확인하지 못했습니다. 잠시 후 \"다시 불러오기\"로 확인해 주세요.";
export const REFRESH_FAILED_MESSAGE = "최신 별점 정보를 불러오지 못했습니다.";

export function ratingFailure(result: Exclude<ApiCallResult<unknown>, { kind: "ok" }>): RatingFailure {
  if (result.kind === "unavailable") {
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
    case "INVALID_INPUT": {
      const reason = result.fieldErrors.find((error) => error.field === "score")?.reason;
      return { kind: "field", message: reason ?? result.message ?? "별점을 다시 선택해 주세요." };
    }
    default:
      if (result.status === 401) {
        return { kind: "session-expired" };
      }
      if (result.status >= 500) {
        return { kind: "message", message: "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요." };
      }
      return { kind: "message", message: result.message ?? "요청을 처리하지 못했습니다." };
  }
}
