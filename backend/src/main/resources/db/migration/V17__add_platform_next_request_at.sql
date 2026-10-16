-- #68: 다음 요청 시각은 실행이 아니라 플랫폼에 남긴다. V14 의 crawl_runs.next_request_at 은 고치지 않는다.
-- 실행이 끝나거나 중단·차단되어도 이 열은 지우지 않는다. 시작 시 이전 실행에서 복사하지 않는다.
-- 이미 실행 행에 남은 시각이 있으면 플랫폼별로 가장 늦은 값을 옮긴다.

ALTER TABLE platform_collection_policies
    ADD COLUMN next_request_at TIMESTAMPTZ;

UPDATE platform_collection_policies AS policy
SET next_request_at = latest.max_at
FROM (
    SELECT platform_code, MAX(next_request_at) AS max_at
    FROM crawl_runs
    WHERE next_request_at IS NOT NULL
    GROUP BY platform_code
) AS latest
WHERE policy.platform_code = latest.platform_code;
