# Work · Creator 구현 명세 (Phase 2-1)

작품(Work), 작품 제목(WorkTitle), 작가(Creator), 작품-작가 연결(WorkCreator)의 **등록·조회** 기능 명세입니다.
이 문서만으로 구현·리뷰할 수 있도록 확정된 정책과 구현 기준을 모두 기술합니다.

- 범위: 등록, 단건 조회, 목록 조회
- 제외: 수정, 삭제, soft delete, 연재 상태, 연재 연도, 표지, 장르, 플랫폼, 검색, 인증

---

## 1. 패키지 구조

```
com.manhwanet
├── creator
│   ├── api          CreatorController, CreatorAdminController, CreateCreatorRequest, CreatorResponse
│   ├── application  CreatorService, CreatorSummary
│   └── domain       Creator, CreatorRepository
├── work
│   ├── api          WorkController, WorkAdminController, 요청·응답 DTO
│   ├── application  WorkService
│   └── domain       Work, WorkTitle, WorkCreator, WorkFormat, CreatorRole,
│                    LanguageTags, CountryCodes, Repository 3종
└── global
    ├── config       ConditionalOnAdminApiEnabled, JpaAuditingConfig
    ├── error        InvalidInputException, ConstraintViolations, ErrorCode 추가
    ├── jpa          BaseTimeEntity
    ├── pagination   PageableFactory
    └── response     PageResponse
```

- `work`는 `creator`의 Repository·Entity를 참조하지 않습니다. 작가 존재 확인과 이름 조회는 `CreatorService`(공개 API)를 통해서만 합니다.
- 반대 방향(`creator` → `work`) 의존은 없습니다.

## 2. 데이터 모델

### 2.1 관계

```
creators 1 ──< work_creators >── 1 works 1 ──< work_titles
```

- Work 1 : N WorkTitle (작품의 모든 제목. 대표 제목 포함)
- Work N : M Creator, 역할(role)을 가진 연결 테이블 `work_creators`
- `WorkCreator`는 JPA 연관관계가 아니라 **`creatorId`(Long) 값으로 작가를 참조**합니다. DB에는 `creators(id)` 외래 키가 있어 존재하지 않는 작가 연결과 사용 중인 작가 삭제를 막습니다.

### 2.2 테이블 (V2 마이그레이션)

**creators**

| 컬럼 | 타입 | NULL | 제약 |
|---|---|---|---|
| id | BIGINT identity | N | PK |
| name | VARCHAR(100) | N | `ck_creators_name_not_blank` |
| original_name | VARCHAR(100) | Y | `ck_creators_original_name_not_blank` (NULL 또는 공백 아님) |
| created_at | TIMESTAMPTZ | N | DEFAULT now() |
| updated_at | TIMESTAMPTZ | N | DEFAULT now() |

- 인덱스: `idx_creators_created_at_id (created_at DESC, id DESC)`
- `name`은 유니크가 아닙니다. 동명이인을 허용합니다.

**works**

| 컬럼 | 타입 | NULL | 제약 |
|---|---|---|---|
| id | BIGINT identity | N | PK |
| work_format | VARCHAR(20) | N | `ck_works_work_format`: 'WEBTOON', 'PAGE_COMIC' |
| origin_country | VARCHAR(2) | Y | `ck_works_origin_country`: NULL 또는 `^[A-Z]{2}$` |
| description | TEXT | Y | 줄거리 |
| created_at / updated_at | TIMESTAMPTZ | N | DEFAULT now() |

- 인덱스: `idx_works_created_at_id (created_at DESC, id DESC)` (정순·역순 정렬 모두에 사용)

**work_titles**

| 컬럼 | 타입 | NULL | 제약 |
|---|---|---|---|
| id | BIGINT identity | N | PK |
| work_id | BIGINT | N | `fk_work_titles_work` → works(id) ON DELETE CASCADE |
| title | VARCHAR(255) | N | `ck_work_titles_title_not_blank` |
| language_code | VARCHAR(35) | N | `ck_work_titles_language_code_not_blank` |
| is_primary | BOOLEAN | N | DEFAULT FALSE |

