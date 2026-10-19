import Link from "next/link";
import type { ReactNode } from "react";

import { describeReturnTo, loginHref, signupHref } from "@/lib/auth/return-to";

type Props = {
  mode: "login" | "signup";
  /** 검증된 내부 복귀 주소. 로그인·가입 전환 링크에 그대로 이어 붙인다 */
  returnTo: string;
  title: string;
  lead?: ReactNode;
  /** 폼 위 안내(세션 만료 등) */
  notice?: ReactNode;
  children: ReactNode;
};

/**
 * 로그인·회원가입 공통 화면 틀(대문·작품 찾기·상세와 같은 카드·버튼 스타일).
 * - 넓은 화면: 왼쪽 서비스 소개(장식 아님, 짧은 글) + 오른쪽 폼 카드. 좁은 화면: 폼 카드만, 소개는 아래로.
 * - 위쪽 "로그인 | 회원가입" 전환은 링크이며 현재 화면에 aria-current 를 둔다. 두 링크 모두 같은 returnTo 를 유지한다.
 * - "돌아갈 곳"은 검증된 returnTo 를 주소 대신 화면 종류(대문·작품 찾기·작품 화면 등)로만 알려 준다.
 * - 비밀번호 찾기·소셜 로그인은 아직 없어서 버튼·링크로 넣지 않고, 로그인 화면에 없다는 안내 문장만 둔다.
 */
export function AuthShell({ mode, returnTo, title, lead, notice, children }: Props) {
  const headingId = `${mode}-heading`;
  return (
    <div className="auth-layout">
      <section className="auth-card" aria-labelledby={headingId}>
        <nav className="auth-switch" aria-label="로그인 또는 회원가입">
          <Link
            href={loginHref(returnTo)}
            className="auth-switch__link"
            aria-current={mode === "login" ? "page" : undefined}
            replace
          >
            로그인
          </Link>
          <Link
            href={signupHref(returnTo)}
            className="auth-switch__link"
            aria-current={mode === "signup" ? "page" : undefined}
            replace
          >
            회원가입
          </Link>
        </nav>
        <h1 id={headingId} className="auth-card__title">
          {title}
        </h1>
        {lead ? <p className="auth-card__lead">{lead}</p> : null}
        <p className="auth-card__return">
          <span className="auth-card__return-label">돌아갈 곳</span> {describeReturnTo(returnTo)}
        </p>
        {notice}
        {children}
        {mode === "login" ? (
          <p className="auth-card__help">비밀번호 찾기와 소셜 로그인은 아직 제공하지 않아요.</p>
        ) : null}
        <p className="auth-card__switch">
          {mode === "login" ? (
            <>
              아직 계정이 없나요? <Link href={signupHref(returnTo)}>회원가입</Link>
            </>
          ) : (
            <>
              이미 계정이 있나요? <Link href={loginHref(returnTo)}>로그인</Link>
            </>
          )}
        </p>
      </section>

      <aside className="auth-intro" aria-label="만화넷 소개">
        <p className="auth-intro__kicker">만화넷</p>
        <p className="auth-intro__title">
          찾고, 매기고,
          <br />
          남기는 곳
        </p>
        <ul className="auth-intro__points">
          <li>웹툰과 만화를 한 목록에서 찾아보기</li>
          <li>다 본 작품에 0.5점 단위 별점 남기기</li>
          <li>짧은 리뷰로 다음 독자의 선택 돕기</li>
        </ul>
      </aside>
    </div>
  );
}
