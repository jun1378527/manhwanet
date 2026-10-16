# 작품·플랫폼 관리 구현 명세 (초안)

> **상태: 초안. 사용자 검토 대기 (#37).** #38로 §5.1·§5.2와 V6을, #39로 §5.3·§5.5와 V7을, #40으로 §3.3·§5.4·§5.6과 V8을 구현했습니다. 그 밖의 절은 후속 설계입니다. 이 문서는 구현 이슈(§12)의 기준입니다.
> 기준 커밋: `1435e91`. 조사 근거는 [work-data-design-draft.md](work-data-design-draft.md)(§3.4 플랫폼 조사표, §3.4.4 판단 4구분, §3.4.5 ID 단위)를 따릅니다.
> **범위 변경 (2026-10-11):** 데이터 확보 범위가 10개 플랫폼 공개 작품 전체로 바뀌었습니다. 이 명세(V6~V8)는 전체 수집의 기반으로 그대로 쓰고, staging·수집 실행 기록(V9~V11)은 [work-collection-design.md](work-collection-design.md)에 있습니다. 반영할 변경: `work_platform_listings`의 `age_rating_note`를 `age_rating_raw`로 바꾸고 `age_class`(`GENERAL`·`ADULT`·`UNKNOWN`)를 추가합니다(수집 설계 §8.3). §10 파일럿은 완료 조건이 아니라 수집기 검증 단계입니다(수집 설계 §14).
> **#38 구현 (2026-10-12):** §5.1·§5.2의 작품·작가 관리자 조회·수정과 버전 충돌 방지를 구현했습니다. V6은 `works.version`·`creators.version`만 추가했고, `visibility`·`duplicate_of_work_id`와 숨김·중복 표시 API는 #41로 옮겼습니다(§3.1). `reason`·`evidence[]`와 변경 기록은 #40에서 추가합니다. 구현 세부는 §5.1 "구현 기록"을 보세요.
> **등급 규칙 변경 (2026-10-12, 사용자 지시):** 이전의 "전체 이용가 전용·성인 거절" 파일럿 규칙은 폐기했습니다. 18세 미만 등급은 `GENERAL`, 성인 등급은 `ADULT`, 표기가 없거나 확인하지 못하면 `UNKNOWN`이며, 원래 표기를 보존합니다. `UNKNOWN`은 일반으로 처리하지 않습니다(§5.3, §10, S-12).
> **#39 구현 (2026-10-13):** V7(플랫폼 코드 표·허용 호스트·상태 이력·작품 플랫폼 연결)과 §5.3·§5.5의 관리자 API를 구현했습니다. 계약은 §3.2 구현 기록과 `V7__create_platforms_and_work_listings.sql`입니다. 외부 ID·근거·변경 기록(#40), 숨김·중복 검수(#41), 공개 표시(#42)는 포함하지 않았습니다.
> 적용된 마이그레이션 V1~V6은 고치지 않습니다. **#39의 마이그레이션은 V7(플랫폼·허용 호스트·상태 이력·작품 연결)입니다.** **#40의 마이그레이션은 V8(외부 ID·공식 링크·필드 근거·관리 변경 기록)입니다.**
> **#40 구현 (2026-10-14):** §3.3·§5.4·§5.6과 수정 사유·변경 기록을 구현했습니다. 기존 등록·수정·연결·플랫폼 API 에 `reason`·`evidence[]`를 넣었고, 계약과 설계와 다른 점은 §3.3 "구현 기록 (#40)"에 있습니다. 숨김·중복 검수(#41), 공개 표시(#42), 수집기는 포함하지 않았습니다.

> **#41 최소 구현 (2026-10-16, 미커밋):** 작품 공개 상태(`visibility` PUBLIC·HIDDEN, V16)와 관리자 상태 변경 API, 숨김 작품의 공개 경로 제외만 구현했습니다(#51 의 새 작품 HIDDEN 등록 선행). 중복 후보 조회(`duplicate-candidates`)·중복 표시(`duplicate_of_work_id`)·연결 이전 흐름·판단 규칙 문서화는 #41 남은 범위입니다. 계약은 §3.1 "구현 기록 (#41 최소)".

- 관련 문서: [work-creator-spec.md](work-creator-spec.md)(작품·작가 등록·조회), [review-design-draft.md](review-design-draft.md)(버전 충돌 방식), [rating-design-draft.md](rating-design-draft.md), [auth-design-draft.md](auth-design-draft.md)(ADMIN·CSRF), [architecture.md](architecture.md)
- 표기: **[결정]** 이 명세의 정한 값, **[선택]** 사용자가 고를 수 있는 구현 선택(§11), **[후속]** 이번 범위 밖

---

## 1. 목표와 범위

| 구분 | 내용 |
|---|---|
| 목표 | 실제 작품을 소량 등록하기 전에, 잘못 넣은 정보를 고칠 수 있고, 한 작품을 여러 플랫폼에 안전하게 연결하며, 각 값의 근거를 남기는 최소 기능 |
| 포함 | ① ADMIN 작품·작가 수정과 동시 수정 충돌 방지 ② 외부 ID·출처·확인 시각 ③ 한 Work에 여러 플랫폼 연결 ④ 공식 링크와 필드별 근거 ⑤ 플랫폼 상태 변경·이전 이력 ⑥ 제목만으로 자동 병합하지 않는 검수 흐름 ⑦ 별점·리뷰·내부 ID 보존 ⑧ 등록 API·화면 최소 확장 ⑨ 전체 이용가 소량 검증 계획 |
| 제외 | 자동 수집·크롤링, 플랫폼 API 연동, 표지·줄거리 수집, 장르·연재 상태 동기화, 작품 병합(별점·리뷰 이전), 검색, 관리자 전용 웹 화면 전체(§9.3은 선택) |
| 원칙 | 내부 작품·작가 ID는 바뀌지 않습니다. 작품·작가를 지우는 API는 만들지 않습니다(§7). 같은 제목이라는 이유로 합치지 않습니다(§8). |

---

## 2. 현재 상태 [사실, 코드 확인]

- 테이블: `works`, `work_titles`, `creators`, `work_creators`(V2), `work_ratings`(V4, 작품 FK `ON DELETE CASCADE`), `work_reviews`(V5, 작품 FK `ON DELETE CASCADE`, 자체 `version`).
- 관리자 API: `POST /api/v1/admin/works`, `POST /api/v1/admin/creators`만 있음. 수정·삭제 없음. 멱등 키 없음.
- 상세 응답 `WorkDetailResponse`: id, workFormat, originCountry, description, primaryTitle, titles, creators, rating, createdAt, updatedAt.
- 리뷰는 `reviewId + version` 비교로 동시 수정 충돌을 막습니다(409 `REVIEW_VERSION_CONFLICT`). 이 명세도 같은 방식을 씁니다.
- 별점·리뷰가 작품 FK에 CASCADE로 걸려 있어 **작품을 DB에서 지우면 별점·리뷰가 함께 지워집니다.** 그래서 삭제 API를 두지 않습니다.

---

## 3. 데이터 모델 (V6~V8)

### 3.1 V6: 작품·작가 버전과 공개 상태

```sql
ALTER TABLE works
    ADD COLUMN version    BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    ADD COLUMN duplicate_of_work_id BIGINT,
    ADD CONSTRAINT ck_works_version CHECK (version >= 0),
    ADD CONSTRAINT ck_works_visibility CHECK (visibility IN ('PUBLIC', 'HIDDEN')),
    ADD CONSTRAINT fk_works_duplicate_of FOREIGN KEY (duplicate_of_work_id) REFERENCES works (id),
    ADD CONSTRAINT ck_works_duplicate_not_self CHECK (duplicate_of_work_id IS NULL OR duplicate_of_work_id <> id),
    ADD CONSTRAINT ck_works_duplicate_hidden CHECK (duplicate_of_work_id IS NULL OR visibility = 'HIDDEN');

ALTER TABLE creators
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_creators_version CHECK (version >= 0);
```

- **구현(#38):** V6은 위 SQL 중 `works.version`·`creators.version`과 두 CHECK만 적용했습니다(`V6__add_work_creator_versions.sql`). `visibility`·`duplicate_of_work_id`와 관련 CHECK·FK는 #41에서 별도 마이그레이션으로 추가합니다. 숨김은 공개 목록·상세·별점·리뷰 조회 전체에 영향을 주므로, 숨김 API와 함께 한 번에 넣기 위해서입니다.
- 기존 행은 `version 0`, `PUBLIC`이 됩니다. 기존 데이터·ID·별점·리뷰는 그대로입니다.
- `HIDDEN` 작품은 공개 목록·상세에서 보이지 않습니다(§7). 별점·리뷰 행은 남습니다.
- `duplicate_of_work_id`는 "이 작품은 저 작품의 중복"이라는 검수 결과입니다. 별점·리뷰를 옮기지 않습니다(병합은 [후속]).

**구현 기록 (#41 최소, 2026-10-16)**

| 항목 | 구현 |
|---|---|
| 마이그레이션 | `V16__add_work_visibility.sql`: `works.visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC'` + `ck_works_visibility`. 기존 행은 모두 `PUBLIC`(지금 공개 동작 보존). `duplicate_of_work_id` 와 그 CHECK·FK 는 넣지 않았습니다(#41 남은 범위). 일반 열 색인은 두지 않았습니다(§5.1 주의) |
| 등록 | 관리자 등록 API(`POST /admin/works`)는 지금처럼 `PUBLIC`. 수집 대기 항목에서 만든 작품(#51 `CREATE_WORK`)은 같은 트랜잭션에서 **처음부터 `HIDDEN` 으로 INSERT** 합니다(잠깐 공개했다가 숨기지 않음). 변경 기록 `WORK·CREATE` 의 이후 값에 `visibility` 가 들어갑니다 |
| 상태 변경 | `POST /api/v1/admin/works/{workId}/visibility` `{version, visibility: PUBLIC\|HIDDEN, reason}`(ADMIN + CSRF). 작품 행 잠금 → version 비교(다르면 409 `WORK_VERSION_CONFLICT`, `currentVersion`) → 같은 상태면 그대로 200(version·기록 없음) → 조건부 `UPDATE ... version + 1` → 변경 기록 `WORK·STATUS_CHANGE`(이전·이후 `visibility`, 사유 필수). 응답은 관리자 상세(`visibility` 포함) |
| 공개 전환 정책 | ADULT·UNKNOWN 공개 표시는 미결정(D-15)이라, **ACTIVE 연결이 하나 이상 있고 모두 `GENERAL`** 일 때만 `PUBLIC` 으로 바꿉니다. 아니면 409 `WORK_PUBLICATION_BLOCKED`. 연결이 없는 작품도 등급을 확인할 수 없어 공개로 되돌리지 못합니다(기존 공개 작품은 그대로 공개). `HIDDEN` 전환은 언제나 가능 |
| 공개 경로 | 숨김 작품은 없는 작품과 같게: 공개 상세 404 `WORK_NOT_FOUND`(응답 본문도 같음), 공개 목록·`totalElements` 에서 제외, 공개 리뷰 목록 404. ADMIN 도 공개 경로에서는 같은 규칙이며 관리자 상세(`GET /admin/works/{id}`)로 봅니다. 작가 API 는 작품 목록을 내보내지 않아 영향 없음 |
| 내 별점·리뷰 | #41 결정("작성자 본인의 기록 조회·삭제는 유지")대로: 내 기록이 있으면 조회·삭제 가능, 없으면 없는 작품과 같은 404. 새 별점·리뷰 작성과 수정은 404. 쓰기 전 확인은 `SELECT visibility ... FOR SHARE` 라 숨김 전환과 직렬화됩니다 |
| 보존 | 숨김·공개 전환은 작품 ID·별점·리뷰·플랫폼 연결·근거를 바꾸지 않습니다. 다시 공개하면 별점 집계·리뷰가 그대로 보입니다 |


### 3.2 V7: 플랫폼, 상태 이력, 작품 연결

스키마·제약·초기 행은 `V7__create_platforms_and_work_listings.sql`이 기준입니다. 허용 호스트는 `platform_hosts`에 두고, `base_url` 컬럼은 없습니다. 작품 URL 패턴(`work_url_pattern`)은 선택입니다. 운영 상태 기본값은 `UNKNOWN`이고, `CLOSED`·`MERGING`은 `OFFICIAL`일 때만 저장합니다. 연결 수정은 작품 `version`을 쓰는 PUT 전체 교체입니다(§5.3).

**초기 데이터.** 10곳 모두 운영 상태 `UNKNOWN`, 근거 `UNVERIFIED`, 기본 ID 단위 `UNKNOWN`입니다. 아래 패턴이 있는 곳만 URL 규칙 `OBSERVED`입니다.

| code | name | 허용 호스트 | work_url_pattern |
|---|---|---|---|
| NAVER_WEBTOON | 네이버웹툰 | `comic.naver.com` | `^https://comic\.naver\.com/webtoon/list\?titleId=(?<id>[0-9]+)$` (확인일 2026-10-12) |
| KAKAO_PAGE | 카카오페이지 | `page.kakao.com` | `^https://page\.kakao\.com/content/(?<id>[0-9]+)$` |
| KAKAO_WEBTOON | 카카오웹툰 | `webtoon.kakao.com` | `^https://webtoon\.kakao\.com/content/[^/?#]+/(?<id>[0-9]+)$` |
| LEZHIN | 레진코믹스 | `www.lezhin.com` | `^https://www\.lezhin\.com/ko/comic/(?<id>[A-Za-z0-9_-]+)$` |
| RIDI | 리디 | `ridibooks.com` | `^https://ridibooks\.com/books/(?<id>[0-9]+)$` |
| BOMTOON | 봄툰 | `www.bomtoon.com` | `^https://www\.bomtoon\.com/detail/(?<id>[^/?#]+)$` |
| TOPTOON | 탑툰 | `toptoon.com` | `^https://toptoon\.com/(comic\|complete)/ep_list/(?<id>[A-Za-z0-9_-]+)$` |
| TOOMICS | 투믹스 | `toomics.com` | 없음 (`UNVERIFIED`) |
| COMICO_KR | 코미코 | `www.comico.kr`, `comico.kr` | 없음 (`UNVERIFIED`). 보도만으로 `CLOSED`로 두지 않습니다 |
| MRBLUE | 미스터블루 | `www.mrblue.com`, `m.mrblue.com` | 없음 (`UNVERIFIED`). 웹툰 경로와 만화 `C{9}`를 한 관찰 패턴으로 확정하지 않았습니다 |

확인일이 없는 `OBSERVED` 행은 2026-10-11입니다. 카카오웹툰 통합·코미코 종료는 초기 행에 넣지 않고, 관리자가 근거 URL과 함께 상태 API로 기록합니다(§5.5).

**구현 기록 (#39, 2026-10-13)**

| 항목 | 구현 |
|---|---|
| 마이그레이션 | `V7__create_platforms_and_work_listings.sql`(새 파일). V1~V6은 고치지 않았습니다. 새 표는 JPA 엔티티 없이 JDBC(`NamedParameterJdbcTemplate`)로 읽고 씁니다(`ddl-auto=validate` 영향 없음) |
| `platforms` | `code`(PK, `^[A-Z][A-Z0-9_]{1,29}$`)·`name`·`operator_name`·`status`·`status_basis`·`merged_into`·`status_effective_on`·`status_evidence_url`·`default_id_scope`·`work_url_pattern`·`url_rule_basis`·`url_rule_checked_on`·`terms_checked_on`·`version`. 설계의 `base_url`·`url_pattern`(NOT NULL)은 허용 호스트 표와 선택 패턴으로 바꿨습니다 |
| 운영 상태 | `ACTIVE`·`MERGING`·`CLOSED`·**`UNKNOWN`**(설계에 없던 값, 기본값). 근거는 `OFFICIAL`·`PRESS`·`UNVERIFIED`. **`CLOSED`·`MERGING`은 `OFFICIAL`일 때만** 저장됩니다(CHECK `ck_platforms_closed_official`). 보도만 있으면 `UNKNOWN` + `PRESS` + 근거 URL로 남깁니다. 코미코를 보도만으로 `CLOSED` 처리하지 않습니다. 보도는 `UNKNOWN` + `PRESS`입니다 |
| 상태 CHECK | `MERGING`이면 `merged_into` 필수, `merged_into`는 `MERGING`·`CLOSED`에서만, 자기 자신 금지, `UNVERIFIED`가 아니면 근거 URL 필수. 이력 표에도 같은 규칙(`ck_psh_*`, `ck_psh_merging_target`)이 있습니다 |
| 허용 호스트 | `platform_hosts(platform_code, host)`, 호스트는 전체에서 유일(`uk_platform_hosts_host`)하고 소문자 DNS 이름만 허용합니다. URL 호스트는 이 목록과 **정확히 같아야** 합니다(하위 도메인 자동 허용 없음) |
| 초기 데이터 | 10곳의 **코드·표시 이름**이 확정값입니다. 운영 상태 `UNKNOWN`·근거 `UNVERIFIED`·기본 ID 단위 `UNKNOWN`·운영사·약관 확인일은 비워 둡니다. URL 규칙은 공개 작품 주소를 관찰한 7곳만 `OBSERVED` + 확인일(2026-10-11, 네이버웹툰은 2026-10-12 사용자 확인 예시 `titleId=651673`)입니다. 투믹스·코미코·미스터블루는 패턴 없이 `UNVERIFIED`입니다. 미스터블루는 웹툰 경로가 조사 문서의 `detail/wt_`와 성인 리다이렉트에서 본 `wt_`만으로 갈리고, 만화 `C{9}`는 수집 설계 §8.4에서 PROBE 전이라 한 패턴을 `OBSERVED`로 두지 않았습니다. 호스트(`www.mrblue.com`, `m.mrblue.com`)는 남깁니다. 그 외 호스트 변형은 확인 뒤 관리자 API로 추가합니다 |
| URL 패턴 | 이름 있는 그룹 `(?<id>...)` 정확히 하나가 필요하고, 그 값이 `platformWorkId`와 같아야 합니다. 역참조(`\1`, `\k<name>`, `\k'name'`)·전후방 탐색은 거절합니다. 매칭은 잠금 밖에서 하고, `charAt` 10만 회 또는 50ms를 넘으면 거절합니다. 이 한계는 정규식 엔진이 `charAt`을 부르는 동안 그 매칭을 끝냅니다. `Thread.interrupt`는 하지 않으며, `charAt`을 부르지 않는 경로는 멈추지 않습니다. 입력 길이(500)와 패턴 길이(300), 역참조 금지가 그 간격을 줄입니다 |
| URL 검증·정규화 | `https`만, 사용자 정보·443 이외 포트·`#` 조각 금지, 호스트 소문자·기본 포트 제거. 비 ASCII는 퍼센트 인코딩하고, 예약되지 않은 문자의 퍼센트 인코딩(`%63` → `c`)만 **한 번** 풉니다. 반복해서 풀지 않습니다. 제어 문자 인코딩(`%00`, `%0D%0A` 등), `%5C`, 경로의 `%2F`, `%25`를 최대 두 겹 안쪽까지 봤을 때의 `.` `/` `\` 제어 문자는 거절합니다. 세 겹을 넘는 `%25`는 더 풀지 않습니다. `.`·`..` 경로 조각과 공백·제로폭 문자도 거절합니다. UTF-8로 퍼센트 인코딩된 공백·제어·서식 문자(`%20`, `%E2%80%8B`, `%C2%A0`)도 원문과 같이 거절합니다. `%25`는 최대 두 겹까지 풀어 같은 문자를 봅니다. 한글·일본어 URL은 허용합니다. 금지 문자가 아닌 잘못된 UTF-8(`%FF`, 이어지지 않은 `%C3`)은 이 규칙으로 거절하지 않습니다. 설계의 "추적 쿼리 제거"는 하지 않고, 패턴에 맞지 않는 쿼리는 400입니다. **서버는 URL을 가져오지 않습니다** |
| `work_platform_listings` | FK에 CASCADE 없음. `UNIQUE(platform_code, platform_work_id)`, `platform_work_id`는 보이는 ASCII 1~100자(CHECK). 이전 대상은 복합 FK `(moved_to_listing_id, work_id) → (id, work_id)`로 **같은 작품의 연결만** 가리킵니다. `status = MOVED` ⇔ 이전 대상 있음, 자기 참조 금지, `age_class` 3값, 확인 시각 순서 CHECK |
| 등급 | `ageRatingRaw`를 그대로 저장하고 `ageClass`를 분류합니다. N세(뒤에 미만·이하·이상이 없을 때, N<18)·전체 → `GENERAL`. 18·19세 이용가, `19금`, `청소년 이용불가`, `청불`, `18+`·`19+`·`R18` → `ADULT`. `19금`은 19세 이용가의 공개 약칭이고, `청불`은 앞뒤에 다른 한글이 없을 때의 `청소년 이용불가` 약칭입니다. **"성인" 부분 문자열만으로는 ADULT가 되지 않습니다.** `18세 미만`, `미성인`, `성인 아님`, `전체 이용불가`, `청소년 이용불가 아님`, `18세 이용가 아님`처럼 부정·모호한 표기는 `UNKNOWN`입니다. `전체 이용가`, `청소년 이용불가`, `18세 이용가`는 그대로 분류합니다. 신호가 겹치거나 표기가 없거나 인식하지 못하면 `UNKNOWN`이며 일반으로 올리지 않습니다. 명시한 `ageClass`가 분류와 다르면 400, 원래 표기 없이 `GENERAL`·`ADULT`면 400, **인식하지 못한 표기로 `GENERAL`을 지정해도 400**입니다. 인식하지 못한 표기를 `ADULT`로 지정하려면 사유(`note`)가 필요하고, 그 메모를 연결에 남깁니다 |
| 버전 | 연결 변경은 S-3대로 **작품 `version`**을 요청으로 받고 올립니다(#38 작품 수정과 같은 버전). 플랫폼 변경은 `platforms.version`, 불일치는 409 `PLATFORM_VERSION_CONFLICT` |
| 잠금 순서 | 연결 등록·수정·이전: 플랫폼 `FOR SHARE` → 작품 `FOR NO KEY UPDATE`. 플랫폼 상태·규칙 변경: 플랫폼 `FOR NO KEY UPDATE`. 따라서 종료 처리와 연결 등록이 겹치면 한쪽이 기다리고, 먼저 커밋된 연결은 `activeListingCount`에 포함됩니다. id 목록은 최대 100개입니다. 통합 대상 지정은 순환을 동시에 만들지 못하게 플랫폼 10행을 코드 순으로 잠급니다 |
| 원자성 | 상태 이력 INSERT와 플랫폼 UPDATE, 연결 INSERT·UPDATE와 작품 `version` 증가, 이전(새 연결 + 이전 연결 `MOVED`)은 각각 한 트랜잭션입니다. 중간 실패 시 모두 되돌아갑니다(트리거로 실패를 주입해 검증) |
| 중복 연결 | `INSERT ... ON CONFLICT ON CONSTRAINT uk_listings_platform_work DO NOTHING` 후 기존 행을 찾아 409 `PLATFORM_LISTING_ALREADY_LINKED`, `error.existingWorkId`·`error.existingListingId`를 넣습니다. 같은 작품에 이미 있는 경우도 409입니다 |
| 이전 체인 | 연결이 `MOVED`가 되면 그 연결을 가리키던 연결을 새 대상으로 다시 가리키게 합니다. `MOVED` 연결을 가리키는 연결이 남지 않아 순환이 생기지 않습니다 |
| 삭제 | 플랫폼·연결 삭제 API는 없습니다. 상태 변경(`ENDED`·`MOVED`·`UNKNOWN`)만 씁니다 |
| 테스트 DB | `TestDatabaseCleaner`가 새 표를 비우고 `src/test/resources/db/platform-seed.sql`로 초기 데이터를 다시 넣습니다. 이 파일이 V7 초기 데이터와 같은지 새 스키마에 마이그레이션해 전체 열을 비교합니다 |

결정하지 않은 정책

- 추적 쿼리를 제거해 받아들일지, 지금처럼 거절할지.
- `m.comic.naver.com`·`www.toomics.com` 등 호스트 변형과 투믹스·코미코 URL 규칙은 확인 뒤 관리자 API로 추가합니다.
- 플랫폼 운영 상태·기본 ID 단위·약관 확인일은 공식 근거를 확인한 뒤 관리자 API로 입력합니다(카카오웹툰 통합 공지 등).

### 3.3 V8: 외부 ID, 공식 링크, 필드 근거, 관리 변경 기록

```sql
CREATE TABLE work_external_ids (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    work_id      BIGINT       NOT NULL REFERENCES works (id),
    source       VARCHAR(30)  NOT NULL,   -- 'WIKIDATA','MADB','NDL','KMAS'
    external_id  VARCHAR(100) NOT NULL,
    source_url   VARCHAR(500),
    status       VARCHAR(20)  NOT NULL,   -- 'ACTIVE','SUPERSEDED','INVALID'
    verified_at  TIMESTAMPTZ  NOT NULL,
    verified_by  BIGINT       NOT NULL REFERENCES users (id),
    CONSTRAINT uk_work_external UNIQUE (source, external_id),
    CONSTRAINT ck_work_external_source CHECK (source IN ('WIKIDATA','MADB','NDL','KMAS')),
    CONSTRAINT ck_work_external_status CHECK (status IN ('ACTIVE','SUPERSEDED','INVALID'))
);

CREATE TABLE creator_external_ids (  -- 같은 구조, creator_id
    ...,
    CONSTRAINT uk_creator_external UNIQUE (source, external_id)
);

CREATE TABLE work_links (               -- 플랫폼이 아닌 공식 링크(출판사·공식 사이트)
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    work_id       BIGINT       NOT NULL REFERENCES works (id),
    link_type     VARCHAR(20)  NOT NULL,   -- 'PUBLISHER','OFFICIAL_SITE'
    url           VARCHAR(500) NOT NULL,
    language_code VARCHAR(35)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,   -- 'ACTIVE','BROKEN','REMOVED'
    verified_at   TIMESTAMPTZ  NOT NULL,
    verified_by   BIGINT       NOT NULL REFERENCES users (id),
    CONSTRAINT uk_work_links UNIQUE (work_id, url)
);

CREATE TABLE work_field_sources (       -- 필드별 근거
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    work_id       BIGINT      NOT NULL REFERENCES works (id),
    field         VARCHAR(30) NOT NULL,   -- 'TITLE','CREDIT','WORK_FORMAT','ORIGIN_COUNTRY','DESCRIPTION','LISTING'
    target_ref    VARCHAR(50),            -- 대상 행(예: 'title:12', 'credit:creator=5,role=ART', 'listing:3')
    source_type   VARCHAR(30) NOT NULL,   -- 'PLATFORM_PAGE_MANUAL','WIKIDATA','MADB','NDL','PUBLISHER_PAGE','STAFF'
    source_ref    VARCHAR(500),           -- 연결 ID·외부 ID·URL
    verified_at   TIMESTAMPTZ NOT NULL,
    verified_by   BIGINT      NOT NULL REFERENCES users (id),
    note          VARCHAR(300)
);
CREATE INDEX idx_field_sources_work ON work_field_sources (work_id, field);

CREATE TABLE admin_change_log (         -- 관리 변경 기록(작품·작가·연결·플랫폼)
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    entity_type  VARCHAR(30) NOT NULL,    -- 'WORK','CREATOR','LISTING','PLATFORM','EXTERNAL_ID','LINK'
    entity_id    VARCHAR(50) NOT NULL,
    action       VARCHAR(30) NOT NULL,    -- 'CREATE','UPDATE','STATUS_CHANGE','HIDE','MARK_DUPLICATE','UNHIDE'
    before_json  JSONB,
    after_json   JSONB,
    reason       VARCHAR(500),
    actor_id     BIGINT NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_change_log_entity ON admin_change_log (entity_type, entity_id, created_at DESC);
```

- **플랫폼 연결과 데이터 출처의 구분** [결정]: 연결(`work_platform_listings`)은 작품을 볼 수 있는 곳이고, 출처(`work_external_ids`·`work_field_sources`)는 값을 넣은 근거입니다. 플랫폼 페이지가 근거가 되는 것은 사람이 확인한 경우(`PLATFORM_PAGE_MANUAL`)뿐입니다.
- 외부 ID·링크·근거 행도 지우지 않고 상태로 비활성화합니다. 변경 기록에는 이메일·비밀번호 같은 개인 정보를 넣지 않습니다(actor는 사용자 ID만).
- 새 테이블의 작품 FK는 **CASCADE를 두지 않습니다.** 작품 삭제 경로가 없으므로 필요 없고, 실수로 지울 때 함께 사라지지 않게 합니다.

> 위 SQL 은 #37 설계안입니다. **실제 계약은 아래 구현 기록과 `V8__create_external_ids_links_sources_change_log.sql`입니다.**

**구현 기록 (#40, 2026-10-14)**

| 항목 | 구현 |
|---|---|
| 마이그레이션 | `V8__create_external_ids_links_sources_change_log.sql`(새 파일). V1~V7은 고치지 않았습니다. 새 표는 JPA 엔티티 없이 JDBC로 읽고 씁니다(`ddl-auto=validate` 영향 없음) |
| 외부 ID | `work_external_ids`·`creator_external_ids`: 출처(`WIKIDATA`·`MADB`·`NDL`·`KMAS`), 외부 ID, 출처 URL(선택), 상태(`ACTIVE`·`SUPERSEDED`·`INVALID`), 확인 시각·확인자, 메모. 설계의 `UNIQUE(source, external_id)` 대신 **`INVALID`가 아닌 행만 유일한 부분 유니크 인덱스**입니다. 잘못 연결한 ID를 `INVALID`로 바꾸면 올바른 대상에 다시 연결할 수 있고, `SUPERSEDED`(출처에서 대체됨)는 자리를 유지합니다. 작품 표와 작가 표는 따로 유일합니다 |
| 외부 ID 형식 | Wikidata 는 `Q`+숫자(소문자 `q`는 대문자로)만 받습니다. MADB·NDL·KMAS 는 형식을 확인하지 못해 보이는 ASCII 1~100자만 검사하고 응답에 `EXTERNAL_ID_FORMAT_UNVERIFIED` 경고를 줍니다 |
| 공식 링크 | `work_links`: `PUBLISHER`·`OFFICIAL_SITE`, URL, 언어, 상태(`ACTIVE`·`BROKEN`·`REMOVED`), 확인 시각·확인자. `UNIQUE(work_id, url)`(상태와 무관, 다시 쓰려면 상태를 바꿈). URL 은 플랫폼 URL 과 같은 정규화·검사(https·사용자 정보·포트·조각·숨은 문자·점 경로)를 하되 호스트는 도메인 이름 형식만 봅니다(IP·localhost 거절). **플랫폼 허용 호스트의 주소는 링크가 아니라 연결로 등록**하도록 400 입니다 |
| 필드별 근거 | `work_field_sources`: 필드(`TITLE`·`CREDIT`·`WORK_FORMAT`·`ORIGIN_COUNTRY`·`DESCRIPTION`·`LISTING`), 근거 종류(`PLATFORM_PAGE_MANUAL`·`WIKIDATA`·`MADB`·`NDL`·`KMAS`·`PUBLISHER_PAGE`·`STAFF`·`LICENSED`), `source_ref`·`source_url`, 상태(`ACTIVE`·`RETRACTED`). 줄거리 근거는 `STAFF`·`LICENSED`만, `STAFF` 외에는 `source_ref`나 `source_url`, `LICENSED`는 허락 근거 `source_ref`가 필요합니다(DB CHECK와 서비스 검증이 같음). `LISTING` 근거는 같은 작품의 연결만 가리킵니다(복합 FK). 제목·크레딧 행은 수정 때 다시 만들어 ID 가 바뀌므로 설계의 `target_ref`(행 참조)는 사람이 읽는 값 설명(예: `ko:유미의 세포들`)으로 바꿨습니다 |
| 변경 기록 | `admin_change_log`: 대상 종류(`WORK`·`CREATOR`·`LISTING`·`PLATFORM`·`WORK_EXTERNAL_ID`·`CREATOR_EXTERNAL_ID`·`WORK_LINK`·`FIELD_SOURCE`)·대상 ID·동작(`CREATE`·`UPDATE`·`STATUS_CHANGE`·`MOVE`)·작품 ID·작가 ID·이전/이후 JSON·사유·관리자 사용자 ID·시각. `CREATE`만 이전 값이 없고, 그 밖에는 이전 값과 사유가 반드시 있습니다(CHECK). **UPDATE·DELETE 를 거절하는 트리거로 추가만** 할 수 있습니다. 설계의 `HIDE`·`MARK_DUPLICATE`·`UNHIDE`는 #41에서 추가합니다. 이메일 등 개인 정보는 넣지 않습니다 |
| 원자성 | 변경과 기록은 같은 트랜잭션입니다(기록 쓰기는 `Propagation.MANDATORY`). 기록이나 version 증가가 실패하면 변경도 되돌아갑니다(트리거로 실패를 주입해 검증). 내용이 같아 바뀌지 않은 요청은 version·근거·기록을 남기지 않습니다 |
| 버전 | 작품 외부 ID·링크·근거 변경은 S-3대로 작품 `version`을 받고 올립니다. 작가 외부 ID 는 작가 `version`을 씁니다. 등록과 함께 넣은 하위 리소스는 첫 상태의 일부라 새 작품·작가의 `version`은 0 입니다 |
| 수정 사유 | 수정·상태 변경·이전 요청(작품·작가 `PUT`, 연결 `PUT`·`move`, 외부 ID·링크·근거 `PATCH`, 플랫폼 `PUT`·상태 변경)은 `reason`(1~500자)이 **필수**입니다. 등록·추가는 선택입니다. 플랫폼 상태 변경의 `note`는 상태 이력 메모로 그대로 두고, `reason`은 변경 기록의 사유입니다 |
| 근거 필수 규칙 | 작품 등록: `TITLE`·`CREDIT` 각 1개 이상, 줄거리가 있으면 `DESCRIPTION` 1개 이상. 작품 `PUT`: **실제로 바뀐** 제목·크레딧·줄거리(비우는 경우 제외)만 근거가 필요합니다(이 확인은 잠금·내용 비교 뒤라 400 이 version 확인 뒤에 날 수 있음). 새 연결(등록·이전·작품 등록의 `listings[]`): 연결 근거 1개 이상(`field` 생략 시 `LISTING`). 형식·국가 근거와 연결 수정 근거는 선택입니다 |
| 등록 확장 | `POST /admin/works`에 `listings[]`·`externalIds[]`·`links[]`·`evidence[]`·`reason`. 모든 입력을 먼저 검증(400)한 뒤 작품 → 변경 기록 → 연결 → 외부 ID → 링크 → 근거 순으로 저장합니다. 연결·외부 ID 가 이미 있으면 409 로 등록 전체가 롤백되므로 **재시도해도 작품이 두 번 생기지 않습니다**(키 없는 등록은 여전히 멱등이 아님). 응답은 관리자 상세(`version`·연결·외부 ID·링크·근거 포함)로 바뀌었습니다. `POST /admin/creators`도 `externalIds[]`·`reason`을 받고 관리자 상세를 돌려줍니다 |
| 관리자 상세 | `GET /admin/works/{id}`에 `listings`·`externalIds`·`links`·`fieldSources`(비활성 행 포함), `GET /admin/creators/{id}`에 `externalIds`를 더했습니다. 공개 API 에는 아직 내보내지 않습니다(#42) |
| 테스트 픽스처 | 변경 기록이 사용자 FK를 가지므로 `ApiFixtures`는 테스트 DB 에 실제 ADMIN 행을 만들고 그 ID 의 `AuthenticatedUser`로 요청합니다 |

결정하지 않은 정책(이번에 기본 동작만 정함)

- MADB·NDL·KMAS 외부 ID 의 정확한 형식, Wikidata 출처 URL 의 호스트 제한
- 같은 Wikidata ID 를 작품과 작가에 동시에 붙이는 경우(지금은 표가 달라 막지 않음)
- 형식·국가 변경에도 근거를 필수로 할지(지금은 선택)
- `PATCH`의 `note`를 비우는 방법(지금은 생략·null 이면 유지)
- 변경 기록 보관 기간과 줄거리 전문을 이전·이후 값에 그대로 남길지
- 관리자 상세의 근거 목록이 커질 때의 쪽 나누기

---

## 4. 플랫폼 작품 ID 규칙

- `platform_work_id`는 플랫폼의 정규 형식으로 저장합니다(앞뒤 공백 제거, 슬러그는 소문자 변환하지 않음).
- `id_scope`는 연결마다 저장합니다. 플랫폼 기본값(§3.2 표)을 따르되, 검수자가 다른 단위임을 확인하면 연결 값으로 바꿉니다.
  - `SERIES`: 작품(시리즈) 전체. 카카오페이지 content, 카카오웹툰, 레진, 탑툰, 미스터블루 웹툰(작품 페이지 확인)
  - `VOLUME_OR_EPISODE`: 권·회차. 리디 `/books/{id}`는 대표 회차(보통 1화·1권) ID로 저장
  - `EDITION`: 판본(단행본·개정판·완전판). 카카오페이지 [단행본] content ID 등
  - `UNKNOWN`: 봄툰·투믹스·네이버웹툰(파일럿 전)
- `UNKNOWN`인 연결은 공개 화면에 표시하지 않고 관리자 확인 대기로 둡니다 [결정]. 파일럿에서 단위가 확정되면 표시합니다.
- 슬러그형 ID(레진·탑툰)는 바뀔 수 있으므로, 바뀌면 새 연결을 만들고 이전 연결을 `MOVED`로 둡니다(§5.3).
- 구현(#39): `platform_work_id`는 앞뒤 공백을 제거하지 않고 공백·제어문자·비 ASCII가 있으면 거절합니다(보이는 ASCII 1~100자). 같은 번호라도 플랫폼이 다르면 다른 식별자입니다(`RIDI 100`과 `KAKAO_PAGE 100`은 둘 다 등록 가능). 제목으로 자동 병합하지 않습니다.

---

## 5. 관리자 API

공통: prefix `/api/v1/admin`, ADMIN 세션 + CSRF(`X-XSRF-TOKEN`), 응답은 `ApiResponse`. 큰 ID는 문자열이 아닌 JSON 숫자로 주고받되 프론트는 무손실 파서를 씁니다(기존 규칙). 모든 변경은 `admin_change_log`에 한 줄씩 남깁니다.

### 5.1 작품 수정과 동시 수정 충돌 방지

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/admin/works/{workId}` | 관리자용 상세. 공개 상세 + `version`, `visibility`, `duplicateOfWorkId`, 연결·외부 ID·링크·근거 전체 |
| PUT | `/admin/works/{workId}` | 핵심 정보 전체 교체: `version`, `workFormat`, `originCountry`, `description`, `titles[]`, `creators[]`, `evidence[]`, `reason` |
| POST | `/admin/works/{workId}/visibility` | `{version, visibility: PUBLIC\|HIDDEN, reason}` |
| POST | `/admin/works/{workId}/duplicate-of` | `{version, duplicateOfWorkId, reason}` → `HIDDEN`과 함께 기록. 해제는 `duplicateOfWorkId: null` |

충돌 규칙 [결정]
- 작품의 모든 변경(핵심 정보, 공개 상태, 중복 표시, 연결·외부 ID·링크·근거 추가·변경)은 요청 본문의 `version`이 현재 `works.version`과 같을 때만 적용하고, 성공하면 `version + 1`입니다. 하나의 버전으로 작품 단위 변경을 직렬화합니다.
- 다르면 **409 `WORK_VERSION_CONFLICT`**, 아무것도 바꾸지 않습니다. 응답 `error`에 현재 `version`을 넣어 관리자가 최신 상태를 다시 읽게 합니다.
- 구현: `UPDATE works SET ..., version = version + 1, updated_at = now() WHERE id = :id AND version = :expected` 후 영향 행 수 확인(리뷰와 같은 조건부 갱신). JPA 엔티티를 쓸 경우 같은 트랜잭션에서 먼저 조건부 갱신으로 버전을 올리고 자식 컬렉션을 바꿉니다. `@Version`만으로는 자식 컬렉션 변경 시 버전이 오르지 않을 수 있어 쓰지 않습니다 [선택 S-1].
- 내용이 같으면 버전을 올리지 않고 200(멱등).
- `PUT`의 검증은 등록과 같습니다(work-creator-spec §3·§4): 제목 1~20개·대표 정확히 1개·언어 태그·크레딧 1~20개·중복 금지·작가 존재. 제목·크레딧 행은 교체하되 작품 ID는 그대로입니다.

**구현 기록 (#38, 2026-10-12)**

| 항목 | 구현 |
|---|---|
| 처리 순서 | 입력 검증(400) → 작품 행 잠금(없으면 404) → `version` 비교(다르면 409) → 내용 비교(같으면 그대로 200) → 제목 삭제·삽입, 크레딧 삭제·삽입 → 핵심 필드 조건부 `UPDATE ... WHERE version = :expected`(`version + 1`, `updated_at = GREATEST(updated_at, now())`) |
| 잠금 | `SELECT ... FOR NO KEY UPDATE`. 같은 작품의 다른 수정은 기다리고, 이 작품을 참조하는 별점·리뷰 작성(FK의 KEY SHARE)은 막지 않습니다. 작가도 같은 방식이라 작가 수정 중에도 다른 작품의 크레딧 추가가 막히지 않습니다 |
| 원자성 | 한 트랜잭션입니다. 커밋 전 상태(대표 제목 0개·2개 등)는 다른 트랜잭션에 보이지 않고, 중간에 실패하면 제목·크레딧·`version`이 모두 되돌아갑니다 |
| 읽기 일관성 | 공개 상세·목록과 관리자 상세는 작품·제목과 크레딧을 서로 다른 SQL로 읽습니다. 그 사이에 수정이 커밋되면 이전·이후 값이 섞일 수 있어, 이 조회들은 `REPEATABLE READ` 읽기 전용 트랜잭션 하나로 읽습니다 |
| 409 응답 | `error.code = WORK_VERSION_CONFLICT`(작가는 `CREATOR_VERSION_CONFLICT`), `error.currentVersion`에 현재 값. 요청 `version`이 현재보다 커도 409 |
| `version` 형식 | JSON 정수만 받습니다(리뷰와 같은 `JsonInteger`). 문자열·소수 표기·음수는 400 |
| 멱등 | **같은 `version`으로 보낸 경우에만** 적용합니다. 대표 여부를 포함한 제목 집합, 크레딧 집합, 형식·국가·줄거리가 모두 같으면(순서 무관, 정규화 후 비교) `version`·`updated_at`을 바꾸지 않습니다. 성공한 수정을 같은 본문(이전 `version`)으로 다시 보내면 409이므로, 화면은 409를 받으면 최신 상태를 다시 읽습니다 |
| 부분 교체 | 제목만 바뀌면 크레딧 행은 그대로 두고, 크레딧만 바뀌면 제목 행은 그대로 둡니다. 바뀐 쪽은 행을 지우고 요청 순서대로 다시 넣으므로 대체 제목의 표시 순서가 요청 순서를 따릅니다 |
| 영속성 컨텍스트 | 삭제와 마지막 `UPDATE`만 영속성 컨텍스트를 비웁니다(`clearAutomatically`). 다른 서비스가 같은 트랜잭션에서 이 수정을 부를 때(#40·#41) 이미 읽은 엔티티가 분리될 수 있으니 수정 뒤 다시 읽어야 합니다 |
| 주의 | `works`·`creators`의 일반 컬럼에 유니크 인덱스를 추가하면 PostgreSQL이 `UPDATE`를 `FOR UPDATE` 잠금으로 올려 별점·리뷰·크레딧 삽입을 막게 됩니다. 이런 인덱스는 별도 테이블에 둡니다 |
| 줄거리 | 전체 교체라서 `description`을 생략하거나 `null`로 보내면 비웁니다. 작가 `originalName`도 같습니다 |
| 응답 | `AdminWorkDetailResponse` = 공개 상세 필드 + `version`. 공개 상세(`/api/v1/works/{id}`)에는 `version`이 없습니다 |
| 이번에 넣지 않은 것 | `reason`·`evidence[]`·변경 기록(#40에서 구현, §3.3 구현 기록), 공개 상태·중복 표시(#41) |

### 5.2 작가 수정

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/admin/creators/{creatorId}` | 관리자용 상세(+`version`, 외부 ID, 연결된 작품 수). #38에서는 `version`·`linkedWorkCount`까지 구현, 외부 ID는 #40 |
| PUT | `/admin/creators/{creatorId}` | `{version, name, originalName, reason}`. 불일치 409 **`CREATOR_VERSION_CONFLICT`** |
| POST·PATCH | `/admin/creators/{creatorId}/external-ids` | 외부 ID 추가·상태 변경(작가 `version` 사용) |

- 작가 이름을 바꾸면 그 작가를 크레딧에 둔 모든 작품의 표시가 바뀝니다. 응답에 영향받는 작품 수를 넣고, 관리 기록에 남깁니다.
- 같은 이름의 다른 사람일 수 있으므로 작가 병합은 하지 않습니다 [후속].

### 5.3 플랫폼 연결

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/admin/works/{workId}/listings` | 그 작품의 연결 목록과 작품 `version` |
| POST | `/admin/works/{workId}/listings` | `{version, platformCode, platformWorkId, url, idScope, listingKind, languageCode, ageRatingRaw, ageClass, verifiedAt, note, evidence[], reason?}` (201). `evidence[]` 1개 이상 필수(#40) |
| PUT | `/admin/works/{workId}/listings/{listingId}` | `{version, url, idScope, listingKind, languageCode, ageRatingRaw, ageClass, status, movedToListingId, lastVerifiedAt, note, evidence[]?, reason}` 전체 교체(`reason` 필수, #40). 생략한 nullable 필드는 지워집니다 |
| POST | `/admin/works/{workId}/listings/{listingId}/move` | `{version, newListing{..., evidence[]}, reason}` (201). 새 연결을 만들고 기존 연결을 `MOVED`로 바꿉니다 |
| GET | `/admin/listings/lookup?platformCode=&platformWorkId=` | 이미 연결된 작품이 있는지 확인(중복 검수용) |

> **구현(#39):** 변경 응답은 `{workId, workVersion, listing, movedFrom, warnings}`이고 `warnings`에는 `PLATFORM_MERGING`·`PLATFORM_STATUS_UNKNOWN`·`URL_RULE_UNVERIFIED`·`ID_SCOPE_UNKNOWN`·`AGE_CLASS_UNKNOWN`이 들어갈 수 있습니다. 플랫폼 코드와 외부 작품 ID는 이 요청으로 바꾸지 않습니다(바뀌면 이전). URL이 그대로면 규칙이 바뀌었어도 다시 검사하지 않아 종료·이전 처리를 막지 않습니다. `CLOSED` 플랫폼 연결은 `ACTIVE`로 되돌릴 수 없습니다. 세부는 §3.2 구현 기록.

- `UNIQUE(platform_code, platform_work_id)` 위반은 **409 `PLATFORM_LISTING_ALREADY_LINKED`**(관리자 응답에만 기존 작품 ID 포함). 다른 작품에 이미 붙은 플랫폼 작품을 새 작품으로 만들지 못하게 합니다.
- URL이 플랫폼 `work_url_pattern`에 맞지 않으면 400 `INVALID_INPUT`(`fieldErrors: url`).
- `CLOSED` 플랫폼에는 새 연결을 만들 수 없습니다(400). `MERGING` 플랫폼은 허용하되 응답에 경고 필드를 넣습니다.
- 등급: 연결에 `ageRatingRaw`(원래 표기)와 `ageClass`를 기록합니다. 명확한 18세 미만 표기(전체·7·12·15세)는 `GENERAL`, 명확한 성인 표기(18·19세 이용가, `19금`, `청소년 이용불가`, `청불`, `18+`·`19+`·`R18`)는 `ADULT`입니다. `성인`이라는 낱말만으로, 또는 `18세 미만`·`미성인`·`성인 아님`·`전체 이용불가`·`청소년 이용불가 아님`·`18세 이용가 아님`처럼 부정·모호한 표기로는 자동 분류하지 않고 `UNKNOWN`으로 둡니다. `전체 이용가`·`청소년 이용불가`·`18세 이용가`는 그대로 분류합니다. **세 값 모두 등록할 수 있습니다.** `UNKNOWN`은 `GENERAL`로 자동 처리하지 않고, 인식하지 못한 표기를 `GENERAL`로 지정하면 400입니다. 인식하지 못한 표기를 `ADULT`로 지정하려면 `note`에 사유를 적어야 합니다. 공개 화면에 `ADULT`·`UNKNOWN`을 어떻게 보일지는 공개 표시 정책(수집 설계 §6.3)에서 정하며, 그 전에는 공개하지 않습니다.
- 연결 이전(플랫폼 통합·슬러그 변경): 새 연결을 만든 뒤 이전 연결을 `MOVED`(+`movedToListingId`)로 바꿉니다. 한 요청으로 처리하는 `POST /admin/works/{workId}/listings/{listingId}/move`(`{version, newListing{...}}`)를 둡니다.

### 5.4 외부 ID·공식 링크·근거

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST·PATCH | `/admin/works/{workId}/external-ids[/{id}]` | 추가·상태 변경. 중복은 409 **`EXTERNAL_ID_ALREADY_LINKED`** |
| POST·PATCH | `/admin/works/{workId}/links[/{id}]` | 출판사·공식 사이트 링크 추가·상태 변경 |
| POST | `/admin/works/{workId}/field-sources` | 근거 추가(값 변경 없이 근거만 보강할 때) |

- 근거 필수 규칙 [결정]: 작품 등록·`PUT`·연결 추가 요청은 바뀌는 필드마다 `evidence` 하나 이상이 있어야 합니다(`TITLE`·`CREDIT`·`LISTING`). 없으면 400. 설명(`DESCRIPTION`)은 `STAFF`(직접 작성) 또는 `LICENSED`만 허용합니다.

> **구현(#40):** 설계의 `PATCH`를 그대로 썼습니다. 계약은 다음과 같습니다(모두 ADMIN + CSRF, 삭제 API 없음).
>
> | 메서드 | 경로 | 요청 | 응답 |
> |---|---|---|---|
> | POST | `/admin/works/{workId}/external-ids` | `{version, source, externalId, sourceUrl?, verifiedAt, note?, reason?}` | 201 `{ownerType:"WORK", ownerId, ownerVersion, externalId, warnings}` |
> | PATCH | `/admin/works/{workId}/external-ids/{id}` | `{version, status, verifiedAt?, note?, reason}` | 200 같은 형태 |
> | POST·PATCH | `/admin/creators/{creatorId}/external-ids[/{id}]` | 위와 같음(작가 `version`) | `ownerType:"CREATOR"` |
> | POST | `/admin/works/{workId}/links` | `{version, linkType, url, languageCode, verifiedAt, note?, reason?}` | 201 `{workId, workVersion, link}` |
> | PATCH | `/admin/works/{workId}/links/{id}` | `{version, status, verifiedAt?, note?, reason}` | 200 같은 형태 |
> | POST | `/admin/works/{workId}/field-sources` | `{version, evidence:[{field, listingId?, targetRef?, sourceType, sourceRef?, sourceUrl?, verifiedAt, note?}], reason?}` (1~50개) | 201 `{workId, workVersion, fieldSources}` |
> | PATCH | `/admin/works/{workId}/field-sources/{id}` | `{version, status, note?, reason}` | 200 같은 형태 |
> | GET | `/admin/change-log?workId=` \| `?creatorId=` \| `?entityType=&entityId=` (`limit` 1~100 기본 50, `beforeId`) | — | `{entries:[{id, entityType, entityId, action, workId, creatorId, before, after, reason, actorId, createdAt}], nextBeforeId}` |
>
> 외부 ID·링크의 출처·ID·URL 은 바꾸지 않습니다(바뀌면 새로 추가하고 이전 행을 비활성화). `verifiedAt`을 보내면 다시 확인한 것으로 보고 확인자도 요청자로 바뀝니다. 오류: 409 `EXTERNAL_ID_ALREADY_LINKED`(`existingWorkId` 또는 `existingCreatorId`, `existingExternalIdId`), 409 `WORK_LINK_ALREADY_EXISTS`, 404 `EXTERNAL_ID_NOT_FOUND`·`WORK_LINK_NOT_FOUND`·`FIELD_SOURCE_NOT_FOUND`(다른 작품·작가의 행 포함), 409 버전 충돌. 세부는 §3.3 구현 기록.

### 5.5 플랫폼 관리

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/admin/platforms` | 10곳. 각 항목은 상태·근거·허용 호스트·URL 규칙·`version`·`warnings` |
| GET | `/admin/platforms/{code}` | 한 플랫폼. 코드는 대소문자를 구분하지 않습니다 |
| PUT | `/admin/platforms/{code}` | `{version, name, operatorName, defaultIdScope, workUrlPattern, urlRuleBasis, urlRuleCheckedOn, termsCheckedOn, allowedHosts[]}` 전체 교체. 운영 상태는 바꾸지 않습니다 |
| POST | `/admin/platforms/{code}/status` | `{version, toStatus, mergedInto?, effectiveOn, statusBasis, evidenceUrl, note}` → `platform_status_history` |
| GET | `/admin/platforms/{code}/status-history` | 그 플랫폼의 상태 이력 |

상태 변경 응답은 `{platform, historyId, activeListingCount, activeListingIds}`입니다. `activeListingIds`는 ACTIVE 연결 id를 오름차순으로 **최대 100개**만 담고, `activeListingCount`와 개수가 다를 수 있습니다. 상태·근거·통합 대상·효력일·근거 URL·메모가 모두 같으면 `historyId`는 null이고 `version`도 그대로입니다. 메모만 달라도 이력을 남기고 `version`을 올립니다. 메모는 이력 행에 남습니다. 근거 URL은 https 형식만 보고 가져오지 않습니다. `platform.warnings`에는 통합 대상이 `CLOSED`이면 `MERGED_INTO_CLOSED`, 이 플랫폼이 `CLOSED`인데 다른 플랫폼이 여기를 가리키면 `MERGE_SOURCES_REMAIN`가 들어갑니다. 그 관계는 지우지 않습니다.

- 상태를 `CLOSED`로 바꾸면 그 플랫폼의 `ACTIVE` 연결 수와 id(최대 100개)를 응답에 넣습니다. 연결 상태는 자동으로 바꾸지 않고, 관리자가 연결마다 `ENDED`·`MOVED`로 처리합니다 [결정].
- 카카오웹툰 통합과 코미코 종료는 초기 데이터가 아닙니다. 공식 근거가 있으면 `OFFICIAL`로 상태 API에 기록합니다. 코미코는 공식 공지 확인 전까지 `CLOSED`로 두지 않고, 보도는 `UNKNOWN` + `PRESS`로만 남깁니다. 통합 뒤 연결 이전은 #54입니다.

### 5.6 등록 API 최소 확장

- `POST /admin/works` 요청에 선택 필드 `listings[]`, `externalIds[]`, `links[]`, `evidence[]`를 추가합니다. 한 트랜잭션에서 작품과 함께 저장하고, 응답에 `version`을 넣습니다.
- 등록 요청에 `listings` 또는 `externalIds`가 있으면, 같은 키가 이미 다른 작품에 있을 때 409로 거절되므로 **재시도해도 같은 작품이 두 번 만들어지지 않습니다**(자연 멱등). 키가 없는 등록은 지금처럼 멱등이 아닙니다 → 파일럿 등록은 연결이나 외부 ID를 반드시 함께 보냅니다 [결정].
- `POST /admin/creators`도 `externalIds[]`를 선택으로 받습니다.
- **구현(#40):** 위 규칙대로 구현했습니다. 등록 응답은 관리자 상세로 바뀌었습니다. 세부는 §3.3 구현 기록.

### 5.7 오류 코드 추가

| 코드 | 상태 | 의미 |
|---|---|---|
| `WORK_VERSION_CONFLICT` | 409 | 작품이 다른 곳에서 바뀜. 최신 상태를 다시 읽어야 함 |
| `CREATOR_VERSION_CONFLICT` | 409 | 작가가 다른 곳에서 바뀜 |
| `PLATFORM_VERSION_CONFLICT` | 409 | 플랫폼 정보가 다른 곳에서 바뀜 |
| `PLATFORM_LISTING_ALREADY_LINKED` | 409 | 같은 플랫폼 작품이 이미 다른(또는 같은) 작품에 연결됨 |
| `EXTERNAL_ID_ALREADY_LINKED` | 409 | 같은 출처의 같은 외부 ID가 이미 연결됨(#40). 충돌 직후 그 행이 `INVALID`가 되어 보이지 않으면 같은 409를 연결 ID 없이 반환합니다(#60). `Retry-After`는 없고 서버 장애(503)와 구분됩니다. 이 헤더가 재전송을 일으키지는 않습니다. 공통 클라이언트는 `INVALID_CSRF_TOKEN`일 때만 변경 요청을 한 번 다시 보냅니다. 실패한 변경·version·기록은 롤백됩니다 |
| `EXTERNAL_ID_NOT_FOUND` | 404 | 없는 외부 ID이거나 다른 작품·작가의 외부 ID(#40) |
| `WORK_LINK_ALREADY_EXISTS` | 409 | 같은 작품에 같은 URL의 공식 링크가 있음(#40) |
| `WORK_LINK_NOT_FOUND` | 404 | 없는 공식 링크이거나 다른 작품의 링크(#40) |
| `FIELD_SOURCE_NOT_FOUND` | 404 | 없는 근거이거나 다른 작품의 근거(#40) |
| `PLATFORM_NOT_FOUND` | 404 | 없는 플랫폼 코드 |
| `LISTING_NOT_FOUND` | 404 | 없는 연결이거나 다른 작품의 연결(#39에서 추가) |

- #39 구현: `PLATFORM_LISTING_ALREADY_LINKED` 응답의 `error`에 `existingWorkId`·`existingListingId`가 들어갑니다. `EXTERNAL_ID_ALREADY_LINKED`(#40)는 연결이 보이면 `existingWorkId` 또는 `existingCreatorId`와 `existingExternalIdId`를 담습니다. 충돌 직후 그 행이 `INVALID`가 되면 세 필드를 생략합니다(#60). 상태 코드는 409이고 `Retry-After`는 없습니다. `Retry-After`는 재전송을 일으키지 않습니다. 공통 클라이언트(`apiRequest`)는 `INVALID_CSRF_TOKEN`일 때만 변경 요청을 한 번 다시 보냅니다.

---

## 6. 공개 API와 화면

### 6.1 공개 상세 응답 추가

`GET /api/v1/works/{workId}`에 아래를 추가합니다(목록 응답은 그대로).

```json
"platformLinks": [
  { "platformCode": "KAKAO_PAGE", "platformName": "카카오페이지",
    "url": "https://page.kakao.com/content/50866481",
    "listingKind": "MAIN", "languageCode": "ko" }
],
"officialLinks": [ { "linkType": "OFFICIAL_SITE", "url": "...", "languageCode": "ja" } ],
"dataSources": ["WIKIDATA", "MADB"]
```

- `platformLinks`: `status = ACTIVE`이고 `id_scope ≠ UNKNOWN`인 연결만, 플랫폼 `CLOSED`는 제외. 정렬: `MAIN` → `TRANSLATION` → `SEASON` → `EDITION`, 같은 종류는 플랫폼 이름순.
- `dataSources`: 출처 표기용(Wikidata는 권장 표기, MADB는 CC BY 출처 표기 의무). 확인자·시각 등 관리 정보는 공개하지 않습니다.
- `HIDDEN` 작품: 공개 상세는 404 `WORK_NOT_FOUND`, 목록에서 제외. 내 별점·리뷰 API는 작품이 숨겨져도 내 기록을 지우지 않습니다(조회 정책은 §7).

### 6.2 화면 (A 디자인 유지)

- 작품 상세의 작품 정보 영역(줄거리 아래)에 **"보러 가기"** 목록: 플랫폼 이름 텍스트 링크(`rel="noopener noreferrer"`, 새 탭), 언어가 다르면 "(한국어판)" 같은 표시. 표지·플랫폼 로고 이미지는 쓰지 않습니다.
- 상세 하단에 출처 표기 한 줄: "작품 정보 출처: Wikidata, 文化庁「MADB Lab」(가공)". 실제로 쓴 출처만.
- 연결이 없으면 영역을 숨깁니다. 빈 상태 문구는 쓰지 않습니다.
- 키보드 포커스·대비는 기존 링크 규칙을 따릅니다.

### 6.3 관리자 화면 [선택 S-11]

- 기본안: 이번에는 관리자 웹 화면을 만들지 않고 API와 문서화된 요청 예(PowerShell)로 운영합니다. 파일럿은 15~30건이라 감당 가능합니다.
- 대안: 최소 관리자 화면 `/admin/works/{id}`(읽기 + 연결 추가·상태 변경 폼). 충돌 시 "다른 곳에서 바뀌었습니다. 최신 내용을 불러와 다시 적용해 주세요" 안내.

---

## 7. 별점·리뷰·내부 ID 보존

| 규칙 | 내용 |
|---|---|
| 삭제 없음 | 작품·작가 삭제 API를 만들지 않습니다. 잘못 만든 작품은 `HIDDEN` 또는 `duplicate_of`로 처리합니다 |
| ID 불변 | 수정·숨김·중복 표시·연결 이전 모두 `works.id`·`creators.id`를 바꾸지 않습니다. 제목·크레딧 행을 교체해도 별점·리뷰는 작품 ID에만 묶여 있어 영향이 없습니다 |
| 숨긴 작품의 별점·리뷰 | 행은 그대로 둡니다. 공개 목록·집계 화면에는 나오지 않습니다. 작성자 본인이 자기 기록을 조회·삭제하는 기능은 유지합니다(구현 이슈에서 엔드포인트 영향 확인) |
| 중복 표시 | 별점·리뷰를 옮기지 않습니다. 중복 작품 상세 URL로 들어오면 원본 작품으로 안내할지(308) 404로 둘지는 [선택 S-5] |
| 회귀 테스트 | 수정·숨김·중복 표시 전후로 별점 집계·리뷰 수·작품 ID가 같은지 통합 테스트로 고정 |

---

## 8. 중복 후보 검수 흐름 (자동 병합 없음)

1. **등록 전 조회**: 관리자 `GET /admin/works/duplicate-candidates?title=&creatorName=&platformCode=&platformWorkId=&source=&externalId=`
   - 1순위: 같은 연결 키·외부 ID → "이미 있음"(등록 시 409로도 막힘)
   - 2순위: 정규화 제목(NFKC·공백·기호 정리)이 같고 작가 이름 1명 이상 일치
   - 3순위: 정규화 제목만 같음 → "참고"(같은 작품이라는 뜻이 아님)
   - 응답은 후보 목록과 일치 근거만 줍니다. **서버는 합치거나 연결을 옮기지 않습니다.**
2. **검수자 판단**: 같은 작품이면 기존 작품에 연결 추가, 다른 작품이면 새 작품 등록(이유 기록), 번역판·개정판·시즌·리메이크는 조사 문서 §5.3 규칙.
3. **이미 중복 생성된 경우**: 나중 작품을 `duplicate_of` + `HIDDEN`, 연결은 원본 작품으로 `move`. 별점·리뷰 이전은 [후속] 병합 이슈.
4. 판단은 `admin_change_log`(action `MARK_DUPLICATE` 등)에 이유와 함께 남깁니다.

---

## 9. 테스트 계획

| 구분 | 내용 |
|---|---|
| 마이그레이션 | V6~V8 적용, 기존 행 기본값(version 0·PUBLIC), 제약 이름·CHECK·유니크, 10개 플랫폼 초기 행, V1~V5 미변경 |
| 버전 충돌 | 같은 작품 동시 PUT 2개 중 1개만 성공·나머지 409, 오래된 version 409·무변경, 같은 내용 PUT 멱등, 연결 추가와 PUT 경쟁 |
| 수정 검증 | 등록과 같은 입력 규칙, 대표 제목 정확히 1개, 크레딧 중복, 작품 ID 불변 |
| 연결 | 유니크 409, URL 패턴, CLOSED 플랫폼 거절, MERGING 경고, move 후 이전 연결 MOVED, `ageClass` 세 값 저장·`UNKNOWN`이 공개 응답에 일반으로 나오지 않음 |
| 공개 응답 | ACTIVE·scope 확정 연결만 노출, HIDDEN 작품 404·목록 제외, dataSources |
| 보존 | 수정·숨김·중복 표시 전후 별점 집계·리뷰 수 동일 |
| 보안 | 익명 401, USER 403, CSRF 누락 403, 관리 기록 actor |
| 화면 | 보러 가기 링크(새 탭·noopener), 출처 표기, 연결 없을 때 숨김, A 디자인·포커스, 기존 E2E 회귀 |

---

## 10. 소량 검증 계획 (파일럿)

> 2026-10-12 변경: 이 파일럿은 **수집·등록 흐름의 검증 단계이며 최종 완료 조건이 아닙니다.** 목표는 10개 플랫폼의 접근 가능한 공개 작품 전체입니다(수집 설계). 이전의 "전체 이용가 작품만·성인 제외" 규칙은 폐기했습니다. 표본에는 `GENERAL`·`ADULT`·`UNKNOWN`이 모두 들어가며, 성인 인증·로그인·유료 영역은 열지 않습니다. 실행은 #38~#40 구현 뒤입니다.

| 항목 | 내용 |
|---|---|
| 규모 | 작품 20개 안팎, 연결 약 30개 |
| 표본 | 10곳 각각에서 작품 2개씩 + 여러 플랫폼 동시 유통 5개 + 크레딧 복잡 4개 + 번역판 2개 + 시즌·판본 분리 2개(겹침 허용). 등급은 `GENERAL`·`ADULT`·`UNKNOWN`이 각각 포함되도록 고릅니다. 코미코는 공식 종료 확인 전이므로 접근 가능 여부부터 확인합니다 |
| 등급 기록 | 공개 페이지·목록에서 보이는 등급 표기를 `ageRatingRaw`에 그대로 기록하고 `ageClass`를 정합니다. 성인 인증 화면이 나오면 인증하지 않고, 목록에 보인 표기만 기록합니다. 표기가 없으면 `UNKNOWN` |
| 기록 순서 | ① 검토용 표(저장소 밖)에 플랫폼·작품 URL·작품 ID·ID 단위·제목·작가·역할·연령 표기·확인 시각·확인자 기록 → ② 검수 → ③ **개발 DB가 아닌 검수용 DB**에 관리자 API로 등록(연결·외부 ID와 함께) |
| 금지 | 403·CAPTCHA·로그인·성인 인증·유료 영역 우회 |
| 네이버웹툰 | 약관 URL·적용일(2026-09-18)과 작품 페이지 구조(예: 유미의 세포들 `titleId=651673`, 이동건 글/그림, 12세)는 사용자가 확보했습니다. 다른 플랫폼과 같은 순위로 진행합니다 |
| 측정 | 중복 판단이 갈린 수, 플랫폼 간 크레딧 불일치 유형, 플랫폼별 확인 불가 필드, ID 단위 확정 여부, URL 패턴 위반, 연결 1개 입력·검수 시간 |
| 통과 기준 | ① 모든 연결의 ID 단위 확정(UNKNOWN 0) ② 중복 판단이 §8 규칙으로 설명됨 ③ 크레딧 불일치 유형이 역할 대응 규칙에 반영됨 ④ 409·400이 의도대로 동작 ⑤ 별점·리뷰·작품 ID 보존 테스트 통과 |
| 결과 반영 | 플랫폼 `url_pattern`·`id_scope` 갱신, 조사 문서 §3.4.5 갱신. 이후 전체 수집(#45~#56)으로 진행합니다. 약관 위험 기록은 공개 표시 결정에 참고합니다 |

---

## 11. 구현 선택 사항

| 번호 | 선택 | 기본안(권장) | 대안 |
|---|---|---|---|
| S-1 | 동시 수정 방식 | 요청 본문 `version` + 조건부 UPDATE(리뷰와 같음) | JPA `@Version`, `If-Match`/ETag |
| S-2 | 작품 수정 단위 | 핵심 정보는 `PUT` 전체 교체, 연결·외부 ID·링크는 하위 리소스 | 필드별 `PATCH` |
| S-3 | 버전 범위 | 작품 하나에 버전 하나(하위 리소스 변경도 작품 버전 증가). **#39 연결 API에 적용** | 하위 리소스별 버전 |
| S-4 | 잘못 만든 작품 처리 | `HIDDEN`·`duplicate_of`(삭제 없음) | 별점·리뷰가 0일 때만 삭제 허용 |
| S-5 | 중복 작품 상세 URL | 404 | 원본 작품으로 308 안내 |
| S-6 | 필드 근거 | 등록·수정·연결에 근거 필수. **#40 구현(바뀐 제목·크레딧·줄거리·새 연결)** | 권장(선택 입력) |
| S-7 | 플랫폼 목록 | DB 코드 테이블 + 초기 10행. **#39 구현(코드·이름만 확정, 허용 호스트 별도 표)** | enum + CHECK |
| S-8 | 변경 이력 | 공통 `admin_change_log`(JSONB) + 플랫폼 상태 이력 전용 표. **#40 구현(추가 전용)** | 엔티티별 이력 표 |
| S-9 | 공개 응답 | 상세에만 `platformLinks`·`officialLinks`·`dataSources` | 목록에도 플랫폼 표시 |
| S-10 | ID 단위 미확정 연결 | 공개 화면 비표시 | 표시 + "확인 중" |
| S-11 | 관리자 화면 | 이번에는 API만 | 최소 관리자 화면 |
| S-12 | 성인 작품 | **변경(2026-10-12):** 세 등급 모두 등록·`ageClass` 기록, `ADULT`·`UNKNOWN`은 공개 표시 정책 결정 전 비공개 | (폐기) 파일럿 기간 등록 거절 |

---

## 12. 구현 이슈 (#37에서 분리)

| 순서 | 이슈 | 범위 | 선행 |
|---|---|---|---|
| 1 | #38 작품·작가 관리자 수정 API와 동시 수정 충돌 방지 | V6, §5.1·§5.2·§7, 오류 코드, 테스트 | — |
| 2 | #39 플랫폼 코드 표·상태 이력·작품 플랫폼 연결 | V7, §3.2·§4·§5.3·§5.5 | #38 |
| 3 | #40 외부 ID·공식 링크·필드 근거·관리 변경 기록과 등록 API 확장 | V8, §3.3·§5.4·§5.6 | #38(#39와 병행 가능) |
| 4 | #41 중복 후보 검수 흐름과 작품 숨김·중복 표시 | §8, `duplicate-candidates` | #38·#39·#40 |
| 5 | #42 작품 상세 플랫폼 링크·출처 표시 | §6.1·§6.2 | #39·#40 |
| 6 | #43 플랫폼 연결 소량 검증(완료 조건 아님. 전체 이용가 전용 규칙 폐기) | §10 | #38~#42 |
| 7 | #44 플랫폼 제휴·데이터 문의 (발송 보류) | 조사 문서 부록 A | 사용자 결정 D-7 |

전체 수집(범위 변경 2026-10-11)을 위한 이슈 #45~#56은 [work-collection-design.md](work-collection-design.md) §15에 있습니다. #43은 수집기 검증 단계로 바뀝니다.