- `uk_work_titles_work_language_title UNIQUE (work_id, language_code, title)`: 같은 작품의 같은 언어·같은 제목 중복 금지
- `uk_work_titles_primary_per_work`: `UNIQUE (work_id) WHERE is_primary` 부분 유니크 인덱스
  - 이 인덱스는 대표 제목을 **최대 1개**로만 제한합니다.
  - "정확히 1개"는 등록 요청 검증(§4)과 도메인 로직(`Work.create`)이 보장합니다.
  - 목록 조회에서 대표 제목을 일괄 조회할 때도 이 인덱스를 사용합니다.

**work_creators**

| 컬럼 | 타입 | NULL | 제약 |
|---|---|---|---|
| id | BIGINT identity | N | PK |
| work_id | BIGINT | N | `fk_work_creators_work` → works(id) ON DELETE CASCADE |
| creator_id | BIGINT | N | `fk_work_creators_creator` → creators(id) ON DELETE RESTRICT |
| role | VARCHAR(20) | N | `ck_work_creators_role`: 'STORY', 'ART', 'ORIGINAL_STORY' |

- `uk_work_creators_work_creator_role UNIQUE (work_id, creator_id, role)`
- 인덱스: `idx_work_creators_creator_id (creator_id)` (작가 기준 조회와 FK 검사용)

### 2.3 DB 제약과 애플리케이션 검증의 역할 분담

- DB의 `btrim(...) <> ''` 검사는 U+0020 공백만 제거하므로 **최후의 안전장치**입니다.
- 전각 공백(U+3000) 등 유니코드 공백 처리, 언어 태그 정규화, 대문자 국가 코드 정규화는 애플리케이션이 책임집니다.
- DB에는 애플리케이션이 정규화한 값만 저장된다고 가정합니다.

## 3. 필드 규칙

### 3.1 Work

| 필드 | 필수 | 규칙 |
|---|---|---|
| workFormat | ✔ | `WEBTOON`(세로 스크롤 웹툰) 또는 `PAGE_COMIC`(페이지 단위 만화). 대소문자 정확히 일치 |
| originCountry | | ISO 3166-1 alpha-2 국가 코드. 앞뒤 공백 제거 → 영문 2자 형식 검사 → 대문자 변환 → Java가 제공하는 ISO 3166-1 alpha-2 목록(`Locale.getISOCountries`)에 있는지 확인. 생략(null)하면 저장하지 않음. 빈 문자열이나 공백만 있는 값은 400 |
| description | | 앞뒤 공백 제거 후 최대 5,000자(코드 포인트 기준). 공백만 있는 값은 400. 생략 가능 |
| titles | ✔ | 1~20개. 대표 제목(`primary: true`) 정확히 1개 |
| creators | ✔ | 1~20개 작가 연결 |

### 3.2 WorkTitle

| 필드 | 필수 | 규칙 |
|---|---|---|
| title | ✔ | 앞뒤 공백 제거(`String.strip()`, 유니코드 공백 포함). 제거 후 1~255자(코드 포인트 기준) |
| languageCode | ✔ | BCP 47 언어 태그. §3.3 |
| primary | | 대표 제목 여부. 생략하면 false |

중복 규칙:
- 같은 작품 안에서 **정규화된 languageCode + 공백 제거된 title**이 같으면 중복으로 거부합니다.
- 비교는 대소문자와 유니코드 정규형(NFC/NFD)을 구분하는 정확한 문자열 비교입니다.
- 언어가 다르면 같은 제목도 허용합니다.
- 다른 작품과 제목이 같아도 허용합니다.

정렬(상세 응답): 대표 제목이 먼저, 나머지는 등록 순서(id 오름차순).

### 3.3 languageCode (BCP 47)

