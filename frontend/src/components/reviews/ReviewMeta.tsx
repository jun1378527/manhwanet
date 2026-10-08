import { formatSeoulDateTime } from "@/lib/reviews/time";

/** 작성·수정 시각 (Asia/Seoul). 수정된 리뷰는 수정 시각을 함께 보여 준다. */
export function ReviewMeta({ createdAt, updatedAt, edited }: { createdAt: string; updatedAt: string; edited: boolean }) {
  return (
    <p className="review-meta">
      <span className="visually-hidden">작성 </span>
      <time dateTime={createdAt}>{formatSeoulDateTime(createdAt)}</time>
      {edited ? (
        <>
          {" · "}
          <span>
            수정됨 <span className="visually-hidden">수정 시각 </span>
            <time dateTime={updatedAt}>({formatSeoulDateTime(updatedAt)})</time>
          </span>
        </>
      ) : null}
    </p>
  );
}
