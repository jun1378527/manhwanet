/**
 * 홈 배너 슬라이드 내용. 모든 링크는 지금 있는 화면으로만 간다(빈 버튼 없음).
 * 문구는 이용자용이다. 내부 처리 방식(데이터 수집·검수 절차 등)은 쓰지 않는다.
 */
export type BannerSlideId = "explore" | "rate" | "about";

export type BannerSlide = {
  id: BannerSlideId;
  /** 탭에 보이는 짧은 이름. 접근 가능한 이름(kicker)에 포함되는 낱말이어야 한다 */
  tabLabel: string;
  kicker: string;
  /** 줄 단위 제목 */
  titleLines: readonly string[];
  description: string;
  /** 보조 표시(짧은 목록). 링크가 아니다 */
  points?: readonly string[];
  cta: { href: string; label: string };
};

export const BANNER_SLIDES: readonly BannerSlide[] = [
  {
    id: "explore",
    tabLabel: "탐색",
    kicker: "작품 탐색",
    titleLines: ["쌓여 있는 작품 더미에서", "오늘 볼 한 편을"],
    description: "웹툰도 만화도 한 목록에서. 독자 별점과 새로 들어온 작품을 한눈에 살펴보세요.",
    cta: { href: "/works", label: "작품 둘러보기" },
  },
  {
    id: "rate",
    tabLabel: "참여",
    kicker: "별점·리뷰 참여",
    titleLines: ["다 본 작품에", "별 하나 남겨 주세요"],
    description: "작품 상세에서 0.5점 단위 별점과 짧은 리뷰를 남길 수 있어요. 여러분의 평가가 다음 독자의 선택을 돕습니다.",
    cta: { href: "/works", label: "평가할 작품 찾기" },
  },
  {
    id: "about",
    tabLabel: "소개",
    kicker: "서비스 소개",
    titleLines: ["찾고, 매기고,", "남기는 곳"],
    description: "만화넷은 웹툰과 만화를 한곳에서 찾아보고, 다 본 작품에 별점과 리뷰를 남겨 다른 독자와 나누는 서비스예요.",
    points: ["작품 찾기", "별점", "리뷰"],
    cta: { href: "/signup", label: "회원가입하고 시작하기" },
  },
];
