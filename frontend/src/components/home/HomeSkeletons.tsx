/** 홈 작품·리뷰 영역을 불러오는 동안 보여 주는 자리(같은 배치, 움직임 줄이기에서는 깜빡임 없음) */
export function HomeWorksSkeleton() {
  return (
    <section className="home-works" aria-busy="true" aria-labelledby="home-works-loading">
      <div className="home-section-head">
        <h2 id="home-works-loading">지금 둘러볼 작품</h2>
      </div>
      <p className="visually-hidden" role="status">
        작품을 불러오는 중입니다
      </p>
      <div className="segmented segmented--placeholder" aria-hidden="true">
        <span className="segmented__button">평점순</span>
        <span className="segmented__button">평가 많은 순</span>
        <span className="segmented__button">최근 등록</span>
      </div>
      <ol className="home-grid" aria-hidden="true">
        {Array.from({ length: 10 }, (_, i) => (
          <li key={i} className="skeleton-card">
            <span className="skeleton skeleton--cover" />
            <span className="skeleton skeleton--line" />
            <span className="skeleton skeleton--line skeleton--short" />
          </li>
        ))}
      </ol>
    </section>
  );
}

export function RecentReviewsSkeleton() {
  return (
    <section className="home-reviews" aria-busy="true" aria-labelledby="home-reviews-loading">
      <div className="home-section-head">
        <h2 id="home-reviews-loading">최근 리뷰</h2>
      </div>
      <p className="visually-hidden" role="status">
        리뷰를 불러오는 중입니다
      </p>
      <ol className="recent-reviews" aria-hidden="true">
        {Array.from({ length: 4 }, (_, i) => (
          <li key={i} className="skeleton-review">
            <span className="skeleton skeleton--line skeleton--short" />
            <span className="skeleton skeleton--line" />
            <span className="skeleton skeleton--line" />
          </li>
        ))}
      </ol>
    </section>
  );
}
