import Link from "next/link";

import { formatScore } from "@/lib/ratings/score";
import { formatSeoulDateTime } from "@/lib/reviews/time";
import { type MyRatingItem, type MyReviewItem, reviewEditHref } from "@/lib/me/model";
import { WORK_FORMAT_LABELS, groupCredits } from "@/lib/works/labels";
import { workDetailHref } from "@/lib/works/params";

import { SpoilerExcerpt } from "./SpoilerExcerpt";

function credits(item: { work: MyRatingItem["work"] }): string {
  if (!item.work) return "";
  return groupCredits(item.work.creators)
    .map((g) => `${g.label} ${g.names.join(", ")}`)
    .join(" · ");
}

/** 공개되지 않는 작품: 작품명·작가를 모르고(응답에 없음) 상세로 갈 수 없다. 내 기록은 보존된다 */
function Unavailable() {
  return (
    <>
      <p className="me-item__title me-item__title--muted">이용할 수 없는 작품</p>
      <p className="me-item__note">지금은 공개되지 않은 작품이라 상세를 볼 수 없어요. 남긴 기록은 그대로 보관됩니다.</p>
    </>
  );
}

export function MyRatingList({ items }: { items: MyRatingItem[] }) {
  return (
    <ol className="me-list">
      {items.map((item) => (
        <li key={item.workId} className="me-item">
          <div className="me-item__main">
            {item.work ? (
              <>
                <p className="me-item__meta">{WORK_FORMAT_LABELS[item.work.workFormat] ?? item.work.workFormat}</p>
                <h3 className="me-item__title" lang={item.work.primaryTitle.languageCode}>
                  <Link href={workDetailHref(item.work.id, 1)}>{item.work.primaryTitle.title}</Link>
                </h3>
                <p className="me-item__credits">{credits(item) || "작가 정보 없음"}</p>
              </>
            ) : (
              <Unavailable />
            )}
            <p className="me-item__time">
              평가 <time dateTime={item.updatedAt}>{formatSeoulDateTime(item.updatedAt)}</time>
            </p>
          </div>
          <p className="me-item__score">
            <span className="visually-hidden">저장한 별점 5점 만점에 </span>
            <span aria-hidden="true" className="me-item__star">
              ★
            </span>{" "}
            {formatScore(item.score)}
            <span className="visually-hidden">점</span>
          </p>
        </li>
      ))}
    </ol>
  );
}

export function MyReviewList({ items }: { items: MyReviewItem[] }) {
  return (
    <ol className="me-list">
      {items.map((item) => (
        <li key={item.id} className="me-item me-item--review">
          <div className="me-item__main">
            {item.work ? (
              <h3 className="me-item__title" lang={item.work.primaryTitle.languageCode}>
                <Link href={workDetailHref(item.work.id, 1)}>{item.work.primaryTitle.title}</Link>
              </h3>
            ) : (
              <Unavailable />
            )}
            <p className="me-item__time">
              작성 <time dateTime={item.createdAt}>{formatSeoulDateTime(item.createdAt)}</time>
              {item.edited ? (
                <>
                  {" · "}수정 <time dateTime={item.updatedAt}>{formatSeoulDateTime(item.updatedAt)}</time>
                </>
              ) : null}
              {item.containsSpoiler ? <span className="badge badge--warning me-item__badge">스포일러</span> : null}
            </p>
            {item.containsSpoiler ? (
              <SpoilerExcerpt excerpt={item.excerpt} truncated={item.excerptTruncated} />
            ) : (
              <p className="me-review__excerpt" dir="auto">
                {item.excerpt}
                {item.excerptTruncated ? "…" : null}
              </p>
            )}
            {item.work ? (
              <p className="me-item__actions">
                <Link href={reviewEditHref(item.work.id)} className="me-item__edit">
                  상세에서 수정하기
                  <span className="visually-hidden"> ({item.work.primaryTitle.title})</span>
                </Link>
              </p>
            ) : null}
          </div>
        </li>
      ))}
    </ol>
  );
}
