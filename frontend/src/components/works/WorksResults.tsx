import Link from "next/link";

import { HomeWorkCard } from "@/components/home/HomeWorkCard";
import { Pagination } from "@/components/Pagination";
import type { PageResponse, WorkSummary } from "@/lib/api/types";
import { type WorkSummaryWithReviews, reviewCountOf } from "@/lib/works/detail";
import {
  DEFAULT_WORKS_QUERY,
  type WorksQuery,
  hasConditions,
  workDetailHrefFor,
  withPage,
  worksSearchHref,
} from "@/lib/works/search";

type Props = {
  query: WorksQuery;
  data: PageResponse<WorkSummary>;
};

/** 작품 찾기 결과: 건수, 카드 목록(대문과 같은 카드), 페이지 이동, 빈 결과 안내 */
export function WorksResults({ query, data }: Props) {
  const filtered = hasConditions(query);
  const clearHref = worksSearchHref({ ...DEFAULT_WORKS_QUERY, sort: query.sort });
  const total = data.totalElements.toLocaleString("ko-KR");

  return (
    <section className="works-list" aria-labelledby="works-list-heading">
      <div className="works-summary">
        <h2 id="works-list-heading" className="works-summary__title">
          {filtered ? "찾은 작품" : "전체 작품"}
        </h2>
        {/* 조건이 바뀌어 다시 그려질 때 건수를 읽어 준다 */}
        <p className="works-summary__count" role="status">
          {query.q ? (
            <>
              <span className="works-summary__query">‘{query.q}’</span> 검색 결과{" "}
            </>
          ) : null}
          <strong>{total}</strong>개
          {data.totalPages > 1 && data.content.length > 0 ? (
            <span className="works-summary__page">
              {" "}
              · {query.page.toLocaleString("ko-KR")}/{data.totalPages.toLocaleString("ko-KR")}쪽
            </span>
          ) : null}
        </p>
      </div>

      {data.totalElements === 0 ? (
        filtered ? (
          <div className="home-state works-empty">
            <p className="home-state__title">조건에 맞는 작품이 없어요</p>
            <p>검색어의 철자를 확인하거나, 더 짧은 낱말로 찾아보세요. 필터를 줄이면 더 많은 작품이 보여요.</p>
            <Link className="button button--secondary" href={clearHref}>
              검색어·필터 모두 지우기
            </Link>
          </div>
        ) : (
          <div className="home-state works-empty">
            <p className="home-state__title">등록된 작품이 없습니다</p>
            <p>작품이 등록되면 이곳에 표시됩니다.</p>
          </div>
        )
      ) : data.content.length === 0 ? (
        <div className="home-state works-empty">
          <p className="home-state__title">이 페이지에는 작품이 없어요</p>
          <p>
            전체 {data.totalPages.toLocaleString("ko-KR")}쪽 중 {query.page.toLocaleString("ko-KR")}쪽을 요청했습니다.
          </p>
          <Link className="button button--secondary" href={worksSearchHref(withPage(query, data.totalPages))}>
            마지막 쪽으로
          </Link>
        </div>
      ) : (
        <>
          <ol className="home-grid works-grid">
            {data.content.map((work) => (
              <li key={work.id}>
                <HomeWorkCard
                  work={work}
                  href={workDetailHrefFor(work.id, query)}
                  showRegistered={query.sort === "recent"}
                  reviewCount={reviewCountOf(work as WorkSummaryWithReviews)}
                />
              </li>
            ))}
          </ol>
          <Pagination
            currentPage={query.page}
            totalPages={data.totalPages}
            hrefFor={(page) => worksSearchHref(withPage(query, page))}
            label="작품 찾기 결과 페이지"
          />
        </>
      )}
    </section>
  );
}
