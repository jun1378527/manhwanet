# 포트폴리오 데모 실행 가이드 (#67)

새 PC에서 이 문서만 따라 **가상 데이터**로 다음 흐름을 재현합니다.

```
모의 수집 → 검수 → HIDDEN 등록 → 명시적 공개 → 작품 목록·상세 → 회원 로그인 → 별점·리뷰
└──────── 명령(Gradle demo 작업)으로 보여 줌 ────────┘ └────────── 기존 프론트 화면에서 시연 ──────────┘
```

관리자 화면은 따로 만들지 않았습니다. 수집·검수·공개는 명령 출력으로 보여 주고, 독자 쪽은 기존 프론트(`/works`, `/works/{id}`, `/login`)를 그대로 씁니다.

## 1. 무엇이 가상이고 무엇이 실제인가

| 구분 | 내용 |
| --- | --- |
| 작품·작가 | `[DEMO] 가상의 별빛 정원`·`[DEMO] 가상의 달빛 서점`·`[DEMO] 가상의 시계탑 소녀`, 작가 `[DEMO] 가상작가 …`. 모두 지어낸 이름이고 원래 등급 표기는 전체·12세·15세 이용가(GENERAL)입니다. 지금 공개 정책상 공개할 수 있는 등급만 씁니다 |
| 수집 대상 | 데모 명령 안에서 뜨는 **127.0.0.1 모의 플랫폼 서버**. 주소 `toomics.demo.mock.test` 는 데모 명령에서만 127.0.0.1 로 연결되고 그 밖의 이름은 해석하지 않습니다. 실제 플랫폼 요청·표지 다운로드는 없습니다 |
| 플랫폼 코드 | 외래 키 때문에 `TOOMICS` 코드를 빌립니다. 허용 호스트 추가·수집 설정 ENABLED 는 **데모 DB 에서만**, 관리 서비스를 거쳐 변경 기록과 함께 바뀝니다 |
| 실제 코드 경로 | 계정(`AdminAccountService`·회원 가입 `UserService`), 수집(`CollectorEngine`, 요청 간격 5초·robots.txt 그대로), 검수(`StagingReviewService.CREATE_WORK` → 작품을 처음부터 HIDDEN 으로 등록), 공개(`WorkService.changeVisibility`, version·사유·변경 기록). DB 에 직접 넣어 흐름을 건너뛰지 않습니다 |
| DB | 개발 DB(`manhwanet`)·테스트 DB(`manhwanet_test`)와 분리된 **데모 DB `manhwanet_demo`** |

### 안전장치

- 데모 DB 이름은 `_demo` 로 끝나야 하고 `DB_NAME`·`TEST_DB_NAME` 과 달라야 합니다. `demo` 프로필은 Flyway 마이그레이션 **전에** 실제로 연결된 DB 이름(`current_database()`)을 확인하고, 맞지 않으면 기동하지 않습니다.
- 데모 명령(`gradlew demo`)은 데이터를 지우는 단계가 없습니다. 다시 실행하면 있는 계정·작품은 그대로 두고 남은 일만 합니다.
- 초기화는 별도 스크립트(`scripts/demo/reset-demo-db.ps1`)로만 하고, 데모 DB 이름을 직접 입력해야 진행합니다.
- 모의 서버·데모 어댑터·데모 명령은 별도 소스 세트(`backend/src/demo`)에 있어 **bootJar·일반 bootRun 에 들어가지 않습니다.** 운영 수집기의 공개 주소 검사, 인증·CSRF·비밀번호 규칙은 바뀌지 않습니다.
- 계정 비밀번호는 저장소에 없습니다. 실행할 때 사용자가 넣거나, 명령이 만들어 **한 번만** 화면에 보여 줍니다.

## 2. 요구 환경 (Windows 기준)

