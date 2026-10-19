import Link from "next/link";

import { RatingSummaryLine } from "@/components/ratings/RatingSummaryLine";
import type { WorkSummary } from "@/lib/api/types";
import { formatRegisteredDate, isoDateTime } from "@/lib/home/model";
import { WORK_FORMAT_LABELS, groupCredits } from "@/lib/works/labels";
import { workDetailHref } from "@/lib/works/params";

import { WorkCover } from "./WorkCover";

type Props = {
  work: WorkSummary;
  /** 전체 순위(1부터). 최근 등록 목록에는 없다 */
  rank?: number;
  /** 사이트 등록일을 보여 줄지 */
  showRegistered?: boolean;
  /** 상세 주소. 기본값은 목록 복귀 상태 없는 상세(홈). 작품 찾기 화면은 검색 조건을 이어 붙인 주소를 넘긴다 */
  href?: string;
  /** 공개 리뷰 수. 넘기면(작품 찾기) 별점 아래에 보여 준다. 홈은 넘기지 않는다 */
  reviewCount?: number | null;
};

/**
 * 홈 작품 카드(시안 v2): 표지 → 형식 → 제목(2줄) → 작가(1줄) → 별점·평가 수.
 * 제목 링크 하나가 카드 전체를 덮어 상세로 이동한다(키보드에서 카드당 탭 한 번).
 */
export function HomeWorkCard({ work, rank, showRegistered = false, href, reviewCount }: Props) {
  const names = groupCredits(work.creators)
    .map((group) => `${group.label} ${group.names.join(", ")}`)
    .join(" · ");
  return (
    <article className="home-card">
      <div className="home-card__cover">
        <WorkCover title={work.primaryTitle} format={work.workFormat} />
        {rank !== undefined ? (
          <span className={`home-card__rank${rank <= 3 ? " home-card__rank--top" : ""}`}>
            <span className="visually-hidden">순위 </span>
            {rank}
            <span className="visually-hidden">위</span>
          </span>
        ) : null}
      </div>
      <p className="home-card__meta">{WORK_FORMAT_LABELS[work.workFormat] ?? work.workFormat}</p>
      <h3 className="home-card__title" lang={work.primaryTitle.languageCode} title={work.primaryTitle.title}>
        <Link href={href ?? workDetailHref(work.id, 1)} className="home-card__link">
          {work.primaryTitle.title}
        </Link>
      </h3>
      <p className="home-card__author" title={names || undefined}>
        {names || "작가 정보 없음"}
      </p>
      <RatingSummaryLine summary={work.rating} className="home-card__rating" />
      {typeof reviewCount === "number" ? (
        <p className="home-card__reviews">
          {reviewCount > 0 ? `리뷰 ${reviewCount.toLocaleString("ko-KR")}개` : "리뷰 없음"}
        </p>
      ) : null}
      {showRegistered ? (
        <p className="home-card__registered">
          <time dateTime={isoDateTime(work.createdAt)}>{formatRegisteredDate(work.createdAt)}</time> 등록
        </p>
      ) : null}
    </article>
  );
}
