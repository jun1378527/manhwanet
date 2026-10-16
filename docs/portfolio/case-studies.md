# 대표 문제 해결 사례

> 기준 커밋 [`0a1c7f7`](https://github.com/jun1378527/manhwanet/commit/0a1c7f7). 재현 조건·원인·검증 결과는 각 이슈에 남긴 기록을 옮긴 것입니다. 코드 링크는 저장소 기준 상대 경로입니다.

| 사례 | 이슈 | 수정 커밋 | 한 줄 요약 |
| --- | --- | --- | --- |
| 1 | [#22](https://github.com/jun1378527/manhwanet/issues/22), 별점 [#26](https://github.com/jun1378527/manhwanet/issues/26) | [`dec44fa`](https://github.com/jun1378527/manhwanet/commit/dec44fa) | 계정을 바꾼 뒤 CSRF 자동 재시도가 이전 화면의 글을 새 계정에 저장 → 서버가 같은 요청 안에서 "화면의 사용자"와 세션 사용자를 비교 |
| 2 | [#61](https://github.com/jun1378527/manhwanet/issues/61) (관련 #45) | [`8302af6`](https://github.com/jun1378527/manhwanet/commit/8302af6) | 서로의 대표 ID를 별칭으로 동시에 등록하면 둘 다 커밋되어 다른 작품을 덮어씀 → 대표 ID·별칭을 하나의 키 공간 + 정렬된 자문 잠금 |
| 3 | [#65](https://github.com/jun1378527/manhwanet/issues/65) (관련 #46) | [`5132d5d`](https://github.com/jun1378527/manhwanet/commit/5132d5d) | 다음 요청 시각이 메모리에만 있어 재시작하면 5초 간격·Retry-After가 사라짐 → 실행 행에 `next_request_at` 저장 |

---

<a id="사례-1"></a>
## 사례 1. 계정 전환과 CSRF 재시도로 다른 계정에 저장되던 문제

### 재현 조건 (#22, #21 독립 리뷰에서 격리 DB로 재현)

1. 계정 A로 작품 상세를 열고 리뷰를 쓴 뒤 "등록"을 누릅니다.
2. 프론트의 사전 확인 `GET /auth/me`가 A로 통과한 뒤, `POST /api/v1/me/reviews/{workId}`가 나가기 전에 같은 브라우저에서 계정 B로 로그인합니다.
3. 첫 POST는 A 때 읽어 둔 CSRF 토큰과 B의 세션이 어긋나 403 `INVALID_CSRF_TOKEN`이 됩니다.
4. 클라이언트가 토큰을 다시 받아 한 번 재시도하고, 재시도는 201이 됩니다.

결과: 리뷰 행의 소유자는 B인데 본문은 A가 쓰던 글이었습니다. 화면은 "리뷰를 등록했습니다"를 보여 줬습니다. 삭제도 같은 간격에서 재현됐습니다. 새 계정에 리뷰가 없으면 204가 되어, 아무것도 지우지 않았는데 삭제된 것처럼 진행됐습니다. 서버는 늘 세션 사용자에게만 쓰므로 남의 행을 직접 덮어쓰지는 않았습니다. 문제는 **잘못된 계정에 생성되는 것**이었습니다.

### 원인

- 사용자 일치를 클라이언트의 `GET /auth/me` 사전 확인으로만 봤습니다. 확인과 실제 요청 사이의 간격은 클라이언트가 막을 수 없습니다.
- `apiRequest`가 CSRF 거절이면 같은 본문을 한 번 다시 보냈고, 그 사이 브라우저 쿠키는 이미 새 계정 세션이었습니다.
- 서버는 이 요청이 어떤 사용자의 화면에서 왔는지 알 수 없었습니다.

### 선택한 해결책

#22 기록의 판단은 다음과 같습니다.

| 선택지 | 판단 (#22) |
| --- | --- |
| 재시도·성공 반영 직전에 클라이언트가 세션 사용자를 다시 확인 | 이슈의 최소 수정안이었지만, "클라이언트 재확인을 늘려도 확인과 실제 요청 사이의 간격은 남는다"고 보고 최종 방어로 쓰지 않음(빠른 안내용으로만 유지) |
| **요청에 "화면을 그린 사용자 ID"를 싣고 서버가 같은 요청의 세션 사용자와 비교** | 같은 요청 안에서 판단하므로 간격이 없음 → 채택. 사용자가 바뀐 경우의 자동 재시도는 하지 않음 |

- 서버: [`ExpectedUser.verify`](../../backend/src/main/java/com/manhwanet/auth/api/ExpectedUser.java)
  - 내 리뷰 POST·PUT·DELETE와 내 별점 PUT·DELETE에는 `X-Expected-User-Id` 헤더가 필수입니다. GET은 헤더가 있을 때만 확인합니다.
  - 값이 세션 사용자와 다르면 409 `USER_MISMATCH`이고 아무것도 바꾸지 않습니다.
  - 헤더 값은 비교에만 씁니다. 작성자는 계속 세션 사용자입니다.
  - 형식은 십진수 문자열 `^[1-9][0-9]{0,18}$`이라, JSON 숫자 정밀도(2^53)와 관계없이 정확히 비교합니다.
  - 처리 순서: 인증·CSRF → 경로 형식 → 예상 사용자 → 본문 검증 → 작품·리뷰 상태
  - 호출 위치: [`MyReviewController`](../../backend/src/main/java/com/manhwanet/review/api/MyReviewController.java), [`MyRatingController`](../../backend/src/main/java/com/manhwanet/rating/api/MyRatingController.java)
- 프론트: [`lib/api/browser.ts`](../../frontend/src/lib/api/browser.ts) `apiRequest`
  - 첫 요청과 CSRF 재시도가 같은 예상 사용자 헤더를 보냅니다.
  - 재시도는 `INVALID_CSRF_TOKEN`일 때만 합니다. `USER_MISMATCH`는 재시도하지 않습니다.
  - [`WorkReviewSection`](../../frontend/src/components/reviews/WorkReviewSection.tsx)·[`WorkRatingSection`](../../frontend/src/components/ratings/WorkRatingSection.tsx)는 불일치를 받으면 이전 사용자의 작성 글·충돌 상태를 지우고 새 사용자 기준으로 다시 그립니다. 늦게 도착한 응답은 반영하지 않습니다.
- 같은 출처 프록시가 이 헤더를 백엔드로 넘기도록 허용 목록에 넣었습니다.
- 명세: [review-design-draft.md §5.1](../review-design-draft.md), [rating-design-draft.md](../rating-design-draft.md)

### 검증 (#22 기록)

- 백엔드 [`ReviewExpectedUserIntegrationTest`](../../backend/src/test/java/com/manhwanet/review/ReviewExpectedUserIntegrationTest.java)·[`RatingExpectedUserIntegrationTest`](../../backend/src/test/java/com/manhwanet/rating/RatingExpectedUserIntegrationTest.java)
  - 헤더 필수·형식(빈 값, `0`, `01`, `-1`, 공백, `1.0`, `1e3`, Long 초과)
  - B의 세션과 유효한 CSRF로 A의 화면 요청을 보내면 POST·PUT·DELETE·GET 모두 409이고, A·B 데이터가 모두 바뀌지 않음
  - B에 리뷰가 없어도 DELETE·PUT은 409 (204·404로 보이지 않음)
  - 큰 ID: 2^53+1 일치, 2^53 거절
  - 검사를 제거하는 뮤테이션을 넣으면 테스트 5건이 실패하는 것을 확인한 뒤 원복
- 프론트 단위 테스트 ([`browser.test.ts`](../../frontend/src/lib/api/browser.test.ts) 등): CSRF 재시도에 같은 헤더를 보냄, `USER_MISMATCH`는 재시도하지 않음, 형식이 틀린 viewerId는 요청을 만들지 않음, 프록시가 헤더를 전달함
- 실제 브라우저 계정 전환: `/auth/me` 통과 직후 B로 로그인, CSRF 403 뒤 토큰 재발급 직전에 B로 로그인 → 재시도가 409. 두 계정 행 모두 그대로였고, A의 글이 B 화면에 남지 않았습니다.
- Windows 격리 DB에서 같은 시나리오(POST·PUT·DELETE)를 다시 확인. 커밋 시점 백엔드 `clean build` 475건, 실패 0 (#22 마지막 기록)

### 남은 한계

- GET은 헤더가 있을 때만 확인합니다. 조회 응답이 잘못된 사용자 기준으로 보일 수 있는 경우는 화면이 다시 그리는 처리에 의존합니다.
- 서버는 "화면이 보낸 사용자 ID"를 믿고 비교만 합니다. 클라이언트가 헤더를 잘못 채우면 막을 수 없습니다(데이터는 여전히 세션 사용자에게만 반영되고, 불일치는 거절됩니다).
- 불일치가 나면 이전 사용자가 쓰던 글은 보존하지 않고 지웁니다. 다른 계정 화면에 남기지 않으려는 의도적인 선택입니다.
- 앞으로 생길 계정 귀속 변경(댓글 등)도 같은 검사를 붙여야 합니다. 지금은 리뷰·별점에만 적용되어 있습니다.

---

## 사례 2. staging 대표 ID·별칭 동시 등록으로 다른 작품을 덮어쓰던 문제

### 재현 조건 (#61)

PostgreSQL 17 테스트 DB, 수집 설정 ENABLED, 실행 잠금을 가진 상태에서 `StagingIngestService.ingest` 두 건을 동시에 호출했습니다. 별칭 INSERT 직전에 잠시 멈추게 해서, 두 트랜잭션이 조회를 마친 뒤 커밋되도록 했습니다.

- 요청 1: 대표 ID `X`, 별칭 `[Y]`, 제목 "작품 X"
- 요청 2: 대표 ID `Y`, 별칭 `[X]`, 제목 "작품 Y"

결과: 둘 다 성공해 staging 행이 두 개 생겼고, 별칭은 X→Y, Y→X로 교차 연결됐습니다. 이어서 `Y`를 다시 반영하자 별칭 `Y`가 X 행을 가리켜 **X의 제목·URL이 작품 Y 내용으로 바뀌었습니다.** 순서대로 하나씩 넣으면 기존 규칙대로 409 `STAGING_ALIAS_CONFLICT`가 났으므로, 동시성에서만 생기는 문제였습니다. 공개 작품(`works`)까지는 번지지 않았습니다.

### 원인

- 대표 ID는 `staging_listings(platform_code, platform_work_id)`, 별칭은 `staging_id_aliases(platform_code, alias_id)`라는 **서로 다른 표의 유니크 제약**으로 지켰습니다.
- READ COMMITTED에서는 한 트랜잭션이 상대의 커밋 전 삽입을 보지 못합니다. 그래서 "대표 ID 조회 → 별칭 INSERT" 사이에서 두 요청이 서로를 놓쳤고, 두 표의 제약은 서로를 막지 못했습니다.

### 선택한 해결책

- 이미 적용된 V10은 고치지 않고, [`V12__create_staging_identity_keys.sql`](../../backend/src/main/resources/db/migration/V12__create_staging_identity_keys.sql)을 추가했습니다.
  - `staging_identity_keys(platform_code, external_id)` 기본 키 하나로 **대표 ID와 별칭이 같은 식별 공간**을 씁니다. 같은 외부 ID는 대표든 별칭이든 한 행만 가리킵니다.
  - 역할은 `CANONICAL`·`ALIAS`이고, 한 행의 대표 키는 하나입니다(부분 유니크 인덱스).
  - 기존 행을 옮길 때, 대표 ID와 겹치는 별칭은 넣지 않았습니다(교차 연결을 확정하지 않음).
- [`StagingRepository.lockIdentity`](../../backend/src/main/java/com/manhwanet/collection/domain/StagingRepository.java): 반영 전에 이번 요청이 건드리는 모든 ID를 **정렬해서** `pg_advisory_xact_lock`으로 잠급니다. 순서가 같으므로 교차 요청도 교착 없이 한 줄로 섭니다.
- [`StagingIngestService`](../../backend/src/main/java/com/manhwanet/collection/application/StagingIngestService.java): 다른 행에 연결된 키가 있으면 409 `STAGING_ALIAS_CONFLICT`를 내고 그 반영 전체를 롤백합니다.
- **자동 병합은 넣지 않았습니다.** 두 행이 같은 작품인지는 검수(#51)에서 사람이 판단할 일이라고 봤습니다.
- 명세: [collection-staging-spec.md §3.3](../collection-staging-spec.md)

### 검증 (#61 기록, Windows·JDK 21·Gradle 8.14.3·PostgreSQL 17 테스트 DB)

[`StagingIngestTest`](../../backend/src/test/java/com/manhwanet/collection/StagingIngestTest.java)

- `conflictingAliasRegistrationRollsBackAndRecollectionKeepsTheOriginal`
  - 기존 행의 대표 ID를 다른 작품의 별칭으로 넣으면 거절되고, 행 수가 늘지 않습니다.
  - 원래 ID를 다시 수집해도 제목과 내용 해시가 유지됩니다.
- `swappedOrderConcurrentAliasRegistrationLeavesOneRow`
  - `CyclicBarrier`로 순서를 바꾼 동시 요청을 6회 반복했습니다.
  - 매번 성공 1·충돌 1·staging 행 1이었습니다.
  - 이긴 쪽을 다시 수집하고 진 쪽을 반대 방향으로 다시 넣어도 제목이 그대로였습니다.
- `concurrentIngestOfANewKeyCreatesOneRow`: 같은 새 키를 동시에 넣어도 한 행만 생깁니다.
- 커밋 시점 `clean build` 721건, 실패 0 (#61 기록)

### 남은 한계

- 충돌하면 거절만 합니다. 별칭으로 찾은 행의 대표 ID를 바꾸거나 두 행을 합치는 규칙은 정하지 않았습니다(collection-staging-spec §5).
- `hashtext` 해시가 겹치면 관계없는 ID끼리 잠시 줄을 설 수 있습니다. 정합성은 기본 키가 지키므로 성능 영향뿐입니다.
- 실제 플랫폼 데이터로는 확인하지 않았습니다. 가상 ID와 테스트 DB에서만 검증했습니다.

---

## 사례 3. 수집기 재시작으로 요청 간격·Retry-After가 사라지던 문제

### 재현 조건 (#65)

- 수집기는 플랫폼당 최소 5초 간격을 지키고, 429를 받으면 `Retry-After`가 지날 때까지 그 플랫폼에 요청하지 않아야 합니다.
- 그런데 **요청 직후 프로세스가 끝나고** 새 프로세스가 같은 실행을 재개(`resume`)하면, 다음 요청이 바로 나갔습니다.
- 429 횟수(`rate_limit_hits`, V13)와 하루 요청 수(`collector_daily_requests`)는 DB에 있어 이어졌습니다. 다음 요청 시각만 이어지지 않았습니다.

### 원인

다음 허용 시각을 프로세스 메모리의 [`PlatformThrottle`](../../backend/src/main/java/com/manhwanet/collection/collector/core/PlatformThrottle.java)에만 두었습니다. 같은 프로세스 안에서는 맞게 동작했지만, 프로세스가 바뀌면 그 상태가 사라졌습니다.

### 선택한 해결책

- [`V14__add_crawl_run_next_request_at.sql`](../../backend/src/main/resources/db/migration/V14__add_crawl_run_next_request_at.sql): `crawl_runs.next_request_at`을 추가했습니다. V13은 고치지 않았습니다.
- [`CrawlRunRepository.postponeNextRequest`](../../backend/src/main/java/com/manhwanet/collection/domain/CrawlRunRepository.java)
  - 요청을 보낸 뒤 다음 허용 시각을 짧은 트랜잭션으로 커밋합니다.
  - `GREATEST(COALESCE(next_request_at, now()), now() + 간격)`을 써서, 나중에 더 짧은 간격이 저장돼도 **Retry-After로 미룬 시각을 앞당기지 않습니다.**
  - 시각은 DB의 `now()` 기준이라 프로세스마다 시계가 달라도 같은 기준을 씁니다.
- [`CollectorEngine`](../../backend/src/main/java/com/manhwanet/collection/collector/core/CollectorEngine.java)
  - 다음 요청 전에 `millisUntilNextRequest`로 남은 시간만 읽고, 기다리는 일은 **트랜잭션 밖에서** 합니다. 네트워크·대기 중 DB 연결을 잡지 않으려는 것입니다.
  - 기다리는 동안 1초 단위로 깨어 중단 요청·잠금 연장·설정 변경을 확인합니다.
- 같은 프로세스 안의 "플랫폼당 동시 1건"은 계속 메모리 잠금이 맡습니다. 프로세스 사이는 "플랫폼당 활성 실행 하나"와 실행 잠금(#45)으로 나뉩니다.
- #68에서는 허용 시각의 기준을 실행 행에서 플랫폼 행으로 옮겼습니다. [`V17__add_platform_next_request_at.sql`](../../backend/src/main/resources/db/migration/V17__add_platform_next_request_at.sql)이 `platform_collection_policies.next_request_at`을 추가합니다. V14 파일은 고치지 않았습니다. 이미 실행 행에 남은 시각은 플랫폼별 가장 늦은 값으로 채웁니다.
  - 같은 실행 재개와, 완료·중단·차단 뒤의 새 실행이 이 시각을 읽습니다. 새 실행을 시작할 때 이전 실행에서 복사하지 않습니다. 복사하면 마지막 갱신이 커밋되기 전에 빈 값을 읽거나, 복사 이후의 갱신이 새 실행에만 남을 수 있습니다.
  - 예약은 잠금을 확인한 뒤 플랫폼 행을 한 문장으로 `GREATEST` 갱신합니다. 이미 더 늦은 시각은 앞당겨지지 않습니다. 실행 행에는 같은 시각을 남기지만, 허용 판단은 플랫폼 행만 봅니다.
  - 실행을 완료·중단·차단으로 닫는 갱신은 이 시각을 지우지 않습니다. 기다리는 동안에는 트랜잭션과 연결을 잡지 않습니다.
- 명세: [collector-core-spec.md §5](../collector-core-spec.md)

### 검증 (#65 기록, Windows·JDK 21·Gradle 8.14.3·PostgreSQL 17 테스트 DB)

[`CollectorEngineIntegrationTest`](../../backend/src/test/java/com/manhwanet/collection/collector/CollectorEngineIntegrationTest.java)는 로컬 모의 서버를 쓰고, 메모리 간격을 공유하지 않는 엔진 두 개로 재시작을 흉내 냈습니다.

- `restartedProcessKeepsTheMinimumGap`: 요청 직후 실행을 멈추고 다른 엔진이 재개했습니다. 다음 요청은 첫 요청보다 5초 이상 뒤에 시작했습니다.
- `restartedProcessKeepsRemainingRetryAfter`: `Retry-After` 30초를 받은 뒤, 작업 재시도 시각을 과거로 돌려도 플랫폼 행에 25~30초가 남았습니다. 재개한 엔진은 그만큼 기다린 뒤 요청했습니다.
- 기다리는 동안 트랜잭션이 잡혀 있지 않은 것도 확인했습니다.
- 커밋 시점 `clean build` 829건, 실패 0 (#65 기록)

### 검증 (#68, 2026-10-16, Windows·JDK 21·Gradle 8.14.3·PostgreSQL 17 테스트 DB)

수정 커밋: [`3f5d94f`](https://github.com/jun1378527/manhwanet/commit/3f5d94f308bdf1055c9e71c07f506acbd76f4449) `fix(collection): 새 실행에서도 플랫폼 요청 간격 유지 (#68)`

로컬 모의 서버에서 메모리 간격을 공유하지 않는 엔진으로 확인했습니다. 개발 DB와 실제 플랫폼은 쓰지 않았습니다.

- `newProcessRunKeepsTheMinimumGapAfterCompletion`: 완료된 실행 뒤 새 실행의 첫 요청은 직전 요청보다 5초 이상 뒤였습니다. 플랫폼 행에는 5~6초가 남아 있었습니다.
- `restartedProcessKeepsTheMinimumGap`, `restartedProcessKeepsRemainingRetryAfter`: 같은 실행 재개도 이전과 같이 최소 간격과 `Retry-After` 30초를 유지했습니다.
- `newProcessRunKeepsRetryAfterAfterStop`: `Retry-After` 30초 뒤 실행을 중단해도 플랫폼 행의 남은 시각은 그대로였고, 새 실행이 그 시각을 지켰습니다.
- `blockedRunLeavesThePlatformWait`: 429 두 번으로 차단된 뒤 중단으로 닫아도 플랫폼 대기가 지워지지 않았고, 새 실행이 그 시각 이후에 요청했습니다.
- `concurrentNewRunsKeepTheGapAndOnlyOneStarts`: 남은 간격이 있는 동안 새 실행 두 개를 동시에 시작했습니다. 하나는 5초 이상 기다린 뒤 요청했고, 다른 하나는 활성 실행이 있어 요청을 보내지 못했습니다.
- Windows, JDK 21, Gradle 8.14.3, PostgreSQL 17 테스트 DB에서 `clean build` 870건, 실패 0, 오류 0, 건너뜀 0 (JUnit XML 82개). 개발 DB는 쓰지 않았습니다.

### 확인하지 않은 것

- 운영체제 프로세스 두 개를 따로 띄우지는 않았습니다. 한 테스트 프로세스 안에서 메모리 간격을 공유하지 않는 엔진으로 확인했습니다.
- 메모리 간격이 남아 있는 같은 엔진으로 새 실행을 시작하는 경우는 이번 회귀에 넣지 않았습니다.
- 실제 플랫폼 요청과 클라우드 CI에서는 확인하지 않았습니다.
