-- 홈 최근 공개 리뷰(GET /api/v1/reviews/recent): 작성 시각 DESC, id DESC 로 최신 몇 건만 읽는다.
-- V5 의 (work_id, created_at, id) 색인은 작품별 목록용이라 전체 최신순에는 쓰이지 않는다. V1~V17 은 고치지 않는다.
CREATE INDEX idx_work_reviews_created_at_id ON work_reviews (created_at DESC, id DESC);
