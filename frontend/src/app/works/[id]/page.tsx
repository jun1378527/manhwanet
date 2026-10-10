import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";

import { CreditList } from "@/components/CreditList";
import { WorkRatingSection } from "@/components/ratings/WorkRatingSection";
import { WorkReviewSection } from "@/components/reviews/WorkReviewSection";
import { ApiUnavailable } from "@/components/StatusMessage";
import { WorkTile } from "@/components/WorkTile";
import { getWork } from "@/lib/api/works";
import { getSession } from "@/lib/auth/session";
import { type MyRatingState, getMyRating } from "@/lib/ratings/server";
import { type MyReviewState, getMyReview, getReviewPage } from "@/lib/reviews/server";
import { WORK_FORMAT_LABELS, countryLabel, countryName, languageLabel } from "@/lib/works/labels";
import { isValidWorkId, parseListPage, parseReturnPage, workDetailHref, worksListHref } from "@/lib/works/params";

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
  const { from, reviewPage: reviewPageParam } = await searchParams;
  const fromPage = parseReturnPage(from);
  const listHref = worksListHref(fromPage);

  if (!isValidWorkId(id)) {
    notFound();
  }
  // 공개 리뷰 페이지(?reviewPage, 1부터). 잘못된 값·중복 값은 reviewPage 를 뺀 주소로 보낸다(307). from 은 보존한다.
  const reviewPage = parseListPage(reviewPageParam);
  if (reviewPage === null) {
    redirect(workDetailHref(id, fromPage));
  }
  // 작품, 내 별점·내 리뷰(로그인했을 때만), 공개 리뷰 목록을 함께 불러온다.
  // 내 별점·내 리뷰는 사용자별 응답이므로 캐시하지 않는다. 공개 목록은 쿠키 없이 읽는다.
  const [result, viewer, reviewList] = await Promise.all([getWork(id), loadViewer(id), getReviewPage(id, reviewPage - 1)]);
  const { myRating, viewerKey } = viewer;
  if (!result.ok) {
    if (result.reason === "not-found") {
      notFound();
    }
    return (
      <>
        <BackLink href={listHref} />
        <ApiUnavailable retryHref={workDetailHref(id, fromPage, reviewPage)} />
      </>
    );
  }

  const work = result.data;
  const alternativeTitles = work.titles.filter((title) => !title.primary);

  return (
    <article className="work-detail">
      <BackLink href={listHref} />

      {/* 작품 정보를 먼저 두고(읽기·탭 순서), 별점·리뷰는 그 뒤에 둔다. 넓은 화면에서는 별점이 오른쪽 열로 간다. */}
      <header className="work-detail__header">
        <WorkTile title={work.primaryTitle} format={work.workFormat} size="lg" />
        <div className="work-detail__heading">
          <p className="work-detail__badges">
            <span className="badge">{WORK_FORMAT_LABELS[work.workFormat] ?? work.workFormat}</span>
            {work.originCountry ? <span className="badge">{countryName(work.originCountry)}</span> : null}
          </p>
          <h1 lang={work.primaryTitle.languageCode}>{work.primaryTitle.title}</h1>
          <section aria-labelledby="credits-heading" className="work-detail__credits">
            <h2 id="credits-heading" className="visually-hidden">
              작가
            </h2>
            <CreditList credits={work.creators} />
          </section>
        </div>
      </header>

      <div className="work-detail__layout">
        <div className="work-detail__info">
          <section aria-labelledby="description-heading">
            <h2 id="description-heading">줄거리</h2>
            {work.description ? (
              <p className="work-detail__description">{work.description}</p>
            ) : (
              <p className="muted">등록된 줄거리가 없습니다.</p>
            )}
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
          </section>
        </div>

        <div className="work-detail__side">
          <WorkRatingSection
            key={viewerKey}
            workId={work.id}
            initialSummary={work.rating}
            initialMine={myRating}
            selfHref={workDetailHref(work.id, fromPage, reviewPage)}
            viewerId={viewer.viewerId}
          />
        </div>

        <div className="work-detail__reviews">
          <WorkReviewSection
            key={viewer.reviewViewerKey}
            workId={work.id}
            viewerId={viewer.viewerId}
            initialMine={viewer.myReview}
            initialList={reviewList}
            reviewPage={reviewPage}
            fromPage={fromPage}
          />
        </div>
      </div>
    </article>
  );
}

/**
 * 로그인 상태에 따라 내 별점·내 리뷰를 불러온다. 백엔드 장애는 비로그인으로 바꾸지 않는다.
 * - viewerKey: 로그인·로그아웃 후(router.refresh) 사용자나 상태가 바뀌면 별점 영역을 새 상태로 다시 만들기 위한 key
 * - reviewViewerKey: 리뷰 영역용 key. 사용자(또는 비로그인·확인 실패 상태)가 바뀔 때만 바뀐다.
 *   내 리뷰 조회 결과가 바뀌어도 key 를 바꾸지 않아, 작성 중인 글이 router.refresh 로 사라지지 않는다.
 *   사용자가 바뀌면 key 가 바뀌어 이전 사용자의 글·응답이 남지 않는다.
 */
async function loadViewer(workId: string): Promise<{
  myRating: MyRatingState;
  viewerKey: string;
  myReview: MyReviewState;
  reviewViewerKey: string;
  viewerId: string | null;
}> {
  const session = await getSession();
  if (session.status !== "authenticated") {
    return {
      myRating: { status: session.status },
      viewerKey: session.status,
      myReview: { status: session.status },
      reviewViewerKey: session.status,
      viewerId: null,
    };
  }
  const [myRating, myReview] = await Promise.all([getMyRating(workId), getMyReview(workId)]);
  return {
    myRating,
    viewerKey: `${session.user.id}:${myRating.status}`,
    myReview,
    reviewViewerKey: `user:${session.user.id}`,
    viewerId: session.user.id,
  };
}

function BackLink({ href }: { href: string }) {
  return (
    <p className="back-link">
      <Link href={href}>← 작품 목록으로</Link>
    </p>
  );
}
