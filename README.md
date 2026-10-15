# 만화넷 (manhwanet)

한국 웹툰과 일본 만화를 통합하여 작품을 검색하고, 별점·리뷰·댓글을 작성하며, 여러 기준의 작품 랭킹을 확인할 수 있는 서비스입니다.

> **현재 단계: Phase 2 — 작품·작가 등록/조회**
> 작품(Work·WorkTitle)과 작가(Creator·WorkCreator)의 등록·조회 API가 구현되어 있습니다. ([명세](docs/work-creator-spec.md))
> Frontend에서 작품 목록(`/works`)과 상세(`/works/[id]`)를 보고, 가입(`/signup`)·로그인(`/login`)·로그아웃할 수 있습니다.
> 작품 별점(내 별점 등록·수정·삭제, 작품 목록·상세의 평균·참여자 수)을 API와 화면에서 쓸 수 있습니다. ([명세](docs/rating-design-draft.md))
> 작품 리뷰(내 리뷰 작성·수정·삭제, 작품별 공개 목록)를 API와 작품 상세 화면에서 쓸 수 있습니다. ([명세](docs/review-design-draft.md))
> 댓글, 랭킹, 검색과 작품·작가 수정/삭제는 아직 구현되어 있지 않습니다.
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
│       │   └── db/migration/        # Flyway 마이그레이션 (V1__init ~ V5__create_work_reviews)
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
- **Node.js 24** 및 npm. 지원 범위는 `>=24.19.0 <25`입니다. 2026-10-10에 Windows에서 설치하고 `npm ci`, 테스트, lint, build를 실행해 확인한 버전은 **Node.js 24.21.0 (npm 11.19.0, OpenSSL 3.5.8)** 입니다. 24.19.0·24.20.0에 포함된 OpenSSL 3.5.7은 2026-08-25 OpenSSL 보안 권고의 수정 전 버전이므로 24.21.0 이상을 권장합니다.
- Gradle은 설치할 필요 없습니다. (`gradlew` Wrapper가 8.14.3을 내려받습니다.)

Node.js 20은 2026-04-30에 지원이 끝났습니다. 2026-10-06 기준 지원 중인 LTS는 24(Active, 2026-10-20부터 Maintenance, 지원 종료 2028-04-30)와 22(Maintenance, 지원 종료 2027-04-30)입니다. 26은 2026-10-28에 LTS가 되기 전까지 Current입니다. 프론트엔드 잠금 파일의 `eslint-visitor-keys@5.0.1`은 `^20.19.0 || ^22.13.0 || >=24`, `@napi-rs/wasm-runtime@1.2.5`는 `^20.19.0 || ^22.13.0 || >=23.5.0`을 요구합니다. Next.js 16.3.8은 `>=20.9.0`입니다. 이 조건을 모두 만족하는 Active LTS로 24를 사용합니다.

`frontend/package.json`의 `engines.node`는 `>=24.19.0 <25`입니다. 이 값은 지원 범위를 알리는 안내이며, 24.19.0만 허용한다는 뜻이 아닙니다. `.npmrc`에 `engine-strict`를 넣지 않았으므로 다른 버전에서 설치와 실행을 막지 않습니다.

### Windows에서 Node.js 맞추기

공식 설치 파일은 `C:\Program Files\nodejs` 한 곳에 둡니다. nvm, fnm 같은 버전 관리 도구는 추가하지 않습니다. 이미 winget의 `OpenJS.NodeJS.20`이 있으면 먼저 제거해 실행 파일이 두 개가 되지 않게 합니다.

```powershell
winget uninstall --id OpenJS.NodeJS.20 --exact
winget install --id OpenJS.NodeJS.LTS --exact
```

2026-10-10 기준 winget `OpenJS.NodeJS.LTS`의 최신은 24.20.0(OpenSSL 3.5.7)이고 24.21.0은 아직 없습니다. 이 경우 nodejs.org의 공식 Windows x64 MSI(`node-v24.21.0-x64.msi`)를 **관리자 권한** PowerShell에서 실행해 기존 설치를 업데이트합니다. 같은 "Node.js" MSI 제품이 그 자리에서 교체되므로 설치가 두 개로 늘지 않습니다. 실행 전에 MSI의 SHA256이 공식 `SHASUMS256.txt`와 같은지, 서명자가 OpenJS Foundation인지 확인합니다. 관리자 권한 없이 실행하면 Windows Installer 오류 1925로 실패합니다.

설치 뒤 **새** PowerShell에서 확인합니다.

```powershell
node -v
npm -v
node -p "process.versions.openssl"
Get-Command node -All | Select-Object -ExpandProperty Source
```

