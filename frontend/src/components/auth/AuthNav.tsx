"use client";

import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";

import { useSessionChrome } from "@/components/auth/SessionProvider";
import { loginHref, safeReturnTo, signupHref } from "@/lib/auth/return-to";

/**
 * 헤더의 로그인 상태 영역. 문구는 SessionProvider 의 상태를 그대로 쓴다.
 * - 로그인 사용자 영역은 내 활동(/me) 링크다.
 * - 이 탭에서 세션이 만료되면 안내를 보여주고, 다시 로그인하면 안내가 사라진다.
 */
export function AuthNav() {
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { session, expired, logoutPending, logoutError, checking, logout, retry } = useSessionChrome();

  // 로그인 후 돌아올 곳: 로그인·가입 화면이면 그 화면의 returnTo 를 이어받고, 그 밖에는 현재 주소
  const onAuthPage = pathname === "/login" || pathname === "/signup";
  const query = searchParams.toString();
  const returnTo = onAuthPage ? safeReturnTo(searchParams.get("returnTo")) : safeReturnTo(query ? `${pathname}?${query}` : pathname);

  return (
    <div className="auth-nav">
      {session.status === "authenticated" ? (
        <>
          <Link href="/me" className="auth-nav__user" aria-current={pathname === "/me" ? "page" : undefined}>
            <span className="visually-hidden">로그인한 사용자 </span>
            <span dir="auto" className="auth-nav__nickname">
              {session.user.nickname}
            </span>
            <span className="auth-nav__me">내 활동</span>
          </Link>
          <button type="button" className="link-button" onClick={logout} disabled={logoutPending}>
            {logoutPending ? "로그아웃 중…" : "로그아웃"}
          </button>
        </>
      ) : session.status === "anonymous" ? (
        <>
          <Link href={loginHref(returnTo)} aria-current={pathname === "/login" ? "page" : undefined}>
            로그인
          </Link>
          <Link href={signupHref(returnTo)} aria-current={pathname === "/signup" ? "page" : undefined}>
            회원가입
          </Link>
        </>
      ) : (
        <>
          <span className="auth-nav__status">로그인 상태를 확인하지 못했습니다</span>
          <button type="button" className="link-button" onClick={retry} disabled={checking}>
            {checking ? "확인 중…" : "다시 확인"}
          </button>
        </>
      )}
      {expired && session.status === "anonymous" ? (
        <p className="auth-nav__notice" role="status">
          세션이 만료되어 로그아웃되었습니다. <Link href={loginHref(returnTo, { expired: true })}>다시 로그인</Link>
        </p>
      ) : null}
      {logoutError ? (
        <p className="auth-nav__notice auth-nav__notice--error" role="alert">
          {logoutError}
        </p>
      ) : null}
    </div>
  );
}
