import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";

import { MeViewerGuard } from "@/components/me/MeViewerGuard";
import { MyRatingList, MyReviewList } from "@/components/me/MyActivityLists";
import { Pagination } from "@/components/Pagination";
import { getMyActivityPage, getMyActivitySummary } from "@/lib/api/me";
import { loginHref } from "@/lib/auth/return-to";
import { getSession } from "@/lib/auth/session";
import {
  ME_TABS,
  ME_TAB_LABELS,
  type MeQuery,
  type MyRatingItem,
  type MyReviewItem,
  type Page,
  meHref,
  parseMeQuery,
} from "@/lib/me/model";

export const metadata: Metadata = {
  title: "내 활동",
  robots: { index: false, follow: false },
};

/**
 * 내 활동(/me): 닉네임·활동 요약, "내 별점"/"내 리뷰" 탭(주소 ?tab·?page), 탭별 페이지 이동.
 * - 비로그인은 로그인 화면으로 보내고, 성공하면 지금 주소(/me?...)로 돌아온다.
 * - 사용자는 세션에서만 정한다. 모든 조회는 세션 쿠키로 no-store, 그리고 이 화면의 사용자 ID 를 예상 사용자로 보낸다.
 * - 수정·삭제는 이 화면에 두지 않고 작품 상세의 기존 별점·리뷰 영역으로 연결한다.
 * - 이메일 등 계정 정보는 보이지 않는다(닉네임만).
 */
export default async function MePage({ searchParams }: PageProps<"/me">) {
  const { query, changed } = parseMeQuery(await searchParams);
  if (changed) {
    redirect(meHref(query));
  }
  const self = meHref(query);

  const session = await getSession();
  if (session.status === "anonymous") {
    redirect(loginHref(self));
  }
  if (session.status === "unavailable") {
    return (
      <div className="me-page">
        <h1 className="me-head__title">내 활동</h1>
        <MeError retryHref={self} text="로그인 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요." />
      </div>
    );
  }

  const viewerId = session.user.id;
  const nickname = session.user.nickname;
  const [summary, list] = await Promise.all([
    getMyActivitySummary(viewerId),
    getMyActivityPage(query.tab, query.page, viewerId),
  ]);
  if (summary.status === "anonymous" || list.status === "anonymous") {
    redirect(loginHref(self, { expired: true }));
  }

  return (
    // 사용자가 바뀌면 key 가 바뀌어 이전 사용자의 화면 상태를 새로 만든다
    <MeViewerGuard key={viewerId} viewerId={viewerId}>
      <div className="me-page">
        <header className="me-head">
          <p className="me-head__kicker">내 활동</p>
          <h1 className="me-head__title">
            <span dir="auto">{nickname}</span>님의 기록
          </h1>
          {summary.status === "loaded" ? (
            <dl className="me-stats">
              <div className="me-stats__item">
                <dt>남긴 별점</dt>
                <dd>{summary.data.ratingCount.toLocaleString("ko-KR")}개</dd>
              </div>
              <div className="me-stats__item">
                <dt>쓴 리뷰</dt>
                <dd>{summary.data.reviewCount.toLocaleString("ko-KR")}개</dd>
              </div>
            </dl>
          ) : summary.status === "switched" ? null : (
            <p className="me-head__note">활동 요약을 불러오지 못했습니다.</p>
          )}
        </header>

        <nav className="me-tabs" aria-label="내 활동 종류">
          {ME_TABS.map((tab) => (
            <Link
              key={tab}
              href={meHref({ tab, page: 1 })}
              className="me-tabs__link"
              aria-current={tab === query.tab ? "page" : undefined}
            >
              {ME_TAB_LABELS[tab]}
              {summary.status === "loaded" ? (
                <span className="me-tabs__count">
                  {" "}
                  {(tab === "ratings" ? summary.data.ratingCount : summary.data.reviewCount).toLocaleString("ko-KR")}
                </span>
              ) : null}
            </Link>
          ))}
        </nav>

        <section className="me-section" aria-labelledby="me-section-heading">
          <h2 id="me-section-heading" className="visually-hidden">
            {ME_TAB_LABELS[query.tab]}
          </h2>
          {list.status === "switched" ? (
            <p className="me-state" role="status">
              로그인한 사용자가 바뀌었습니다. <a href={self}>다시 불러오기</a>
            </p>
          ) : list.status === "unavailable" ? (
            <MeError retryHref={self} text={`${ME_TAB_LABELS[query.tab]}을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.`} />
          ) : (
            <MeListBody query={query} page={list.data} />
          )}
        </section>
      </div>
    </MeViewerGuard>
  );
}

function MeListBody({ query, page }: { query: MeQuery; page: Page<MyRatingItem> | Page<MyReviewItem> }) {
  if (page.totalElements === 0) {
    return (
      <div className="home-state me-empty">
        <p className="home-state__title">{query.tab === "ratings" ? "아직 남긴 별점이 없어요" : "아직 쓴 리뷰가 없어요"}</p>
        <p>다 본 작품을 찾아 {query.tab === "ratings" ? "별점을" : "리뷰를"} 남겨 보세요.</p>
        <Link className="button button--secondary" href="/works">
          작품 찾기
        </Link>
      </div>
    );
  }
  if (page.content.length === 0) {
    return (
      <div className="home-state me-empty">
        <p className="home-state__title">이 페이지에는 기록이 없어요</p>
        <p>
          전체 {page.totalPages.toLocaleString("ko-KR")}쪽 중 {query.page.toLocaleString("ko-KR")}쪽을 요청했습니다.
        </p>
        <Link className="button button--secondary" href={meHref({ ...query, page: page.totalPages })}>
          마지막 쪽으로
        </Link>
      </div>
    );
  }
  return (
    <>
      <p className="me-section__count">
        {query.tab === "ratings" ? "내 별점" : "내 리뷰"} {page.totalElements.toLocaleString("ko-KR")}개
        {page.totalPages > 1 ? ` · ${query.page}/${page.totalPages}쪽` : ""}
        <span className="me-section__order"> · {query.tab === "ratings" ? "최근 평가 순" : "최근 작성 순"}</span>
      </p>
      {query.tab === "ratings" ? (
        <MyRatingList items={page.content as MyRatingItem[]} />
      ) : (
        <MyReviewList items={page.content as MyReviewItem[]} />
      )}
      <Pagination
        currentPage={query.page}
        totalPages={page.totalPages}
        hrefFor={(n) => meHref({ ...query, page: n })}
        label={`${ME_TAB_LABELS[query.tab]} 페이지`}
      />
    </>
  );
}

function MeError({ retryHref, text }: { retryHref: string; text: string }) {
  return (
    <div className="status" role="alert">
      <h2 className="status__title">불러오지 못했습니다</h2>
      <p>{text}</p>
      <a className="button" href={retryHref}>
        다시 시도
      </a>
    </div>
  );
}
