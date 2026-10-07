import type { Metadata } from "next";
import Link from "next/link";

import { AuthNav } from "@/components/auth/AuthNav";
import { getSession } from "@/lib/auth/session";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "만화넷",
    template: "%s · 만화넷",
  },
  description: "한국 웹툰과 일본 만화를 한곳에서 찾아보는 서비스",
};

/**
 * 헤더의 로그인 상태는 요청마다 서버에서 확인한다. (세션 쿠키를 읽으므로 모든 화면이 동적 렌더링된다)
 * 서버 상태가 바뀌면(로그인·로그아웃 후 router.refresh) key 가 바뀌어 AuthNav 를 새 상태로 다시 만든다.
 */
export default async function RootLayout({ children }: LayoutProps<"/">) {
  const session = await getSession();
  const sessionKey = session.status === "authenticated" ? `authenticated:${session.user.id}` : session.status;

  return (
    <html lang="ko">
      <body>
        <a className="skip-link" href="#main">
          본문으로 건너뛰기
        </a>
        <header className="site-header">
          <div className="container site-header__inner">
            <Link href="/" className="site-header__logo">
              만화넷
            </Link>
            <div className="site-header__menus">
              <nav aria-label="주 메뉴">
                <Link href="/works">작품 목록</Link>
              </nav>
              <AuthNav key={sessionKey} initial={session} />
            </div>
          </div>
        </header>
        <main id="main" className="container" tabIndex={-1}>
          {children}
        </main>
      </body>
    </html>
  );
}
