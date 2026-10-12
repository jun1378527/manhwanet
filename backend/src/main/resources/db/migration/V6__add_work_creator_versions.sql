-- 작품·작가 관리자 수정의 동시 수정 충돌 방지 (#38, docs/work-platform-admin-spec.md §3.1·§5.1·§5.2)
-- 요청 본문의 version 이 현재 값과 같을 때만 수정하고, 수정할 때마다 1씩 올린다.
-- 기존 행은 version 0 이 된다. 작품·작가 ID와 별점·리뷰 행은 바뀌지 않는다.
-- 공개 상태(visibility)·중복 표시(duplicate_of_work_id)는 #41 에서 별도 마이그레이션으로 추가한다.

ALTER TABLE works
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_works_version CHECK (version >= 0);

ALTER TABLE creators
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_creators_version CHECK (version >= 0);
