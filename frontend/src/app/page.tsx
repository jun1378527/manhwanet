import { Suspense } from "react";

import { GenreRail } from "@/components/home/GenreRail";
import { HomeBanner } from "@/components/home/HomeBanner";
import { HomeWorksSkeleton, RecentReviewsSkeleton } from "@/components/home/HomeSkeletons";
import { HomeWorks } from "@/components/home/HomeWorks";
import { RecentReviews } from "@/components/home/RecentReviews";

/**
 * 대문(시안 docs/ui-mockups/home-portal-v2).
 * PC: 장르 메뉴(왼쪽) + 물리 배너(오른쪽) → 작품(정렬 탭) + 최근 리뷰. 모바일: 배너 → 장르 칩 → 작품 → 리뷰.
 * 작품·리뷰는 따로 스트리밍해 한쪽이 늦거나 실패해도 다른 쪽과 배너는 먼저 보인다.
 */
export default function Home() {
  return (
    <div className="home">
      <h1 className="visually-hidden">만화넷</h1>
      <div className="home-hero">
        <div className="home-hero__banner">
          <HomeBanner />
        </div>
        <div className="home-hero__genres">
          <GenreRail />
        </div>
      </div>
      <div className="home-main">
        <Suspense fallback={<HomeWorksSkeleton />}>
          <HomeWorks />
        </Suspense>
        <Suspense fallback={<RecentReviewsSkeleton />}>
          <RecentReviews />
        </Suspense>
      </div>
    </div>
  );
}