`node -v`가 `v24.21.0` 이상(25 미만), OpenSSL이 `3.5.8` 이상이고, 경로가 `C:\Program Files\nodejs\node.exe` 하나뿐이면 됩니다.

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
# 작품 등록에는 제목·크레딧 근거(evidence)가 필요합니다(#40). STAFF 는 관리자가 직접 확인했다는 뜻입니다.
Invoke-RestMethod http://127.0.0.1:8080/api/v1/admin/works -Method Post -WebSession $s -Headers $h -ContentType "application/json; charset=utf-8" -Body '{"workFormat":"WEBTOON","titles":[{"title":"달빛 기사단","languageCode":"ko","primary":true}],"creators":[{"creatorId":1,"role":"STORY"}],"evidence":[{"field":"TITLE","sourceType":"STAFF","verifiedAt":"2026-10-14T00:00:00Z"},{"field":"CREDIT","sourceType":"STAFF","verifiedAt":"2026-10-14T00:00:00Z"}]}'
```

#### 수집 명령 (#46, `collector` 프로필)

플랫폼 수집은 웹 서버와 별도인 명령으로만 시작합니다. 일반 웹 서버 실행(기본 프로필)으로는 수집이 시작되지 않습니다. 실제 플랫폼 어댑터는 아직 없습니다(#47~#50). 규칙은 [docs/collector-core-spec.md](docs/collector-core-spec.md)를 참고하세요.

```powershell
cd backend
.\gradlew.bat bootJar
# 새 실행: kind 는 PROBE·FULL·INCREMENTAL·RECHECK. 연락처(contact-url)는 기본값이 없고 지정해야 시작합니다(D-16)
java -jar build\libs\manhwanet-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,collector `
  --manhwanet.collector.contact-url=https://<서비스 소개 페이지> `
  --manhwanet.collector.command=start --manhwanet.collector.platform=RIDI --manhwanet.collector.kind=PROBE
