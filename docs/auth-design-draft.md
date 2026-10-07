# 회원·인증 명세 (확정)

> **상태: 확정 — 2026-10-06 사용자 결정 반영, 백엔드 구현 완료(커밋 전).**
> 파일 이름은 초안 때와 같지만 내용은 확정 명세입니다. 초안의 결정 항목 D-1~D-13은 §1에서 모두 결정되었습니다.
> 프론트엔드(같은 출처 프록시, 로그인·가입 화면, 헤더 로그인 상태)는 §14에 정리했습니다(#12).

- 관련 문서: [architecture.md](architecture.md), [work-creator-spec.md](work-creator-spec.md)
- 기준: Spring Boot 3.5.16 / **Spring Security 6.5.11** / PostgreSQL 17

---

## 1. 확정 정책

| 항목 | 결정 |
|---|---|
| 로그인 방식 | 이메일 + 비밀번호. 서버 세션 + HttpOnly 쿠키 (JWT 미사용) |
| 이메일 | 앞뒤 공백 제거(`String.strip`) + `Locale.ROOT` 소문자 정규화 후 저장·비교. 유니크 |
| 닉네임 | 필수, 앞뒤 공백 제거 후 2~30 코드 포인트. **중복 허용** |
| 권한 | `USER`, `ADMIN`. 가입은 항상 USER, 요청으로 ADMIN 지정 불가 |
| 이메일 인증 | 비공개 개발 단계에서는 생략. **공개 운영 전 이메일 인증·비밀번호 재설정 정책을 준비해야 함**(§13) |
| 가입 후 | 자동 로그인 |
| 세션 | 서버 메모리(Tomcat) 세션, 유휴 만료 30분, remember-me 없음 |
| 비밀번호 해시 | Argon2id (`{argon2}` 접두사, DelegatingPasswordEncoder) |
| 비밀번호 길이 | NFKC 정규화 후 **15~128 유니코드 코드 포인트** |
| 비밀번호 처리 | trim·잘라내기 없음. 유니코드 정규화는 NFKC(§5.2) |
| 조합·변경 | 특수문자 등 문자 종류 조합 강제 없음, 주기적 변경 강제 없음 |
| 차단 목록 | 흔한 비밀번호(출처·라이선스 §5.3), 이메일·닉네임·서비스명과 같은 비밀번호 거부 |
| local 바인딩 | `server.address: 127.0.0.1` 유지 |
| 로그인 실패 제한 | 이메일 키당 15분 내 5회 실패 → 15분 거절 (개발 단계 기본값, §6) |
| 관리자 생성 | 별도 비웹 명령(`admin-cli` 프로필). 웹 서버 시작 시 자동 생성 없음(§8) |

## 2. 데이터 모델 — `V3__create_users.sql`

| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGINT identity | PK |
| email | VARCHAR(254) | NOT NULL, `uk_users_email` UNIQUE, `ck_users_email_not_blank` |
| password_hash | VARCHAR(255) | NOT NULL (Argon2id 인코딩 문자열, 약 100자) |
| nickname | VARCHAR(30) | NOT NULL, `ck_users_nickname_not_blank` |
| role | VARCHAR(20) | NOT NULL, `ck_users_role`: USER, ADMIN |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL, JPA Auditing |

- 관리자 계정·기본 비밀번호는 마이그레이션에 넣지 않습니다.
- V1·V2는 변경하지 않았습니다.
- 패키지: `user`(도메인·가입·관리자 생성), `auth`(로그인·실패 제한·세션 처리), `global.security`(보안 설정). `auth`는 `UserService`(공개 API)로만 사용자 정보를 읽습니다.

## 3. API

공통: prefix `/api/v1`, 응답 `ApiResponse`. 변경 요청(POST 등)은 CSRF 토큰이 필요합니다(§7).

| 메서드 | 경로 | 인증 | 성공 | 설명 |
|---|---|---|---|---|
| GET | `/auth/csrf` | 없음 | 204 | `XSRF-TOKEN` 쿠키 발급. 본문 없음 |
| POST | `/auth/signup` | 없음 + CSRF | 201, `Location: /api/v1/auth/me` | `{email, password, nickname}`. 항상 USER. 자동 로그인 |
| POST | `/auth/login` | 없음 + CSRF | 200 | `{email, password}` → 세션 생성 |
| POST | `/auth/logout` | CSRF | 204 | 세션 무효화, 세션·CSRF 쿠키 삭제 (Spring Security LogoutFilter) |
| GET | `/auth/me` | 로그인 | 200 | 현재 사용자 |

응답 `data` (가입·로그인·me): `{"id": 1, "email": "reader@example.com", "nickname": "독자", "role": "USER"}`. 비밀번호·해시는 응답에 없습니다.

| 상황 | 상태 | error.code |
|---|---|---|
| 입력 오류(이메일 형식, 닉네임·비밀번호 길이, 차단 목록 등) | 400 | INVALID_INPUT (`fieldErrors`) |
| 이메일 중복(정규화 후) | 409 | DUPLICATE_RESOURCE |
| 이메일 없음·비밀번호 틀림 | 401 | INVALID_CREDENTIALS (두 경우 응답 본문 동일) |
| 실패 제한 | 429 + `Retry-After` | TOO_MANY_LOGIN_ATTEMPTS |
| 로그인 필요(익명) | 401 | UNAUTHORIZED |
| 권한 부족 | 403 | FORBIDDEN |
| CSRF 토큰 누락·불일치 | 403 | INVALID_CSRF_TOKEN |

- 로그아웃은 익명 상태에서도 CSRF 토큰이 맞으면 204입니다(멱등).
- 가입 중복 409는 이메일 가입 여부를 드러냅니다. 비공개 개발 단계에서 허용하며, 공개 운영 전 이메일 인증과 함께 재검토합니다.

## 4. 접근 규칙 (`WebSecurityConfig`)

| 규칙 | 대상 |
|---|---|
| `permitAll` | ERROR 디스패치(`/error`), `GET /health`, `GET /works`, `GET /works/**`, `GET /creators`, `GET /creators/**`, `GET /auth/csrf`, `POST /auth/signup`, `POST /auth/login` |
| `hasRole("ADMIN")` | `/admin/**` |
| `authenticated()` | 그 밖의 모든 요청 (기본 거부) |

| 요청 | 결과 |
|---|---|
| 익명 + 유효 CSRF → `POST /admin/**` | 401 UNAUTHORIZED |
| USER + 유효 CSRF → `POST /admin/**` | 403 FORBIDDEN |
| CSRF 누락(익명·ADMIN 모두) → `POST /admin/**` | 403 INVALID_CSRF_TOKEN |
| ADMIN + 유효 CSRF | 기존 등록 API 동작 (201 등) |

- 공개 경로의 없는 리소스는 기존처럼 404입니다(예: `GET /works/999` → WORK_NOT_FOUND).
- **변경점:** 공개 목록에 없는 경로는 익명이면 404 대신 401입니다(기본 거부). 로그인 사용자는 없는 경로에 404를 받습니다.
- 보안 필터 단계의 실패는 `@RestControllerAdvice`에 도달하지 않으므로, `JsonSecurityErrorHandler`(AuthenticationEntryPoint + AccessDeniedHandler)가 `ApiResponse`를 직접 씁니다.
- ERROR 디스패치를 허용해 컨테이너 오류(예: 방화벽 400)가 401로 가려지지 않습니다. 컨트롤러의 예상하지 못한 예외는 기존대로 500입니다.
- `formLogin`, `httpBasic`은 비활성화했습니다. 익명 401 때 요청을 세션에 저장하지 않습니다(`NullRequestCache`).
- `@ConditionalOnAdminApiEnabled`와 `manhwanet.admin-api.enabled` 속성은 **제거**되었습니다. 등록 Controller는 항상 등록되고 인증·인가로 보호됩니다.

## 5. 가입 검증과 비밀번호

### 5.1 검증 순서

1. Bean Validation: 필수값, 원시 입력 상한(이메일 320자, 비밀번호 1,024자, 닉네임 200자 — 정규화·해시 비용 제한용)
2. `UserService.validate`: 이메일 정규화·형식, 닉네임 길이, 비밀번호 정책. 위반을 모아 400 `fieldErrors`로 응답
3. 이메일 중복 확인 → 409. 동시 가입은 `uk_users_email` 위반만 409로 변환합니다(기존 409 변환 규칙과 같음).

이메일 형식: 로컬 파트 1~64자, `@` 1개, 도메인에 점(`.`) 포함, 공백 없음, 전체 254자 이하. 소유 확인은 하지 않습니다.

### 5.2 비밀번호 유니코드 정규화

- **NFKC**로 정규화한 뒤 길이 검사, 차단 목록 비교, 해시, 로그인 검증을 합니다. 가입·로그인·관리자 생성이 모두 같은 정규화를 거칩니다(NIST SP 800-63B-4 권고).
- 결과: 전각 `ｐｌｕｍ`과 반각 `plum`, 조합형 한글과 완성형 한글은 같은 비밀번호로 취급됩니다.
- 앞뒤 공백을 제거하지 않고, 128자를 넘으면 잘라내지 않고 거부합니다.
- 길이는 정규화 후 코드 포인트로 셉니다. 이모지 1개 = 1자입니다.

### 5.3 차단 목록

- 파일: `backend/src/main/resources/security/common-passwords.txt` (10,898개)
- 출처: SecLists `Passwords/Common-Credentials/xato-net-10-million-passwords-1000000.txt` (커밋 `49c3b2d`, MIT 라이선스). 원 데이터는 Mark Burnett가 2015년 퍼블릭 도메인으로 공개한 데이터셋입니다.
- 가공: NFKC + 소문자 → 15~128자만 → 중복 제거. 15자 미만은 길이 정책에서 이미 거부되므로 넣지 않았습니다. 상세는 `COMMON-PASSWORDS-SOURCE.md`에 있습니다.
- 추가 거부(대소문자 무시, 완전 일치): 이메일, 닉네임, 서비스명(`manhwanet`, `만화넷`, `manhwa net`, `manhwa-net`)

### 5.4 저장

- Argon2id: salt 16바이트, hash 32바이트, parallelism 1, **memory 19 MiB, iterations 2** (OWASP 최소 설정)
- Bouncy Castle `bcprov-jdk18on:1.86`을 사용합니다(Spring Boot 의존성 관리 대상이 아니라 버전을 명시함).

## 6. 로그인과 실패 제한

### 6.1 로그인 절차

1. 이메일 정규화 → 실패 제한 확인. 제한 중이면 비밀번호를 검증하지 않고 429를 반환합니다.
2. `AuthenticationManager`(`DaoAuthenticationProvider`)로 검증합니다.
   - 사용자 없음과 비밀번호 틀림을 모두 `BadCredentialsException`으로 처리합니다.
   - 사용자가 없을 때도 더미 해시와 비교해 응답 시간 차이를 줄입니다.
3. 성공 시 실패 기록을 초기화하고 세션을 확립합니다(§6.2). 실패 시 실패 1회를 기록하고 401을 반환합니다.

### 6.2 세션 확립 (`AuthenticationSessionHandler`)

Spring Security 6.5 문서의 "직접 인증 정보 저장" 방식을 따르며, 폼 로그인 필터와 같은 순서로 처리합니다.

1. `SessionAuthenticationStrategy`
   - `ChangeSessionIdAuthenticationStrategy`: 기존 세션이 있으면 세션 ID를 변경합니다(세션 고정 방지).
   - `CsrfAuthenticationStrategy`(SPA 핸들러): 이전 CSRF 토큰 쿠키를 삭제하고 새 토큰을 같은 응답에 발급합니다.
2. 새 `SecurityContext`를 만들어 `SecurityContextHolderStrategy`에 설정합니다.
3. `SecurityContextRepository.saveContext`(요청 속성 + HttpSession)로 저장합니다. 필터 체인도 같은 저장소 Bean을 씁니다.

가입 자동 로그인도 **같은 처리기**를 사용합니다. 세션에 저장되는 principal(`AuthenticatedUser`)은 인증 후 비밀번호 해시를 지웁니다.

### 6.3 실패 제한 (`LoginAttemptLimiter`)

| 값 | 기본 (`manhwanet.auth.login-limit.*`) |
|---|---|
| 키 | 정규화된 이메일 (존재하지 않는 이메일도 동일 적용) |
| 실패 한도 / 기간 | 5회 / 15분(슬라이딩) |
| 거절 기간 | 15분 (`Retry-After` 초 단위) |
| 최대 키 수 | 10,000 |

- 성공하면 해당 키의 기록을 삭제합니다.
- 키별 상태는 `ConcurrentHashMap.compute`로 원자적으로 바꿉니다. 결과가 나오지 않은 시도(in-flight)도 한도에 포함합니다. 그래서 동시 요청이 몰려도 한 기간에 한도보다 많은 비밀번호 검증이 일어나지 않고, 진행 중 시도가 한도를 채우면 `Retry-After: 1`로 거절합니다.
- 요청 스레드를 재우는(sleep) 지연은 쓰지 않습니다.
- 용량: 가득 차면 만료 항목을 먼저 정리하고, 그래도 가득 차면 진행 중 시도가 없는 가장 오래된 항목을 내보냅니다. 상한은 동시 요청 수만큼 일시적으로 넘을 수 있는 근사값이며, 내보낸 키는 제한 기록을 잃습니다.

**한계 (공개 운영 전 추가 대응 필요)**
- 서버 재시작 시 기록이 사라집니다.
- 여러 인스턴스 사이에 공유되지 않아, 인스턴스 수만큼 시도 기회가 늘어납니다.
- 이메일 키 기준이라, 한 IP가 많은 이메일을 시도하는 분산 공격(credential stuffing)은 막지 못합니다.
- 공격자가 타인 이메일을 15분간 잠글 수 있습니다(계정 잠금 악용).
- 대응 후보: 공유 저장소(DB·Redis), IP·전역 기준 제한 추가(프록시 뒤 `X-Forwarded-For` 신뢰 설정 포함), 지연 기반 완화, 유출 비밀번호 확인, 모니터링

## 7. CSRF와 쿠키

### 7.1 CSRF (Spring Security 6.5 SPA 구성)

- `CookieCsrfTokenRepository.withHttpOnlyFalse()`: 쿠키 `XSRF-TOKEN`(Path=/, SameSite=Lax, HttpOnly 아님), 헤더 `X-XSRF-TOKEN`
- `SpaCsrfTokenRequestHandler`: 6.5 공식 문서 예시. 헤더 토큰은 쿠키 원래 값과 비교하고, 그 밖은 BREACH 대응 XOR 값으로 처리합니다. 요청마다 지연 토큰을 불러와 쿠키를 발급합니다.
  - 6.5에는 `csrf.spa()`가 없습니다(7.x 기능).
- 클라이언트 흐름:
  1. `GET /auth/csrf` → `XSRF-TOKEN` 쿠키 수신
  2. 변경 요청에 쿠키 값을 `X-XSRF-TOKEN` 헤더로 그대로 전송
  3. **로그인·가입 성공 응답:** 이전 토큰 쿠키 삭제(`Max-Age=0`) 후 새 토큰 쿠키 발급 → 이후 요청은 새 값 사용
  4. **로그아웃 응답:** 토큰 쿠키 삭제(`Max-Age=0`). 다음 요청(또는 `GET /auth/csrf`)에서 새 토큰을 받음

### 7.2 쿠키 속성

| 속성 | 운영 기본 (`application.yml`) | local (`application-local.yml`) |
|---|---|---|
| 세션 쿠키 이름 | `__Host-MANHWANET_SESSION` | `MANHWANET_SESSION` |
| HttpOnly | ✔ | ✔ |
| Secure | ✔ | ✗ (HTTP 개발) |
| SameSite | Lax | Lax |
| Path | / | / |
| Domain | 지정 안 함 | 지정 안 함 |
| Max-Age/Expires | 없음(세션 쿠키) | 없음 |
| `XSRF-TOKEN` | Secure ✔, SameSite=Lax, Path=/, HttpOnly ✗ | Secure ✗ |

- `__Host-` 접두사의 요구 조건(Secure, Path=/, Domain 없음)은 실제 HTTP 응답 테스트로 검증합니다.
- local은 `__Host-`를 쓰지 않습니다. Secure가 없으면 브라우저가 그 쿠키를 거부하기 때문입니다.
- 세션 추적은 쿠키만 사용합니다(`tracking-modes: cookie`, URL에 세션 ID 없음).
- CORS 설정은 추가하지 않았습니다. 브라우저는 Next.js 같은 출처 프록시를 거칩니다(§14).

## 8. 관리자 계정 생성

```powershell
cd backend
.\gradlew.bat bootJar
java -jar build\libs\manhwanet-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,admin-cli --manhwanet.admin-cli.email=admin@example.com
# 콘솔에서 비밀번호를 두 번 입력 (화면에 표시되지 않음)
```

- `admin-cli` 프로필에서만 `CreateAdminCommand`가 등록되고, `spring.main.web-application-type: none`이라 웹 서버를 띄우지 않고 종료합니다. 일반 웹 서버 시작 시에는 실행되지 않습니다.
- 비밀번호 입력: 콘솔(`System.console().readPassword`, 두 번 입력) 또는 환경변수 `MANHWANET_ADMIN_PASSWORD`(콘솔이 없을 때, 예: `bootRun`). **명령행 인자로는 받지 않고**, 로그·예외 메시지에 남기지 않습니다.
- 닉네임은 `--manhwanet.admin-cli.nickname`(기본 `관리자`)입니다. 비밀번호 정책은 가입과 같습니다.

| 기존 계정 | 결과 |
|---|---|
| 없음 | ADMIN 생성, 종료 코드 0 |
| 같은 이메일의 ADMIN | 변경 없이 완료, 종료 코드 0 |
| 같은 이메일의 USER | 실패(종료 코드 1). **자동 승격하지 않음** |
| 입력 오류·입력 수단 없음 | 실패(종료 코드 1) |

## 9. 설정

| 속성 | 값 |
|---|---|
| `server.servlet.session.timeout` | 30m |
| `server.servlet.session.cookie.*` | §7.2 |
| `manhwanet.auth.login-limit.*` | §6.3 |
| 환경변수 `MANHWANET_ADMIN_PASSWORD` | 관리자 생성 명령 전용(선택) |

## 10. 테스트

모든 DB 통합 테스트는 `test` 프로필의 테스트 DB와 기존 안전장치(`TestDatabaseNames`, `TestDatabaseCleaner`)를 사용합니다. 정리 대상에 `users`를 추가했습니다.

| 테스트 | 검증 |
|---|---|
| `PasswordPolicyTest`, `AccountFieldsTest` | 15/128 코드 포인트 경계(이모지 포함), trim 없음, NFKC, 차단 목록, 이메일·닉네임·서비스명, 이메일·닉네임 정규화 |
| `LoginAttemptLimiterTest` | 5회 후 잠금·15분 만료, 슬라이딩 기간, 성공 초기화, 키 독립, 동시 64요청 중 정확히 5회만 허용, 1,000키 동시 기록, 용량 상한·만료 정리 |
| `AuthApiIntegrationTest` | CSRF 발급·누락·불일치, 가입·role 무시·Argon2 해시·자동 로그인·세션 ID 변경, 중복 409, 닉네임 중복 허용, 경계값, trim 없음, NFKC 로그인, 로그인 세션 유지·ID 변경·CSRF 교체, 동일 실패 응답, 로그아웃 세션 무효화·CSRF 삭제, 실패 제한(존재하지 않는 이메일 포함, 성공 초기화) |
| `AdminAuthorizationIntegrationTest` | 익명+CSRF 401, USER+CSRF 403, ADMIN 201, CSRF 누락 403, 다른 토큰 403, 공개 GET 유지 |
| `AdminAccountServiceTest`, `CreateAdminCommandTest`, `AdminCliProfileTest` | 생성·기존 ADMIN 변경 없음·USER 거부·입력 오류, 비밀번호 비노출, 일반 실행에 명령 없음, `admin-cli` 프로필이 비웹으로 실행·생성·재실행 |
| `SessionCookieHttpTest`, `LocalSessionCookieHttpTest` | 실제 HTTP: `__Host-` 쿠키 속성, local 쿠키, 가입→me→재로그인 세션 ID 변경→이전 세션 401→로그아웃 쿠키 삭제, 익명 401 세션 미생성, 방화벽 400이 ERROR 디스패치에서 401로 바뀌지 않음, 30분 만료 설정, 127.0.0.1 바인딩 |

**대체한 테스트 (변경 이유: 등록 API가 조건부 등록에서 인증·인가 보호로 바뀜)**
- `AdminApiDisabledByDefaultTest`, `AdminApiDisabledWithoutLocalProfileTest` → `AdminAuthorizationIntegrationTest`. loopback 바인딩 검증은 `LocalSessionCookieHttpTest`로 옮겼습니다.
- `AdminApiIntegrationTest` + `ApiFixtures`: local 프로필 + 플래그 대신 ADMIN 사용자와 CSRF 토큰으로 요청합니다.
- `ManhwanetApplicationTests.unknownPath…`: 익명 401, 로그인 사용자 404, 공개 경로 404로 나누었습니다.
- WebMvc 슬라이스 테스트는 실제 보안 설정을 `@Import`합니다.

## 11. 운영 전 확인 사항

- HTTPS 종단과 `Secure` 쿠키. 프록시 뒤라면 `server.forward-headers-strategy` 설정이 필요합니다.
- 실패 제한의 공유 저장소·IP 기준 제한(§6.3)
- 세션 저장소: 메모리 세션은 재시작 시 전원 로그아웃되고 다중 인스턴스를 지원하지 않습니다. 배포 형태에 따라 Spring Session JDBC 등을 검토합니다.
- 이메일 인증·비밀번호 재설정(§13)

## 12. 변경된 기존 동작 요약

- 공개 목록에 없는 경로: 익명 404 → 401
- 등록 API: local + 플래그일 때만 존재 → 항상 존재, ADMIN 필요
- 변경 요청 전체에 CSRF 토큰 필요

## 13. 후속 범위

| 기능 | 필요한 것 | 시점 |
|---|---|---|
| 이메일 소유 인증, 비밀번호 재설정 | 메일 발송 서비스, 일회용 토큰·만료 정책 | **공개 운영 전 필수** |
| 소셜 로그인 | OAuth 공급자 등록, `user_identities` | 수요 확인 후 |
| 유출 비밀번호 외부 API 확인 | 외부 API 연동 | 공개 운영 전 검토 |
| remember-me, MFA, 비밀번호 변경·탈퇴·정지, 감사 로그 | 정책 결정 | 후속 |

## 14. 프론트엔드 (#12)

| 항목 | 구현 |
|---|---|
| 같은 출처 프록시 | Route Handler `frontend/src/app/api/v1/[...path]/route.ts` → 서버 설정 `API_BASE_URL` 아래 같은 경로. 요청이 목적지를 고를 수 없음 |
| rewrites 대신 Route Handler | `next.config` rewrites 는 빌드 시점에 목적지가 고정되고 모든 요청 헤더·쿠키를 넘깁니다. Route Handler 는 `API_BASE_URL`을 실행 시점에 읽고, 넘길 헤더·쿠키를 목록으로 제한합니다 |
| 넘기는 요청 헤더 | `Accept`, `Accept-Language`, `Content-Type`, `X-XSRF-TOKEN`, 쿠키는 세션(`__Host-MANHWANET_SESSION`/`MANHWANET_SESSION`)·`XSRF-TOKEN`만 |
| 돌려주는 응답 헤더 | `Set-Cookie`(모두), `Content-Type`, `Cache-Control`(없으면 `private, no-store`), `Expires`, `Pragma`, `Retry-After`, 같은 출처 `Location`. Location 은 URL 해석 후 기준 출처와 같을 때만 경로·query·해시를 넘긴다. 역슬래시·제어 문자·외부 출처·`//` 는 버린다. `/api/v1/...` 는 유지한다 |
| 거절 | `.`·`..`·빈 세그먼트·역슬래시·인코딩된 슬래시 경로 404, 본문 64KiB 초과 413, Backend 연결 실패 503 `SERVICE_UNAVAILABLE` |
| 서버 측 로그인 상태 | `getSession()`: 세션 쿠키만 골라 `GET /auth/me` (`no-store`, 3초 제한, React `cache`로 한 요청 안에서만 공유). 세션 쿠키가 없으면 호출하지 않음. 401 → 비로그인, 그 밖의 실패 → `unavailable` |
| CSRF 흐름 | 변경 요청 직전에 `XSRF-TOKEN` 쿠키를 읽어 헤더로 보냄 → 쿠키가 없으면 `GET /auth/csrf` → `INVALID_CSRF_TOKEN`이면 토큰을 다시 받아 1회 재시도. 토큰을 따로 저장하지 않으므로 로그인·가입 후 교체된 토큰을 자동으로 사용 |
| 응답 파싱 | 브라우저 응답도 `parseApiBody`(lossless-json)로 읽어 사용자 `id`를 원문 십진수 문자열로 유지 |
| 화면 | `/login`, `/signup`(이미 로그인했으면 `returnTo`로 307), 헤더 닉네임·로그아웃 또는 로그인·회원가입 링크 |
| returnTo | `/`로 시작하는 같은 출처 경로만. `//`, `\`, 스킴, 제어 문자, `/login`·`/signup`·`/api/` → `/works` |
| 입력 검사 | 백엔드와 같은 기준으로 미리 안내(이메일 형식, 닉네임 2~30 코드 포인트, 비밀번호 NFKC 후 15~128 코드 포인트). 차단 목록 등 최종 판정은 백엔드 `fieldErrors`를 해당 입력 칸에 연결 |
| 범위 밖 | 비밀번호 재설정, 소셜 로그인, 관리자 등록 화면. HTTPS·프록시 운영 검증은 #11 |
