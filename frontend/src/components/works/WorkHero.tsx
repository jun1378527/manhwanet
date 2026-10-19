import { CreditList } from "@/components/CreditList";
import { WorkCover } from "@/components/home/WorkCover";
import { NO_RATINGS_TEXT, describeSummary, formatScore } from "@/lib/ratings/score";
import { WORK_FORMAT_LABELS, languageLabel } from "@/lib/works/labels";
import { type WorkDetailWithLinks, editionLanguageLabel, reviewCountOf, visiblePlatformLinks } from "@/lib/works/detail";

type Props = {
  work: WorkDetailWithLinks;
  /** 로그인한 사용자에게 이미 리뷰가 있으면 "내 리뷰 보기", 없으면 "리뷰 쓰기" */
  hasMyReview: boolean;
};

/**
 * 작품 상세 상단(대문·작품 찾기와 같은 표지 디자인):
 * 표지(가상 데모 작품만 데모 표지, 그 밖에는 표지 없음) → 형식 → 제목 → 다른 제목 → 작가 → 평가 요약 → 플랫폼 바로가기 → 별점·리뷰 진입.
 * 평가 요약은 서버 렌더링 값이며, 별점·리뷰를 바꾸면 router.refresh 로 다시 그려진다.
 */
export function WorkHero({ work, hasMyReview }: Props) {
  const altTitles = work.titles.filter((title) => !title.primary);
  const links = visiblePlatformLinks(work.platformLinks);
  const reviewCount = reviewCountOf(work);
  const rated = work.rating.count > 0 && work.rating.average !== null;

  return (
    <header className="work-hero">
      <div className="work-hero__cover">
        <WorkCover title={work.primaryTitle} format={work.workFormat} />
      </div>

      <div className="work-hero__body">
        <p className="work-hero__meta">
          <span className="work-hero__format">{WORK_FORMAT_LABELS[work.workFormat] ?? work.workFormat}</span>
        </p>
        <h1 className="work-hero__title" lang={work.primaryTitle.languageCode}>
          {work.primaryTitle.title}
        </h1>
        {altTitles.length > 0 ? (
          <div className="work-hero__alt">
            <h2 className="visually-hidden">다른 제목</h2>
            <ul className="work-hero__alt-list">
              {altTitles.map((title) => (
                <li key={`${title.languageCode}:${title.title}`}>
                  <span lang={title.languageCode}>{title.title}</span>
                  <span className="work-hero__alt-lang"> {languageLabel(title.languageCode)}</span>
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        <section className="work-hero__credits" aria-labelledby="work-credits-heading">
          <h2 id="work-credits-heading" className="visually-hidden">
            작가
          </h2>
          <CreditList credits={work.creators} />
        </section>

        <section className="work-hero__stats" aria-labelledby="work-stats-heading">
          <h2 id="work-stats-heading" className="visually-hidden">
            평가 요약
          </h2>
          <dl className="work-stats">
            <div className="work-stats__item work-stats__item--score">
              <dt>평균 별점</dt>
              <dd>
                {rated ? (
                  <>
                    <span className="visually-hidden">{describeSummary(work.rating)}</span>
                    <span aria-hidden="true">
                      <span className="work-stats__star">★</span> {formatScore(work.rating.average as number)}
                    </span>
                  </>
                ) : (
                  <span className="work-stats__empty">{NO_RATINGS_TEXT}</span>
                )}
              </dd>
            </div>
            <div className="work-stats__item">
              <dt>별점 참여</dt>
              <dd>{work.rating.count.toLocaleString("ko-KR")}명</dd>
            </div>
            {reviewCount !== null ? (
              <div className="work-stats__item">
                <dt>리뷰</dt>
                <dd>{reviewCount.toLocaleString("ko-KR")}개</dd>
              </div>
            ) : null}
          </dl>
        </section>

        {links.length > 0 ? (
          <section className="work-hero__links" aria-labelledby="work-links-heading">
            <h2 id="work-links-heading" className="work-hero__links-title">
              보러 가기
            </h2>
            <ul className="platform-links">
              {links.map((link) => (
                <li key={link.href}>
                  <a className="platform-link" href={link.href} target="_blank" rel="noopener noreferrer nofollow">
                    {link.label}
                    {link.languageCode !== work.primaryTitle.languageCode ? (
                      <span className="platform-link__lang"> ({editionLanguageLabel(link.languageCode)})</span>
                    ) : null}
                    <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true" className="platform-link__icon">
                      <path d="M4.5 2.5h5v5M9.5 2.5 3 9" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                    </svg>
                    <span className="visually-hidden"> (새 창, 외부 사이트)</span>
                  </a>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        <nav className="work-hero__actions" aria-label="평가하기">
          <a className="button" href="#my-rating">
            별점 남기기
          </a>
          <a className="button button--secondary" href={hasMyReview ? "#my-review" : "#write-review"}>
            {hasMyReview ? "내 리뷰 보기" : "리뷰 쓰기"}
          </a>
        </nav>
      </div>
    </header>
  );
}
