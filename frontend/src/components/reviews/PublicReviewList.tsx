"use client";

import Link from "next/link";
import { useId, useState } from "react";

import { Pagination } from "@/components/Pagination";
import type { PublicReview, PublicReviewPage } from "@/lib/reviews/model";
import { ReviewMeta } from "./ReviewMeta";

type Props = {
  page: PublicReviewPage;
  /** 1부터 시작하는 현재 리뷰 페이지 */
  reviewPage: number;
  hrefFor: (reviewPage: number) => string;
  /** 내 리뷰 id (공개 응답은 사용자별 정보가 없으므로 화면이 비교해 표시한다) */
  myReviewId: string | null;
};

/**
 * 공개 리뷰 한 페이지. 부모가 페이지 번호로 key 를 주므로, 페이지를 옮기면 펼친 스포일러 상태가 초기화된다.
 * - 본문·닉네임은 React 텍스트로만 출력한다(HTML 로 해석하지 않음). 줄바꿈은 CSS pre-wrap.
 * - 스포일러 리뷰는 펼치기 전까지 본문을 DOM 에 렌더링하지 않는다. 공개 API 는 본문을 포함하므로 이는 표시 정책이다.
 */
export function PublicReviewList({ page, reviewPage, hrefFor, myReviewId }: Props) {
  if (page.totalElements === 0) {
    return <p className="review-list__empty">아직 리뷰가 없습니다.</p>;
  }
  if (page.content.length === 0) {
    const last = Math.max(1, page.totalPages);
    return (
      <div className="review-list__empty" role="status">
        <p>이 페이지에는 리뷰가 없습니다.</p>
        <Link href={hrefFor(last)} scroll={false}>
          마지막 리뷰 페이지로
        </Link>
      </div>
    );
  }
  return (
    <>
      <ol className="review-list">
        {page.content.map((review) => (
          <PublicReviewItem key={review.id} review={review} mine={review.id === myReviewId} />
        ))}
      </ol>
      <Pagination
        currentPage={reviewPage}
        totalPages={page.totalPages}
        hrefFor={hrefFor}
        label="리뷰 목록 페이지"
        scroll={false}
      />
    </>
  );
}

function PublicReviewItem({ review, mine }: { review: PublicReview; mine: boolean }) {
  const bodyId = useId();
  const [revealed, setRevealed] = useState(false);
  const hidden = review.containsSpoiler && !revealed;

  return (
    <li className="review-item">
      <p className="review-item__author">
        <span dir="auto" className="review-item__nickname">
          {review.authorNickname ?? "알 수 없는 사용자"}
        </span>
        {mine ? <span className="badge review-item__mine">내 리뷰</span> : null}
        {review.containsSpoiler ? <span className="badge badge--warning">스포일러</span> : null}
      </p>
      <ReviewMeta createdAt={review.createdAt} updatedAt={review.updatedAt} edited={review.edited} />
      <div id={bodyId} className="review-item__content">
        {hidden ? (
          <p className="review-item__spoiler">스포일러가 포함된 리뷰입니다.</p>
        ) : (
          <p className="review-body" dir="auto">
            {review.body}
          </p>
        )}
      </div>
      {review.containsSpoiler ? (
        <button
          type="button"
          className="link-button"
          aria-expanded={revealed}
          aria-controls={bodyId}
          onClick={() => setRevealed((current) => !current)}
        >
          {revealed ? "스포일러 내용 숨기기" : "스포일러 내용 보기"}
        </button>
      ) : null}
    </li>
  );
}
