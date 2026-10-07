import type { RatingSummary } from "@/lib/api/types";
import { NO_RATINGS_TEXT, describeSummary, formatScore } from "@/lib/ratings/score";

/**
 * 공개 별점 집계 한 줄. 서버·클라이언트 컴포넌트 모두에서 쓴다.
 * 별 기호는 장식이고, 스크린 리더는 전체 문장("평균 별점 5점 만점에 4.3점, 12명 참여")을 읽는다.
 */
export function RatingSummaryLine({ summary, className }: { summary: RatingSummary; className?: string }) {
  const classes = ["rating-summary", className].filter(Boolean).join(" ");
  if (summary.count === 0 || summary.average === null) {
    return <p className={`${classes} rating-summary--empty`}>{NO_RATINGS_TEXT}</p>;
  }
  return (
    <p className={classes}>
      <span className="visually-hidden">{describeSummary(summary)}</span>
      <span aria-hidden="true">
        <span className="rating-summary__star">★</span> <strong>{formatScore(summary.average)}</strong>
        <span className="rating-summary__count"> · {summary.count.toLocaleString("ko-KR")}명 참여</span>
      </span>
    </p>
  );
}
