import Link from "next/link";

import { FooterAccount } from "@/components/auth/FooterAccount";

/**
 * 사이트 푸터. 실제로 있는 화면과 안내만 둔다(없는 문의 페이지나 임의 연락처는 만들지 않음).
 */
export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="container site-footer__grid">
        <section aria-labelledby="footer-about">
          <h2 id="footer-about" className="site-footer__title">
            만화넷 소개
          </h2>
          <p>웹툰과 만화를 한 목록에서 찾아보고, 다 본 작품에 별점과 리뷰를 남겨 다른 독자와 나누는 서비스입니다.</p>
          <ul className="site-footer__links">
            <li>
              <Link href="/works">작품 목록</Link>
            </li>
            <li>
              <Link href="/#reviews">최근 리뷰</Link>
            </li>
          </ul>
        </section>
        <section aria-labelledby="footer-account">
          <h2 id="footer-account" className="site-footer__title">
            계정
          </h2>
          <FooterAccount />
        </section>
        <section aria-labelledby="footer-source">
          <h2 id="footer-source" className="site-footer__title">
            정보 출처 안내
          </h2>
          <p>작품 제목과 작가 정보는 출처를 확인한 뒤 등록합니다. 표지 이미지는 아직 제공하지 않으며, 표지가 없는 작품은 &lsquo;표지 없음&rsquo;으로 보여 줍니다.</p>
          <p>&lsquo;가상 표지&rsquo;와 [DEMO] 표시가 있는 작품은 시연용 가상 데이터입니다.</p>
        </section>
      </div>
      <div className="container site-footer__bottom">
        <span>© 만화넷</span>
      </div>
    </footer>
  );
}
