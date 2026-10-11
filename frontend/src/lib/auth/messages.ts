/**
 * 인증 API 실패를 화면 안내로 바꾼다.
 */
import type { ApiCallResult } from "./client-api";
import type { FieldErrors, FieldName } from "./validation";

export type FormFeedback = {
  /** 입력 칸에 연결할 오류 */
  fieldErrors: FieldErrors;
  /** 폼 전체 안내 (입력 칸에 연결할 수 없는 오류) */
  formError: string | null;
};

const FIELD_NAMES: ReadonlySet<string> = new Set<FieldName>(["email", "nickname", "password"]);

export const UNAVAILABLE_MESSAGE = "서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.";
/** 서버가 과부하로 지금은 처리하지 않음(503 AUTH_BUSY). 연결 실패와 구분한다. 자동으로 다시 보내지 않는다. */
export function authBusyMessage(retryAfterSeconds: number | null): string {
  return `요청이 많습니다. ${retryAfterText(retryAfterSeconds)} 다시 시도해 주세요.`;
}
export const CSRF_FAILED_MESSAGE = "보안 토큰을 확인하지 못했습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.";

/** Retry-After 초를 "약 N분" 으로 바꾼다. */
export function retryAfterText(seconds: number | null): string {
  if (seconds === null || seconds <= 0) {
    return "잠시 후";
  }
  if (seconds < 60) {
    return `약 ${seconds}초 후`;
  }
  return `약 ${Math.ceil(seconds / 60)}분 후`;
}

/**
 * 실패 결과를 입력 칸 오류와 폼 오류로 나눈다.
 * @param context login: 로그인 실패는 이유를 구분하지 않는다. signup: 409 는 이메일 칸 오류로 표시한다.
 */
export function feedbackFor(result: Exclude<ApiCallResult<unknown>, { kind: "ok" }>, context: "login" | "signup"): FormFeedback {
  if (result.kind === "unavailable") {
    return { fieldErrors: {}, formError: UNAVAILABLE_MESSAGE };
  }

  switch (result.code) {
    case "INVALID_INPUT": {
      const fieldErrors: FieldErrors = {};
      const others: string[] = [];
      for (const { field, reason } of result.fieldErrors) {
        if (FIELD_NAMES.has(field)) {
          const name = field as FieldName;
          fieldErrors[name] = fieldErrors[name] ? `${fieldErrors[name]} ${reason}` : reason;
        } else {
          others.push(reason);
        }
      }
      const hasFieldErrors = Object.keys(fieldErrors).length > 0;
      return {
        fieldErrors,
        formError: others.length > 0 ? others.join(" ") : hasFieldErrors ? null : (result.message ?? "입력값을 확인해 주세요."),
      };
    }
    case "INVALID_CREDENTIALS":
      return { fieldErrors: {}, formError: "이메일 또는 비밀번호가 올바르지 않습니다." };
    case "TOO_MANY_LOGIN_ATTEMPTS":
      return {
        fieldErrors: {},
        formError: `로그인 시도가 너무 많습니다. ${retryAfterText(result.retryAfterSeconds)} 다시 시도해 주세요.`,
      };
    case "AUTH_BUSY":
      return { fieldErrors: {}, formError: authBusyMessage(result.retryAfterSeconds) };
    case "DUPLICATE_RESOURCE":
      return context === "signup"
        ? { fieldErrors: { email: result.message ?? "이미 가입된 이메일입니다." }, formError: null }
        : { fieldErrors: {}, formError: result.message ?? "이미 존재하는 데이터입니다." };
    case "INVALID_CSRF_TOKEN":
      return { fieldErrors: {}, formError: CSRF_FAILED_MESSAGE };
    default:
      if (result.status >= 500) {
        return { fieldErrors: {}, formError: "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요." };
      }
      return { fieldErrors: {}, formError: result.message ?? "요청을 처리하지 못했습니다." };
  }
}
