import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { CreditList } from "@/components/CreditList";
import { ApiUnavailable } from "@/components/StatusMessage";
import { getWork } from "@/lib/api/works";
import { WORK_FORMAT_LABELS, countryLabel, languageLabel } from "@/lib/works/labels";
import { isValidWorkId, parseReturnPage, worksListHref } from "@/lib/works/params";

export async function generateMetadata({ params }: PageProps<"/works/[id]">): Promise<Metadata> {
  const { id } = await params;
  if (!isValidWorkId(id)) {
    return { title: "작품을 찾을 수 없습니다" };
  }
  const result = await getWork(id);
  return { title: result.ok ? result.data.primaryTitle.title : "작품 상세" };
}

export default async function WorkDetailPage({ params, searchParams }: PageProps<"/works/[id]">) {
  const { id } = await params;
  const { from } = await searchParams;
  const listHref = worksListHref(parseReturnPage(from));

  if (!isValidWorkId(id)) {
    notFound();
  }
  const result = await getWork(id);
  if (!result.ok) {
    if (result.reason === "not-found") {
      notFound();
    }
    return (
      <>
        <BackLink href={listHref} />
        <ApiUnavailable retryHref={`/works/${id}${from ? `?from=${parseReturnPage(from)}` : ""}`} />
      </>
    );
  }

  const work = result.data;
  const alternativeTitles = work.titles.filter((title) => !title.primary);

  return (
    <article className="work-detail">
      <BackLink href={listHref} />

      <header className="work-detail__header">
        <h1 lang={work.primaryTitle.languageCode}>{work.primaryTitle.title}</h1>
        <p>
          <span className="badge">{WORK_FORMAT_LABELS[work.workFormat] ?? work.workFormat}</span>
        </p>
      </header>

      <dl className="work-detail__facts">
        <div>
          <dt>대표 제목 언어</dt>
          <dd>{languageLabel(work.primaryTitle.languageCode)}</dd>
        </div>
        <div>
          <dt>국가</dt>
          <dd>{work.originCountry ? countryLabel(work.originCountry) : "정보 없음"}</dd>
        </div>
      </dl>

      <section aria-labelledby="credits-heading">
        <h2 id="credits-heading">작가</h2>
        <CreditList credits={work.creators} />
      </section>

      <section aria-labelledby="titles-heading">
        <h2 id="titles-heading">다른 제목</h2>
        {alternativeTitles.length > 0 ? (
          <ul className="title-list">
            {alternativeTitles.map((title) => (
              <li key={`${title.languageCode}:${title.title}`}>
                <span lang={title.languageCode}>{title.title}</span>{" "}
                <span className="title-list__lang">{languageLabel(title.languageCode)}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">등록된 다른 제목이 없습니다.</p>
        )}
      </section>

      <section aria-labelledby="description-heading">
        <h2 id="description-heading">줄거리</h2>
        {work.description ? (
          <p className="work-detail__description">{work.description}</p>
        ) : (
          <p className="muted">등록된 줄거리가 없습니다.</p>
        )}
      </section>
    </article>
  );
}

function BackLink({ href }: { href: string }) {
  return (
    <p className="back-link">
      <Link href={href}>← 작품 목록으로</Link>
    </p>
  );
}
