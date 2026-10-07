# 만화넷 (manhwanet)

한국 웹툰과 일본 만화를 통합하여 작품을 검색하고, 별점·리뷰·댓글을 작성하며, 여러 기준의 작품 랭킹을 확인할 수 있는 서비스입니다.

> **현재 단계: Phase 2 — 작품·작가 등록/조회**
> 작품(Work·WorkTitle)과 작가(Creator·WorkCreator)의 등록·조회 API가 구현되어 있습니다. ([명세](docs/work-creator-spec.md))
> Frontend에서 작품 목록(`/works`)과 상세(`/works/[id]`)를 보고, 가입(`/signup`)·로그인(`/login`)·로그아웃할 수 있습니다.
> 작품 별점(내 별점 등록·수정·삭제, 작품 목록·상세의 평균·참여자 수)을 API와 화면에서 쓸 수 있습니다. ([명세](docs/rating-design-draft.md))
> 리뷰, 댓글, 랭킹, 검색과 작품·작가 수정/삭제는 아직 구현되어 있지 않습니다.
> 공통 기반(빌드, DB 연결, 마이그레이션, 공통 응답/예외 처리, 테스트 환경) 위에 작품·작가 등록·조회 API가 구현되어 있습니다.

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Backend | Java 21, Spring Boot 3.5.16, Gradle 8.14.3 (Wrapper), Spring Web, Spring Data JPA, Spring Validation, Flyway, PostgreSQL JDBC Driver, Spring Boot Test |
| Frontend | Next.js 16.3.8 (App Router), React 19.2.8, TypeScript 5 |
| Database | PostgreSQL 17 |
| Infra (로컬) | Docker Compose |

아키텍처: **Modular Monolith** — 도메인별 패키지(`com.manhwanet.<domain>`)로 나누고, 각 도메인은 `Controller → Service → Repository` 계층을 따릅니다. 자세한 규약은 [docs/architecture.md](docs/architecture.md)를 참고하세요.

## 프로젝트 구조

```
manhwanet/
├── backend/                         # Spring Boot (base package: com.manhwanet)
│   ├── build.gradle
│   ├── gradlew, gradlew.bat, gradle/wrapper/
│   └── src/
│       ├── main/java/com/manhwanet/
│       │   ├── ManhwanetApplication.java
│       │   ├── creator/             # 작가 (api / application / domain / dto)
│       │   ├── work/                # 작품·제목·작품-작가 연결 (api / application / domain / dto)
│       │   └── global/
│       │       ├── config/          # WebConfig(/api/v1), JPA Auditing, 등록 API 노출 조건
│       │       ├── response/        # ApiResponse, ApiError, PageResponse (공통 응답)
│       │       ├── error/           # ErrorCode, BusinessException, InvalidInputException, GlobalExceptionHandler
│       │       ├── jpa/             # BaseTimeEntity (created_at / updated_at)
│       │       ├── pagination/      # PageableFactory (page·size·sort 검증)
│       │       └── health/          # GET /api/v1/health
│       ├── main/resources/
│       │   ├── application.yml      # 공통 설정
│       │   ├── application-local.yml# 로컬 프로필 (기본)
│       │   ├── application-test.yml # 테스트 프로필
│       │   └── db/migration/        # Flyway 마이그레이션 (V1__init, V2__create_work_and_creator_tables)
│       └── test/java/com/manhwanet/
├── frontend/                        # Next.js + TypeScript + App Router
│   ├── src/app/                     # 화면 (/, /works, /works/[id], /login, /signup) — error / not-found 포함
│   ├── src/app/api/v1/[...path]/    # 같은 출처 API 프록시 (→ API_BASE_URL)
│   ├── src/components/              # 크레딧, 페이지 이동, 상태 안내, 로그인 폼·헤더(auth/)
│   ├── src/lib/api/                 # 서버 전용 API 클라이언트, 응답 타입, 무손실 JSON 파서, 프록시 규칙
│   ├── src/lib/auth/                # 로그인 상태 조회, 브라우저 인증 API(CSRF), returnTo, 입력 검사
│   ├── src/lib/works/               # URL 파라미터 해석, 표시용 라벨
│   └── .env.example
├── docker/postgres/init/            # PostgreSQL 최초 기동 시 실행 스크립트 (테스트 DB 생성)
├── docs/                            # 설계 문서
├── docker-compose.yml               # 로컬 PostgreSQL
├── .env.example                     # Backend/DB 환경변수 목록
├── .gitignore
└── README.md
```