검증·정규화 절차:
1. 앞뒤 공백을 제거합니다.
2. Java `Locale.Builder#setLanguageTag`로 파싱합니다. RFC 5646 문법에 맞지 않으면(예: `en_US`, `12`) 400입니다.
3. 기본 언어 subtag가 없는 태그는 400입니다. `und`(미정), `x-...`(사설 전용 태그)가 여기에 해당합니다.
4. `Locale#toLanguageTag()` 결과를 저장합니다.
   - 대소문자 표준화: `JA` → `ja`, `ko-kr` → `ko-KR`, `ja-latn` → `ja-Latn`, `zh-hant-tw` → `zh-Hant-TW`
   - Java가 수행하는 정규화도 함께 적용됩니다: 확장 언어 태그 `zh-yue-HK` → `yue-HK`, 구 코드 `iw` → `he`, grandfathered 태그 `i-klingon` → `tlh`
5. 정규화 전후 길이가 35자를 넘으면 400입니다.

지원 범위와 저장 길이:
- 형식 검증(well-formed)만 수행하며, subtag가 IANA 레지스트리에 실제 등록되어 있는지(valid)는 검사하지 않습니다.
- 지원 구성: 언어(2~8자) + 선택적 스크립트(4자) + 지역(영문 2자 또는 숫자 3자) + 변형(variant) + 확장·사설 subtag
- 저장 컬럼은 `VARCHAR(35)`입니다. RFC 5646 §4.4.1이 언어 태그 저장 공간으로 최소 35자 지원을 권고하는 데 따른 것입니다.
- 확장·사설 subtag 때문에 35자를 넘는 태그는 잘라서 저장하지 않고 400으로 거부합니다.

### 3.4 Creator

| 필드 | 필수 | 규칙 |
|---|---|---|
| name | ✔ | 앞뒤 공백 제거 후 1~100자. 대표 표기 |
| originalName | | 원어 표기. 앞뒤 공백 제거 후 1~100자. 생략(null) 가능, 공백만 있는 값은 400 |

동명이인을 허용하므로 이름이 같아도 각각 별도 작가로 등록됩니다.

### 3.5 WorkCreator (크레딧)

| 필드 | 필수 | 규칙 |
|---|---|---|
| creatorId | ✔ | 양수. 존재하는 작가여야 함 |
| role | ✔ | `STORY`(글), `ART`(그림), `ORIGINAL_STORY`(원작) |

- 같은 사람이 여러 역할을 맡으면 **역할별로 행을 따로** 저장합니다. 예: 글·그림 동일인은 STORY 행과 ART 행
- 한 요청 안에서 같은 (creatorId, role)이 반복되면 400입니다.
- 크레딧 표시 순서: **STORY → ART → ORIGINAL_STORY**, 같은 역할 안에서는 **creatorId 오름차순**
  - 순서는 `CreatorRole` enum 선언 순서로 정의됩니다. enum 순서를 바꾸면 표시 순서가 바뀝니다.

## 4. 등록 검증 순서와 오류

1. **Bean Validation(요청 DTO)**: 필수값, 목록 크기, enum 문자열 형식, creatorId 양수
   - 실패하면 서비스 로직을 실행하지 않고 400을 반환합니다.
2. **서비스 검증(`WorkService`)**: 정규화가 필요한 규칙
   - 공백 제거 후 길이, 언어 태그, 국가 코드, 대표 제목 개수, 제목 중복, 크레딧 중복, 작가 존재 여부
   - 모든 오류를 모아 한 번에 400(`INVALID_INPUT`, `fieldErrors`)으로 반환합니다.
3. **도메인 불변식(`Work.create`)**: 제목 1개 이상, 대표 제목 정확히 1개, 크레딧 1개 이상, 중복 없음
   - 2단계를 통과한 값은 항상 만족하므로, 여기서 위반이 나면 프로그래밍 오류로 보고 500으로 처리합니다.
4. **DB 제약**: 최후 방어선(§6.3)

