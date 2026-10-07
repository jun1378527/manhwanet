"use client";

import { useRouter } from "next/navigation";
import { type FormEvent, useRef, useState } from "react";

import { FormField } from "@/components/auth/FormField";
import { signup } from "@/lib/auth/client-api";
import { feedbackFor } from "@/lib/auth/messages";
import {
  type FieldErrors,
  NICKNAME_MAX_LENGTH,
  NICKNAME_MIN_LENGTH,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  passwordLength,
  validateSignup,
} from "@/lib/auth/validation";

/**
 * 가입 폼. 백엔드가 가입과 동시에 로그인시키므로(자동 로그인), 성공하면 바로 returnTo 로 이동하고 헤더를 새로 그린다.
 */
export function SignupForm({ returnTo }: { returnTo: string }) {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [nickname, setNickname] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const emailRef = useRef<HTMLInputElement>(null);
  const nicknameRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  function focusFirstError(errors: FieldErrors) {
    if (errors.email) emailRef.current?.focus();
    else if (errors.nickname) nicknameRef.current?.focus();
    else if (errors.password) passwordRef.current?.focus();
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;

    const errors = validateSignup({ email, nickname, password });
    setFieldErrors(errors);
    setFormError(null);
    if (Object.keys(errors).length > 0) {
      focusFirstError(errors);
      return;
    }

    setPending(true);
    const result = await signup({ email, nickname, password });
    if (result.kind === "ok") {
      router.replace(returnTo);
      router.refresh();
      return;
    }
    const feedback = feedbackFor(result, "signup");
    setFieldErrors(feedback.fieldErrors);
    setFormError(feedback.formError);
    setPending(false);
    focusFirstError(feedback.fieldErrors);
  }

  const length = passwordLength(password);

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
        hint={
          <>
            <p>
              {PASSWORD_MIN_LENGTH}자 이상 {PASSWORD_MAX_LENGTH}자 이하. 공백을 포함한 모든 문자를 쓸 수 있고, 문자 종류를 섞을 필요는
              없습니다. 긴 문장이나 비밀번호 관리자가 만든 값을 권장합니다.
            </p>
            <p>
              전각·반각처럼 같은 글자로 보는 문자는 같은 문자로 셉니다(유니코드 NFKC 정규화). 흔한 비밀번호, 이메일·닉네임과 같은
              비밀번호는 쓸 수 없습니다.
            </p>
            <p className="field__count" aria-hidden="true">
              현재 {length}자
            </p>
          </>
        }
      />
      <button type="submit" className="button auth-form__submit" disabled={pending}>
        {pending ? "가입하는 중…" : "가입하기"}
      </button>
    </form>
  );
}