README의 [요구 환경](../README.md#요구-환경)과 같습니다: JDK 21, Docker Desktop, Node.js 24.19 이상 25 미만, PowerShell(Windows PowerShell 5.1 또는 PowerShell 7). 아래 명령은 모두 저장소 루트(`manhwanet\`)의 PowerShell 기준입니다.

> 스크립트 실행이 막혀 있으면(실행 정책) 현재 창에서만 허용합니다: `Set-ExecutionPolicy -Scope Process Bypass`

## 3. 준비 (최초 1회)

```powershell
Copy-Item .env.example .env               # 이미 있으면 건너뜀. DB_PASSWORD 를 채웁니다
docker compose up -d
docker compose ps                         # STATUS 가 healthy 인지 확인
.\scripts\demo\create-demo-db.ps1         # 데모 DB 가 없으면 만듦(있으면 그대로)
```

- 빈 볼륨으로 처음 띄우면 `docker/postgres/init/02-create-demo-database.sh` 가 데모 DB 를 함께 만듭니다. 이미 쓰던 볼륨이면 위 스크립트가 만듭니다. 둘 다 데이터를 지우지 않습니다.
- 데모 DB 이름을 바꾸려면 `.env` 에 `DEMO_DB_NAME=<이름>_demo` 를 둡니다(기본 `manhwanet_demo`).
- 프론트 의존성: `cd frontend; Copy-Item .env.example .env.local; npm install` (README §3)

## 4. 데모 데이터 만들기 (명령으로 보여 주는 부분)

### 4.1 비밀번호 정하기 (선택)

정하지 않으면 계정을 처음 만들 때 24자 비밀번호를 만들어 **그때 한 번만** 출력합니다(파일·로그·변경 기록에 저장하지 않음). 직접 정하려면 **데모 명령과 백엔드를 실행하는 PowerShell 창**에서만 입력합니다(15~128자, 화면·명령 기록에 남지 않음).

```powershell
$env:MANHWANET_DEMO_READER_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host -AsSecureString '독자 비밀번호')).Password
$env:MANHWANET_DEMO_ADMIN_PASSWORD  = [System.Net.NetworkCredential]::new('', (Read-Host -AsSecureString '관리자 비밀번호')).Password
```

`MANHWANET_DEMO_ADMIN_PASSWORD` 와 `MANHWANET_DEMO_READER_PASSWORD` 는 그 창의 데모 명령(`gradlew demo`)과 백엔드(`bootRun`)에만 제공합니다. 사용자 환경변수나 시스템 환경변수로 등록하지 마세요. 프론트는 §5 의 **이 두 변수가 없는 새 창**에서 실행합니다. 변수가 있는 창에서 `npm run dev` 를 켜면 프론트 개발 서버가 프로세스 환경을 캐시에 남길 수 있습니다.

시연이 끝나면 그 창에서 `Remove-Item Env:MANHWANET_DEMO_*` 로 지웁니다. 계정이 이미 있으면 비밀번호를 바꾸지 않습니다(잊었으면 §7 초기화).

### 4.2 한 번에 실행

```powershell
cd backend
.\gradlew.bat demo -Pstep=all
```

요청 간격 규칙(플랫폼당 5초 이상) 때문에 수집에 30초쯤 걸립니다. 출력 예(번호·시각은 다를 수 있음):

```
[DEMO] ========== [DEMO] all (DB manhwanet_demo) ==========
[DEMO] 관리자 계정 생성: demo-admin@manhwanet.test
    비밀번호(실행 시 생성, 이번에만 표시하고 어디에도 저장하지 않음): ********
[DEMO] 독자 계정 생성(회원 가입 서비스): demo-reader@manhwanet.test ...
[DEMO] --- 1) 모의 수집 ---
[DEMO] 플랫폼 TOOMICS 허용 호스트에 데모 호스트 추가: toomics.demo.mock.test (데모 DB 에서만)
[DEMO] 수집 설정 TOOMICS → ENABLED (데모 DB 에서만, 변경 기록 남음)
[DEMO] 모의 플랫폼 서버 127.0.0.1:xxxxx 시작 (실제 사이트 요청·표지 다운로드 없음). ...
[DEMO] 수집 실행 #1: COMPLETED, 모의 서버 요청 5건
[DEMO] --- 2) 검수 → HIDDEN 등록 ---
[DEMO] staging #1 `[DEMO] 가상의 별빛 정원` (전체 이용가) → 후보 없음 → CREATE_WORK → 작품 #1 를 HIDDEN 으로 등록. 공개 상세: 404(숨김: 없는 작품과 같음)
...
[DEMO] --- 3) 명시적 공개 ---
[DEMO] 작품 #1 HIDDEN → PUBLIC (version 0 → 1, 변경 기록·사유 남김). 공개 상세: 200(보임)
...
[DEMO] 공개 목록 작품 수: 3
```

### 4.3 단계별로 보여 주기 (HIDDEN 확인을 화면에서 보이고 싶을 때)

```powershell
.\gradlew.bat demo -Pstep=collect   # 계정 + 모의 수집 → staging
.\gradlew.bat demo -Pstep=review    # 검수 → 작품을 HIDDEN 으로 등록 (이때 프론트 /works 에 안 보이고 상세는 404)
.\gradlew.bat demo -Pstep=publish   # 관리자가 명시적으로 PUBLIC 전환 (이제 목록·상세에 보임)
.\gradlew.bat demo                  # status: 현황만 읽기 (기본값)
```

단계 이름: `accounts`·`collect`·`review`·`publish`·`status`·`all`. 데모 서버(§5)를 켜 둔 채로 실행해도 됩니다.

## 5. 기동

창을 나눕니다. 비밀번호 환경변수가 있는 창은 백엔드·데모 명령만 쓰고, 프론트는 그 변수가 없는 새 창에서 켭니다.

```powershell
# 창 1: §4 와 같은 창. Backend (데모 DB 에 연결)
# MANHWANET_DEMO_ADMIN_PASSWORD, MANHWANET_DEMO_READER_PASSWORD 는 이 창에만 둡니다.
cd backend
.\gradlew.bat bootRun --args="--spring.profiles.active=local,demo"
# 확인: Invoke-RestMethod http://127.0.0.1:8080/api/v1/health  → status UP

# 창 2: 새 PowerShell. 위 두 비밀번호 환경변수를 넣지 않습니다.
cd frontend
npm run dev
```

- 프론트 창을 열기 전에 `$env:MANHWANET_DEMO_ADMIN_PASSWORD` 와 `$env:MANHWANET_DEMO_READER_PASSWORD` 가 비어 있는지 확인하세요. 비밀번호 창에서 프론트를 실행하지 마세요.
- 같은 포트(8080)를 쓰므로 개발 DB 로 띄운 Backend 가 있으면 먼저 끕니다.
- `local,demo` 는 일반 `local` 설정과 같고 DB 만 데모 DB 로 바뀝니다(보안 설정 동일).

## 6. 화면 시연 순서

1. http://localhost:3000/works — `[DEMO]` 작품 3개(작가 크레딧, 별점 없음)
2. 작품 하나를 눌러 상세 — 제목·작가 역할·국가, 별점·리뷰 영역
3. 오른쪽 위 로그인 → `demo-reader@manhwanet.test` / §4 의 독자 비밀번호
4. 상세에서 별점(0.5~5.0)을 고르고 "저장" → 평균·참여자 수 갱신
5. 리뷰(10자 이상)를 쓰고 "등록" → 내 리뷰·공개 리뷰 목록에 표시

## 7. 종료와 초기화

- **종료**: 두 창에서 `Ctrl+C`. DB 는 `docker compose stop`(데이터 유지). `docker compose down -v` 는 개발·테스트 DB 까지 지우므로 데모 초기화에 쓰지 마세요.
- **데모 데이터 초기화** (데모 DB 만, 확인 필요):

```powershell
# 데모 서버·데모 명령을 먼저 끕니다
.\scripts\demo\reset-demo-db.ps1                                   # DB 이름을 직접 입력해야 진행
.\scripts\demo\reset-demo-db.ps1 -ConfirmDatabase manhwanet_demo   # 확인 이름을 인자로 넘김
cd backend; .\gradlew.bat demo -Pstep=all                          # 다시 만들기(새 비밀번호)
```

`reset-demo-db.ps1` 은 `DEMO_DB_NAME` 이 `_demo` 로 끝나지 않거나 개발·테스트 DB 이름과 같으면 아무것도 하지 않고 멈춥니다. 확인 이름이 다르면 취소합니다. 그 밖의 스크립트·명령은 데이터를 지우지 않습니다.

## 8. 문제 해결

| 증상 | 원인·조치 |
| --- | --- |
| `Refusing to run demo on database ...` / `DEMO_DB_NAME must ...` | 데모 DB 이름 규칙 위반 또는 다른 DB 에 연결됨. `.env` 의 `DEMO_DB_NAME`·`DB_NAME`·`TEST_DB_NAME` 확인 |
| `database "manhwanet_demo" does not exist` | §3 `create-demo-db.ps1` 실행 |
| `psql 실행 실패` | `docker compose ps` 로 postgres 가 healthy 인지 확인 |
| 8080 포트 사용 중 | 개발용 Backend 를 끄거나 `SERVER_PORT` 를 바꾸고 `frontend/.env.local` 의 `API_BASE_URL` 도 맞춤 |
| 로그인 실패(비밀번호 모름) | 생성 비밀번호는 다시 볼 수 없습니다. §7 초기화 뒤 다시 만들기 |
| 검수 단계에서 "열린 중복 후보 → 건너뜀" | 데모 DB 에 같은 제목 작품을 직접 만든 경우입니다. 데모 명령은 중복 판단을 대신하지 않습니다. 초기화 후 다시 실행 |

## 9. 검증 범위

- 클라우드 Linux(격리 PostgreSQL 17, Node 24)에서 확인: 데모 명령 전체·재실행, 데모 DB 이름 거절, bootJar 에 데모 코드 없음, `local,demo` 서버의 health·목록·상세·로그인·별점·리뷰 API, 프론트(`next build`/`start`) 화면에서 로그인·별점·리뷰, PowerShell 7 로 DB 스크립트(docker 대신 대체 명령) 확인. 테스트: `DemoScenarioIntegrationTest`, `DemoDatabaseNamesTest`.
- Windows 실행 결과는 #67 이슈 기록을 참고하세요.
