"use client";

import { type InputHTMLAttributes, type KeyboardEvent, type ReactNode, type Ref, useEffect, useId, useRef, useState } from "react";

type Props = {
  label: string;
  name: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
  hint?: ReactNode;
  inputRef?: Ref<HTMLInputElement>;
  /** password 이면 "비밀번호 표시" 전환 버튼과 Caps Lock 안내를 붙인다. */
  type?: "email" | "text" | "password";
} & Pick<InputHTMLAttributes<HTMLInputElement>, "autoComplete" | "inputMode" | "required" | "disabled" | "readOnly">;

/**
 * 레이블·안내·오류가 연결된 입력 칸.
 * - 오류는 aria-invalid 와 aria-describedby 로 입력 칸에 연결한다(오류를 안내보다 먼저 읽도록 앞에 둔다).
 * - maxLength·trim 을 두지 않는다. 붙여넣은 값이 조용히 잘리거나 바뀌지 않게 하고 길이는 검사 단계에서 안내한다.
 * - 비밀번호 표시 상태는 이 칸에만 있고 어디에도 저장하지 않는다. Caps Lock 안내는 키 상태만 보고 입력값은 보지 않는다.
 * - 폼을 제출하는 순간 비밀번호를 다시 가린다(브라우저·비밀번호 관리자가 일반 텍스트 칸으로 보지 않게, 옆 사람이 보지 않게).
 * - 표시 단추의 접근 가능한 이름은 상태에 따라 "비밀번호 표시"/"비밀번호 숨기기"로 바뀐다(눈에 보이는 "표시"/"숨기기"를 포함).
 */
export function FormField({ label, name, value, onChange, error, hint, inputRef, type = "text", ...rest }: Props) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const capsId = `${id}-caps`;
  const [revealed, setRevealed] = useState(false);
  const [capsLock, setCapsLock] = useState(false);
  const isPassword = type === "password";
  const wrapperRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isPassword) return;
    const form = wrapperRef.current?.closest("form");
    if (!form) return;
    // 캡처 단계: 폼의 onSubmit 보다 먼저 가린다
    const conceal = () => {
      const input = wrapperRef.current?.querySelector("input");
      if (input) input.type = "password"; // 제출 이벤트를 받는 쪽이 바로 가려진 칸을 보도록 즉시 바꾸고
      setRevealed(false); // React 상태도 맞춘다
    };
    form.addEventListener("submit", conceal, true);
    return () => form.removeEventListener("submit", conceal, true);
  }, [isPassword]);
  const describedBy =
    [error ? errorId : null, isPassword && capsLock ? capsId : null, hint ? hintId : null].filter(Boolean).join(" ") || undefined;

  function trackCapsLock(event: KeyboardEvent<HTMLInputElement>) {
    if (isPassword && typeof event.getModifierState === "function") {
      setCapsLock(event.getModifierState("CapsLock"));
    }
  }

  return (
    <div ref={wrapperRef} className={error ? "field field--invalid" : "field"}>
      <label className="field__label" htmlFor={id}>
        {label}
        {rest.required ? <span className="field__required"> (필수)</span> : null}
      </label>
      <div className={isPassword ? "field__control field__control--password" : "field__control"}>
        <input
          id={id}
          ref={inputRef}
          name={name}
          type={isPassword && revealed ? "text" : type}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={trackCapsLock}
          onKeyUp={trackCapsLock}
          onBlur={() => setCapsLock(false)}
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
            onClick={() => setRevealed((current) => !current)}
          >
            <svg width="18" height="18" viewBox="0 0 20 20" aria-hidden="true">
              <path
                d="M1.8 10S5 4.5 10 4.5 18.2 10 18.2 10 15 15.5 10 15.5 1.8 10 1.8 10Z"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinejoin="round"
              />
              <circle cx="10" cy="10" r="2.6" fill="none" stroke="currentColor" strokeWidth="1.6" />
              {revealed ? <path d="M3 17 17 3" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /> : null}
            </svg>
            <span className="visually-hidden">비밀번호 </span>
            <span className="field__reveal-text">{revealed ? "숨기기" : "표시"}</span>
          </button>
        ) : null}
      </div>
      {error ? (
        <p id={errorId} className="field__error">
          <span aria-hidden="true" className="field__error-icon">
            !
          </span>
          {error}
        </p>
      ) : null}
      {isPassword && capsLock ? (
        <p id={capsId} className="field__warn" role="status">
          Caps Lock이 켜져 있습니다.
        </p>
      ) : null}
      {hint ? (
        <div id={hintId} className="field__hint">
          {hint}
        </div>
      ) : null}
    </div>
  );
}
