/** 로그인·가입 제출 단추. 진행 중에는 막고 진행 표시와 문구를 바꾼다(스크린 리더는 바뀐 이름을 읽는다). */
export function SubmitButton({ pending, label, pendingLabel }: { pending: boolean; label: string; pendingLabel: string }) {
  return (
    <button type="submit" className="button auth-form__submit" disabled={pending} aria-disabled={pending}>
      {pending ? <span className="auth-form__spinner" aria-hidden="true" /> : null}
      {pending ? pendingLabel : label}
    </button>
  );
}