fieldErrors의 field 경로 예: `workFormat`, `titles`, `titles[1].languageCode`, `creators[2].creatorId`

## 5. API

공통: prefix `/api/v1`, 응답은 `ApiResponse` 형식(`{success, data}` / `{success, error}`)입니다. 시각은 UTC ISO-8601 문자열(최대 마이크로초 정밀도)이고, 값이 없는 선택 필드(`originCountry`, `description`, `originalName`)는 `null`로 응답합니다.

| 메서드 | 경로 | 설명 | 성공 |
|---|---|---|---|
| POST | `/api/v1/admin/creators` | 작가 등록 | 201 + `Location: /api/v1/creators/{id}` |
| GET | `/api/v1/creators/{creatorId}` | 작가 단건 조회 | 200 |
| GET | `/api/v1/creators` | 작가 목록 (페이지) | 200 |
| POST | `/api/v1/admin/works` | 작품 등록 | 201 + `Location: /api/v1/works/{id}` |
| GET | `/api/v1/works/{workId}` | 작품 상세 조회 | 200 |
| GET | `/api/v1/works` | 작품 목록 (페이지) | 200 |

### 5.1 작가 등록

```http
POST /api/v1/admin/creators
Content-Type: application/json

{ "name": "  한서윤 ", "originalName": null }
```
```json
HTTP/1.1 201 Created
Location: http://localhost:8080/api/v1/creators/12

{ "success": true,
  "data": { "id": 12, "name": "한서윤", "originalName": null,
            "createdAt": "2026-10-06T03:00:00.123456Z", "updatedAt": "2026-10-06T03:00:00.123456Z" } }
```

### 5.2 작품 등록

```http
POST /api/v1/admin/works
Content-Type: application/json

{
  "workFormat": "WEBTOON",
  "originCountry": "kr",
  "description": "몰락한 기사단의 마지막 견습생 이야기",
  "titles": [
    { "title": " 달빛 기사단 ", "languageCode": "ko", "primary": true },
    { "title": "Moonlight Knights", "languageCode": "EN" },
    { "title": "月光騎士団", "languageCode": "ja" }
  ],
  "creators": [
    { "creatorId": 15, "role": "ORIGINAL_STORY" },
    { "creatorId": 12, "role": "ART" },
    { "creatorId": 12, "role": "STORY" }
  ]
}
```
```json
HTTP/1.1 201 Created
Location: http://localhost:8080/api/v1/works/101

{ "success": true,
  "data": {
    "id": 101,
    "workFormat": "WEBTOON",
    "originCountry": "KR",
    "description": "몰락한 기사단의 마지막 견습생 이야기",
    "primaryTitle": { "title": "달빛 기사단", "languageCode": "ko" },
    "titles": [
      { "title": "달빛 기사단", "languageCode": "ko", "primary": true },
      { "title": "Moonlight Knights", "languageCode": "en", "primary": false },
      { "title": "月光騎士団", "languageCode": "ja", "primary": false }
    ],
    "creators": [
      { "creatorId": 12, "name": "한서윤", "role": "STORY" },
      { "creatorId": 12, "name": "한서윤", "role": "ART" },
      { "creatorId": 15, "name": "윤도현", "role": "ORIGINAL_STORY" }
    ],
    "createdAt": "2026-10-06T03:00:01.000001Z",
    "updatedAt": "2026-10-06T03:00:01.000001Z" } }
```

`GET /api/v1/works/{workId}`도 같은 `data` 형식으로 응답합니다.

### 5.3 목록 조회

```http
GET /api/v1/works?page=0&size=20&sort=createdAt,desc
```
```json
{ "success": true,
  "data": {
    "content": [
      { "id": 101, "workFormat": "WEBTOON", "originCountry": "KR",
        "primaryTitle": { "title": "달빛 기사단", "languageCode": "ko" },
        "creators": [ { "creatorId": 12, "name": "한서윤", "role": "STORY" },
                      { "creatorId": 12, "name": "한서윤", "role": "ART" },
                      { "creatorId": 15, "name": "윤도현", "role": "ORIGINAL_STORY" } ],
        "createdAt": "2026-10-06T03:00:01.000001Z" } ],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false } }
```

