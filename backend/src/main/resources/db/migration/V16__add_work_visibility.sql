-- #41 최소 선행(#51 연동): 작품 공개 상태. V1~V15 는 이미 적용되어 고치지 않는다.
-- 기존 작품은 지금처럼 공개(PUBLIC)로 둔다. 숨김(HIDDEN) 작품은 공개 목록·상세·공개 리뷰 목록에서 빠지고,
-- 별점·리뷰·플랫폼 연결·작품 ID 는 그대로 남는다. 중복 표시(duplicate_of_work_id)는 #41 남은 범위다.
-- 일반 열 색인은 두지 않는다(works 의 UPDATE 잠금 수준에 영향을 주지 않게. work-platform-admin-spec §5.1 주의).
ALTER TABLE works ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE works ADD CONSTRAINT ck_works_visibility CHECK (visibility IN ('PUBLIC', 'HIDDEN'));
