import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "만화넷",
  description: "한국 웹툰과 일본 만화를 한곳에서 검색하고 평가하는 서비스",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