`GET /api/v1/creators`의 `content` 항목은 작가 단건 응답과 같은 형식입니다.

### 5.4 오류 응답

| 상황 | 상태 | error.code | fieldErrors 예 |
|---|---|---|---|
| 필수값 누락, 형식 오류, 길이 초과 | 400 | INVALID_INPUT | `titles[0].title` |
| 대표 제목 0개 또는 2개 이상 | 400 | INVALID_INPUT | `titles` |
| 같은 작품 내 동일 언어·동일 제목 | 400 | INVALID_INPUT | `titles[2].title` |
| 요청 내 같은 (creatorId, role) 반복 | 400 | INVALID_INPUT | `creators[1]` |
| 존재하지 않는 creatorId | 400 | INVALID_INPUT | `creators[0].creatorId` |
| 잘못된 page / size / sort | 400 | INVALID_INPUT | `page`, `size`, `sort` |
| 없는 작품·작가 단건 조회 | 404 | WORK_NOT_FOUND / CREATOR_NOT_FOUND | - |
| 비활성 상태의 등록 API 호출 | 404 | RESOURCE_NOT_FOUND | - |
| 예상한 유니크 제약 위반(§6.3) | 409 | DUPLICATE_RESOURCE | - |

```json
{ "success": false,
  "error": { "code": "INVALID_INPUT", "message": "입력값이 올바르지 않습니다.",
             "fieldErrors": [ { "field": "creators[0].creatorId", "reason": "존재하지 않는 작가입니다." } ] } }
```

## 6. 구현 기준

### 6.1 등록 API 노출 조건 (인증 구현 전)

등록 Controller(`WorkAdminController`, `CreatorAdminController`)에는 `@ConditionalOnAdminApiEnabled`가 붙어 있습니다. 아래 **두 조건이 모두** 충족될 때만 Bean으로 등록됩니다.

1. `local` 프로필이 활성화되어 있음
2. `manhwanet.admin-api.enabled=true` (기본값 false, `application.yml`에 명시)

조건이 맞지 않으면 해당 경로에 Handler가 없으므로 404 `RESOURCE_NOT_FOUND`가 반환됩니다. 조회 API는 항상 등록됩니다.

- `local` 프로필은 `server.address=127.0.0.1`로 서버를 loopback 주소에만 바인딩해 외부 네트워크에서 접속할 수 없게 합니다.
- 로컬에서 켜는 방법: OS 환경변수 `MANHWANET_ADMIN_API_ENABLED=true`를 설정하고 실행합니다.
  - 루트 `.env`는 Docker Compose와 공유하는 파일이고 Spring 속성 이름이 Compose 변수 이름과 다르므로, 이 플래그는 `.env`에 넣지 않습니다.
- 테스트에서는 등록 API 통합 테스트 클래스만 `@SpringBootTest(properties = "manhwanet.admin-api.enabled=true")`와 `local`, `test` 프로필로 명시적으로 켭니다.

> ⚠️ **이 장치는 관리자 인증을 대신하지 않습니다.** 같은 PC의 누구나(브라우저의 다른 사이트에서 보낸 요청 포함) loopback 주소로 등록 API를 호출할 수 있습니다. 운영·공유 환경에서 등록 API를 활성화해서는 안 되며, auth 도메인을 구현하면 `/api/v1/admin/**`에 관리자 인증·인가를 적용하고 이 조건부 등록을 대체합니다.

### 6.2 페이지네이션과 정렬

| 파라미터 | 기본값 | 허용 범위 | 위반 시 |
|---|---|---|---|
| page | 0 | 0 이상 정수 (0부터 시작) | 400 (`page`) |
| size | 20 | 1~100 정수 | 400 (`size`) |
| sort | createdAt DESC, id DESC | `속성` 또는 `속성,방향` 하나 | 400 (`sort`) |

