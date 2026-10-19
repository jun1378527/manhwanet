"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useRef, useState } from "react";

import { AuthAlert } from "@/components/auth/AuthAlert";
import { FormField } from "@/components/auth/FormField";
import { useSessionChrome } from "@/components/auth/SessionProvider";
import { SubmitButton } from "@/components/auth/SubmitButton";
import { signup } from "@/lib/auth/client-api";
import { type FeedbackTone, feedbackFor, retryAtText } from "@/lib/auth/messages";
import {
  type FieldErrors,
  NICKNAME_MAX_LENGTH,
  NICKNAME_MIN_LENGTH,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  passwordLength,
  validateSignup,
} from "@/lib/auth/validation";

type FormAlert = { tone: FeedbackTone; message: string; retryAt: string | null };

/**
 * 가입 폼. 백엔드가 가입과 동시에 로그인시키므로(자동 로그인), 성공하면 헤더·푸터를 바로 맞추고 returnTo 로 이동한다.
 * - 제출 중에는 버튼을 막고 입력을 읽기 전용으로 둔다. 503 AUTH_BUSY 등 실패에서도 자동으로 다시 보내지 않는다.
 * - 비밀번호 길이 안내는 백엔드와 같은 기준(NFKC 정규화 후 코드 포인트)이며 입력값을 바꾸지 않는다(trim·maxLength 없음).
 */
export function SignupForm({ returnTo }: { returnTo: string }) {
  const router = useRouter();
  const { noteLogin } = useSessionChrome();
  const [email, setEmail] = useState("");
  const [nickname, setNickname] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [alert, setAlert] = useState<FormAlert | null>(null);
  const [pending, setPending] = useState(false);
  const pendingRef = useRef(false);
  const alertRef = useRef<HTMLDivElement>(null);
  const emailRef = useRef<HTMLInputElement>(null);
  const nicknameRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  function focusFirstError(errors: FieldErrors): boolean {
    if (errors.email) emailRef.current?.focus();
    else if (errors.nickname) nicknameRef.current?.focus();
    else if (errors.password) passwordRef.current?.focus();
    else return false;
    return true;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pendingRef.current) return;

    const errors = validateSignup({ email, nickname, password });
    setFieldErrors(errors);
    setAlert(null);
    if (focusFirstError(errors)) {
      return;
    }

    pendingRef.current = true;
    setPending(true);
    const result = await signup({ email, nickname, password });
    if (result.kind === "ok") {
      noteLogin({ id: result.data.id, nickname: result.data.nickname, role: result.data.role });
      router.replace(returnTo);
      router.refresh();
      return; // 이동이 끝날 때까지 버튼을 막아 둔다
    }
    const feedback = feedbackFor(result, "signup");
    setFieldErrors(feedback.fieldErrors);
    setAlert(
      feedback.formError
        ? { tone: feedback.tone, message: feedback.formError, retryAt: retryAtText(feedback.retryAfterSeconds, Date.now()) }
        : null,
    );
    pendingRef.current = false;
    setPending(false);
    requestAnimationFrame(() => {
      if (!focusFirstError(feedback.fieldErrors)) {
        alertRef.current?.focus();
      }
    });
  }

  const length = passwordLength(password);
  const lengthOk = length >= PASSWORD_MIN_LENGTH && length <= PASSWORD_MAX_LENGTH;

  return (
    <form className="auth-form" method="post" noValidate onSubmit={handleSubmit} aria-busy={pending}>
      {alert ? <AuthAlert tone={alert.tone} message={alert.message} retryAt={alert.retryAt} alertRef={alertRef} /> : null}
      <FormField
        label="이메일"
        name="email"
        type="email"
        inputMode="email"
        autoComplete="username"
        required
        value={email}
        onChange={setEmail}
        error={fieldErrors.email}
        inputRef={emailRef}
        readOnly={pending}
        hint="로그인에 사용합니다. 대소문자는 구분하지 않습니다."
      />
      <FormField
        label="닉네임"
        name="nickname"
        autoComplete="nickname"
        required
        value={nickname}
        onChange={setNickname}
        error={fieldErrors.nickname}
        inputRef={nicknameRef}
        readOnly={pending}
        hint={`다른 사용자에게 보이는 이름입니다. ${NICKNAME_MIN_LENGTH}~${NICKNAME_MAX_LENGTH}자.`}
      />
      <FormField
        label="비밀번호"
        name="password"
        type="password"
        autoComplete="new-password"
        required
        value={password}
        onChange={setPassword}
        error={fieldErrors.password}
        inputRef={passwordRef}
        readOnly={pending}
        hint={
          <>
            <p className={`field__meter${lengthOk ? " field__meter--ok" : ""}`} aria-hidden="true">
              <span className="field__count">현재 {length}자</span>
              <span>
                {" "}
                / {PASSWORD_MIN_LENGTH}~{PASSWORD_MAX_LENGTH}자
              </span>
            </p>
            <p className="field__rule">
              {PASSWORD_MIN_LENGTH}자 이상 {PASSWORD_MAX_LENGTH}자 이하. 공백을 포함한 모든 문자를 쓸 수 있고 문자 종류를 섞을 필요는 없어요.
            </p>
            <details className="field__more">
              <summary>비밀번호 규칙 더 보기</summary>
              <ul className="field__rules">
                <li>긴 문장이나 비밀번호 관리자가 만든 값을 권장합니다.</li>
                <li>전각·반각처럼 같은 글자로 보는 문자는 같은 문자로 셉니다(유니코드 NFKC 정규화).</li>
                <li>흔한 비밀번호, 이메일·닉네임과 같은 비밀번호는 쓸 수 없습니다.</li>
              </ul>
            </details>
          </>
        }
      />
      <SubmitButton pending={pending} label="가입하기" pendingLabel="가입하는 중…" />
    </form>
  );
}
