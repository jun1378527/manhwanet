"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useRef, useState } from "react";

import { AuthAlert } from "@/components/auth/AuthAlert";
import { FormField } from "@/components/auth/FormField";
import { useSessionChrome } from "@/components/auth/SessionProvider";
import { SubmitButton } from "@/components/auth/SubmitButton";
import { login } from "@/lib/auth/client-api";
import { type FeedbackTone, feedbackFor, retryAtText } from "@/lib/auth/messages";
import { type FieldErrors, validateLogin } from "@/lib/auth/validation";

type FormAlert = { tone: FeedbackTone; message: string; retryAt: string | null };

/**
 * 로그인 폼. 성공하면 헤더·푸터를 그 사용자로 바로 맞추고, returnTo(검증된 내부 경로)로 이동한 뒤 서버 화면을 새로 그린다.
 * - 폼은 method="post" 이므로 스크립트가 붙기 전에 제출되어도 비밀번호가 URL 에 실리지 않는다.
 * - 제출 중에는 버튼을 막고 입력을 읽기 전용으로 둬 요청이 하나만 나간다. 실패해도 자동으로 다시 보내지 않는다.
 * - 입력값(비밀번호 포함)은 이 컴포넌트 상태에만 있고 URL·웹 저장소·로그에 남기지 않는다.
 */
export function LoginForm({ returnTo }: { returnTo: string }) {
  const router = useRouter();
  const { noteLogin } = useSessionChrome();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [alert, setAlert] = useState<FormAlert | null>(null);
  const [pending, setPending] = useState(false);
  const pendingRef = useRef(false);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const alertRef = useRef<HTMLDivElement>(null);

  function focusFirstError(errors: FieldErrors): boolean {
    if (errors.email) emailRef.current?.focus();
    else if (errors.password) passwordRef.current?.focus();
    else return false;
    return true;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // 같은 차례에 두 번 눌러도(상태 반영 전) 요청은 하나만
    if (pendingRef.current) return;

    const errors = validateLogin({ email, password });
    setFieldErrors(errors);
    setAlert(null);
    if (focusFirstError(errors)) {
      return;
    }

    pendingRef.current = true;
    setPending(true);
    const result = await login({ email, password });
    if (result.kind === "ok") {
      noteLogin({ id: result.data.id, nickname: result.data.nickname, role: result.data.role });
      router.replace(returnTo);
      router.refresh();
      return; // 이동이 끝날 때까지 버튼을 막아 둔다
    }
    const feedback = feedbackFor(result, "login");
    setFieldErrors(feedback.fieldErrors);
    setAlert(
      feedback.formError
        ? { tone: feedback.tone, message: feedback.formError, retryAt: retryAtText(feedback.retryAfterSeconds, Date.now()) }
        : null,
    );
    pendingRef.current = false;
    setPending(false);
    requestAnimationFrame(() => {
      if (focusFirstError(feedback.fieldErrors)) return;
      if (result.kind === "error" && result.code === "INVALID_CREDENTIALS") {
        // 로그인 실패: 비밀번호를 다시 입력하기 쉽게 선택해 둔다(안내는 role=alert 로 읽힌다)
        passwordRef.current?.focus();
        passwordRef.current?.select();
      } else {
        alertRef.current?.focus();
      }
    });
  }

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
      />
      <FormField
        label="비밀번호"
        name="password"
        type="password"
        autoComplete="current-password"
        required
        value={password}
        onChange={setPassword}
        error={fieldErrors.password}
        inputRef={passwordRef}
        readOnly={pending}
      />
      <SubmitButton pending={pending} label="로그인" pendingLabel="로그인하는 중…" />
    </form>
  );
}