## 요구 환경

- **JDK 21** (Gradle toolchain이 Java 21을 요구합니다. `java -version`으로 확인)
- **Docker** + Docker Compose v2 (`docker compose version`)
- **Node.js 24** 및 npm. 지원 범위는 `>=24.19.0 <25`입니다. 이 환경에서 설치하고 `npm ci`, 테스트, lint, build를 실행해 확인한 버전은 **24.19.0**입니다.
- Gradle은 설치할 필요 없습니다. (`gradlew` Wrapper가 8.14.3을 내려받습니다.)

Node.js 20은 2026-04-30에 지원이 끝났습니다. 2026-10-06 기준 지원 중인 LTS는 24(Active, 2026-10-20부터 Maintenance, 지원 종료 2028-04-30)와 22(Maintenance, 지원 종료 2027-04-30)입니다. 26은 2026-10-28에 LTS가 되기 전까지 Current입니다. 프론트엔드 잠금 파일의 `eslint-visitor-keys@5.0.1`은 `^20.19.0 || ^22.13.0 || >=24`, `@napi-rs/wasm-runtime@1.2.5`는 `^20.19.0 || ^22.13.0 || >=23.5.0`을 요구합니다. Next.js 16.3.8은 `>=20.9.0`입니다. 이 조건을 모두 만족하는 Active LTS로 24를 사용합니다.

`frontend/package.json`의 `engines.node`는 `>=24.19.0 <25`입니다. 이 값은 지원 범위를 알리는 안내이며, 24.19.0만 허용한다는 뜻이 아닙니다. `.npmrc`에 `engine-strict`를 넣지 않았으므로 다른 버전에서 설치와 실행을 막지 않습니다.

### Windows에서 Node.js 맞추기

공식 설치 파일은 `C:\Program Files\nodejs` 한 곳에 둡니다. nvm, fnm 같은 버전 관리 도구는 추가하지 않습니다. 이미 winget의 `OpenJS.NodeJS.20`이 있으면 먼저 제거해 실행 파일이 두 개가 되지 않게 합니다.

```powershell
winget uninstall --id OpenJS.NodeJS.20 --exact
winget install --id OpenJS.NodeJS.LTS --exact
```

2026-10-06에 `OpenJS.NodeJS.LTS`는 24.19.0입니다. nodejs.org의 24 최신 패치는 24.21.0이지만, 그 MSI는 이 환경에서 관리자 권한 부족으로 설치하지 못했습니다. 설치 뒤 **새** PowerShell에서 확인합니다.

```powershell
node -v
npm -v
Get-Command node | Select-Object -ExpandProperty Source
```

`node -v`가 `v24.19.0`이고 경로가 `C:\Program Files\nodejs\node.exe`이면 됩니다.

## 빠른 시작

명령은 모두 저장소 루트(`manhwanet/`) 기준입니다. Windows에서는 PowerShell 명령을 함께 표기했습니다.

### 0. 환경변수 파일 만들기 (최초 1회)

```bash
cp .env.example .env                      # macOS / Linux / Git Bash
Copy-Item .env.example .env               # Windows PowerShell
```

`.env`를 열어 `DB_PASSWORD`에 **로컬에서만 쓸 비밀번호**를 입력합니다. `.env`는 `.gitignore`에 포함되어 커밋되지 않습니다.

### 1. PostgreSQL 실행

```bash
docker compose up -d
docker compose ps                         # STATUS 가 healthy 인지 확인
```

- 최초 실행 시 개발 DB(`DB_NAME`, 기본 `manhwanet`)와 테스트 DB(`TEST_DB_NAME`, 기본 `manhwanet_test`)가 함께 생성됩니다.
- 중지: `docker compose down` / 데이터까지 삭제: `docker compose down -v`
- `docker/postgres/init/` 스크립트는 **볼륨이 비어 있을 때만** 실행됩니다. DB 이름을 바꿨다면 `docker compose down -v` 후 다시 올리세요.

### 2. Backend 실행

```bash
cd backend
./gradlew bootRun                         # macOS / Linux / Git Bash
.\gradlew.bat bootRun                     # Windows PowerShell
```

