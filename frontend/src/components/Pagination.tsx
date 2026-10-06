import Link from "next/link";

import { worksListHref } from "@/lib/works/params";

type Props = {
  /** 1부터 시작 */
  currentPage: number;
  totalPages: number;
};

const WINDOW = 2;

/** 목록 페이지 이동. 현재 페이지 앞뒤 2쪽과 처음·마지막 쪽을 보여 준다. */
export function Pagination({ currentPage, totalPages }: Props) {
  if (totalPages <= 1) {
    return null;
  }
  const pages = visiblePages(currentPage, totalPages);

  return (
    <nav className="pagination" aria-label="작품 목록 페이지">
      <ul>
        <li>
          {currentPage > 1 ? (
            <Link href={worksListHref(currentPage - 1)} rel="prev">
              이전
            </Link>
          ) : (
            <span className="pagination__disabled" aria-disabled="true">
              이전
            </span>
          )}
        </li>
        {pages.map((page, index) =>
          page === null ? (
            <li key={`gap-${index}`} aria-hidden="true" className="pagination__gap">
              …
            </li>
          ) : (
            <li key={page}>
              {page === currentPage ? (
                <span className="pagination__current" aria-current="page">
                  <span className="visually-hidden">현재 페이지 </span>
                  {page}
                </span>
              ) : (
                <Link href={worksListHref(page)} aria-label={`${page}페이지`}>
                  {page}
                </Link>
              )}
            </li>
          ),
        )}
        <li>
          {currentPage < totalPages ? (
            <Link href={worksListHref(currentPage + 1)} rel="next">
              다음
            </Link>
          ) : (
            <span className="pagination__disabled" aria-disabled="true">
              다음
            </span>
          )}
        </li>
      </ul>
    </nav>
  );
}

function visiblePages(current: number, total: number): (number | null)[] {
  const pages = new Set<number>([1, total]);
  for (let page = current - WINDOW; page <= current + WINDOW; page++) {
    if (page >= 1 && page <= total) {
      pages.add(page);
    }
  }
  const sorted = [...pages].sort((a, b) => a - b);
  const result: (number | null)[] = [];
  sorted.forEach((page, index) => {
    if (index > 0 && page - sorted[index - 1] > 1) {
      result.push(null);
    }
    result.push(page);
  });
  return result;
}