- Spring Data의 `Pageable` 자동 바인딩은 범위를 벗어난 값을 조용히 보정하므로 사용하지 않습니다. Controller가 `page`, `size`, `sort`를 직접 받아 요청 경계에서 검증하고(`@Min`, `@Max`), `PageableFactory`가 정렬을 검증해 `Pageable`로 변환합니다. `PageableFactory`도 page·size 범위를 다시 확인합니다.
- 정렬 속성은 `createdAt`, `id`만 허용합니다. 방향은 `asc`, `desc`(대소문자 무시)이며, 생략하면 `asc`입니다(Spring Data 관례).
- `createdAt`으로 정렬하면 같은 방향의 `id`를 보조 기준으로 추가합니다. 예: `sort=createdAt,asc` → `createdAt ASC, id ASC`. 생성 시각이 같아도 순서가 항상 같습니다.
- `sort` 파라미터를 여러 번 보내거나 형식이 다르면 400입니다. 여러 값은 쉼표로 합쳐져 토큰이 3개 이상이 되기 때문입니다.
- 허용 목록 밖의 정렬은 Repository에 도달하기 전에 거부합니다. 내부의 `PropertyReferenceException`은 사용자 오류로 바꾸지 않고, 프로그래밍 오류로 남겨 둡니다(500).