# 재개(멈춘 실행·잠금이 만료된 실행): --manhwanet.collector.command=resume --manhwanet.collector.run-id=12
# 중단 요청: --manhwanet.collector.command=stop --manhwanet.collector.run-id=12 --manhwanet.collector.actor-email=admin@example.com --manhwanet.collector.reason=점검
```

- 플랫폼 수집 설정이 `DISABLED`(기본값)이면 실행을 만들지 않고 요청도 보내지 않습니다. `PROBE` 설정에서는 `PROBE` 실행만 되며 robots.txt·사이트맵만 요청합니다.
- 요청 간격은 플랫폼당 최소 5초(+0~1초), 동시 1건입니다. 403·CAPTCHA·429 반복은 실행을 멈추고 기록합니다(우회 없음).

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
| `/works/{id}?from=N&reviewPage=M` | 작품 상세 (대표·다른 제목, 작가 역할, 국가, 줄거리, 별점, 리뷰). `from`은 "작품 목록으로" 링크가 돌아갈 목록 페이지, `reviewPage`는 공개 리뷰 페이지(1부터, 10개씩) |
| `/login?returnTo=경로` | 로그인 (이메일·비밀번호). 성공하면 `returnTo`, 없으면 `/works`로 이동 |
| `/signup?returnTo=경로` | 회원가입 (이메일·닉네임·비밀번호). 가입과 동시에 로그인됨 |

- 작품 목록·상세에 별점 평균과 참여자 수를 표시합니다(없으면 "아직 평가가 없습니다"). 상세에서 로그인한 사용자는 0.5~5.0점을 골라 "저장" 버튼으로 등록·변경하고 삭제할 수 있습니다. 상세 동작은 [docs/rating-design-draft.md](docs/rating-design-draft.md) §7을 참고하세요.
- 작품 상세에 공개 리뷰 목록(닉네임, 작성·수정 시각 Asia/Seoul, 스포일러는 "내용 보기"를 눌러야 표시)과 내 리뷰 영역이 있습니다. 로그인한 사용자는 제목 없는 일반 텍스트(10~2,000자)를 "등록"·"수정 저장" 버튼으로 저장하고, 확인 후 삭제할 수 있습니다. 다른 탭에서 먼저 바뀌었으면 작성 중인 글을 지우지 않고 최신 리뷰와 함께 선택지를 보여 줍니다. 상세 동작은 [docs/review-design-draft.md](docs/review-design-draft.md) §6을 참고하세요.

- 작품 조회는 **서버(Server Component)**에서 `API_BASE_URL`로 직접 호출합니다.
- 브라우저의 인증 요청(가입·로그인·로그아웃·상태 확인)은 **같은 출처 `/api/v1/**`**로 보내고, Next.js Route Handler(`src/app/api/v1/[...path]/route.ts`)가 서버 설정 `API_BASE_URL` 아래 같은 경로로만 전달합니다. 브라우저가 Backend를 직접 호출하지 않으므로 Backend CORS 설정이 필요 없습니다.
  - 넘기는 것: `Accept`, `Accept-Language`, `Content-Type`, `X-XSRF-TOKEN`, `X-Expected-User-Id`(리뷰·별점 변경의 예상 사용자), 세션·`XSRF-TOKEN` 쿠키. 돌려주는 것: 상태 코드, 본문, `Set-Cookie`(모두), `Content-Type`, `Cache-Control`, `Retry-After`, 같은 출처 `Location`
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
| `PageableFactoryTest`, `LanguageTagsTest`, `CountryCodesTest`, `WorkTest`, `WorkServiceTest` | 정렬 파싱, page·size·offset 경계(int 오버플로 없음), 언어·국가 코드 정규화, 도메인 불변식, 중복 제약 → 409 변환 | X |
| `PaginationLimitIntegrationTest` | 작품·작가·리뷰 목록의 최대 지원 page(200)와 바로 다음 값·음수·int 초과·형식 오류(400 `fieldErrors`), 거절 시 SQL 미실행 (#20) | O |
| `WorkControllerTest`, `CreatorControllerTest` | 페이지·정렬 파라미터 검증, 404 | X |
| `WorkCreatorMigrationTest` | V2 적용, 제약·인덱스 이름, DB 제약 동작 | O |
| `PasswordPolicyTest`, `AccountFieldsTest`, `LoginAttemptLimiterTest`, `CreateAdminCommandTest` | 비밀번호 정책·정규화, 이메일·닉네임, 로그인 실패 제한(동시성·만료·완료 순서·잠금 보존·용량 상한·포화 `Retry-After`·최악 유지 시간), 관리자 명령 입력 | X |
| `PasswordHashGateTest`, `AuthServiceProtectionTest` | 해시 게이트(동시 해시 ≤ P, 대기 ≤ Q, 거절 결정 시간, 인터럽트), 획득·해제 순서와 예외·시간 초과·인터럽트 후 누수 없음 (#28) | X |
| `AuthApiIntegrationTest` | 가입·로그인·로그아웃·me, CSRF 발급·교체·누락, 세션 ID 변경, 실패 제한 | O |
| `LoginProtectionIntegrationTest`, `LoginStoreSaturationIntegrationTest` | 503 `AUTH_BUSY`(로그인·가입 공통, 없는 이메일·있는 이메일 동일, 실패 미집계), `admin-cli` 제외, 저장소 포화 시 잠금 유지·신규 이메일 503·기존 키 정상, 로그에 이메일·비밀번호 없음 (#28) | O |
| `AdminAuthorizationIntegrationTest` | 등록 API 접근 규칙: 익명 401, USER 403, ADMIN 허용, CSRF 누락 403 | O |
| `WorkAdminUpdateApiIntegrationTest` | 작품·작가 관리자 조회·수정: 익명 401·USER 403·CSRF 누락 403, 대표 제목·크레딧 교체, 오래된·미래 version 409(`currentVersion`), 같은 내용은 version 유지, 입력 오류 400, 없는 작품·작가 404, 별점·리뷰·ID 보존 (#38) | O |
| `WorkAdminConcurrencyTest` | 같은 version 동시 수정 시 하나만 성공·나머지 409·제목과 크레딧이 섞이지 않음(작품·작가 각 20회), 중간 실패 시 전부 롤백, 작품·작가 잠금이 별점 작성·크레딧 추가를 막지 않음 (#38) | O |
| `WorkCreatorVersionMigrationTest` | V6 적용·기본값 0·CHECK, V5 데이터에 V6 적용 시 ID·별점·리뷰·제목 보존 (#38) | O |
| `WorkListingApiIntegrationTest` | 작품 플랫폼 연결: 익명 401·USER 403·CSRF 누락 403, 한 작품 여러 플랫폼, 같은 플랫폼·외부 ID 중복 409(`existingWorkId`·`existingListingId`), 다른 플랫폼 같은 번호 허용, URL 스킴·호스트·패턴·정규화·숨은 문자 거절, 큰 ID·입력 오류, 등급 분류·`UNKNOWN`, `CLOSED` 거절·`MERGING` 경고, 종료·이전·이전 체인, 규칙 변경 뒤 종료, 오래된 version 409, 별점·리뷰·ID 보존 (#39) | O |
| `PlatformAdminApiIntegrationTest` | 플랫폼 10곳 초기값(상태·약관 미입력), ADMIN·CSRF, 보도는 `UNKNOWN`·종료는 `OFFICIAL`만, 통합 대상·자기 참조·순환·`CLOSED` 대상 거절, 근거 URL, 상태·이력 원자성, `activeListingIds`, URL 규칙·호스트 검증과 위험한 패턴 거절 (#39) | O |
| `WorkListingConcurrencyTest` | 같은 플랫폼 작품을 두 작품에 동시에 연결하면 하나만 성공, 같은 version 동시 등록, 플랫폼 종료와 연결 등록 경쟁, 작품 version 증가 실패 시 연결 롤백 (#39) | O |
| `WorkProvenanceApiIntegrationTest` | 작품 외부 ID·공식 링크·필드별 근거·변경 기록 API: 익명 401·USER 403·CSRF 누락 403, 같은 출처 외부 ID 중복 409(`existingWorkId`)·다른 출처 같은 번호 허용·INVALID 로 자리 비우기·되살리기 409, 형식·URL·큰 ID·오래된 version, 링크 URL 검증·플랫폼 호스트 거절·같은 작품 중복 409, 근거 규칙(줄거리 STAFF·LICENSED, LISTING 대상), 철회, 변경 기록 필터·쪽 나누기 (#40) | O |
| `AdminChangeFlowIntegrationTest` | 작품 등록과 함께 연결·외부 ID·링크·근거 저장과 재시도 409(작품 1개 유지), 근거 필수, 수정 사유·바뀐 필드 근거·같은 내용 무기록, 작가 외부 ID·이름 변경 기록, 연결 생성·상태·이전·평탄화와 플랫폼 상태 기록, 별점·리뷰·ID 보존 (#40) | O |
| `CollectionSchemaMigrationTest` | V9~V12 적용(V13 은 #46), 새 FK CASCADE 없음, 수집 설정 10곳 `DISABLED` 시작, 설정 변경자 CHECK, 플랫폼당 활성 실행 하나, 작업·staging·식별 키·이력 유니크, 등급·소개문·실패 CHECK, 후보·표지·누락 현황 제약 (#45) | O |
| `CrawlRunLifecycleTest` | 수집 설정 게이트, 동시 시작 시 실행 하나, 잠금 만료 뒤 같은 실행 넘겨받기·남은 작업만 재개, 잠금 잃은 프로세스 쓰기 거절, 일시 정지·차단·중단 요청·중단 확정, 작업 중복 방지·재시도·실패 표본·동시 가져가기 (#45) | O |
| `StagingIngestTest` | 재수집해도 한 행·실행당 이력 한 줄·처음 이전 값 유지, 실패 시 내용 보존·연속 실패 한 번만 세기, 명시 신호로만 등급(미확인 `UNKNOWN`), 소개문 정책, 별칭·충돌, 동시 반영 한 행, 이력 실패 시 롤백, 작품·연결 미등록 (#45) | O |
| `CollectionAdminApiIntegrationTest` | 익명 401·USER 403·CSRF 누락 403, 수집 설정 사유·version·변경 기록·활성 실행 경고, 실행·staging 조회, 중단 요청·기록, 큰 ID·잘못된 조회 400 (#45) | O |
| `CollectorEngineIntegrationTest` | 수집기 공통 기반(모의 HTTP 서버 127.0.0.1·격리 DB): `DISABLED` 실행 없음·요청 없음, `PROBE` 는 robots·사이트맵만, 웹 컨텍스트에 수집 명령 없음, 요청 간격 5초(리다이렉트·재시도·재개 포함)·Crawl-delay, robots 금지·6시간 뒤 다시 읽기·읽기 실패 시 차단, 429 Retry-After·간격 2배·2회째 차단, 403·CAPTCHA 즉시 차단, 로그인·성인·결제 리다이렉트 미추적(GATED), 허용하지 않은 호스트·사설 IP·http·다른 포트 리다이렉트 거절, 5xx 30초·2분·8분·30분 재시도 뒤 실패, 회로 차단기, 타임아웃·크기 상한·404·파싱 실패 표본 가림, 하루 상한 일시 정지, 설정 끄면 일시 정지, 대기 중 중단 요청·잠금 연장·잠금 상실, 요청 중 잠금 상실 시 결과 쓰기 거절, 네트워크 대기 중 DB 연결 0, 쿠키·세션 값 로그 미기록 (#46) | O |
| `HttpTransportTest`, `RobotsRulesTest`, `PublicAddressPolicyTest`, `PlatformThrottleTest`, `CollectorCoreUnitTest`, `CollectCommandTest` | 고정 요청 헤더·Set-Cookie 버림, Content-Length·청크·gzip 크기 상한, 전체 시간 제한, 운영 네트워크의 로컬·사설·메타데이터 주소 거절(연결 전), TLS 인증서 호스트 이름 확인 / robots 해석(RFC 9309) / 목적지 IP 판정 / 동시 1건·간격·Retry-After / 재시도 계산·Retry-After·회로 차단기·사용자 에이전트·차단·인증 화면 판단·로그 가림 / `collector` 프로필 전용·웹 서버 없음·인자 검사 (#46) | X |
| `ProvenanceConcurrencyTest`, `ProvenanceMigrationTest` | 같은 외부 ID 동시 연결은 하나만 성공, 같은 version 하위 리소스 동시 변경, 기록 실패·version 증가 실패 시 등록·변경 전체 롤백 / V8 FK(CASCADE 없음)·부분 유니크·CHECK·복합 FK·변경 기록 추가 전용 (#40) | O |
| `PlatformListingMigrationTest`, `AgeRatingClassifierTest`, `PlatformUrlsTest` | V7 FK(CASCADE 없음)·유니크·CHECK·복합 FK, 초기 데이터와 테스트 시드 일치, 등급 분류, URL 정규화·패턴 검사 (#39) | O / X |
| `AdminAccountServiceTest`, `AdminCliProfileTest` | 관리자 생성 분기, 비웹 `admin-cli` 프로필 | O |
| `SessionCookieHttpTest`, `LocalSessionCookieHttpTest` | 실제 HTTP 응답의 쿠키 속성(`__Host-`, local), 세션 흐름, ERROR 디스패치, loopback 바인딩 | O |
| `CreatorApiIntegrationTest`, `WorkApiIntegrationTest` | 등록·조회 API 전체 흐름, 입력 오류, 정렬·페이징 | O |
| `WorkQueryCountTest` | 데이터(작품·제목·작가·별점)가 늘어도 목록·상세 조회 SQL 수가 늘지 않음, 빈 페이지는 별점 집계 생략 (N+1 회귀) | O |
| `RatingScoreTest`, `RatingAverageTest` | 점수 경계·0.5 단위·정확한 소수, 평균 HALF_UP | X |
| `WorkRatingMigrationTest` | V4 적용, 유니크·CHECK·FK(작품 CASCADE, 사용자 RESTRICT) | O |
| `RatingApiIntegrationTest` | 내 별점 GET·PUT·DELETE, 시각 정책, 점수 검증(문자열·null 거절), 401·403·404, USER·ADMIN 격리, 공개 집계·반올림, 큰 ID | O |
| `RatingExpectedUserIntegrationTest` | 별점 `X-Expected-User-Id` 필수·형식, 계정 전환 뒤 PUT·DELETE·GET 409 `USER_MISMATCH` 와 무변경, 큰 사용자 ID 정확 비교 | O |
| `RatingConcurrencyTest`, `RatingRollbackTest` | 동시 PUT, PUT·DELETE 경쟁(트랜잭션 순서 재현 포함), 롤백, FK 위반 → 404 | O |
| `ReviewBodyTest` | 리뷰 본문 정규화: 코드 포인트 경계(이모지·조합 문자), 앞뒤 공백, 줄바꿈 LF 통일, NUL·제어 문자 거절 | X |
| `WorkReviewMigrationTest` | V5 적용, 유니크·본문 길이·공백·version·시각 CHECK, FK(작품 CASCADE, 사용자 RESTRICT), NUL 저장 불가 | O |
| `ReviewApiIntegrationTest` | 공개 목록·내 리뷰 GET·POST·PUT·DELETE, 보안 규칙(익명 공개 GET·`/me` 401·CSRF 403), 입력 검증, `reviewId`+`version` 충돌, 삭제 후 재작성, USER·ADMIN 격리, 정렬·페이지, 공개 필드, 큰 ID·version | O |
| `ReviewConcurrencyTest`, `ReviewRollbackTest` | 동시 작성, 같은 버전 동시 수정, 수정·삭제 경쟁, 잠금 대기 중 재작성(트랜잭션 순서 재현), 롤백, FK 위반 → 404 | O |
| `ReviewQueryCountTest` | 리뷰·작성자 수가 늘어도 공개 목록 SQL 수가 같음, 닉네임 일괄 조회 1회, 빈 페이지는 닉네임 조회 생략 (N+1 회귀) | O |
| `ReviewExpectedUserIntegrationTest` | `X-Expected-User-Id` 필수·형식, 계정 전환 뒤 요청(새 세션·유효 CSRF)의 POST·PUT·DELETE·GET 409 `USER_MISMATCH` 와 무변경, 입력 검증보다 먼저 확인, 큰 사용자 ID 정확 비교 (#22) | O |

- 통합 테스트는 테스트 DB 이름이 `_test`로 끝나고 `DB_NAME`과 다른지 확인한 뒤, `TRUNCATE` 직전에 `current_database()`가 그 이름과 같은지 다시 확인합니다. 이름이 같거나, 비어 있거나, 접미사가 없으면 정리를 거절합니다. `_test` 접미사는 안전장치이며, 그 DB의 과거 용도를 증명하지는 않습니다.
- 등록 API 통합 테스트는 ADMIN 사용자와 CSRF 토큰으로 요청합니다(spring-security-test).

테스트 리포트: `backend/build/reports/tests/test/index.html`

#### 로그인 보호 측정 도구 (#28)

`test`·`build`에서는 실행되지 않는 별도 작업입니다. 운영 코드와 분리된 테스트 소스(`com.manhwanet.benchmark`)에 있습니다. 결과 해석과 재조정 기준은 [docs/login-limit-design-draft.md](docs/login-limit-design-draft.md) §5.2·§10·§14를 보세요.

```powershell
cd backend
.\gradlew.bat hashBenchmark                    # Argon2id 동시성별 처리량·p50·p95, 1회 할당량, 게이트(P·Q·W) 경유 결과 (DB 불필요)
.\gradlew.bat limiterMemory                    # 제한기 항목당 메모리: 이메일 45·254자, 실패 1·4회, 잠금, 320자 UTF-16 최악 (DB 불필요)
.\gradlew.bat loginLoad -PbenchArgs="--base http://127.0.0.1:8080/api/v1 --concurrency 16,64,128"   # 실행 중인 서버에 부하
```

- `loginLoad`는 무작위 `example.test` 주소(없는 이메일)로만 로그인하므로 DB에 계정·데이터를 만들지 않습니다(서버 메모리의 제한기 항목만 생김). 그래도 개발 DB 대신 테스트용 DB에 연결한 서버에서 실행하기를 권장합니다.
- 인자 예: `-PbenchArgs="--concurrency 1,2,4,8 --seconds 5 --gate-p 2 --gate-q 4 --gate-wait-ms 250"`

### Frontend

```bash
cd frontend
npm test                                  # src/**/*.test.ts (JSON 무손실 파싱, 프록시 규칙, 브라우저 API·CSRF·204, returnTo, 입력 검사, 별점 규칙, 리뷰 본문·직렬화·충돌 판단·시각, 오류 안내)
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

