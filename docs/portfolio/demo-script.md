# 3~5분 시연 대본

> 기준 커밋 [`0a1c7f7`](https://github.com/jun1378527/manhwanet/commit/0a1c7f7). 녹화는 아직 하지 않았습니다. 명령과 화면 문구는 [데모 가이드](../demo-guide.md)와 현재 코드에서 가져왔습니다. 모든 작품·작가는 `[DEMO]` 가상 데이터이고, 수집은 127.0.0.1 모의 플랫폼에서만 일어납니다.

흐름: **모의 수집 → 검수 → HIDDEN 등록 → 명시적 공개 → 목록·상세 → 로그인·별점·리뷰**

## 0. 녹화 전 준비 (화면에 넣지 않음)

창을 세 개 엽니다. 창 A·B는 데모 비밀번호 환경변수를 넣는 창이고, 창 C(프론트)에는 넣지 않습니다(가이드 §5).

```powershell
# 창 A (명령용, 저장소 루트)
docker compose up -d
.\scripts\demo\reset-demo-db.ps1 -ConfirmDatabase manhwanet_demo   # 이전 녹화 데이터를 비움 (데모 DB만)
$env:MANHWANET_DEMO_READER_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host -AsSecureString '독자 비밀번호')).Password
$env:MANHWANET_DEMO_ADMIN_PASSWORD  = [System.Net.NetworkCredential]::new('', (Read-Host -AsSecureString '관리자 비밀번호')).Password
cd backend; .\gradlew.bat compileDemoJava     # 첫 빌드 시간을 녹화에서 빼기 위해 미리 컴파일

# 창 B (백엔드): 비어 있는 데모 DB로 서버를 먼저 켬
cd backend; .\gradlew.bat bootRun --args="--spring.profiles.active=local,demo"

# 창 C (프론트, 비밀번호 변수 없음)
cd frontend; npm run dev
```

- 브라우저에 `http://localhost:3000/works`를 열고 "등록된 작품이 없습니다"가 보이는지 확인합니다.
- 비밀번호를 환경변수로 정했으므로 데모 출력에 비밀번호가 나오지 않습니다. 화면에 비밀번호를 띄우지 않습니다.
- 수집 단계는 요청 간격(최소 5초) 때문에 30초쯤 걸립니다. 편집에서 배속하거나 자르고, 자막으로 "실제 5초 간격 대기"라고 밝힙니다.
- **리허설(2026-10-16, 데모 DB, 서버 실행 중)**: `/works` 는 "등록된 작품이 없습니다". `/works/1` 은 HTTP 404, 화면 제목 "작품을 찾을 수 없습니다". 공개 뒤 API·페이지는 HTTP 200이고 제목이 보입니다. 로그인·별점 4.5·리뷰 1개도 새로고침 뒤 유지되었습니다.

## 1. 대본 (약 4분 30초)

| 시간 | 화면 | 동작·명령 | 말할 내용 |
| --- | --- | --- | --- |
| 0:00–0:30 | [아키텍처 그림](project-overview.md#아키텍처) | — | "여러 플랫폼의 작품 정보를 모을 때 가장 위험한 건 잘못 합쳐지거나 검수 전에 공개되는 것입니다. 그래서 수집 결과는 바로 공개하지 않고, 비공개 대기 → 검수 → 숨김 등록 → 명시적 공개를 거치게 만들었습니다. 오늘은 분리된 데모 DB와 가상 작품으로 그 흐름을 보여 드립니다." |
| 0:30–1:15 | 창 A | `.\gradlew.bat demo -Pstep=collect` | 출력의 `허용 호스트에 데모 호스트 추가`, `수집 설정 TOOMICS → ENABLED (… 변경 기록 남음)`, `모의 플랫폼 서버 127.0.0.1:…`, `수집 실행 #1: COMPLETED, 모의 서버 요청 5건`을 가리키며: "수집 설정은 기본이 꺼짐이라 관리 서비스를 거쳐 켜고, 변경 기록이 남습니다. 수집기는 robots.txt를 읽고 플랫폼당 5초 이상 간격으로 한 건씩 요청합니다. 지금은 실제 사이트가 아니라 내 PC 안의 모의 서버입니다." |
| 1:15–1:30 | 브라우저 `/works` 새로고침 | — | "수집했지만 목록은 여전히 비어 있습니다. 수집 결과는 비공개 대기 표(staging)에만 있습니다." |
| 1:30–2:10 | 창 A | `.\gradlew.bat demo -Pstep=review` | `staging #1 … → 후보 없음 → CREATE_WORK → 작품 #1 를 HIDDEN 으로 등록. 공개 상세: 404(숨김: 없는 작품과 같음)`: "검수는 중복 후보부터 확인합니다. 제목만 같다고 합치지 않고, 열린 후보가 있으면 이 명령은 자동으로 판단하지 않고 건너뜁니다. 새 작품은 처음부터 숨김 상태로 들어갑니다." |
| 2:10–2:30 | 브라우저 `/works`, `/works/1` | 주소창에 직접 입력 | 목록은 비어 있고 상세는 "작품을 찾을 수 없습니다": "숨긴 작품은 공개 경로에서 없는 작품과 똑같이 404로 보입니다. 존재 여부도 드러내지 않습니다." (2026-10-16 리허설에서 HTTP 404와 이 문구를 확인) |
| 2:30–3:00 | 창 A → 브라우저 `/works` | `.\gradlew.bat demo -Pstep=publish` 후 새로고침 | `작품 #1 HIDDEN → PUBLIC (version 0 → 1, 변경 기록·사유 남김). 공개 상세: 200(보임)`: "공개는 관리자가 버전과 사유를 붙여 명시적으로 합니다. 지금 공개 정책상 전체·12세·15세 같은 일반 등급만 공개할 수 있습니다." 목록에 `[DEMO]` 작품 3개가 나타남 |
| 3:00–3:25 | `/works/1` 상세 | 작품 카드 클릭 | 제목·작가 역할(글·그림)·국가, 별점 "아직 평가가 없습니다", 리뷰 영역: "여기부터는 기존 프론트 화면 그대로입니다." |
| 3:25–3:50 | `/login` | 오른쪽 위 "로그인" → `demo-reader@manhwanet.test` / 준비한 비밀번호 | "세션 쿠키와 CSRF 토큰으로 인증합니다. 비밀번호는 저장소에 없고 실행할 때 정했습니다." (비밀번호 입력 장면은 가리거나 자름) |
| 3:50–4:20 | `/works/1` | 별점 4.5 선택 → "저장", 리뷰 10자 이상 입력 → "등록" | "별점·리뷰 저장 요청에는 이 화면을 그린 사용자 ID가 함께 갑니다. 계정을 바꾼 사이 CSRF 재시도가 다른 계정에 저장되던 문제를 서버에서 막은 부분입니다([사례 1](case-studies.md#사례-1))." 평균 4.5·1명, "리뷰를 등록했습니다" 확인 |
| 4:20–4:30 | `/works/1` 새로고침 | F5 | 별점과 리뷰가 남아 있음 |
| 4:30–4:50 | 정리 화면(글자) | — | "모든 데이터는 가상이고, 실제 플랫폼 수집과 공개 운영은 아직 하지 않았습니다. 데모 DB는 개발·테스트 DB와 분리되어 있고, 이름 규칙이 맞지 않으면 서버가 마이그레이션 전에 멈춥니다. 데모 코드는 운영 jar에 들어가지 않습니다." |

> 시간이 3분으로 짧으면 ②의 브라우저 확인(1:15)과 새로고침 확인(4:20)을 빼고, `-Pstep=all` 한 번으로 ①~③ 명령을 대신합니다. 이때는 숨김 상태를 명령 출력의 `404(숨김…)` 줄로만 보여 줍니다.

## 2. 녹화 후 정리

```powershell
# 창 B·C: Ctrl+C
Remove-Item Env:MANHWANET_DEMO_*        # 창 A·B
docker compose stop                     # 데이터 유지. down -v 는 개발·테스트 DB까지 지우므로 쓰지 않음
```

## 3. 질문이 나오면 보여 줄 근거

| 질문 | 보여 줄 것 |
| --- | --- |
| "DB에 직접 넣은 것 아닌가요?" | [`DemoScenario`](../../backend/src/demo/java/com/manhwanet/demo/DemoScenario.java): 계정·설정·수집·검수·공개를 모두 기존 서비스로 호출. 현황 표시만 JDBC 읽기 |
| "개발 DB를 건드리면?" | [`DemoDatabaseGuard`](../../backend/src/main/java/com/manhwanet/global/config/DemoDatabaseGuard.java): 마이그레이션 전에 `current_database()`가 `_demo`로 끝나는지 확인 |
| "실제 사이트에 요청하나요?" | [`DemoCollectorConfig`](../../backend/src/demo/java/com/manhwanet/demo/DemoCollectorConfig.java): 데모 호스트 하나만 127.0.0.1로 보냄. 운영 수집기는 공개 IP·허용 호스트만 연결([collector-core-spec.md](../collector-core-spec.md) §4) |
| "동시에 승인하면?" | [staging-review-spec.md](../staging-review-spec.md): 항목 행 잠금, 결정 유니크 인덱스, 동시 승인 테스트 |
