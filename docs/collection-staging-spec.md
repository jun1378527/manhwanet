# 수집 실행·staging 구현 기록 (#45)

> **상태: 구현됨(2026-10-15, 미커밋).** 기준 커밋 `c2aa0b2`. 설계는 [work-collection-design.md](work-collection-design.md) §6·§7.4·§8(초안)이고, 이 문서는 실제 구현 계약과 설계와 다른 점을 적습니다. 설계 문서는 고치지 않았습니다.
> 범위: 수집 설정, 수집 실행·작업 큐·실패 표본, staging(식별 키·원문·등급 신호·크레딧·발견 경로·별칭·변경 이력), 검수 후보·표지·누락 현황 **표**. 하지 않은 것: HTTP 수집기(#46), 플랫폼 어댑터(#47~#50), 검수·작품 등록 연동(#51), 누락 집계(#52), 표지 다운로드(#55), 실제 플랫폼 수집.

## 1. 마이그레이션

| 파일 | 내용 |
|---|---|
| `V9__create_collection_policies_and_crawl_runs.sql` | `platform_collection_policies`(10곳 모두 `DISABLED`로 시작), `crawl_runs`, `crawl_tasks`, `crawl_failure_samples`. `admin_change_log`의 대상 종류 CHECK 에 `COLLECTION_POLICY`·`CRAWL_RUN` 추가(V8 파일은 고치지 않고 V9 에서 제약을 다시 만듦) |
| `V10__create_staging_listings.sql` | `staging_listings`, `staging_id_aliases`, `staging_listing_discoveries`, `staging_credits`, `staging_listing_changes` |
| `V11__create_review_candidates_covers_coverage.sql` | `staging_match_candidates`, `cover_assets`, `cover_asset_stage_history`, `platform_coverage_snapshots` (표와 제약만, 기능은 #51·#55·#52) |
| `V12__create_staging_identity_keys.sql` | `staging_identity_keys`. 대표 ID 와 별칭이 플랫폼마다 하나의 식별 공간을 쓴다(#61). V10 은 이미 적용되어 고치지 않음 |

- V1~V8 은 고치지 않았습니다. 새 FK 에 CASCADE 가 없습니다.
- 새 표는 JPA 엔티티 없이 JDBC 로 읽고 씁니다(`ddl-auto=validate` 영향 없음).

## 2. 설계·이슈와 다른 점

| 항목 | 설계·이슈 | 구현 | 이유 |
|---|---|---|---|
| 실행 게이트 값 | #45 본문 `APPROVED`, 설계 §8.1 `ENABLED` | `DISABLED`·`PROBE`·`ENABLED` | 설계(2026-10-12 갱신)를 따름 |
| 근거 필수 CHECK | #45 본문 "근거 필수 CHECK" | 기본값이 아닌 설정은 **바꾼 관리자·시각**만 필수(`ck_pcp_decided`). 외부 근거(`basis_kind`)는 선택 | 2026-10-12 사용자 결정(D-11): 외부 허락 문서는 실행 조건이 아님 |
| 실패 정보 | `detail_status`·작업 `last_error` | staging 에 `last_failure_reason`·`last_failure_at`·`last_failure_run_id`·`consecutive_failures` 추가 | 재수집 실패를 내용과 별개로 보존하고, 사라짐 확인(연속 404, #53)의 근거로 쓰기 위해 |
| 실행 기록 | — | `crawl_runs.stop_requested_at`, `crawl_failure_samples.error`·`expires_at`, `staging_listings.first_run_id`, 발견 경로 `last_run_id`, 크레딧 `position` | 재개·보고에 필요한 최소 열 |
| 변경 이력 조건 | `content_hash`가 바뀐 경우 | 내용 해시 또는 **수집 상태(detail_status)** 가 바뀐 경우 | 실패·회복도 이력에 남기기 위해 |
| 작업 URL | `VARCHAR(1000)` | 같음. 500자 이하는 공식 URL 과 같은 정규화·검사, 그보다 길면 https·보이는 ASCII 만 검사 | 사이트맵 하위 URL 대비 |
| #40 근거 `COLLECTOR` | 설계 §8.4(D-19) | 넣지 않음 | 미결정(D-19). 검수 등록(#51)에서 정함 |

## 3. 서비스 계약 (수집기용, HTTP 로 열지 않음)

수집기(#46)는 같은 애플리케이션 안에서 아래 서비스를 부릅니다(설계 C-1). 모든 쓰기는 **실행 잠금을 쥔 프로세스만** 할 수 있고, 시각 비교는 DB `now()` 기준입니다.

### 3.1 실행 (`CrawlRunService`)

| 메서드 | 규칙 |
|---|---|
| `start(platform, kind, adapterVersion, owner, leaseSeconds, robotsSha256)` | 수집 설정이 `DISABLED`면 409 `COLLECTION_NOT_ALLOWED`, `PROBE`면 `PROBE` 실행만. 활성 실행(`RUNNING`·`PAUSED`·`STOP_REQUESTED`·`BLOCKED`)이 있으면 409 `CRAWL_RUN_ACTIVE`(`CrawlRunActiveException.activeRunId`)이고 새 실행을 만들지 않음 |
| `acquire(runId, owner, leaseSeconds)` | 재개·넘겨받기. 다른 프로세스의 잠금이 살아 있으면 409 `CRAWL_RUN_LEASE_LOST`. 잠금이 만료됐거나 다른 소유자의 것이었을 때, 그리고 `PAUSED`·`BLOCKED` 를 다시 시작할 때는 끝나지 않은 `RUNNING` 작업을 `PENDING`으로 되돌림(시도 횟수 유지). `STOP_REQUESTED` 에서 유효한 잠금을 가진 같은 소유자가 다시 확인하면 진행 중 작업은 그대로 두고 상태도 `STOP_REQUESTED` 로 유지(#62). 설정이 꺼져 있으면 재개 불가. 끝난 실행은 다시 열지 않음 |
| `renew`·`pause`·`block(reason)` | 잠금을 쥔 프로세스만. `pause`·`block`은 잠금을 놓음 |
| `finishStop` | `STOP_REQUESTED` → `STOPPED`, 남은 작업 `SKIPPED` |
| `complete`·`fail` | 끝나지 않은 작업이 있으면 완료 불가(409). 실패는 남은 작업을 `SKIPPED`로 닫음 |
| `requestStop(runId, reason, actorId)` (관리자) | `RUNNING` → `STOP_REQUESTED`, 수집기가 돌지 않는 `PAUSED`·`BLOCKED` → 바로 `STOPPED`. 변경 기록(`CRAWL_RUN`)을 같은 트랜잭션에 남김 |

- 결과·작업 쓰기는 실행 행을 공유 잠금으로 확인합니다. 넘겨받기·중단 확정과 직렬화되어, 잠금을 잃은 프로세스는 넘겨받은 뒤에 쓰지 못합니다.

### 3.2 작업 (`CrawlTaskService`)

- `enqueue`: 실행 안에서 (종류, URL 해시)가 같으면 기존 작업을 돌려줍니다. `STOP_REQUESTED` 에서는 409 `CRAWL_RUN_STATE_CONFLICT`.
- `claim(limit 1~100)`: 대기 시각이 지난 `PENDING`을 `SKIP LOCKED`로 가져갑니다. 같은 작업이 두 번 나가지 않습니다. 잠금이 살아 있는 `STOP_REQUESTED` 에서는 빈 목록으로 끝나며, 이는 잠금 상실(`CRAWL_RUN_LEASE_LOST`)과 다릅니다(#62).
- `succeed`·`fail`: 진행 중인 작업만. 잠금이 살아 있으면 `STOP_REQUESTED` 에서도 이미 가져간 작업을 끝내거나 실패로 닫을 수 있습니다. 실행 완료(`complete`)는 중단 요청 중 409 `CRAWL_RUN_STATE_CONFLICT`이고, 실행은 `finishStop` 으로 닫습니다. 타임아웃·연결·5xx·429 는 지정한 시각 뒤 재시도(최대 5회), robots·성인 건너뜀은 `SKIPPED`, 그 밖은 `FAILED`. 마지막 HTTP 상태·오류를 남깁니다.
- 파싱 실패 원문은 실행당 200건까지 gzip 으로 보관합니다(1MB 이하, 30일 뒤 정리 대상).

### 3.3 staging 반영 (`StagingIngestService.ingest(runId, owner, CollectedListing)`)

| 상태(`detailStatus`) | 반영 |
|---|---|
| `OK` | 내용 통째 갱신(제목·장르·등급 표기·연재 상태·표지 URL·소개문·extra·필드 출처·크레딧) |
| `GATED`·`SKIPPED_ADULT` | 목록에서 본 값만(URL·제목·등급 표기·등급 신호). 상세 내용은 그대로 |
| `NOT_FOUND`·`PARSE_FAILED`·`FAILED` | 제목·소개문·등급 표기·등급 신호·`age_class`·필드 출처를 바꾸지 않고 실패 정보만 기록(#63) |

- **식별 키**: 대표 ID 와 별칭은 `staging_identity_keys` 한 공간입니다. 반영 전에 건드리는 ID 를 정렬해 트랜잭션 자문 잠금으로 묶습니다. 별칭 → (플랫폼, 외부 작품 ID) → 없으면 새 행. 같은 번호라도 플랫폼이 다르면 다른 행입니다. 다른 행에 연결되는 키는 409 `STAGING_ALIAS_CONFLICT`이고 그 반영 전체가 롤백됩니다. 행을 자동으로 합치지 않습니다(#61).
- **등급**: 원래 표기와 명시 신호(`explicit=true`)를 #39 분류기로 분류해 한 값으로 모일 때만 그 값입니다. 신호가 없거나, 분류할 수 없거나, 서로 다르면 `UNKNOWN`이며 `GENERAL`로 올리지 않습니다. 후보 신호는 저장만 합니다. 성공·목록 값 반영은 출처별 최신 값을 쌓습니다. 실패 결과의 신호는 저장하지 않습니다.
- **변경 이력**: 바뀐 필드의 이전·이후 값을 staging 갱신과 같은 트랜잭션에 (staging, 실행)당 한 줄로 남깁니다. 재개로 같은 실행이 다시 써도 줄이 늘지 않고 처음 이전 값을 유지합니다. 소개문은 해시·길이만 남깁니다.
- **실패 보존**: 실패는 `last_failure_*`와 `consecutive_failures`에 남고, 같은 실행에서 같은 실패를 다시 반영해도 횟수·내용·등급 신호·`age_class`·필드 출처가 그대로입니다. 성공하면 그 결과로 내용을 다시 쓰고 연속 실패만 0 으로 돌리며 마지막 실패 기록은 남깁니다.
- **소개문·표지**: 소개문 정책이 `NOT_COLLECTED`면 저장하지 않습니다. 표지는 URL 문자열만 저장합니다.
- **별칭**: 이미 성공한 별칭으로 다시 발견하면 그 한 행을 갱신합니다. 다른 행의 대표 ID 이거나 이미 다른 행을 가리키는 별칭이면 409 `STAGING_ALIAS_CONFLICT`이고 그 반영 전체가 롤백됩니다.
- staging 은 `review_status = NEW`로 만들기만 하고 바꾸지 않습니다. 공개 작품·연결로 등록하지 않습니다(#51).

## 4. 관리자 API (ADMIN + CSRF)

| 메서드 | 경로 | 내용 |
|---|---|---|
| GET | `/api/v1/admin/collection-policies` | 10곳의 수집 설정, `activeRunId`, `warnings` |
| GET·PUT | `/api/v1/admin/collection-policies/{platformCode}` | 전체 교체 `{version, collectionPolicy, synopsisPolicy, coverPolicy, note, basisKind?, basisNote?, rateIntervalMs, dailyRequestCap, reason}`. `reason` 필수, 불일치 409 `COLLECTION_POLICY_VERSION_CONFLICT`. 바꾼 관리자·시각 기록, 변경 기록 `COLLECTION_POLICY`. 활성 실행이 있는데 끄면 `ACTIVE_RUN_REMAINS` 경고(실행은 따로 중단) |
| GET | `/api/v1/admin/crawl-runs?platformCode=&limit=&beforeId=` | 실행 목록(최신순) |
| GET | `/api/v1/admin/crawl-runs/{runId}` | 실행 상세: 작업 상태별 수, 실패 표본 수 |
| POST | `/api/v1/admin/crawl-runs/{runId}/stop` | `{reason}` 중단 요청 |
| GET | `/api/v1/admin/staging-listings?platformCode=&reviewStatus=&detailStatus=&limit=&beforeId=` | staging 목록(소개문 원문 제외) |
| GET | `/api/v1/admin/staging-listings/{id}` | 상세: 소개문 원문(비공개), 크레딧, 별칭, 발견 경로, 최근 변경 이력 50건 |

- 실행 시작·작업·결과 반영은 HTTP 로 열지 않았습니다. staging 을 공개하거나 작품으로 등록하는 API 는 없습니다.

## 5. 결정하지 않은 정책 (기본 동작만 정함)

- 수집 설정을 `DISABLED`로 바꿀 때 활성 실행을 자동으로 중단할지(지금은 경고만, 재개는 막음)
- 신호 출처가 오래 갱신되지 않을 때 등급 신호를 지울지(지금은 출처별 최신 값을 계속 유지)
- 별칭으로 찾은 행의 대표 ID 를 바꾸거나 행을 합치는 규칙(설계 §6.2 흡수, #48·#51)
- 실패 표본·변경 이력 보관 기간과 정리 작업(표본은 `expires_at`만 둠)
- 작업 재시도 간격(지금은 수집기가 `retryAfterSeconds`로 지정, 최대 5회)
- #40 근거 종류 `COLLECTOR`(D-19)
