"use client";

import { type ReactNode, useCallback, useEffect, useRef, useState } from "react";

import { fetchSession } from "@/lib/auth/client-api";

type Props = {
  /** 이 화면을 서버에서 그릴 때의 로그인 사용자 ID(세션에서 얻은 값) */
  viewerId: string;
  children: ReactNode;
};

/**
 * 내 활동 화면의 사용자 확인.
 * - 화면이 붙을 때, 탭으로 돌아올 때(visibilitychange), 창에 포커스가 올 때, 뒤로가기 캐시에서 다시 보일 때(pageshow)
 *   GET /auth/me 로 지금 세션 사용자를 확인한다.
 * - 다른 사용자로 바뀌었거나 로그아웃되었으면 즉시 목록을 화면에서 지우고(이전 사용자의 기록이 남지 않게) 서버에서 다시 그린다.
 *   다시 그릴 때는 문서를 새로 받아(location.replace) 클라이언트 라우터 캐시·처음 받은 페이지 데이터에 남은 이전 기록도 버린다.
 *   로그아웃이면 서버가 로그인 화면으로 보낸다.
 * - 확인 요청이 여러 번 겹치면 마지막 요청의 결과만 쓴다(늦게 도착한 응답 무시). 확인 실패(연결 오류)는 화면을 바꾸지 않는다.
 */
export function MeViewerGuard({ viewerId, children }: Props) {
  const [switched, setSwitched] = useState(false);
  const seq = useRef(0);

  const check = useCallback(async () => {
    const mine = ++seq.current;
    const session = await fetchSession();
    if (mine !== seq.current) return; // 더 최근 확인이 있다
    if (session.status === "unavailable") return;
    if (session.status === "anonymous" || session.user.id !== viewerId) {
      setSwitched(true);
      // 문서를 통째로 다시 받는다: 화면뿐 아니라 처음 받은 페이지 데이터·라우터 캐시에 남은 이전 사용자의 기록까지 버린다
      window.location.replace(window.location.href);
    }
  }, [viewerId]);

  useEffect(() => {
    // 화면이 붙은 직후 한 번 확인한다(렌더 중이 아니라 다음 차례에)
    const first = window.setTimeout(() => void check(), 0);
    const onVisible = () => {
      if (document.visibilityState === "visible") void check();
    };
    const onFocus = () => void check();
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) void check();
    };
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("focus", onFocus);
    window.addEventListener("pageshow", onPageShow);
    return () => {
      window.clearTimeout(first);
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("focus", onFocus);
      window.removeEventListener("pageshow", onPageShow);
    };
  }, [check]);

  if (switched) {
    return (
      <p className="me-state" role="status">
        로그인한 사용자가 바뀌어 내 활동을 다시 불러오는 중입니다…
      </p>
    );
  }
  return <>{children}</>;
}
