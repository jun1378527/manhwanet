import type { Metadata } from "next";
import Link from "next/link";

import { AuthNav } from "@/components/auth/AuthNav";
import { SessionProvider } from "@/components/auth/SessionProvider";
import { SiteFooter } from "@/components/SiteFooter";
import { toNavSession } from "@/lib/auth/nav-session";
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
 * 그 값을 처음 상태로 넘기고, 이후 헤더·푸터 문구는 클라이언트 상태가 서버 props 와 맞춰 간다.
 * 사용자 ID만 넣은 key 로 다시 마운트하지 않는다. 같은 계정으로 다시 로그인해도 상태가 남기 때문이다.
 */
export default async function RootLayout({ children }: LayoutProps<"/">) {
  const session = await getSession();

  return (
    <html lang="ko">
      <body>
        <SessionProvider initial={toNavSession(session)}>
          <a className="skip-link" href="#main">
            본문으로 건너뛰기
          </a>
          <header className="site-header">
            <div className="container site-header__inner">
              <Link href="/" className="site-header__logo">
                만화넷
              </Link>
              {/* 랭킹·리뷰는 아직 별도 화면이 없어 홈의 해당 영역으로 이동한다 */}
              <nav className="site-header__nav" aria-label="주 메뉴">
                <Link href="/works">작품 찾기</Link>
                <Link href="/#works">랭킹</Link>
                <Link href="/#reviews">리뷰</Link>
              </nav>
              <div className="site-header__auth">
                <AuthNav />
              </div>
            </div>
          </header>
          <main id="main" className="container" tabIndex={-1}>
            {children}
          </main>
          <SiteFooter />
        </SessionProvider>
      </body>
    </html>
  );
}
