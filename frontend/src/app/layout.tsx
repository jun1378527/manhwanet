import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "만화넷",
    template: "%s · 만화넷",
  },
  description: "한국 웹툰과 일본 만화를 한곳에서 찾아보는 서비스",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
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
            <nav aria-label="주 메뉴">
              <Link href="/works">작품 목록</Link>
            </nav>
          </div>
        </header>
        <main id="main" className="container" tabIndex={-1}>
          {children}
        </main>
      </body>
    </html>
  );
}
