"use client";

import Link from "next/link";
import { useState } from "react";

import { apiRequest } from "@/lib/api/browser";
import { formatReviewTime, isoDateTime, toRecentReview, type HomeReview } from "@/lib/home/model";
import { workDetailHref } from "@/lib/works/params";

type BodyState = { status: "idle" } | { status: "loading" } | { status: "loaded"; body: string } | { status: "error" };

/**
 * 최근 리뷰 한 건. 스포일러 리뷰는 본문 없이 받아 두고(서버가 뺌), "내용 보기"를 누르면 그때 GET /reviews/{id} 로 받는다.
 * 펼치기 전에는 본문이 DOM·페이지 데이터에 없고, 다시 숨기면 DOM 에서 뺀다.
 */
export function RecentReviewItem({ review }: { review: HomeReview }) {
  const [revealed, setRevealed] = useState(false);
  const [state, setState] = useState<BodyState>(review.body !== null ? { status: "loaded", body: review.body } : { status: "idle" });
  const detailHref = workDetailHref(review.work.id, 1);

  async function toggle() {
    if (revealed) {
      setRevealed(false);
      return;
    }
    setRevealed(true);
    if (state.status === "loaded" || state.status === "loading") return;
    setState({ status: "loading" });
    const result = await apiRequest("GET", `/reviews/${encodeURIComponent(review.id)}`, toRecentReview);
    setState(result.kind === "ok" && result.data.id === review.id ? { status: "loaded", body: result.data.body } : { status: "error" });
  }

  const showBody = !review.containsSpoiler || revealed;
  return (
    <article className="recent-review">
      <h3 className="recent-review__work" lang={review.work.primaryTitle.languageCode} title={review.work.primaryTitle.title}>
        <Link href={detailHref}>{review.work.primaryTitle.title}</Link>
      </h3>
      {review.containsSpoiler ? (
        <div className="recent-review__spoiler">
          <span className="badge badge--warning">스포일러</span>
          <button
            type="button"
            className="recent-review__reveal"
            aria-expanded={revealed}
            aria-label={revealed ? "스포일러 내용 숨기기" : "스포일러 내용 보기"}
            onClick={toggle}
          >
            {revealed ? "내용 숨기기" : "내용 보기"}
          </button>
        </div>
      ) : null}
      {!showBody ? null : state.status === "loaded" ? (
        <p className="recent-review__body">{state.body}</p>
      ) : state.status === "error" ? (
        <p className="recent-review__notice" role="alert">
          본문을 불러오지 못했어요. <Link href={detailHref}>작품 상세</Link>에서 확인해 주세요.
        </p>
      ) : (
        <p className="recent-review__notice" role="status">
          본문을 불러오는 중…
        </p>
      )}
      <p className="recent-review__meta">
        <span className="recent-review__author" title={review.authorNickname ?? undefined}>
          {review.authorNickname ?? "알 수 없는 사용자"}
        </span>
        <time dateTime={isoDateTime(review.createdAt)}>{formatReviewTime(review.createdAt)}</time>
        {review.edited ? <span>수정됨</span> : null}
      </p>
    </article>
  );
}
