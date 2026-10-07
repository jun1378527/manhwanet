import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { CreditList } from "@/components/CreditList";
import { WorkRatingSection } from "@/components/ratings/WorkRatingSection";
import { ApiUnavailable } from "@/components/StatusMessage";
import { getWork } from "@/lib/api/works";
import { getSession } from "@/lib/auth/session";
import { type MyRatingState, getMyRating } from "@/lib/ratings/server";
import { WORK_FORMAT_LABELS, countryLabel, languageLabel } from "@/lib/works/labels";
import { isValidWorkId, parseReturnPage, workDetailHref, worksListHref } from "@/lib/works/params";

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
  // 작품과 내 별점(로그인했을 때만)을 함께 불러온다. 내 별점은 사용자별 응답이므로 캐시하지 않는다.
  const [result, { myRating, viewerKey }] = await Promise.all([getWork(id), loadMyRating(id)]);
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

      <WorkRatingSection
        key={viewerKey}
        workId={work.id}
        initialSummary={work.rating}
        initialMine={myRating}
        selfHref={workDetailHref(work.id, parseReturnPage(from))}
      />

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

/**
 * 로그인 상태에 따라 내 별점을 불러온다. 백엔드 장애는 비로그인으로 바꾸지 않는다.
 * viewerKey: 로그인·로그아웃 후(router.refresh) 사용자나 상태가 바뀌면 별점 영역을 새 상태로 다시 만들기 위한 key
 */
async function loadMyRating(workId: string): Promise<{ myRating: MyRatingState; viewerKey: string }> {
  const session = await getSession();
  if (session.status !== "authenticated") {
    return { myRating: { status: session.status }, viewerKey: session.status };
  }
  const myRating = await getMyRating(workId);
  return { myRating, viewerKey: `${session.user.id}:${myRating.status}` };
}

function BackLink({ href }: { href: string }) {
  return (
    <p className="back-link">
      <Link href={href}>← 작품 목록으로</Link>
    </p>
  );
}
