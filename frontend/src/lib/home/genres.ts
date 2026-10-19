/**
 * 홈 장르 메뉴. 디자인 시안(home-portal-v2)의 장르 구성을 유지한다.
 * 작품에 장르 데이터가 아직 없으므로 "전체"만 실제 목록(/works)으로 연결하고, 나머지는 준비 중으로 비활성 표시한다.
 * 작품 수는 표시하지 않는다(가상 수치를 서비스 데이터처럼 보이지 않게). 실제 장르 필터는 후속 작업이다.
 * 한국 웹툰·일본 만화로 나누지 않는다.
 */
export type GenreMenuItem = { label: string; href: string; available: true } | { label: string; available: false };

export const GENRE_MENU: readonly GenreMenuItem[] = [
  { label: "전체", href: "/works", available: true },
  ...["판타지", "로맨스", "액션", "드라마", "일상", "스릴러", "무협", "SF", "스포츠", "코미디", "학원", "미스터리", "요리"].map(
    (label) => ({ label, available: false as const }),
  ),
];

export const GENRE_PENDING_TEXT = "준비 중";
