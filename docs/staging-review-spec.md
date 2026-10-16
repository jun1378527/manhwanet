# staging 검수·공개 등록 구현 기록 (#51)

> **상태: 구현됨(2026-10-16, 미커밋).** 기준 커밋 `5132d5d`. 설계는 [work-collection-design.md](work-collection-design.md) §9(초안), 관리자 API 계약은 [work-platform-admin-spec.md](work-platform-admin-spec.md) §5, staging 은 [collection-staging-spec.md](collection-staging-spec.md)입니다. 이 문서는 실제 구현 계약과 설계와 다른 점을 적습니다. 설계 문서는 고치지 않았습니다.
> 범위: 수집 대기 항목 한 건의 검수(후보 확인 → 기존 작품 연결·새 작품 등록·보류·제외). 하지 않은 것: #41 전체 중복 검수·중복 표시, 일괄 검수, 새 관리자 화면, 수집기 근거(COLLECTOR) 정책, 줄거리·표지 반영, 실제 플랫폼 수집.

## 1. 선행 작업과 미결정 정책 (먼저 정리한 것)

| 항목 | 상황 | 이번 처리 | 필요한 최소 선행 |
|---|---|---|---|
| 새 작품을 숨김(HIDDEN)으로 등록(설계 §9.1) | **해결(2026-10-16, #41 최소 범위 V16)** | 새 작품은 같은 트랜잭션에서 처음부터 `HIDDEN` 으로 저장합니다. 숨김이라 등급과 관계없이(`ADULT`·`UNKNOWN` 포함) 등록하고, 공개는 관리자가 `POST /admin/works/{id}/visibility` 로 명시적으로 바꿉니다. `ADULT`·`UNKNOWN`(연결 등급이 GENERAL 이 아닌 작품)은 D-15 전까지 공개 전환 409 `WORK_PUBLICATION_BLOCKED` | 없음(완료). 계약은 [work-platform-admin-spec.md](work-platform-admin-spec.md) §3.1 "구현 기록 (#41 최소)" |
| 수집기 근거 `COLLECTOR`(D-19) | 미결정 | **만들지 않았습니다.** 새 작품의 `TITLE`·`CREDIT` 근거와 연결의 `LISTING` 근거는 검수자가 기존 근거 종류(`PLATFORM_PAGE_MANUAL`·`STAFF` 등)로 직접 보냅니다. 어느 수집 항목·revision 에서 왔는지는 근거가 아니라 검수 결정 기록(`staging_review_decisions`)에 남깁니다 | D-19 결정. 정해지면 결정 기록과 근거를 잇는 방식만 바꾸면 됩니다 |
| 줄거리·표지 | 줄거리는 비공개 수집(D-12), 표지는 단계 분리(D-13, #55) | 작품에 옮기지 않습니다(`description` 비움, 표지 URL 미사용) | 각 정책 결정 |
| 중복 후보 | #41(전체 작품 중복 검수)은 범위 밖 | 이 항목에 필요한 최소 후보만 만듭니다(§4). 작품끼리의 중복·작가만 겹치는 동명이인 탐지·색인은 하지 않습니다 | 없음(대량 운영 시 #41 색인) |

## 2. 마이그레이션

| 파일 | 내용 |
|---|---|
| `V15__create_staging_review_decisions.sql` | `staging_listings.revision`(수집 revision), `staging_review_decisions`(검수 결정, 추가만·트리거), 연결·등록 결정은 항목당 하나(`uk_srd_final`), `admin_change_log` 대상 종류에 `STAGING_LISTING` 추가(V9 파일은 고치지 않고 제약을 다시 만듦) |

- V1~V14 는 고치지 않았습니다. 새 FK 에 CASCADE 가 없습니다.
- 수집 revision: `StagingIngestService` 가 내용·수집 상태가 바뀌어 변경 이력을 남길 때마다 1 올립니다. 같은 내용 재수집은 그대로입니다.

## 3. API (ADMIN + CSRF)

| 메서드 | 경로 | 내용 |
|---|---|---|
| GET | `/api/v1/admin/staging-listings/{id}/review` | 항목(플랫폼·ID·URL·제목·등급 표기·상세 상태·검수 상태·**revision**·연결 ID·크레딧·별칭), 저장된 후보, 지난 결정 50건 |
| POST | `/api/v1/admin/staging-listings/{id}/candidates` | 후보 다시 만들기. 같은 입력이면 행이 늘지 않고 ID 가 같음. 열린 후보가 있으면 `NEW` → `CANDIDATES` |
| POST | `/api/v1/admin/staging-listings/{id}/review` | 검수 결정(아래) |

요청:

```json
{
  "decision": "LINK_EXISTING | CREATE_WORK | HOLD | REJECT",
  "stagingRevision": 3,
  "reason": "검수 사유(필수, 1~500자)",
  "candidates": [{"id": 12, "decision": "SAME_WORK | DIFFERENT"}],
  "workId": 5, "workVersion": 2,
  "work": {
    "workFormat": "WEBTOON", "originCountry": "KR",
    "titles": [{"title": "…", "languageCode": "ko", "primary": true}],
    "credits": [{"creatorId": 7, "role": "STORY"},
                {"newCreatorName": "…", "newCreatorOriginalName": null, "sameNameConfirmed": false, "role": "ART"}],
    "evidence": [{"field": "TITLE", "sourceType": "PLATFORM_PAGE_MANUAL", "sourceUrl": "…", "verifiedAt": "…"}]
  },
  "listing": {"listingKind": "MAIN", "idScope": null, "verifiedAt": null, "note": null, "evidence": [{"sourceType": "…"}]}
}
```

- `LINK_EXISTING`: `workId`·`workVersion`·`listing.evidence` 필수. `CREATE_WORK`: `work`(형식·작가·근거)·`listing.evidence` 필수, `titles` 를 생략하면 수집 제목이 대표 제목. `HOLD`·`REJECT`: `decision`·`stagingRevision`·`reason` 만.
- 연결 값(플랫폼·외부 작품 ID·URL·언어·원래 등급 표기)은 수집 결과를 그대로 씁니다. 등급은 원래 표기로 다시 분류하고, 표기가 없으면 `UNKNOWN` 입니다(수집 신호만으로 `GENERAL`·`ADULT` 를 붙이지 않음). `verifiedAt` 을 생략하면 승인 시각입니다.
- 응답은 GET 과 같은 형태(결정 뒤 상태)입니다.

오류:

| 코드 | 상태 | 경우 |
|---|---|---|
| `INVALID_INPUT` | 400 | 입력 형식, 사유 없음, 후보 판단 모순(새 작품인데 SAME_WORK, 연결 대상을 DIFFERENT 등), 같은 이름 작가를 확인 없이 새로 만듦, 근거 규칙(기존 서비스) |
| `STAGING_LISTING_NOT_FOUND` | 404 | 항목 없음 |
| `STAGING_ALREADY_REVIEWED` | 409 | 이미 `LINKED`·`NEW_WORK` 로 끝남(반복·동시 승인) |
| `STAGING_REVISION_CONFLICT` | 409 | 검수 중 재수집. `error.currentVersion` 에 현재 revision |
| `STAGING_REVIEW_CANDIDATES_UNRESOLVED` | 409 | 승인 시점에 다시 만든 열린 후보와 판단한 후보가 다름 |
| `STAGING_NOT_REVIEWABLE` | 409 | 상세 미확인(`NOT_FOUND`·`PARSE_FAILED`·`FAILED`·`PENDING`) 항목 연결·등록 |
| `PLATFORM_LISTING_ALREADY_LINKED` | 409 | 같은 플랫폼 작품(대표 ID·별칭)이 이미 다른 작품에 연결됨. `existingWorkId`·`existingListingId` |
| `WORK_VERSION_CONFLICT` | 409 | 연결 대상 작품 version 불일치 |

## 4. 후보 (설계 §9.2 중 최소 범위)

| 근거 | 점수 | 뜻 |
|---|---|---|
| `LISTING_KEY` | 100 | 같은 (플랫폼, 외부 작품 ID)가 이미 작품에 연결됨(식별 키 충돌) |
| `ALIAS_KEY` | 90 | 이 항목의 별칭 ID 가 이미 작품에 연결됨 |
| `TITLE_MATCH` | 40(다른 수집 항목 30) | 제목 키 일치. **단독으로 같은 작품이라는 뜻이 아님** |
| `CREATOR_OVERLAP` | +40(+30) | 제목 키가 같은 후보 중 작가 이름 키가 겹침 |

- 제목 키: NFKC → 소문자 → 판본(`[개정판]`·`[단행본]`·`_nonadult` 등)·시즌(`시즌 2`·`2부`·`외전`) 표지 제거 → 문자·숫자만.
- 경고: `EDITION_SUFFIX`·`SEASON`(어느 쪽 제목에든 표지), `DIFFERENT_CREATORS`(제목만 같고 작가가 안 겹침), `AGE_CLASS_DIFF`(일반·성인이 엇갈림), `STALE`(이번 생성에 없는 열린 후보를 자동으로 `REJECTED` 로 닫음. 사람 판단이 아님).
- **자동 STALE 과 사람 판단(#66).** 설계는 명시적 판단의 재사용 범위를 정하지 않았다. 이번 구현은 이렇게 둔다.
  - 자동 `STALE` 로 닫힌 후보(경고에 `STALE`, 판단자는 후보를 다시 만든 관리자)가 작품 후보·쌍 후보로 다시 생성되면 같은 행을 `OPEN` 으로 되돌린다. `decided_by`·`decided_at` 은 비우고 `STALE` 경고는 새 경고로 갈아, 상태와 판단 기록이 어긋나지 않게 한다. 승인하려면 그 후보를 다시 판단해야 한다.
  - 사람이 `SAME_WORK`(`ACCEPTED`) 또는 `DIFFERENT`(`REJECTED`, 경고에 `STALE` 없음)로 남긴 판단은 후보가 사라졌다가 다시 나와도 유지한다. 모든 `REJECTED` 를 `OPEN` 으로 바꾸지 않는다.
- 다른 수집 항목 후보는 쌍마다 한 행이라 두 항목이 같은 행을 봅니다. `SAME_WORK` 로 판단하면 `ACCEPTED` 로 남고, 그 항목의 검수는 따로 합니다(새 작품이 생기면 그 항목에서는 작품 후보로 보임).
- 점수는 정렬용입니다. **어떤 점수에서도 자동 연결·병합하지 않습니다**(D-17).

## 5. 결정 처리 순서 (한 트랜잭션)

1. 입력 검증(400)
2. 항목 행 잠금(`FOR UPDATE`) → 끝난 항목이면 409 `STAGING_ALREADY_REVIEWED`
3. 수집 revision 비교 → 다르면 409
4. `HOLD`·`REJECT`: 검수 상태(`ON_HOLD`·`REJECTED`), 결정 기록, 변경 기록(`STAGING_LISTING`·`STATUS_CHANGE`) 후 끝
5. 상세 상태 확인(409 `STAGING_NOT_REVIEWABLE`). 새 작품은 HIDDEN 으로 만들므로 등급 제한은 없음(2026-10-16)
6. 후보 다시 만들기 → 열린 후보 전부에 대한 판단이 없거나 남으면 409, 판단 모순이면 400
7. 식별 키(대표 ID·별칭) 충돌 확인 → 409 `PLATFORM_LISTING_ALREADY_LINKED`
8. 등록·연결은 **기존 서비스**로:
   - `LINK_EXISTING`: `WorkListingService.create`(작품 version 확인·증가, 연결 근거 필수, 변경 기록 `LISTING`). 같은 플랫폼 작품이 이미 **같은 작품**에 연결돼 있으면 새 연결 없이 검수만 마칩니다(`ALREADY_LINKED`, 작품 version 은 확인만)
   - `CREATE_WORK`: 새 작가는 `CreatorService.register`, 작품은 `WorkService.register(…, HIDDEN)`(처음부터 숨김으로 INSERT, 제목·크레딧 근거 필수, `listings[]` 로 연결 함께, 변경 기록 `WORK`·`LISTING`·`CREATOR`). 작가는 검수자가 기존 ID 또는 새 이름으로 지정하며, **이름만으로 기존 작가에 붙이지 않습니다**. 같은 이름의 작가가 있는데 새로 만들려면 `sameNameConfirmed=true`(동명이인 확인)
9. 검수 상태(`LINKED`·`NEW_WORK`)와 `linked_listing_id`, 후보 판단(`ACCEPTED`·`REJECTED`, 판단자·시각), 결정 기록(revision·작품·연결·후보 판단), 변경 기록(`STAGING_LISTING`, 이전·이후 상태와 사유)

- 어디서 실패해도(검증·충돌·기록 실패·DB 오류) 작품·제목·크레딧·작가·연결·근거·변경 기록·후보 판단·검수 상태가 모두 롤백됩니다.
- 반복 승인: 끝난 항목은 409. 동시 승인: 항목 행 잠금으로 직렬화되어 뒤 요청이 409 이고, 결정 유니크 인덱스와 연결 키 유일 제약이 한 번 더 막습니다.
- 보류·제외한 항목은 다시 검수할 수 있습니다(`ON_HOLD`·`REJECTED` → 결정 가능). `LINKED`·`NEW_WORK`·`MERGED_ALIAS` 는 끝난 상태입니다.

## 6. 설계·이슈와 다른 점

| 항목 | 설계·이슈 | 구현 | 이유 |
|---|---|---|---|
| 새 작품 공개 상태 | §9.1 새 Work 는 HIDDEN | 설계대로 HIDDEN(2026-10-16 #41 최소 범위로 해결). 공개 전환은 GENERAL 확인 작품만 | — |
| 근거 | 수집기 근거(D-19) 검토 | 검수자가 기존 근거 종류로 보냄 | D-19 미결정 |
| 후보 생성 | ISBN·기존 외부 ID·교차 링크 포함 | 연결 키·별칭·제목·작가 겹침만 | staging 에 ISBN·외부 ID·교차 링크 값이 아직 없음(어댑터 #47~#50) |
| 경고 | `REMAKE`·`HOMONYM_CREATOR`·`TRANSLATION` | 넣지 않음(동명이인은 새 작가 생성 시 확인으로 대신) | 근거 데이터가 없거나 #41 범위 |
| 일괄 검수 | 이슈 범위 | 하지 않음 | 이번 범위(한 건 흐름)·새 화면 제외 지시. 같은 서비스를 항목마다 부르는 방식으로 후속 추가 가능 |
| 검수 대기 상태 | `IN_REVIEW` | 쓰지 않음(NEW → CANDIDATES → 결정) | 잠금 없이 revision 으로 충돌을 막으므로 "검수 중" 점유 상태가 필요 없음 |
| 닫힌 후보의 재사용 | 설계에 범위 없음 | 자동 `STALE` 만 재등장 시 `OPEN`. 사람 판단은 유지(#66) | 자동 종료를 사람 거절과 같이 두면 재등장 후보를 판단 없이 승인하게 됨 |

## 7. 결정하지 않은 정책 (기본 동작만 정함)

- D-19 수집기 근거, D-15 `ADULT`·`UNKNOWN` 공개 표시(지금은 공개 전환 거절)
- 연결·등록 뒤 재수집으로 내용이 바뀐 항목을 다시 검수 대기로 돌릴지(지금은 revision 만 오르고 상태는 그대로)
- 다른 수집 항목 후보를 `SAME_WORK` 로 판단했을 때 그 항목을 자동으로 같은 작품에 연결할지(지금은 판단만 기록, 연결은 그 항목을 검수할 때)
- 후보 점수 기준값, 판본·시즌을 같은 작품의 `EDITION`·`SEASON` 연결로 둘지 별도 작품으로 둘지(검수자가 `listingKind` 와 결정으로 정함)

## 8. 테스트와 시연

| 테스트 | 내용 |
|---|---|
| `StagingReviewIntegrationTest`(13) | 권한·CSRF·사유, 새 작품 등록과 반복 승인, ADULT·UNKNOWN 새 작품 거절·기존 작품 연결, 상세 미확인 연결 거절·보류·제외, 후보 판단 필수·작품 version 충돌·후보 재생성 시 행 유지, 제목만 같은 작품 미병합·동명이인 확인, 연결 키·별칭 충돌, 두 플랫폼 같은 작품 → 한 작품, 재수집 충돌·후보 변경, 동시 승인(3개 동시 × 5회), 기록 실패 주입 시 전체 롤백. 작품 후보·쌍 후보 각각: 생성 후 사라짐·제목 복귀로 재등장하면 `OPEN` 과 빈 판단자, 판단 없이 승인 409, 다시 판단하면 승인, 사람이 `DIFFERENT` 로 거절한 후보는 재등장해도 `REJECTED` 유지 (#66) |
| `StagingReviewDemoTest` | 포트폴리오 시연(아래) |

- 모든 데이터는 가상입니다. 제목·작가 이름에 `[DEMO]`·`가상`을 붙였고, 플랫폼 주소는 실제로 해석되지 않는 `*.mock.test` 호스트를 테스트 DB 의 허용 호스트에만 넣어 씁니다. 플랫폼 코드(TOOMICS·MRBLUE)는 외래 키 때문에 빌렸을 뿐 그 플랫폼의 실제 작품이 아닙니다.
- 시연: `cd backend; .\gradlew.bat test --tests "*StagingReviewDemoTest*"` → `backend/build/reports/demo/staging-review-demo.md`. 모의 수집기(#46 엔진, 127.0.0.1 모의 HTTP 서버) → staging → 후보 → 기존 작품 연결 → 새 작품 등록 → 반복 승인 409 → 성인 등급 거절·보류 → 검수 중 재수집 409 → 최신 내용 재승인.
- 2026-10-16(#41 최소 범위 연동): 시연이 HIDDEN 등록 → 공개 경로 404·별점 등록 404 → 관리자 명시적 PUBLIC 전환 → 독자 별점·리뷰 → 다시 숨김(본인 기록 유지·행 보존) → 다시 공개, 성인 등급 숨김 등록·공개 전환 409 까지 확인합니다.
