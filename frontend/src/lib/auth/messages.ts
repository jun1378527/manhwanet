/**
 * 인증 API 실패를 화면 안내로 바꾼다.
 */
import type { ApiCallResult } from "./client-api";
import type { FieldErrors, FieldName } from "./validation";

/**
 * 폼 전체 안내의 종류(화면 색·아이콘을 고르는 데만 쓴다).
 * - error: 요청이 처리되었지만 거절됨(401 로그인 실패, 입력·보안 토큰 오류 등)
 * - wait: 지금은 받지 않음(429 시도 제한, 503 AUTH_BUSY 과부하). Retry-After 를 안내하고 자동으로 다시 보내지 않는다
 * - offline: 서버에 닿지 못함(연결 실패·시간 초과·예상하지 못한 응답)
 */
export type FeedbackTone = "error" | "wait" | "offline";

export type FormFeedback = {
  /** 입력 칸에 연결할 오류 */
  fieldErrors: FieldErrors;
  /** 폼 전체 안내 (입력 칸에 연결할 수 없는 오류) */
  formError: string | null;
  /** formError 의 종류 */
  tone: FeedbackTone;
  /** wait 일 때 서버가 알려 준 Retry-After(초). 없으면 null */
  retryAfterSeconds: number | null;
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

/** 응답 코드별 안내 문구(종류·Retry-After 는 feedbackFor 가 붙인다) */
function baseFeedback(
  result: Exclude<ApiCallResult<unknown>, { kind: "ok" }>,
  context: "login" | "signup",
): Pick<FormFeedback, "fieldErrors" | "formError"> & Partial<FormFeedback> {
  if (result.kind === "unavailable") {
    return { fieldErrors: {}, formError: UNAVAILABLE_MESSAGE, tone: "offline", retryAfterSeconds: null };
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

/**
 * 실패 결과를 입력 칸 오류와 폼 오류로 나눈다.
 * @param context login: 로그인 실패는 이유를 구분하지 않는다. signup: 409 는 이메일 칸 오류로 표시한다.
 */
export function feedbackFor(result: Exclude<ApiCallResult<unknown>, { kind: "ok" }>, context: "login" | "signup"): FormFeedback {
  const base = baseFeedback(result, context);
  const waiting = result.kind === "error" && (result.code === "TOO_MANY_LOGIN_ATTEMPTS" || result.code === "AUTH_BUSY");
  return {
    fieldErrors: base.fieldErrors,
    formError: base.formError,
    tone: base.tone ?? (waiting ? "wait" : "error"),
    retryAfterSeconds: waiting && result.kind === "error" ? result.retryAfterSeconds : null,
  };
}

/**
 * Retry-After 가 1분 이상이면 다시 시도할 수 있는 대략의 시각을 "오후 3:12" 처럼 돌려준다(한국 시간).
 * 화면 안내용이며, 이 시각에 자동으로 다시 보내지 않는다.
 */
export function retryAtText(retryAfterSeconds: number | null, receivedAt: number): string | null {
  if (retryAfterSeconds === null || retryAfterSeconds < 60) {
    return null;
  }
  return new Intl.DateTimeFormat("ko-KR", { hour: "numeric", minute: "2-digit", timeZone: "Asia/Seoul" }).format(
    new Date(receivedAt + retryAfterSeconds * 1000),
  );
}
