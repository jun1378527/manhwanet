-- #46: 수집기 공통 기반. V9~V12 는 이미 적용되어 고치지 않는다(제약은 이 파일에서 다시 만든다).
-- 새 FK 에 CASCADE 없음.

-- 한 실행에서 받은 429 횟수. 프로세스가 바뀌어 같은 실행을 넘겨받아도 "2회째면 중단" 규칙이 이어진다(설계 §7.3)
ALTER TABLE crawl_runs ADD COLUMN rate_limit_hits SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE crawl_runs ADD CONSTRAINT ck_crawl_runs_rate_hits CHECK (rate_limit_hits BETWEEN 0 AND 1000);

-- 작업 실패 사유: 수집기가 구분해 남기는 값을 더한다
--   HTTP_4XX            404·410·401·403·429 밖의 4xx (재시도 없음)
--   BLOCKED_DESTINATION 허용하지 않은 호스트·https 가 아닌 곳·공개 주소가 아닌 곳으로 가는 요청이나 리다이렉트
--   RESPONSE_TOO_LARGE  응답 크기 상한 초과
--   PROTOCOL_ERROR      응답 형식 오류, 리다이렉트 한도 초과
ALTER TABLE crawl_tasks DROP CONSTRAINT ck_crawl_tasks_error;
ALTER TABLE crawl_tasks ADD CONSTRAINT ck_crawl_tasks_error CHECK (last_error IS NULL OR last_error IN
    ('TIMEOUT', 'CONNECTION', 'HTTP_5XX', 'RATE_LIMITED', 'BLOCKED_ACCESS', 'GATED', 'NOT_FOUND', 'PARSE_FAILED',
     'SKIPPED_ROBOTS', 'SKIPPED_ADULT', 'OPERATOR_STOP',
     'HTTP_4XX', 'BLOCKED_DESTINATION', 'RESPONSE_TOO_LARGE', 'PROTOCOL_ERROR'));

-- 플랫폼별 하루 요청 수(한국 시간 기준 날짜). 보내기 직전에 한 줄 갱신으로 세고, 상한에 닿으면 실행을 일시 정지한다(설계 §7.2)
CREATE TABLE collector_daily_requests (
    platform_code VARCHAR(30) NOT NULL,
    request_date  DATE        NOT NULL,
    request_count INTEGER     NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_collector_daily_requests PRIMARY KEY (platform_code, request_date),
    CONSTRAINT fk_collector_daily_platform FOREIGN KEY (platform_code) REFERENCES platforms (code),
    CONSTRAINT ck_collector_daily_count CHECK (request_count BETWEEN 0 AND 1000000)
);
