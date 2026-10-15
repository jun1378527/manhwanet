-- #46: 요청 간격·Retry-After 의 다음 허용 시각. V13 은 이미 적용되어 고치지 않는다.
-- 프로세스가 요청 직후 끝나도, 같은 실행을 재개하는 프로세스가 남은 시간 동안 요청하지 않게 한다.
-- 기다리는 동안에는 이 시각만 읽고 트랜잭션을 끝낸다.

ALTER TABLE crawl_runs ADD COLUMN next_request_at TIMESTAMPTZ;
