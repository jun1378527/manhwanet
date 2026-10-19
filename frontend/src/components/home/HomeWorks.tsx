import Link from "next/link";
import { connection } from "next/server";

import { getRecentWorks, getWorkRanking } from "@/lib/api/home";

import { HomeWorkTabs, type HomeWorkPanel } from "./HomeWorkTabs";

/** 홈 작품 영역. 세 목록을 함께 불러와 탭으로 보여 준다 */
export async function HomeWorks() {
  // 요청마다 새로 읽는다(빌드 시점 미리 그리기에서 제외). apiGet 의 try/catch 가 동적 렌더링 신호를 삼키지 않게 먼저 표시한다
  await connection();
  const [byRating, byCount, recent] = await Promise.all([getWorkRanking("rating"), getWorkRanking("ratingCount"), getRecentWorks()]);
  const toState = (r: typeof byRating): HomeWorkPanel["state"] =>
    r.ok ? { status: "ok", works: r.data.content, total: r.data.totalElements } : { status: "error" };

  const panels: HomeWorkPanel[] = [
    {
      id: "rating",
      label: "평점순",
      description: "별점이 있는 전체 공개 작품의 평균 별점순(같으면 평가 수가 많은 순)",
      state: toState(byRating),
      ranked: true,
      emptyTitle: "아직 별점이 남겨진 작품이 없어요",
      emptyText: "작품 상세에서 첫 별점을 남겨 주세요.",
    },
    {
      id: "ratingCount",
      label: "평가 많은 순",
      description: "별점을 남긴 독자가 많은 순(전체 공개 작품 기준)",
      state: toState(byCount),
      ranked: true,
      emptyTitle: "아직 별점이 남겨진 작품이 없어요",
      emptyText: "작품 상세에서 첫 별점을 남겨 주세요.",
    },
    {
      id: "recent",
      label: "최근 등록",
      description: "만화넷에 등록된 시각 기준 최신순(작품의 연재 시작일이 아닙니다)",
      state: toState(recent),
      ranked: false,
      emptyTitle: "등록된 작품이 없습니다",
      emptyText: "작품이 등록되면 이곳에 표시됩니다.",
    },
  ];

  return (
    <section className="home-works" id="works" aria-labelledby="home-works-title">
      <div className="home-section-head">
        <h2 id="home-works-title">지금 둘러볼 작품</h2>
        <Link className="home-section-head__more" href="/works">
          전체 작품 보기 <span aria-hidden="true">→</span>
        </Link>
      </div>
      <HomeWorkTabs panels={panels} />
    </section>
  );
}