- 기본 프로필은 `local`이며, 루트의 `.env`를 자동으로 읽습니다. (`backend/` 디렉터리에서 실행해야 합니다. IDE에서 실행할 경우 Working directory를 `backend`로 설정하거나 환경변수를 직접 지정하세요.)
- 기동 시 Flyway가 `db/migration`의 마이그레이션을 적용합니다.
- 확인:

```bash
curl http://localhost:8080/api/v1/health
# {"success":true,"data":{"status":"UP","database":"UP"}}
```

DB에 연결할 수 없으면 `503`과 `{"success":false,"error":{"code":"SERVICE_UNAVAILABLE",...}}`을 반환합니다.

- `local` 프로필은 서버를 **loopback 주소(127.0.0.1)에만 바인딩**합니다. 같은 PC에서 `http://localhost:8080`으로 접속합니다.

#### 회원·인증과 관리자 계정

이메일·비밀번호 로그인(서버 세션 쿠키)과 CSRF 보호가 적용되어 있습니다. 등록 API(`/api/v1/admin/**`)는 **ADMIN 계정으로 로그인**해야 호출할 수 있습니다. 상세 규칙은 [docs/auth-design-draft.md](docs/auth-design-draft.md)(확정 명세)를 참고하세요.

관리자 계정은 웹 서버와 별도인 생성 명령으로 만듭니다. 이 명령은 웹 서버를 띄우지 않고, 일반 실행 시에는 동작하지 않습니다.

```powershell
cd backend
.\gradlew.bat bootJar
java -jar build\libs\manhwanet-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,admin-cli --manhwanet.admin-cli.email=admin@example.com
# 콘솔에 비밀번호를 두 번 입력합니다(화면에 표시되지 않음, 15~128자).
```

- 비밀번호는 명령행 인자로 받지 않습니다. 콘솔이 없는 환경(예: `bootRun`)에서는 환경변수 `MANHWANET_ADMIN_PASSWORD`를 쓰고, 실행 후 지웁니다.
- 같은 이메일의 ADMIN이 있으면 변경 없이 끝나고, 같은 이메일의 일반 사용자가 있으면 실패합니다(자동 승격 없음).

로그인 후 등록 API 호출 예 (PowerShell, 세션·CSRF 쿠키 유지):

```powershell
$s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-WebRequest http://127.0.0.1:8080/api/v1/auth/csrf -WebSession $s | Out-Null
$h = @{ "X-XSRF-TOKEN" = $s.Cookies.GetCookies("http://127.0.0.1:8080")["XSRF-TOKEN"].Value }
Invoke-RestMethod http://127.0.0.1:8080/api/v1/auth/login -Method Post -WebSession $s -Headers $h -ContentType "application/json" -Body '{"email":"admin@example.com","password":"(비밀번호)"}'
# 로그인하면 CSRF 토큰이 바뀌므로 헤더를 다시 만듭니다.
$h = @{ "X-XSRF-TOKEN" = $s.Cookies.GetCookies("http://127.0.0.1:8080")["XSRF-TOKEN"].Value }
Invoke-RestMethod http://127.0.0.1:8080/api/v1/admin/creators -Method Post -WebSession $s -Headers $h -ContentType "application/json; charset=utf-8" -Body '{"name":"한서윤"}'
```

### 3. Frontend 실행

```bash
cd frontend
cp .env.example .env.local                # PowerShell: Copy-Item .env.example .env.local
npm install
npm run dev
```

브라우저에서 http://localhost:3000 을 열고 "작품 목록 보기"를 누르면 작품 목록(`/works`)으로 이동합니다. Backend가 실행 중이어야 목록이 표시됩니다.

| 경로 | 화면 |
| --- | --- |
| `/` | 첫 화면, 작품 목록으로 이동 |
| `/works?page=N` | 작품 목록 (대표 제목, 작품 형식, 작가 크레딧, 20개씩 페이지 이동). `page`는 **1부터** 시작하며 생략하면 1페이지 |
| `/works/{id}?from=N` | 작품 상세 (대표·다른 제목, 작가 역할, 국가, 줄거리). `from`은 "작품 목록으로" 링크가 돌아갈 목록 페이지 |
| `/login?returnTo=경로` | 로그인 (이메일·비밀번호). 성공하면 `returnTo`, 없으면 `/works`로 이동 |
| `/signup?returnTo=경로` | 회원가입 (이메일·닉네임·비밀번호). 가입과 동시에 로그인됨 |

