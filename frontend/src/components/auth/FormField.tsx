"use client";

import { type InputHTMLAttributes, type ReactNode, type Ref, useId, useState } from "react";

type Props = {
  label: string;
  name: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
  hint?: ReactNode;
  inputRef?: Ref<HTMLInputElement>;
  /** password 이면 "비밀번호 표시" 전환 버튼을 붙인다. */
  type?: "email" | "text" | "password";
} & Pick<InputHTMLAttributes<HTMLInputElement>, "autoComplete" | "inputMode" | "required" | "disabled">;

/**
 * 레이블·안내·오류가 연결된 입력 칸.
 * - 오류는 aria-invalid 와 aria-describedby 로 입력 칸에 연결한다.
 * - maxLength 를 두지 않는다. 붙여넣은 값이 조용히 잘리지 않게 하고 길이는 검사 단계에서 안내한다.
 */
export function FormField({ label, name, value, onChange, error, hint, inputRef, type = "text", ...rest }: Props) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const [revealed, setRevealed] = useState(false);
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ") || undefined;
  const isPassword = type === "password";

  return (
    <div className="field">
      <label className="field__label" htmlFor={id}>
        {label}
      </label>
      <div className={isPassword ? "field__control field__control--password" : "field__control"}>
        <input
          id={id}
          ref={inputRef}
          name={name}
          type={isPassword && revealed ? "text" : type}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          {...rest}
        />
        {isPassword ? (
          <button
            type="button"
            className="field__reveal"
            aria-controls={id}
            aria-pressed={revealed}
            onClick={() => setRevealed((current) => !current)}
          >
            {revealed ? "숨기기" : "표시"}
            <span className="visually-hidden"> 비밀번호</span>
          </button>
        ) : null}
      </div>
      {hint ? (
        <div id={hintId} className="field__hint">
          {hint}
        </div>
      ) : null}
      {error ? (
        <p id={errorId} className="field__error">
          {error}
        </p>
      ) : null}
    </div>
  );
}
