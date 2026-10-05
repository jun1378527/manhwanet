# 만화넷 (manhwanet)

한국 웹툰과 일본 만화를 통합하여 작품을 검색하고, 별점·리뷰·댓글을 작성하며, 여러 기준의 작품 랭킹을 확인할 수 있는 서비스입니다.

> **현재 단계: Phase 1 — Foundation**
> 비즈니스 기능(회원, 작품, 별점, 리뷰, 랭킹, 검색 등)은 아직 구현되어 있지 않습니다.
> 이후 기능 개발을 위한 프로젝트 기반(빌드, DB 연결, 마이그레이션, 공통 응답/예외 처리, 테스트 환경)만 구성되어 있습니다.

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
│       │   └── global/
│       │       ├── config/          # WebConfig (/api/v1 prefix)
│       │       ├── response/        # ApiResponse, ApiError (공통 응답)
│       │       ├── error/           # ErrorCode, BusinessException, GlobalExceptionHandler
│       │       └── health/          # GET /api/v1/health
│       ├── main/resources/
│       │   ├── application.yml      # 공통 설정
│       │   ├── application-local.yml# 로컬 프로필 (기본)
│       │   ├── application-test.yml # 테스트 프로필
│       │   └── db/migration/        # Flyway 마이그레이션 (V1__init.sql)
│       └── test/java/com/manhwanet/
├── frontend/                        # Next.js + TypeScript + App Router
│   ├── src/app/                     # layout.tsx, page.tsx
│   ├── src/config/env.ts            # 환경변수 접근
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
- **Node.js 20.9 이상** (권장: 22 LTS) 및 npm
- Gradle은 설치할 필요 없습니다. (`gradlew` Wrapper가 8.14.3을 내려받습니다.)

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

### 3. Frontend 실행

```bash
cd frontend
cp .env.example .env.local                # PowerShell: Copy-Item .env.example .env.local
npm install
npm run dev
```

브라우저에서 http://localhost:3000 을 열면 "만화넷"이 표시됩니다.

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
| `ManhwanetApplicationTests` | 컨텍스트 로딩, Flyway V1 적용, 실제 DB 대상 health, 404 | O |

테스트 리포트: `backend/build/reports/tests/test/index.html`

### Frontend

```bash
cd frontend
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
| `NEXT_PUBLIC_API_BASE_URL` | Backend API base URL | `http://localhost:8080/api/v1` |

`NEXT_PUBLIC_` 값은 브라우저 번들에 포함되므로 secret을 넣지 않습니다. 코드에서는 `src/config/env.ts`의 `env.apiBaseUrl`로 접근합니다.

## API 공통 규약

- Base prefix: `/api/v1` (Controller에는 도메인 경로만 선언하면 `WebConfig`가 prefix를 붙입니다.)
- 성공: `{"success": true, "data": ...}`
- 실패: `{"success": false, "error": {"code": "INVALID_INPUT", "message": "...", "fieldErrors": [{"field": "name", "reason": "..."}]}}`