응답 `PageResponse<T>`: `content`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`

### 6.3 중복 제약 위반 → 409

- `WorkService`는 `saveAndFlush`에서 발생한 `DataIntegrityViolationException`의 원인 체인에서 Hibernate가 추출한 제약 이름을 확인합니다.
  - `uk_work_titles_work_language_title`, `uk_work_titles_primary_per_work`, `uk_work_creators_work_creator_role` 중 하나면 `BusinessException(DUPLICATE_RESOURCE)`(409)로 바꿉니다.
  - 그 밖의 무결성 오류(FK, CHECK, 알 수 없는 제약)는 그대로 다시 던집니다. 기존 정책대로 `GlobalExceptionHandler`가 500으로 응답하고 로그를 남깁니다.
- `DataIntegrityViolationException` 전체를 409로 바꾸지 않습니다.
- 현재 등록 흐름에서는 서비스 검증이 먼저 막으므로 이 경로는 방어용입니다.

### 6.4 생성·수정 시각

- `BaseTimeEntity`(`@MappedSuperclass`)와 Spring Data JPA Auditing(`@CreatedDate`, `@LastModifiedDate`)이 `works`, `creators`의 `created_at`, `updated_at`을 채웁니다.
  - 적용 대상은 이 두 테이블뿐이며, `work_titles`와 `work_creators`에는 시각 컬럼이 없습니다.
  - 시각은 PostgreSQL 정밀도에 맞춰 마이크로초로 자릅니다(`auditingDateTimeProvider`). 그래서 등록 직후 응답과 이후 조회 결과가 같습니다.
- **JPA Auditing은 JPA를 통해 엔티티를 저장·수정할 때만 동작합니다.** SQL로 DB를 직접 수정하면 `updated_at`이 자동 갱신되지 않습니다. DB 트리거는 두지 않았습니다.
  - 컬럼의 `DEFAULT now()`는 SQL로 직접 INSERT할 때만 적용됩니다.
  - 이번 범위에는 수정 API가 없어서 등록 시점에 `updated_at`과 `created_at`이 같습니다.

### 6.5 조회 쿼리와 N+1 방지

- **상세 조회**: 쿼리 3개로 고정
  1. 작품 + 제목 fetch join (컬렉션 1개)
  2. 크레딧(지연 로딩 1회)
  3. `CreatorService.getSummaries(ids)`로 작가 일괄 조회
- **목록 조회**: 컬렉션 fetch join과 페이징을 결합하지 않습니다.
  1. 작품 페이지 조회(+ 필요할 때 count 쿼리)
  2. 페이지의 작품 ID로 대표 제목 일괄 조회(`IN`, 부분 유니크 인덱스 사용)
  3. 크레딧 일괄 조회(`IN`)
  4. 작가 일괄 조회(`IN`)
  - 페이지 크기와 작가 수에 관계없이 쿼리 수가 일정합니다. 페이지가 비어 있으면 2~4단계를 생략합니다.
- 작가 목록 조회는 단일 테이블 페이지 조회입니다.

## 7. 테스트 계획

| 종류 | 대상 | 검증 |
|---|---|---|
| 마이그레이션 | `WorkCreatorMigrationTest` | V2 적용, 제약·인덱스 이름, 대표 제목 2개·제목 중복·크레딧 중복·잘못된 형식·소문자 국가 코드·사용 중인 작가 삭제의 DB 거부 |
| 단위 | `LanguageTagsTest`, `CountryCodesTest`, `PageableFactoryTest`, `WorkTest` | 정규화·검증, 정렬 파싱, 도메인 불변식 |
| 단위(Mockito) | `WorkServiceTest` | 예상한 제약 이름만 409로 변환, 나머지는 원래 예외 유지 |
| 슬라이스 | `WorkControllerTest`, `CreatorControllerTest` (`@WebMvcTest`) | 기본 정렬, page·size·sort 400, 404 |
| 통합 | `AdminApiDisabledWithoutLocalProfileTest`, `AdminApiDisabledByDefaultTest` | 플래그만 켜고 local 프로필이 없을 때, local 프로필만 있고 플래그가 기본값(false)일 때 등록 Controller 미생성·404, 조회 API 동작, local 프로필의 loopback 바인딩 설정 |
| 통합 | `CreatorApiIntegrationTest`, `WorkApiIntegrationTest` | 등록 성공·정규화, 입력 오류, 없는 작가, 요청 내 중복, 상세·목록, 안정적인 정렬, 페이징 |
| 통합 | `WorkQueryCountTest` | 데이터(작품·제목·작가 수)를 늘려도 목록·상세 조회의 SQL 실행 수가 변하지 않음 |

- 쿼리 수 검증은 Hibernate Statistics(`test` 프로필에서만 활성화)의 `prepareStatementCount`를 사용합니다. 고정 숫자가 아니라 **데이터가 적을 때와 많을 때의 실행 수가 같은지**를 비교합니다. 2차 캐시는 사용하지 않고, 측정은 HTTP 요청 단위(새 영속성 컨텍스트)로 합니다.
- 데이터 정리: `TestDatabaseCleaner`는 테스트 DB 이름이 `_test`로 끝나고 `DB_NAME`과 다른지 먼저 확인합니다. 같거나, 비어 있거나, 접미사가 없으면 정리하지 않습니다. 그다음 `TRUNCATE` 직전에 `current_database()`가 검증된 테스트 DB 이름과 같은지 다시 확인하고, 같을 때만 `TRUNCATE … RESTART IDENTITY CASCADE`를 실행합니다. 잘못된 설정은 Flyway·JPA가 DB를 바꾸기 전에 기동을 막습니다. `_test` 접미사는 안전장치이며, 그 DB가 과거에 테스트 용도로만 쓰였다는 증거는 아닙니다.
- 기존 Foundation 테스트 19개는 수정하지 않고 유지합니다.

## 8. 이후 단계로 미룬 사항

- 관리자 인증·인가(auth) 적용 후 `@ConditionalOnAdminApiEnabled` 제거
- 수정·삭제 API와 그에 따른 `updated_at` 갱신 및 대표 제목 교체 규칙
- 제목 유니코드 정규화(NFC)와 대소문자 무시 중복 판정 여부
- 작품 목록 필터(형식, 국가), 작가별 작품 목록, 제목 정렬, 검색(search 도메인)
- 실제 JPA flush/commit에서 발생한 유니크 제약 위반이 409로 변환되는 통합 테스트. 제약 이름 허용 목록과 Mockito 단위 테스트는 이미 있다.
