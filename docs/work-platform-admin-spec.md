# 작품·플랫폼 관리 구현 명세 (초안)

> **상태: 초안. 사용자 검토 대기 (#37).** #38로 §5.1·§5.2와 V6의 version 컬럼을 구현했습니다. V7·V8과 그 밖의 절은 후속 설계이며 이번 구현에 포함되지 않았습니다. 이 문서는 구현 이슈(§12)의 기준입니다.
> 기준 커밋: `1435e91`. 조사 근거는 [work-data-design-draft.md](work-data-design-draft.md)(§3.4 플랫폼 조사표, §3.4.4 판단 4구분, §3.4.5 ID 단위)를 따릅니다.
> **범위 변경 (2026-10-11):** 데이터 확보 범위가 10개 플랫폼 공개 작품 전체로 바뀌었습니다. 이 명세(V6~V8)는 전체 수집의 기반으로 그대로 쓰고, staging·수집 실행 기록(V9~V11)은 [work-collection-design.md](work-collection-design.md)에 있습니다. 반영할 변경: `work_platform_listings`의 `age_rating_note`를 `age_rating_raw`로 바꾸고 `age_class`(`GENERAL`·`ADULT`·`UNKNOWN`)를 추가합니다(수집 설계 §8.3). §10 파일럿은 완료 조건이 아니라 수집기 검증 단계입니다(수집 설계 §14).
> **#38 구현 (2026-10-12):** §5.1·§5.2의 작품·작가 관리자 조회·수정과 버전 충돌 방지를 구현했습니다. V6은 `works.version`·`creators.version`만 추가했고, `visibility`·`duplicate_of_work_id`와 숨김·중복 표시 API는 #41로 옮겼습니다(§3.1). `reason`·`evidence[]`와 변경 기록은 #40에서 추가합니다. 구현 세부는 §5.1 "구현 기록"을 보세요.
> **등급 규칙 변경 (2026-10-12, 사용자 지시):** 이전의 "전체 이용가 전용·성인 거절" 파일럿 규칙은 폐기했습니다. 18세 미만 등급은 `GENERAL`, 성인 등급은 `ADULT`, 표기가 없거나 확인하지 못하면 `UNKNOWN`이며, 원래 표기를 보존합니다. `UNKNOWN`은 일반으로 처리하지 않습니다(§5.3, §10, S-12).
> 적용된 마이그레이션 V1~V5는 고치지 않습니다. **이번 #38 구현의 마이그레이션은 V6의 `works.version`·`creators.version`뿐입니다.** V7(플랫폼·상태 이력·작품 연결)과 V8(외부 ID·공식 링크·필드 근거·관리 변경 기록)은 후속 설계이며 이번 구현에 포함되지 않았습니다.

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

### 3.2 V7: 플랫폼, 상태 이력, 작품 연결

```sql
CREATE TABLE platforms (
    code            VARCHAR(30)  PRIMARY KEY,         -- 'NAVER_WEBTOON' 등
    name            VARCHAR(50)  NOT NULL,            -- 표시 이름
    operator_name   VARCHAR(100),                     -- 확인한 운영사
    base_url        VARCHAR(200) NOT NULL,            -- https://...
    url_pattern     VARCHAR(300) NOT NULL,            -- 작품 URL 검증 정규식
    id_scope        VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN', -- 기본 ID 단위
    status          VARCHAR(20)  NOT NULL,            -- 'ACTIVE','MERGING','CLOSED'
    status_basis    VARCHAR(20)  NOT NULL,            -- 'OFFICIAL','PRESS','UNVERIFIED'
    merged_into     VARCHAR(30)  REFERENCES platforms (code),
    terms_checked_on DATE,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_platforms_status CHECK (status IN ('ACTIVE','MERGING','CLOSED')),
    CONSTRAINT ck_platforms_status_basis CHECK (status_basis IN ('OFFICIAL','PRESS','UNVERIFIED')),
    CONSTRAINT ck_platforms_id_scope CHECK (id_scope IN ('SERIES','VOLUME_OR_EPISODE','EDITION','UNKNOWN')),
    CONSTRAINT ck_platforms_merged CHECK (merged_into IS NULL OR merged_into <> code)
);

CREATE TABLE platform_status_history (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    platform_code   VARCHAR(30) NOT NULL REFERENCES platforms (code),
    from_status     VARCHAR(20),
    to_status       VARCHAR(20) NOT NULL,
    merged_into     VARCHAR(30) REFERENCES platforms (code),
    effective_on    DATE,                  -- 공지상 효력일(예: 2026-11-09)
    status_basis    VARCHAR(20) NOT NULL,  -- OFFICIAL / PRESS / UNVERIFIED
    evidence_url    VARCHAR(500),          -- 공지·보도 URL
    note            VARCHAR(500),
    changed_by      BIGINT NOT NULL REFERENCES users (id),
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE work_platform_listings (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    work_id           BIGINT       NOT NULL REFERENCES works (id),   -- CASCADE 없음(§7)
    platform_code     VARCHAR(30)  NOT NULL REFERENCES platforms (code),
    platform_work_id  VARCHAR(100) NOT NULL,   -- 플랫폼 정규 형식(숫자 ID, 슬러그, wt_… 등)
    id_scope          VARCHAR(20)  NOT NULL,   -- 이 연결의 ID 단위(플랫폼 기본값과 다를 수 있음)
    url               VARCHAR(500) NOT NULL,
    listing_kind      VARCHAR(20)  NOT NULL,   -- 'MAIN','SEASON','EDITION','TRANSLATION'
    language_code     VARCHAR(35)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,   -- 'ACTIVE','ENDED','MOVED','UNKNOWN'
    moved_to_listing_id BIGINT REFERENCES work_platform_listings (id),
    age_rating_raw    VARCHAR(50),             -- 확인한 표기 그대로(예: '전체이용가', '12세', '19세'). 2026-10-12 age_rating_note 에서 변경
    age_class         VARCHAR(10)  NOT NULL DEFAULT 'UNKNOWN', -- GENERAL(18세 미만)·ADULT(성인)·UNKNOWN(미확인, 일반으로 처리하지 않음)
    first_verified_at TIMESTAMPTZ  NOT NULL,
    last_verified_at  TIMESTAMPTZ  NOT NULL,
    verified_by       BIGINT       NOT NULL REFERENCES users (id),
    note              VARCHAR(500),
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_listings_platform_work UNIQUE (platform_code, platform_work_id),
    CONSTRAINT ck_listings_kind CHECK (listing_kind IN ('MAIN','SEASON','EDITION','TRANSLATION')),
    CONSTRAINT ck_listings_status CHECK (status IN ('ACTIVE','ENDED','MOVED','UNKNOWN')),
    CONSTRAINT ck_listings_age_class CHECK (age_class IN ('GENERAL','ADULT','UNKNOWN')),
    CONSTRAINT ck_listings_scope CHECK (id_scope IN ('SERIES','VOLUME_OR_EPISODE','EDITION','UNKNOWN')),
    CONSTRAINT ck_listings_moved CHECK ((status = 'MOVED') = (moved_to_listing_id IS NOT NULL)),
    CONSTRAINT ck_listings_verified CHECK (last_verified_at >= first_verified_at)
);
CREATE INDEX idx_listings_work ON work_platform_listings (work_id);
```

- **초기 데이터(V7에 포함)** [결정]: 10개 플랫폼 행. 값은 조사 문서 §3.4 기준입니다.

| code | name | status (basis) | id_scope | url_pattern(요지) |
|---|---|---|---|---|
| NAVER_WEBTOON | 네이버웹툰 | ACTIVE (OFFICIAL: 공식 회사 사이트) | SERIES (형식만 관찰, 파일럿에서 확정) | `^https://(m\.)?comic\.naver\.com/webtoon/list\?titleId=\d+$` |
| KAKAO_PAGE | 카카오페이지 | ACTIVE (OFFICIAL) | SERIES | `^https://page\.kakao\.com/content/\d+/?$` |
| KAKAO_WEBTOON | 카카오웹툰 | MERGING (OFFICIAL, effective 2026-11-09, merged_into KAKAO_PAGE) | SERIES | `^https://webtoon\.kakao\.com/content/[^/]+/\d+$` |
| LEZHIN | 레진코믹스 | ACTIVE (OFFICIAL) | SERIES | `^https://www\.lezhin\.com/ko/comic/[A-Za-z0-9_-]+$` |
| RIDI | 리디 | ACTIVE (OFFICIAL) | VOLUME_OR_EPISODE | `^https://ridibooks\.com/books/\d+$` |
| BOMTOON | 봄툰 | ACTIVE (OFFICIAL) | UNKNOWN | 파일럿에서 확정 전까지 `^https://www\.bomtoon\.com/` 접두만 |
| TOPTOON | 탑툰 | ACTIVE (OFFICIAL) | SERIES | `^https://toptoon\.com/(comic\|complete)/ep_list/[A-Za-z0-9_-]+$` |
| TOOMICS | 투믹스 | ACTIVE (OFFICIAL) | UNKNOWN | 파일럿에서 확정 전까지 `^https://(www\.)?toomics\.com/` 접두만 |
| COMICO_KR | 코미코 | CLOSED (PRESS) | UNKNOWN | 신규 연결 금지(§5.3) |
| MRBLUE | 미스터블루 | ACTIVE (OFFICIAL) | SERIES | `^https://(www\|m)\.mrblue\.com/webtoon/detail/wt_\d{9}$` |

- 정규식은 저장하기 전에 URL을 정규화(소문자 호스트, 끝 슬래시·추적 쿼리 제거)한 값에 적용합니다. 패턴이 접두만 있는 플랫폼은 파일럿(§10) 뒤 관리자 API로 좁힙니다.

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
| 이번에 넣지 않은 것 | `reason`·`evidence[]`·변경 기록(#40), 공개 상태·중복 표시(#41) |

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
| POST | `/admin/works/{workId}/listings` | `{version, platformCode, platformWorkId, url, idScope, listingKind, languageCode, ageRatingRaw, ageClass, verifiedAt, note, evidence[]}` |
| PATCH | `/admin/works/{workId}/listings/{listingId}` | `{version, status, movedToListingId?, lastVerifiedAt?, idScope?, note?, reason}` |
| GET | `/admin/listings/lookup?platformCode=&platformWorkId=` | 이미 연결된 작품이 있는지 확인(중복 검수용) |

- `UNIQUE(platform_code, platform_work_id)` 위반은 **409 `PLATFORM_LISTING_ALREADY_LINKED`**(관리자 응답에만 기존 작품 ID 포함). 다른 작품에 이미 붙은 플랫폼 작품을 새 작품으로 만들지 못하게 합니다.
- URL이 플랫폼 `url_pattern`에 맞지 않으면 400 `INVALID_INPUT`(`fieldErrors: url`).
- `CLOSED` 플랫폼에는 새 연결을 만들 수 없습니다(400). `MERGING` 플랫폼은 허용하되 응답에 경고 필드를 넣습니다.
- 등급(2026-10-12 변경): 연결에 `ageRatingRaw`(원래 표기)와 `ageClass`를 기록합니다. 18세 미만 등급(전체·7·12·15세)은 `GENERAL`, 성인 등급(18·19세, "청소년 이용불가", 성인 라벨)은 `ADULT`, 표기가 없거나 확인하지 못하면 `UNKNOWN`입니다. **세 값 모두 등록할 수 있습니다.** `UNKNOWN`은 `GENERAL`로 자동 처리하지 않습니다. 공개 화면에 `ADULT`·`UNKNOWN`을 어떻게 보일지는 공개 표시 정책(수집 설계 §6.3)에서 정하며, 그 전에는 공개하지 않습니다. 이전의 "파일럿 기간 성인 거절"은 폐기했습니다.
- 연결 이전(플랫폼 통합·슬러그 변경): 새 연결을 만든 뒤 이전 연결을 `MOVED`(+`movedToListingId`)로 바꿉니다. 한 요청으로 처리하는 `POST /admin/works/{workId}/listings/{listingId}/move`(`{version, newListing{...}}`)를 둡니다.

### 5.4 외부 ID·공식 링크·근거

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST·PATCH | `/admin/works/{workId}/external-ids[/{id}]` | 추가·상태 변경. 중복은 409 **`EXTERNAL_ID_ALREADY_LINKED`** |
| POST·PATCH | `/admin/works/{workId}/links[/{id}]` | 출판사·공식 사이트 링크 추가·상태 변경 |
| POST | `/admin/works/{workId}/field-sources` | 근거 추가(값 변경 없이 근거만 보강할 때) |

- 근거 필수 규칙 [결정]: 작품 등록·`PUT`·연결 추가 요청은 바뀌는 필드마다 `evidence` 하나 이상이 있어야 합니다(`TITLE`·`CREDIT`·`LISTING`). 없으면 400. 설명(`DESCRIPTION`)은 `STAFF`(직접 작성) 또는 `LICENSED`만 허용합니다.

### 5.5 플랫폼 관리

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/admin/platforms` | 10곳과 상태·패턴·약관 확인일 |
| PATCH | `/admin/platforms/{code}` | `{version, name?, operatorName?, urlPattern?, idScope?, termsCheckedOn?, reason}` |
| POST | `/admin/platforms/{code}/status` | `{version, toStatus, mergedInto?, effectiveOn, statusBasis, evidenceUrl, note}` → `platform_status_history`에 기록 |

- 상태를 `CLOSED`로 바꾸면 그 플랫폼의 `ACTIVE` 연결 목록을 응답에 넣습니다. 연결 상태는 자동으로 바꾸지 않고(이관처가 다를 수 있음), 관리자가 연결마다 `ENDED`·`MOVED`로 처리합니다 [결정].
- 카카오웹툰: 현재 `MERGING`(effective 2026-11-09, OFFICIAL). 통합 뒤 연결마다 카카오페이지 연결을 찾아 `move`합니다. 카카오페이지에 같은 작품 연결이 이미 있으면 기존 연결을 `MOVED`로만 표시합니다.
- 코미코: `CLOSED (PRESS)`. 공식 공지를 찾으면 `statusBasis = OFFICIAL`로 이력을 추가합니다.

### 5.6 등록 API 최소 확장

- `POST /admin/works` 요청에 선택 필드 `listings[]`, `externalIds[]`, `links[]`, `evidence[]`를 추가합니다. 한 트랜잭션에서 작품과 함께 저장하고, 응답에 `version`을 넣습니다.
- 등록 요청에 `listings` 또는 `externalIds`가 있으면, 같은 키가 이미 다른 작품에 있을 때 409로 거절되므로 **재시도해도 같은 작품이 두 번 만들어지지 않습니다**(자연 멱등). 키가 없는 등록은 지금처럼 멱등이 아닙니다 → 파일럿 등록은 연결이나 외부 ID를 반드시 함께 보냅니다 [결정].
- `POST /admin/creators`도 `externalIds[]`를 선택으로 받습니다.

### 5.7 오류 코드 추가

| 코드 | 상태 | 의미 |
|---|---|---|
| `WORK_VERSION_CONFLICT` | 409 | 작품이 다른 곳에서 바뀜. 최신 상태를 다시 읽어야 함 |
| `CREATOR_VERSION_CONFLICT` | 409 | 작가가 다른 곳에서 바뀜 |
| `PLATFORM_VERSION_CONFLICT` | 409 | 플랫폼 정보가 다른 곳에서 바뀜 |
| `PLATFORM_LISTING_ALREADY_LINKED` | 409 | 같은 플랫폼 작품이 이미 다른(또는 같은) 작품에 연결됨 |
| `EXTERNAL_ID_ALREADY_LINKED` | 409 | 같은 외부 ID가 이미 연결됨 |
| `PLATFORM_NOT_FOUND` | 404 | 없는 플랫폼 코드 |

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
| S-3 | 버전 범위 | 작품 하나에 버전 하나(하위 리소스 변경도 작품 버전 증가) | 하위 리소스별 버전 |
| S-4 | 잘못 만든 작품 처리 | `HIDDEN`·`duplicate_of`(삭제 없음) | 별점·리뷰가 0일 때만 삭제 허용 |
| S-5 | 중복 작품 상세 URL | 404 | 원본 작품으로 308 안내 |
| S-6 | 필드 근거 | 등록·수정·연결에 근거 필수 | 권장(선택 입력) |
| S-7 | 플랫폼 목록 | DB 코드 테이블 + 초기 10행 | enum + CHECK |
| S-8 | 변경 이력 | 공통 `admin_change_log`(JSONB) + 플랫폼 상태 이력 전용 표 | 엔티티별 이력 표 |
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