### 로그인 보호 설정 (선택, #28)

기본값은 `application.yml`에 있고 바꾸지 않아도 됩니다. 잠정값이므로 운영 장비에서 측정 후 조정합니다([명세 §2·§5.2](docs/login-limit-design-draft.md)).

| 속성 (`.env`·명령행) | OS 환경변수 | 기본값 | 허용 범위 |
| --- | --- | --- | --- |
| `manhwanet.auth.hash-limit.max-concurrent` | `MANHWANET_AUTH_HASH_LIMIT_MAX_CONCURRENT` | `2` | 1 이상 |
| `manhwanet.auth.hash-limit.max-waiting` | `MANHWANET_AUTH_HASH_LIMIT_MAX_WAITING` | `4` | 0 이상 (0 = 자리가 없으면 바로 503) |
| `manhwanet.auth.hash-limit.max-wait` | `MANHWANET_AUTH_HASH_LIMIT_MAX_WAIT` | `250ms` | 0 초과 1s 이하 |
| `manhwanet.auth.login-limit.max-entries` | `MANHWANET_AUTH_LOGIN_LIMIT_MAX_ENTRIES` | `46000` | 1 이상. 512MiB 초기값. 가득 차면 신규 이메일 로그인만 503. 1GiB 재측정 예시는 `93000` |
| `manhwanet.auth.login-limit.max-failures`·`window`·`lock-duration` | (같은 규칙) | `5`·`15m`·`15m` | 확정 정책. 바꾸지 않습니다 |