- 작품 목록·상세에 별점 평균과 참여자 수를 표시합니다(없으면 "아직 평가가 없습니다"). 상세에서 로그인한 사용자는 0.5~5.0점을 골라 "저장" 버튼으로 등록·변경하고 삭제할 수 있습니다. 상세 동작은 [docs/rating-design-draft.md](docs/rating-design-draft.md) §7을 참고하세요.

- 작품 조회는 **서버(Server Component)**에서 `API_BASE_URL`로 직접 호출합니다.
- 브라우저의 인증 요청(가입·로그인·로그아웃·상태 확인)은 **같은 출처 `/api/v1/**`**로 보내고, Next.js Route Handler(`src/app/api/v1/[...path]/route.ts`)가 서버 설정 `API_BASE_URL` 아래 같은 경로로만 전달합니다. 브라우저가 Backend를 직접 호출하지 않으므로 Backend CORS 설정이 필요 없습니다.
  - 넘기는 것: `Accept`, `Accept-Language`, `Content-Type`, `X-XSRF-TOKEN`, 세션·`XSRF-TOKEN` 쿠키. 돌려주는 것: 상태 코드, 본문, `Set-Cookie`(모두), `Content-Type`, `Cache-Control`, `Retry-After`, 같은 출처 `Location`
  - `.`·`..`·인코딩된 슬래시가 든 경로와 64KiB를 넘는 본문은 거절합니다. Backend에 연결하지 못하면 503 `SERVICE_UNAVAILABLE`입니다.
