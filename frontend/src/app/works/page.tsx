import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";

import { CreditList } from "@/components/CreditList";
import { Pagination } from "@/components/Pagination";
import { RatingSummaryLine } from "@/components/ratings/RatingSummaryLine";
import { ApiUnavailable, StatusMessage } from "@/components/StatusMessage";
import { WorkTile } from "@/components/WorkTile";
import { getWorks } from "@/lib/api/works";
import { WORK_FORMAT_LABELS, countryName } from "@/lib/works/labels";
import { parseListPage, workDetailHref, worksListHref } from "@/lib/works/params";

export const metadata: Metadata = {
  title: "작품 목록",
};

export default async function WorksPage({ searchParams }: PageProps<"/works">) {
  const { page: pageParam } = await searchParams;
  const page = parseListPage(pageParam);
  if (page === null) {
    redirect("/works");
  }

  const result = await getWorks(page - 1);

  return (
    <>
      <header className="page-intro">
        <h1>작품 목록</h1>
        <p className="page-intro__lead">한국 웹툰과 일본 만화를 함께 둘러보고 평가하세요.</p>
      </header>
      {!result.ok ? (
        <ApiUnavailable retryHref={worksListHref(page)} />
      ) : result.data.totalElements === 0 ? (
        <StatusMessage title="등록된 작품이 없습니다">
          <p>작품이 등록되면 이곳에 표시됩니다.</p>
        </StatusMessage>
      ) : result.data.content.length === 0 ? (
        <StatusMessage title="이 페이지에는 작품이 없습니다">
          <p>
            전체 {result.data.totalPages.toLocaleString("ko-KR")}페이지 중 {page.toLocaleString("ko-KR")}
            페이지를 요청했습니다.
          </p>
          <Link className="button" href={worksListHref(result.data.totalPages)}>
            마지막 페이지로
          </Link>
        </StatusMessage>
      ) : (
        <>
          <p className="list-summary">
            전체 {result.data.totalElements.toLocaleString("ko-KR")}개 · {page}/{result.data.totalPages}페이지
          </p>
          <ol className="work-list">
            {result.data.content.map((work) => (
              <li key={work.id} className="work-card">
                <WorkTile title={work.primaryTitle} format={work.workFormat} />
                <div className="work-card__body">
                  <h2 className="work-card__title" lang={work.primaryTitle.languageCode}>
                    <Link href={workDetailHref(work.id, page)} lang={work.primaryTitle.languageCode}>
                      {work.primaryTitle.title}
                    </Link>
                  </h2>
                  <p className="work-card__format">
                    <span className="badge">{WORK_FORMAT_LABELS[work.workFormat] ?? work.workFormat}</span>
                    {work.originCountry ? <span className="work-card__country">{countryName(work.originCountry)}</span> : null}
                  </p>
                  <CreditList credits={work.creators} className="work-card__credits" />
                  <RatingSummaryLine summary={work.rating} className="work-card__rating" />
                </div>
              </li>
            ))}
          </ol>
          <Pagination currentPage={page} totalPages={result.data.totalPages} />
        </>
      )}
    </>
  );
}