- `.env`에는 **속성 이름(소문자·점)** 으로 씁니다. 예: `manhwanet.auth.hash-limit.max-concurrent=3`. `.env`는 `.properties`로 읽히므로 `MANHWANET_...` 대문자 이름은 무시됩니다(오류 없이 기본값 사용). 대문자 이름은 OS 환경변수에서만 동작합니다.
- 명령행: `java -jar ... --manhwanet.auth.hash-limit.max-concurrent=3`
- 범위를 벗어나면 서버가 시작되지 않고 `manhwanet.auth.hash-limit.max-wait must be > 0 and <= 1s`처럼 이유를 남깁니다.
- 적용된 값은 시작 로그 `login-protection config: ...` 한 줄로 확인합니다.

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
| POST | `/api/v1/auth/signup` | 가입(항상 USER) + 자동 로그인. 해시 과부하 시 503 `AUTH_BUSY` + `Retry-After` |
| POST | `/api/v1/auth/login` | 로그인. 계정 잠금·진행 중 한도 429 `TOO_MANY_LOGIN_ATTEMPTS`, 해시 과부하·제한 저장소 포화(신규 이메일) 503 `AUTH_BUSY`, 모두 `Retry-After` |
| POST | `/api/v1/auth/logout` | 로그아웃 |
| GET | `/api/v1/auth/me` | 현재 사용자 (로그인 필요) |
| GET | `/api/v1/me/ratings/{workId}` | 내 별점 (로그인 필요). 평가하지 않았으면 `score: null` |
| PUT | `/api/v1/me/ratings/{workId}` | 내 별점 등록·수정 `{"score": 4.5}` (0.5~5.0, 0.5 단위). 헤더 `X-Expected-User-Id` 필수 |
| DELETE | `/api/v1/me/ratings/{workId}` | 내 별점 삭제 (204, 반복 가능). 헤더 `X-Expected-User-Id` 필수 |
| GET | `/api/v1/works/{workId}/reviews` | 작품별 공개 리뷰 목록 (`page`, `size` 1~50 기본 10, 작성 시각 최신순) |
| GET | `/api/v1/me/reviews/{workId}` | 내 리뷰 (로그인 필요). 작성하지 않았으면 `review: null` |
| POST | `/api/v1/me/reviews/{workId}` | 내 리뷰 작성 `{"body", "containsSpoiler"}` (201, 이미 있으면 409) |
| PUT | `/api/v1/me/reviews/{workId}` | 내 리뷰 수정 `{"reviewId", "version", "body", "containsSpoiler"}` (불일치 409) |
| DELETE | `/api/v1/me/reviews/{workId}?reviewId=&version=` | 내 리뷰 삭제 (204, 리뷰가 없어도 204, 불일치 409) |