- 헤더의 로그인 상태는 요청마다 서버에서 `GET /auth/me`로 확인합니다(세션 쿠키만 전달, `no-store`). Backend 장애일 때는 로그아웃으로 표시하지 않고 "로그인 상태를 확인하지 못했습니다"를 보여 줍니다. 로그인 상태에서 화면을 옮기거나 탭으로 돌아오면 다시 확인하고, 세션이 만료되었으면 안내합니다.
- CSRF: 변경 요청 직전에 `XSRF-TOKEN` 쿠키를 읽어 `X-XSRF-TOKEN` 헤더로 보냅니다. 쿠키가 없으면 `GET /api/v1/auth/csrf`로 받고, `INVALID_CSRF_TOKEN`이면 한 번만 다시 받아 재시도합니다. 토큰·비밀번호는 localStorage, URL, 로그에 남기지 않습니다.
- `returnTo`는 같은 사이트 내부 경로(`/`로 시작, `//`·`\`·스킴·제어 문자 없음)만 허용하며, 그 밖의 값은 `/works`로 바꿉니다.
- 캐시 정책: 모든 조회는 `fetch(..., { cache: "no-store" })`로 요청마다 Backend에서 새로 읽습니다. `/works`, `/works/[id]`는 동적 렌더링(`ƒ`)이며 응답에 `Cache-Control: private, no-cache, no-store`가 붙습니다. 새로 등록한 작품은 새로고침하면 바로 보입니다.
- 세션 쿠키를 읽기 때문에 모든 화면이 동적 렌더링(`ƒ`)입니다.
- 상태 화면: 등록된 작품 없음, 범위 밖 페이지, API 연결 실패(다시 시도 링크), 없는 작품(404 안내)
- 정상 목록·상세는 HTTP 200입니다. 없는 작품과 잘못된 작품 ID는 404이고, 잘못된 `page`와 중복 `page`는 `/works`로 307 리다이렉트됩니다. 없는 작품 화면에는 `noindex`가 붙습니다.
- 작품 `id`와 작가 `creatorId`는 JSON 숫자로 오지만, 서버에서 원문 십진수 문자열로 읽어 링크에 사용합니다. 페이지 번호와 개수는 기존 숫자 타입입니다.

프로덕션 빌드: `npm run build && npm run start`

## 테스트 실행

### Backend

통합 테스트는 실제 PostgreSQL의 **테스트 DB**(`manhwanet_test`)에 연결하므로, 먼저 `docker compose up -d`로 DB를 띄워 두세요.

```bash
cd backend
./gradlew test                            # PowerShell: .\gradlew.bat test
./gradlew build                           # 컴파일 + 테스트 + bootJar
```

| 테스트 | 내용 | DB 필요 |
| --- | --- | --- |
| `GlobalExceptionHandlerTest` | 공통 응답 형식, `/api/v1` prefix, 400/404/405/415/500 예외 변환 | X |
| `HealthControllerTest` | health 정상/503 응답 | X |
| `HealthServiceTest` | DB 연결 성공/실패 판정 | X |
| `ManhwanetApplicationTests` | 컨텍스트 로딩, Flyway V1·V3 적용, 실제 DB 대상 health, 공개 경로 404·비공개 경로 401 | O |
| `PageableFactoryTest`, `LanguageTagsTest`, `CountryCodesTest`, `WorkTest`, `WorkServiceTest` | 정렬 파싱, 언어·국가 코드 정규화, 도메인 불변식, 중복 제약 → 409 변환 | X |
| `WorkControllerTest`, `CreatorControllerTest` | 페이지·정렬 파라미터 검증, 404 | X |
| `WorkCreatorMigrationTest` | V2 적용, 제약·인덱스 이름, DB 제약 동작 | O |
| `PasswordPolicyTest`, `AccountFieldsTest`, `LoginAttemptLimiterTest`, `CreateAdminCommandTest` | 비밀번호 정책·정규화, 이메일·닉네임, 로그인 실패 제한(동시성·만료), 관리자 명령 입력 | X |
| `AuthApiIntegrationTest` | 가입·로그인·로그아웃·me, CSRF 발급·교체·누락, 세션 ID 변경, 실패 제한 | O |
| `AdminAuthorizationIntegrationTest` | 등록 API 접근 규칙: 익명 401, USER 403, ADMIN 허용, CSRF 누락 403 | O |
| `AdminAccountServiceTest`, `AdminCliProfileTest` | 관리자 생성 분기, 비웹 `admin-cli` 프로필 | O |
| `SessionCookieHttpTest`, `LocalSessionCookieHttpTest` | 실제 HTTP 응답의 쿠키 속성(`__Host-`, local), 세션 흐름, ERROR 디스패치, loopback 바인딩 | O |
| `CreatorApiIntegrationTest`, `WorkApiIntegrationTest` | 등록·조회 API 전체 흐름, 입력 오류, 정렬·페이징 | O |
| `WorkQueryCountTest` | 데이터(작품·제목·작가·별점)가 늘어도 목록·상세 조회 SQL 수가 늘지 않음, 빈 페이지는 별점 집계 생략 (N+1 회귀) | O |
| `RatingScoreTest`, `RatingAverageTest` | 점수 경계·0.5 단위·정확한 소수, 평균 HALF_UP | X |
| `WorkRatingMigrationTest` | V4 적용, 유니크·CHECK·FK(작품 CASCADE, 사용자 RESTRICT) | O |
| `RatingApiIntegrationTest` | 내 별점 GET·PUT·DELETE, 시각 정책, 점수 검증(문자열·null 거절), 401·403·404, USER·ADMIN 격리, 공개 집계·반올림, 큰 ID | O |
| `RatingConcurrencyTest`, `RatingRollbackTest` | 동시 PUT, PUT·DELETE 경쟁(트랜잭션 순서 재현 포함), 롤백, FK 위반 → 404 | O |

- 통합 테스트는 테스트 DB 이름이 `_test`로 끝나고 `DB_NAME`과 다른지 확인한 뒤, `TRUNCATE` 직전에 `current_database()`가 그 이름과 같은지 다시 확인합니다. 이름이 같거나, 비어 있거나, 접미사가 없으면 정리를 거절합니다. `_test` 접미사는 안전장치이며, 그 DB의 과거 용도를 증명하지는 않습니다.
- 등록 API 통합 테스트는 ADMIN 사용자와 CSRF 토큰으로 요청합니다(spring-security-test).

테스트 리포트: `backend/build/reports/tests/test/index.html`

### Frontend

```bash
cd frontend
npm test                                  # src/**/*.test.ts (JSON 무손실 파싱, 프록시 규칙, 브라우저 API·CSRF·204, returnTo, 입력 검사, 별점 규칙, 오류 안내)
npm run lint
npm run build
```

## 환경변수

### 루트 `.env` (Docker Compose + Backend 공용)

| 이름 | 설명 | 기본값 | 필수 |
| --- | --- | --- | --- |
| `DB_HOST` | PostgreSQL 호스트 | `localhost` | |
| `DB_PORT` | PostgreSQL 포트 (Compose 호스트 포트) | `5432` | |
| `DB_NAME` | 개발 DB 이름 | `manhwanet` | |
| `TEST_DB_NAME` | 테스트 DB 이름 (test 프로필) | `manhwanet_test` | |
| `DB_USERNAME` | DB 사용자 | 없음 | ✅ |
| `DB_PASSWORD` | DB 비밀번호 | 없음 | ✅ |
| `SERVER_PORT` | Backend 포트 | `8080` | |

- 우선순위: **OS 환경변수 > `.env` > `application*.yml` 기본값**
- `.env`는 Java `.properties` 형식으로 읽히므로 값에 따옴표를 쓰지 마세요.
- 운영 등 다른 환경에서는 `.env` 대신 OS 환경변수로 주입합니다. (`.env` import는 `local`, `test` 프로필에만 있습니다.)

### `frontend/.env.local`

| 이름 | 설명 | 기본값 |
| --- | --- | --- |
| `API_BASE_URL` | 서버 조회와 같은 출처 프록시가 호출할 Backend API 주소. 실행 시점에 읽으며 브라우저에 노출되지 않음 | `http://127.0.0.1:8080/api/v1` |

- `local` 프로필 Backend는 127.0.0.1에만 바인딩되므로 `API_BASE_URL`은 `localhost` 대신 `127.0.0.1`을 권장합니다. (`localhost`가 IPv6 `::1`로 먼저 해석되면 연결되지 않을 수 있습니다)
- 브라우저용 API 주소(`NEXT_PUBLIC_API_BASE_URL`)는 두지 않습니다. 브라우저는 항상 같은 출처 `/api/v1/**`를 호출합니다.

## API

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/health` | 애플리케이션·DB 상태 |
| GET | `/api/v1/works` | 작품 목록 (`page`, `size`, `sort`) |
| GET | `/api/v1/works/{workId}` | 작품 상세 |
| GET | `/api/v1/creators` | 작가 목록 (`page`, `size`, `sort`) |
| GET | `/api/v1/creators/{creatorId}` | 작가 상세 |
| GET | `/api/v1/auth/csrf` | CSRF 토큰 쿠키(`XSRF-TOKEN`) 발급 |
| POST | `/api/v1/auth/signup` | 가입(항상 USER) + 자동 로그인 |
| POST | `/api/v1/auth/login` | 로그인 |
| POST | `/api/v1/auth/logout` | 로그아웃 |
| GET | `/api/v1/auth/me` | 현재 사용자 (로그인 필요) |
| GET | `/api/v1/me/ratings/{workId}` | 내 별점 (로그인 필요). 평가하지 않았으면 `score: null` |
| PUT | `/api/v1/me/ratings/{workId}` | 내 별점 등록·수정 `{"score": 4.5}` (0.5~5.0, 0.5 단위) |
| DELETE | `/api/v1/me/ratings/{workId}` | 내 별점 삭제 (204, 반복 가능) |
| POST | `/api/v1/admin/works` | 작품 등록 (ADMIN) |
| POST | `/api/v1/admin/creators` | 작가 등록 (ADMIN) |

- 페이지: `page` 0부터(기본 0), `size` 1~100(기본 20), `sort`는 `createdAt` 또는 `id`에 `,asc`/`,desc` (기본 `createdAt,desc` + `id,desc`). 범위 밖 값이나 허용하지 않은 정렬은 400입니다.
- 요청·응답 형식과 검증 규칙은 [docs/work-creator-spec.md](docs/work-creator-spec.md), 인증은 [docs/auth-design-draft.md](docs/auth-design-draft.md), 별점은 [docs/rating-design-draft.md](docs/rating-design-draft.md)를 참고하세요.
- 작품 목록·상세 응답에는 `rating: { average, count }`가 있습니다. `average`는 소수 첫째 자리(HALF_UP)이고 별점이 없으면 `null`입니다.
- POST 등 변경 요청은 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보내야 합니다. 공개 GET(작품·작가·health) 외의 경로는 로그인이 필요합니다.

## API 공통 규약

- Base prefix: `/api/v1` (Controller에는 도메인 경로만 선언하면 `WebConfig`가 prefix를 붙입니다.)
- 성공: `{"success": true, "data": ...}`
- 실패: `{"success": false, "error": {"code": "INVALID_INPUT", "message": "...", "fieldErrors": [{"field": "name", "reason": "..."}]}}`
- 페이지 응답 `data`: `{"content": [...], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0, "hasNext": false}`
