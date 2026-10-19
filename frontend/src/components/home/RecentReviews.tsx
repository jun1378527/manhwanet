import Link from "next/link";
import { connection } from "next/server";

import { getRecentReviews } from "@/lib/api/home";
import { withholdSpoilerBody } from "@/lib/home/model";

import { RecentReviewItem } from "./RecentReviewItem";

const HOME_HREF = "/";

/** 홈 최근 공개 리뷰(숨김 작품 제외는 백엔드가 맡는다) */
export async function RecentReviews() {
  // 요청마다 새로 읽는다(빌드 시점 미리 그리기에서 제외). apiGet 의 try/catch 가 동적 렌더링 신호를 삼키지 않게 먼저 표시한다
  await connection();
  const result = await getRecentReviews();
  return (
    <section className="home-reviews" id="reviews" aria-labelledby="home-reviews-title">
      <div className="home-section-head">
        <h2 id="home-reviews-title">최근 리뷰</h2>
      </div>
      {!result.ok ? (
        <div className="home-state home-state--error" role="alert">
          <p className="home-state__title">리뷰를 불러오지 못했습니다</p>
          <p>잠시 후 다시 시도해 주세요.</p>
          {/* 전체 페이지를 다시 요청하도록 일반 링크를 쓴다(ApiUnavailable 과 같은 방식) */}
        <a className="button button--secondary" href={HOME_HREF}>
            다시 시도
          </a>
        </div>
      ) : result.data.length === 0 ? (
        <div className="home-state">
          <p className="home-state__title">아직 등록된 리뷰가 없어요</p>
          <p>다 본 작품이 있다면 첫 리뷰를 남겨 주세요.</p>
          <Link className="button button--secondary" href="/works">
            작품 둘러보기
          </Link>
        </div>
      ) : (
        <ol className="recent-reviews">
          {result.data.map((review) => (
            <li key={review.id}>
              <RecentReviewItem review={withholdSpoilerBody(review)} />
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
