import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";

import { WorkRatingSection } from "@/components/ratings/WorkRatingSection";
import { WorkReviewSection } from "@/components/reviews/WorkReviewSection";
import { ApiUnavailable } from "@/components/StatusMessage";
import { WorkHero } from "@/components/works/WorkHero";
import { getWork } from "@/lib/api/works";
import { getSession } from "@/lib/auth/session";
import { type MyRatingState, getMyRating } from "@/lib/ratings/server";
import { type MyReviewState, getMyReview, getReviewPage } from "@/lib/reviews/server";
import { formatRegisteredDate, isoDateTime } from "@/lib/home/model";
import { type WorkDetailWithLinks } from "@/lib/works/detail";
import { countryLabel, languageLabel } from "@/lib/works/labels";
import { isValidWorkId, parseListPage } from "@/lib/works/params";
import { resolveReturnQuery, workDetailHrefFor, worksSearchHref } from "@/lib/works/search";

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
  const { from, back, reviewPage: reviewPageParam } = await searchParams;
  // 목록 복귀 상태: ?back=(같은 출처 /works 주소만, 다시 검증) 또는 이전 방식 ?from=(페이지). 잘못된 값이면 기본 목록
  const listQuery = resolveReturnQuery(back, from);
  const listHref = worksSearchHref(listQuery);

  if (!isValidWorkId(id)) {
    notFound();
  }
  // 공개 리뷰 페이지(?reviewPage, 1부터). 잘못된 값·중복 값은 reviewPage 를 뺀 주소로 보낸다(307). 목록 복귀 상태는 보존한다.
  const reviewPage = parseListPage(reviewPageParam);
  if (reviewPage === null) {
    redirect(workDetailHrefFor(id, listQuery));
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
        <ApiUnavailable retryHref={workDetailHrefFor(id, listQuery, reviewPage)} />
      </>
    );
  }

  const work = result.data as WorkDetailWithLinks;
  const myReview = viewer.myReview;
  const hasMyReview = myReview.status === "loaded" && myReview.review !== null;
  const registered = formatRegisteredDate(work.createdAt);

  return (
    <article className="work-detail">
      <BackLink href={listHref} />

      {/* 읽기·탭 순서: 작품 정보 → 작품 소개 → 내 별점 → 리뷰. 넓은 화면에서는 내 별점이 오른쪽 열로 간다. */}
      <WorkHero work={work} hasMyReview={hasMyReview} />

      <div className="work-detail__layout">
        <div className="work-detail__info">
          <section className="work-section" aria-labelledby="description-heading">
            <h2 id="description-heading" className="work-section__title">
              작품 소개
            </h2>
            {work.description && work.description.trim() !== "" ? (
              <p className="work-detail__description">{work.description}</p>
            ) : (
              <p className="work-section__empty">아직 작품 소개가 등록되지 않았어요.</p>
            )}
            <dl className="work-detail__facts">
              <div>
                <dt>대표 제목 언어</dt>
                <dd>{languageLabel(work.primaryTitle.languageCode)}</dd>
              </div>
              {work.originCountry ? (
                <div>
                  <dt>국가</dt>
                  <dd>{countryLabel(work.originCountry)}</dd>
                </div>
              ) : null}
              <div>
                <dt>만화넷 등록</dt>
                <dd>
                  <time dateTime={isoDateTime(work.createdAt)}>{registered}</time>
                </dd>
              </div>
            </dl>
          </section>
        </div>

        <div className="work-detail__side" id="my-rating">
          <WorkRatingSection
            key={viewerKey}
            workId={work.id}
            initialSummary={work.rating}
            initialMine={myRating}
            selfHref={workDetailHrefFor(work.id, listQuery, reviewPage)}
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
            listQuery={listQuery}
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
