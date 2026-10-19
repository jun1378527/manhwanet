import Link from "next/link";

import { GENRE_MENU, GENRE_PENDING_TEXT } from "@/lib/home/genres";

/**
 * 장르 메뉴(시안 v2). PC 는 배너 왼쪽 세로 목록, 모바일은 배너 아래 가로 칩.
 * 장르 데이터가 아직 없어 "전체"만 작품 목록으로 연결하고 나머지는 준비 중(비활성)으로 보여 준다.
 */
export function GenreRail() {
  const available = GENRE_MENU.filter((g) => g.available);
  const pending = GENRE_MENU.filter((g) => !g.available);
  return (
    <nav className="genre-rail" aria-labelledby="genre-rail-title">
      <h2 className="genre-rail__title" id="genre-rail-title">
        장르로 둘러보기
      </h2>
      <ul className="genre-rail__list">
        {available.map((genre) => (
          <li key={genre.label} className="genre-rail__all">
            <Link className="genre-rail__item genre-rail__item--active" href={genre.href}>
              {genre.label}
              <span className="genre-rail__hint">전체 작품</span>
            </Link>
          </li>
        ))}
        <li className="genre-rail__group" aria-hidden="true">
          {GENRE_PENDING_TEXT}인 장르
        </li>
        {pending.map((genre) => (
          <li key={genre.label}>
            <span className="genre-rail__item" aria-disabled="true">
              {genre.label}
              <span className="visually-hidden"> ({GENRE_PENDING_TEXT})</span>
            </span>
          </li>
        ))}
      </ul>
      <p className="genre-rail__note">장르별 보기는 준비 중이에요. 지금은 전체 목록만 볼 수 있어요.</p>
    </nav>
  );
}