- 내 리뷰 POST·PUT·DELETE 와 내 별점 PUT·DELETE 는 헤더 `X-Expected-User-Id`(화면을 렌더링한 사용자 ID, 십진수 문자열)가 필수입니다. 이 요청의 로그인 사용자와 다르면 409 `USER_MISMATCH` 로 아무것도 바꾸지 않습니다(계정 전환 중 다른 계정에 반영되는 것을 막음). 작성자·별점 소유자는 항상 세션 사용자입니다. 값은 일치 확인에만 씁니다.
| POST | `/api/v1/admin/works` | 작품 등록 (ADMIN). `evidence` 필수(제목·크레딧, 줄거리가 있으면 줄거리), `listings`·`externalIds`·`links`·`reason` 선택. 응답은 관리자용 상세 |
| GET | `/api/v1/admin/works/{workId}` | 관리자용 작품 상세: 공개 상세 + `version`, `listings`, `externalIds`, `links`, `fieldSources` (ADMIN) |
| PUT | `/api/v1/admin/works/{workId}` | 작품 핵심 정보 전체 교체 `{"version", "workFormat", "originCountry", "description", "titles", "creators", "evidence", "reason"}` (ADMIN, `reason` 필수, 불일치 409 `WORK_VERSION_CONFLICT`) |
| POST | `/api/v1/admin/creators` | 작가 등록 (ADMIN). `externalIds`·`reason` 선택. 응답은 관리자용 상세 |
| GET | `/api/v1/admin/creators/{creatorId}` | 관리자용 작가 상세: `version`, `linkedWorkCount`, `externalIds` (ADMIN) |
| PUT | `/api/v1/admin/creators/{creatorId}` | 작가 이름 전체 교체 `{"version", "name", "originalName", "reason"}` (ADMIN, `reason` 필수, 불일치 409 `CREATOR_VERSION_CONFLICT`) |
| POST·PATCH | `/api/v1/admin/works/{workId}/external-ids[/{id}]`, `/api/v1/admin/creators/{creatorId}/external-ids[/{id}]` | 외부 ID 추가(201)·상태 변경(`ACTIVE`·`SUPERSEDED`·`INVALID`). 같은 출처·ID 중복 409 `EXTERNAL_ID_ALREADY_LINKED` (ADMIN, #40) |
| POST·PATCH | `/api/v1/admin/works/{workId}/links[/{id}]` | 출판사·공식 사이트 링크 추가(201)·상태 변경(`ACTIVE`·`BROKEN`·`REMOVED`) (ADMIN, #40) |
| POST·PATCH | `/api/v1/admin/works/{workId}/field-sources[/{id}]` | 필드별 근거 추가(201)·철회(`RETRACTED`) (ADMIN, #40) |
| GET | `/api/v1/admin/change-log?workId=` \| `?creatorId=` \| `?entityType=&entityId=` | 관리 변경 기록 최신순(`limit` 1~100, `beforeId`) (ADMIN, #40) |
| GET·PUT | `/api/v1/admin/collection-policies[/{platformCode}]` | 플랫폼 수집 설정 조회·전체 교체(`reason` 필수, 불일치 409 `COLLECTION_POLICY_VERSION_CONFLICT`) (ADMIN, #45) |
| GET | `/api/v1/admin/crawl-runs[/{runId}]` | 수집 실행 목록·상세(작업 상태별 수) (ADMIN, #45) |
| POST | `/api/v1/admin/crawl-runs/{runId}/stop` | 수집 실행 중단 요청 `{"reason"}` (ADMIN, #45) |
| GET | `/api/v1/admin/staging-listings[/{id}]` | 수집 대기(staging) 목록·상세. 비공개, 작품으로 자동 등록하지 않음 (ADMIN, #45) |
| GET | `/api/v1/admin/platforms` | 플랫폼 10곳: 코드·이름·운영 상태와 근거·URL 규칙·허용 호스트·`version` (ADMIN) |
| GET·PUT | `/api/v1/admin/platforms/{code}` | 플랫폼 조회·URL 규칙 등 전체 교체 (ADMIN, 불일치 409 `PLATFORM_VERSION_CONFLICT`) |
| POST | `/api/v1/admin/platforms/{code}/status` | 운영 상태 변경 + 이력 `{"version", "toStatus", "statusBasis", "mergedInto", "effectiveOn", "evidenceUrl", "note", "reason"}` (ADMIN, `reason` 필수) |
| GET | `/api/v1/admin/platforms/{code}/status-history` | 상태 변경 이력 최신순 (ADMIN) |
| GET·POST | `/api/v1/admin/works/{workId}/listings` | 작품 플랫폼 연결 목록·등록 (ADMIN, 작품 `version` 사용, 중복 409 `PLATFORM_LISTING_ALREADY_LINKED`) |
| PUT | `/api/v1/admin/works/{workId}/listings/{listingId}` | 연결 수정·종료(`ENDED`)·이전 표시(`MOVED`) (ADMIN) |
| POST | `/api/v1/admin/works/{workId}/listings/{listingId}/move` | 새 연결 등록 + 기존 연결 `MOVED` 를 한 번에 (ADMIN) |
| GET | `/api/v1/admin/listings/lookup?platformCode=&platformWorkId=` | 같은 플랫폼 작품이 이미 연결됐는지 확인 (ADMIN) |

- 페이지: `page` 0부터(기본 0), `size` 1~100(기본 20), `sort`는 `createdAt` 또는 `id`에 `,asc`/`,desc` (기본 `createdAt,desc` + `id,desc`). 범위 밖 값이나 허용하지 않은 정렬은 400입니다. `page × size`(offset)는 2,147,483,647 이하만 지원하며, 넘으면 `page` 필드 오류(400)입니다(예: `size=100`이면 `page` 최대 21474836).
- 요청·응답 형식과 검증 규칙은 [docs/work-creator-spec.md](docs/work-creator-spec.md), 인증은 [docs/auth-design-draft.md](docs/auth-design-draft.md), 별점은 [docs/rating-design-draft.md](docs/rating-design-draft.md), 리뷰는 [docs/review-design-draft.md](docs/review-design-draft.md)를 참고하세요.
- 관리자 수정(PUT)은 GET 으로 받은 `version` 을 그대로 보내야 합니다. 다르면 409 이고 `error.currentVersion` 에 현재 값이 담기며 아무것도 바뀌지 않습니다. 같은 `version` 으로 보낸 내용이 현재와 같으면 `version` 이 오르지 않습니다. 성공한 수정을 이전 `version` 으로 다시 보내면 409 입니다. 제목·크레딧은 한 트랜잭션에서 통째로 바뀌고, 작품·작가 ID와 별점·리뷰는 그대로입니다. 삭제 API 는 없습니다. 규칙은 [docs/work-platform-admin-spec.md](docs/work-platform-admin-spec.md) §5.1·§5.2를 참고하세요.
- 플랫폼 연결(#39)은 작품 `version` 을 함께 올립니다. URL 은 `https`·플랫폼 허용 호스트·작품 URL 패턴으로만 검사하고 서버가 가져오지 않습니다. 제어 문자 인코딩과 중첩된 `.` 경로는 거절합니다. 등급은 원래 표기를 저장하고, 명확한 18세 미만은 `GENERAL`, 명확한 성인 표기(18·19세 이용가, `19금`, 청소년 이용불가, `청불`)는 `ADULT`, 모호하거나 확인하지 못한 표기는 `UNKNOWN`입니다. `UNKNOWN` 은 일반으로 올리지 않고, 인식하지 못한 표기를 `ADULT` 로 지정하려면 사유 메모가 필요합니다. 연결·플랫폼 삭제 API 는 없고 상태로만 바꿉니다. 연결 정보는 아직 공개 API 에 나오지 않습니다(#42). 규칙은 [docs/work-platform-admin-spec.md](docs/work-platform-admin-spec.md) §3.2 구현 기록을 참고하세요.
- #40: 관리자 변경은 바뀐 내용과 같은 트랜잭션에서 `admin_change_log`에 기록되고(이전·이후 값, 사유, 관리자 사용자 ID), 실패하면 둘 다 되돌아갑니다. 수정·상태 변경 요청은 `reason` 이 필수이고, 등록은 선택입니다. 새 플랫폼 연결과 작품 등록에는 근거(`evidence`)가 필요하며 줄거리 근거는 직접 작성(`STAFF`)·허락받은 글(`LICENSED`)만 됩니다. 외부 ID 는 같은 출처에서 한 작품(작가)에만 연결되고, `INVALID`(잘못 연결)로 바꾸면 다른 대상에 다시 연결할 수 있습니다. 외부 ID·링크·근거 URL 은 서버가 가져오지 않습니다. 삭제 API 는 없고 상태로 비활성화합니다. 규칙은 [docs/work-platform-admin-spec.md](docs/work-platform-admin-spec.md) §3.3 구현 기록을 참고하세요.
- 작품 목록·상세 응답에는 `rating: { average, count }`가 있습니다. `average`는 소수 첫째 자리(HALF_UP)이고 별점이 없으면 `null`입니다.
- POST 등 변경 요청은 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보내야 합니다. 공개 GET(작품·작가·health) 외의 경로는 로그인이 필요합니다.

## API 공통 규약

- Base prefix: `/api/v1` (Controller에는 도메인 경로만 선언하면 `WebConfig`가 prefix를 붙입니다.)
- 성공: `{"success": true, "data": ...}`
- 실패: `{"success": false, "error": {"code": "INVALID_INPUT", "message": "...", "fieldErrors": [{"field": "name", "reason": "..."}]}}`
- 페이지 응답 `data`: `{"content": [...], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0, "hasNext": false}`
