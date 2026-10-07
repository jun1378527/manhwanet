"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

import { fetchSession, logout } from "@/lib/auth/client-api";
import { loginHref, safeReturnTo, signupHref } from "@/lib/auth/return-to";
import type { SessionState } from "@/lib/auth/types";

/**
 * 헤더의 로그인 상태 영역.
 * - 처음 상태는 서버(layout)가 GET /auth/me 로 확인한 값이다. 서버 상태가 바뀌면 layout 이 key 를 바꿔 새로 마운트한다.
 * - 로그인 상태에서 다른 화면으로 이동하거나 탭으로 돌아오면 다시 확인한다. 그때 401 이면 세션 만료로 안내한다.
 * - 확인 요청이 실패하면(백엔드 장애) 기존 표시를 유지한다. 장애를 로그아웃으로 바꾸지 않는다.
 */
export function AuthNav({ initial }: { initial: SessionState }) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [session, setSession] = useState<SessionState>(initial);
  const [expired, setExpired] = useState(false);
  const [logoutPending, setLogoutPending] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const sessionRef = useRef(session);
  const lastPathname = useRef(pathname);

  const applySession = useCallback((next: SessionState) => {
    const previous = sessionRef.current;
    if (next.status === "unavailable" && previous.status !== "unavailable") {
      return; // 일시적인 확인 실패: 기존 표시 유지
    }
    if (previous.status === "authenticated" && next.status === "anonymous") {
      setExpired(true);
    }
    sessionRef.current = next;
    setSession(next);
  }, []);

  const recheck = useCallback(async () => {
    if (sessionRef.current.status === "anonymous") {
      return;
    }
    applySession(await fetchSession());
  }, [applySession]);

  useEffect(() => {
    if (lastPathname.current === pathname) {
      return;
    }
    lastPathname.current = pathname;
    void recheck();
  }, [pathname, recheck]);

  useEffect(() => {
    function onVisibilityChange() {
      if (document.visibilityState === "visible") {
        void recheck();
      }
    }
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => document.removeEventListener("visibilitychange", onVisibilityChange);
  }, [recheck]);

  // 로그인 후 돌아올 곳: 로그인·가입 화면이면 그 화면의 returnTo 를 이어받고, 그 밖에는 현재 주소
  const onAuthPage = pathname === "/login" || pathname === "/signup";
  const query = searchParams.toString();
  const returnTo = onAuthPage ? safeReturnTo(searchParams.get("returnTo")) : safeReturnTo(query ? `${pathname}?${query}` : pathname);

  async function handleLogout() {
    if (logoutPending) return;
    setLogoutPending(true);
    setLogoutError(null);
    const result = await logout();
    if (result.kind === "ok") {
      sessionRef.current = { status: "anonymous" };
      setSession({ status: "anonymous" });
      setLogoutPending(false);
      router.refresh();
      return;
    }
    setLogoutPending(false);
    setLogoutError(
      result.kind === "unavailable"
        ? "서버에 연결할 수 없어 로그아웃하지 못했습니다. 잠시 후 다시 시도해 주세요."
        : "로그아웃하지 못했습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.",
    );
  }

  async function handleRetry() {
    setChecking(true);
    const next = await fetchSession();
    setChecking(false);
    if (next.status !== "unavailable") {
      sessionRef.current = next;
      setSession(next);
      router.refresh();
    }
  }

  return (
    <div className="auth-nav">
      {session.status === "authenticated" ? (
        <>
          <span className="auth-nav__user">
            <span className="visually-hidden">로그인한 사용자: </span>
            {session.user.nickname}
          </span>
          <button type="button" className="link-button" onClick={handleLogout} disabled={logoutPending}>
            {logoutPending ? "로그아웃 중…" : "로그아웃"}
          </button>
        </>
      ) : session.status === "anonymous" ? (
        <>
          <Link href={loginHref(returnTo)}>로그인</Link>
          <Link href={signupHref(returnTo)}>회원가입</Link>
        </>
      ) : (
        <>
          <span className="auth-nav__status">로그인 상태를 확인하지 못했습니다</span>
          <button type="button" className="link-button" onClick={handleRetry} disabled={checking}>
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
