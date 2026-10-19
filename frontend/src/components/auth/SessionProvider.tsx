"use client";

import { usePathname, useRouter } from "next/navigation";
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";

import { fetchSession, logout as logoutRequest } from "@/lib/auth/client-api";
import {
  type NavSessionState,
  type NavUser,
  type NavView,
  type SessionDecision,
  adoptServerProps,
  applyClientSession,
  applyExplicitLogout,
  toNavSession,
} from "@/lib/auth/nav-session";

type SessionChrome = {
  session: NavSessionState;
  expired: boolean;
  logoutPending: boolean;
  logoutError: string | null;
  checking: boolean;
  logout: () => void;
  retry: () => void;
  /** 로그인·가입 성공 직후. 같은 사용자 ID여도 만료 안내를 지우고 닉네임을 바로 되돌린다. */
  noteLogin: (user: NavUser) => void;
};

const SessionContext = createContext<SessionChrome | null>(null);

/**
 * 헤더와 푸터가 같은 로그인 상태를 보게 하는 클라이언트 상태.
 * - 처음 값은 서버 layout 이 확인한 세션이다. 이후에는 이 상태가 화면 문구의 기준이다.
 * - 서버 props 는 새 로그인만 반영한다. 만료 안내를 예전 로그인 props 로 되돌리거나, 연결 실패를 로그아웃으로 보지 않는다.
 * - 경로가 바뀌거나, 탭으로 돌아오거나, 뒤로가기 캐시에서 다시 보이면 세션을 다시 확인한다.
 * - 확인이 겹치면 마지막 요청만 반영한다.
 */
export function SessionProvider({ initial, children }: { initial: NavSessionState; children: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [view, setView] = useState<NavView>({ session: initial, expired: false });
  const viewRef = useRef(view);
  const seq = useRef(0);
  const lastPathname = useRef(pathname);
  const [logoutPending, setLogoutPending] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);

  useEffect(() => {
    setView((current) => {
      const next = adoptServerProps(current, initial);
      if (next !== current) {
        viewRef.current = next;
      }
      return next;
    });
  }, [initial]);

  const commit = useCallback(
    (decision: SessionDecision) => {
      if (decision.view !== viewRef.current) {
        viewRef.current = decision.view;
        setView(decision.view);
      }
      if (decision.refresh) {
        router.refresh();
      }
    },
    [router],
  );

  const recheck = useCallback(async () => {
    const mine = ++seq.current;
    const fetched = toNavSession(await fetchSession());
    if (mine !== seq.current) {
      return;
    }
    commit(applyClientSession(viewRef.current, fetched));
  }, [commit]);

  useEffect(() => {
    if (lastPathname.current === pathname) {
      return;
    }
    lastPathname.current = pathname;
    void recheck();
  }, [pathname, recheck]);

  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState === "visible") {
        void recheck();
      }
    };
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) {
        void recheck();
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("pageshow", onPageShow);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("pageshow", onPageShow);
    };
  }, [recheck]);

  const noteLogin = useCallback((user: NavUser) => {
    seq.current += 1;
    const decision = applyClientSession(viewRef.current, { status: "authenticated", user });
    if (decision.view !== viewRef.current) {
      viewRef.current = decision.view;
      setView(decision.view);
    }
  }, []);

  const logout = useCallback(async () => {
    if (logoutPending) {
      return;
    }
    setLogoutPending(true);
    setLogoutError(null);
    const mine = ++seq.current;
    const result = await logoutRequest();
    if (mine !== seq.current) {
      setLogoutPending(false);
      return;
    }
    if (result.kind === "ok") {
      const next = applyExplicitLogout();
      viewRef.current = next;
      setView(next);
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
  }, [logoutPending, router]);

  const retry = useCallback(async () => {
    setChecking(true);
    const mine = ++seq.current;
    const fetched = toNavSession(await fetchSession());
    if (mine !== seq.current) {
      setChecking(false);
      return;
    }
    setChecking(false);
    if (fetched.status === "unavailable") {
      return;
    }
    commit(applyClientSession(viewRef.current, fetched));
  }, [commit]);

  const value: SessionChrome = {
    session: view.session,
    expired: view.expired,
    logoutPending,
    logoutError,
    checking,
    logout,
    retry,
    noteLogin,
  };

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSessionChrome(): SessionChrome {
  const value = useContext(SessionContext);
  if (value === null) {
    throw new Error("SessionProvider is missing");
  }
  return value;
}
