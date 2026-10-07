"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useRef, useState } from "react";

import { FormField } from "@/components/auth/FormField";
import { login } from "@/lib/auth/client-api";
import { feedbackFor } from "@/lib/auth/messages";
import { type FieldErrors, validateLogin } from "@/lib/auth/validation";

/**
 * 로그인 폼. 성공하면 returnTo(검증된 내부 경로)로 이동하고 서버 컴포넌트(헤더)를 새로 그린다.
 * 폼은 method="post" 이므로 스크립트가 붙기 전에 제출되어도 비밀번호가 URL 에 실리지 않는다.
 */
export function LoginForm({ returnTo }: { returnTo: string }) {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  function focusFirstError(errors: FieldErrors) {
    if (errors.email) emailRef.current?.focus();
    else if (errors.password) passwordRef.current?.focus();
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;

    const errors = validateLogin({ email, password });
    setFieldErrors(errors);
    setFormError(null);
    if (Object.keys(errors).length > 0) {
      focusFirstError(errors);
      return;
    }

    setPending(true);
    const result = await login({ email, password });
    if (result.kind === "ok") {
      router.replace(returnTo);
      router.refresh();
      return;
    }
    const feedback = feedbackFor(result, "login");
    setFieldErrors(feedback.fieldErrors);
    setFormError(feedback.formError);
    setPending(false);
    if (Object.keys(feedback.fieldErrors).length > 0) {
      focusFirstError(feedback.fieldErrors);
    } else if (result.kind === "error" && result.code === "INVALID_CREDENTIALS") {
      passwordRef.current?.select();
    }
  }

  return (
    <form className="auth-form" method="post" noValidate onSubmit={handleSubmit} aria-busy={pending}>
      {formError ? (
        <p className="form-alert" role="alert">
          {formError}
        </p>
      ) : null}
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
      />
      <button type="submit" className="button auth-form__submit" disabled={pending}>
        {pending ? "로그인 중…" : "로그인"}
      </button>
    </form>
  );
}
