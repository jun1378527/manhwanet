import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { parseRetryAfter, type ApiFailure } from "./client-api";
import { CSRF_FAILED_MESSAGE, UNAVAILABLE_MESSAGE, authBusyMessage, feedbackFor, retryAfterText, retryAtText } from "./messages";

function failure(partial: Partial<ApiFailure>): ApiFailure {
  return {
    kind: "error",
    status: 400,
    code: undefined,
    message: undefined,
    fieldErrors: [],
    retryAfterSeconds: null,
    ...partial,
  };
}

describe("feedbackFor", () => {
  it("connects backend fieldErrors to inputs", () => {
    const feedback = feedbackFor(
      failure({
        code: "INVALID_INPUT",
        fieldErrors: [
          { field: "password", reason: "너무 흔한 비밀번호입니다. 다른 비밀번호를 사용해 주세요." },
          { field: "nickname", reason: "닉네임은 앞뒤 공백을 제외하고 2자 이상 30자 이하여야 합니다." },
        ],
      }),
      "signup",
    );
    assert.equal(feedback.fieldErrors.password, "너무 흔한 비밀번호입니다. 다른 비밀번호를 사용해 주세요.");
    assert.match(feedback.fieldErrors.nickname ?? "", /2자 이상 30자/);
    assert.equal(feedback.formError, null);
  });

  it("shows unknown fields as a form error", () => {
    const feedback = feedbackFor(
      failure({ code: "INVALID_INPUT", message: "입력값이 올바르지 않습니다.", fieldErrors: [{ field: "body", reason: "형식 오류" }] }),
      "login",
    );
    assert.deepEqual(feedback.fieldErrors, {});
    assert.equal(feedback.formError, "형식 오류");
  });

  it("does not reveal which credential was wrong", () => {
    const feedback = feedbackFor(failure({ status: 401, code: "INVALID_CREDENTIALS" }), "login");
    assert.equal(feedback.formError, "이메일 또는 비밀번호가 올바르지 않습니다.");
    assert.deepEqual(feedback.fieldErrors, {});
  });

  it("explains 429 with Retry-After", () => {
    const feedback = feedbackFor(failure({ status: 429, code: "TOO_MANY_LOGIN_ATTEMPTS", retryAfterSeconds: 900 }), "login");
    assert.equal(feedback.formError, "로그인 시도가 너무 많습니다. 약 15분 후 다시 시도해 주세요.");
  });

  it("explains 503 AUTH_BUSY as overload with Retry-After, not as an outage", () => {
    for (const context of ["login", "signup"] as const) {
      const feedback = feedbackFor(failure({ status: 503, code: "AUTH_BUSY", retryAfterSeconds: 1 }), context);
      assert.equal(feedback.formError, "요청이 많습니다. 약 1초 후 다시 시도해 주세요.");
      assert.notEqual(feedback.formError, UNAVAILABLE_MESSAGE);
      assert.deepEqual(feedback.fieldErrors, {});
    }
    // 저장소 포화는 Retry-After 가 분 단위일 수 있다(올림)
    assert.equal(authBusyMessage(841), "요청이 많습니다. 약 15분 후 다시 시도해 주세요.");
    assert.equal(authBusyMessage(null), "요청이 많습니다. 잠시 후 다시 시도해 주세요.");
  });

  it("puts duplicate email on the email input when signing up", () => {
    const feedback = feedbackFor(failure({ status: 409, code: "DUPLICATE_RESOURCE", message: "이미 가입된 이메일입니다." }), "signup");
    assert.equal(feedback.fieldErrors.email, "이미 가입된 이메일입니다.");
  });

  it("distinguishes outage and CSRF failure", () => {
    assert.equal(feedbackFor({ kind: "unavailable" }, "login").formError, UNAVAILABLE_MESSAGE);
    assert.equal(feedbackFor(failure({ status: 403, code: "INVALID_CSRF_TOKEN" }), "login").formError, CSRF_FAILED_MESSAGE);
  });
});

describe("Retry-After", () => {
  it("parses delta seconds only", () => {
    assert.equal(parseRetryAfter("900"), 900);
    assert.equal(parseRetryAfter(" 60 "), 60);
    assert.equal(parseRetryAfter(null), null);
    assert.equal(parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"), null);
    assert.equal(parseRetryAfter("-1"), null);
  });

  it("formats as minutes", () => {
    assert.equal(retryAfterText(900), "약 15분 후");
    assert.equal(retryAfterText(61), "약 2분 후");
    assert.equal(retryAfterText(30), "약 30초 후");
    assert.equal(retryAfterText(null), "잠시 후");
  });
});

describe("feedback tone", () => {
  it("구분: 401 은 error, 429·503 AUTH_BUSY 는 wait(Retry-After 포함), 연결 실패는 offline", () => {
    const denied = feedbackFor(failure({ status: 401, code: "INVALID_CREDENTIALS" }), "login");
    assert.equal(denied.tone, "error");
    assert.equal(denied.retryAfterSeconds, null);
    const limited = feedbackFor(failure({ status: 429, code: "TOO_MANY_LOGIN_ATTEMPTS", retryAfterSeconds: 900 }), "login");
    assert.equal(limited.tone, "wait");
    assert.equal(limited.retryAfterSeconds, 900);
    const busy = feedbackFor(failure({ status: 503, code: "AUTH_BUSY", retryAfterSeconds: 3 }), "signup");
    assert.equal(busy.tone, "wait");
    assert.equal(busy.retryAfterSeconds, 3);
    const offline = feedbackFor({ kind: "unavailable" }, "login");
    assert.equal(offline.tone, "offline");
    assert.equal(offline.formError, UNAVAILABLE_MESSAGE);
    const csrf = feedbackFor(failure({ status: 403, code: "INVALID_CSRF_TOKEN" }), "signup");
    assert.equal(csrf.tone, "error");
    assert.equal(csrf.formError, CSRF_FAILED_MESSAGE);
  });

  it("1분 이상 기다려야 하면 대략의 시각(한국 시간)을 함께 안내한다", () => {
    const at = Date.parse("2026-10-17T06:00:00Z"); // 15:00 KST
    assert.equal(retryAtText(900, at), "오후 3:15");
    assert.equal(retryAtText(59, at), null);
    assert.equal(retryAtText(null, at), null);
  });
});
