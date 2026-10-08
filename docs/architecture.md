# 아키텍처 원칙

## Modular Monolith

- 하나의 Spring Boot 애플리케이션으로 배포합니다. (Microservice 아님)
- 도메인별 최상위 패키지로 모듈을 나눕니다: `com.manhwanet.<domain>`
- 예정 도메인: `auth`, `user`, `work`, `creator`, `genre`, `platform`, `rating`, `review`, `comment`, `ranking`, `search`, `report`, `admin`, `global`
- 구현된 패키지: `global`(공통 설정·응답·예외·health·페이지·시각·보안), `creator`, `work` ([명세](work-creator-spec.md)), `user`, `auth` ([명세](auth-design-draft.md)), `rating` ([명세](rating-design-draft.md)), `review` ([명세](review-design-draft.md))
- `rating → work` 방향만 허용합니다. `work`가 목록·상세에 별점 집계를 넣을 때는 `work.application.WorkRatingSummaryQuery`(메서드 1개)를 `rating`이 구현해 순환을 막습니다.
- `review → work`, `review → user`, `review → auth` 방향만 허용합니다. 작성자 닉네임은 `UserService.findNicknames`(공개 메서드)로 페이지 단위 일괄 조회하고, `users` 테이블이나 `UserRepository`를 직접 읽지 않습니다. `review`와 `rating`은 서로 참조하지 않습니다.
- 나머지 도메인 패키지는 **기능을 실제로 구현할 때** 만듭니다.
- 도메인 내부는 `api`(Controller), `application`(Service, 다른 도메인에 공개하는 타입), `domain`(Entity, Repository, 도메인 규칙), `dto`(요청·응답)로 나눕니다.
- 다른 도메인의 Entity는 JPA 연관관계로 참조하지 않고 ID 값으로 참조합니다. DB 외래 키는 유지합니다. (예: `WorkCreator.creatorId`)

## 계층 책임

| 계층 | 책임 | 하지 않는 것 |
| --- | --- | --- |
| Controller | HTTP 요청/응답 매핑, 입력 검증(`@Valid`), DTO 변환 호출 | 비즈니스 로직, Repository 직접 호출 |
| Service | 비즈니스 로직, 트랜잭션 경계 | HTTP 개념(Request/Response 객체) 의존 |
| Repository | 영속성 접근 (Spring Data JPA) | 비즈니스 판단 |

- **Entity를 API 응답으로 직접 반환하지 않습니다.** 응답은 DTO(record)로 변환합니다.
- 다른 도메인의 Repository를 직접 참조하지 않고, 해당 도메인의 Service(공개 API)를 통해 접근합니다.

## 교체 가능성 (Ranking / Search)

- Ranking 알고리즘과 Search 구현체는 이후 교체될 수 있으므로, 구현 시점에 호출 측이 구체 구현(예: 특정 검색 엔진 클라이언트)이 아닌 도메인 수준 인터페이스에 의존하도록 설계합니다.
- 단, 구현체가 하나뿐인 상태에서 미리 추상화를 만들지는 않습니다. (Phase 1에는 해당 코드가 없습니다.)

## 공통 규약

### API prefix

`WebConfig`가 `com.manhwanet` 하위 모든 `@RestController`에 `/api/v1`을 붙입니다.

```java
@RestController
@RequestMapping("/works")   // 실제 경로: /api/v1/works
```

### 응답 형식 — `ApiResponse<T>`

```json
{ "success": true, "data": { } }
{ "success": false, "error": { "code": "INVALID_INPUT", "message": "입력값이 올바르지 않습니다.", "fieldErrors": [ { "field": "name", "reason": "..." } ] } }
```

### 예외 처리

- 의도된 오류는 `BusinessException(ErrorCode)`을 던집니다.
- `GlobalExceptionHandler`가 모든 예외를 `ApiResponse` 형식과 `ErrorCode`의 HTTP 상태로 변환합니다.
- 예상하지 못한 예외는 `500 INTERNAL_SERVER_ERROR`로 응답하고 상세 내용은 로그에만 남깁니다.
- 도메인 전용 오류 코드는 해당 도메인을 구현할 때 추가합니다.
- 서비스 계층에서 검증한 입력 오류는 `InvalidInputException(fieldErrors)`로 던져 400 `INVALID_INPUT`과 필드 오류로 응답합니다.
- `DataIntegrityViolationException`을 일괄 변환하지 않습니다. 도메인 서비스가 **예상한 이름의 유니크 제약 위반만** 409 `DUPLICATE_RESOURCE`로 바꾸고, 나머지는 500으로 둡니다.

### 페이지네이션 — `PageResponse<T>`

- 목록 API는 `page`(0부터), `size`(1~100, 기본 20), `sort`를 직접 받아 요청 경계에서 검증하고 `PageableFactory`로 `Pageable`을 만듭니다. Spring의 `Pageable` 자동 바인딩(값을 조용히 보정)은 사용하지 않습니다.
- 정렬 키는 허용 목록(`createdAt`, `id`)으로 제한하고, `createdAt` 정렬에는 같은 방향의 `id`를 보조 기준으로 붙입니다.
- 컬렉션 fetch join과 페이징을 결합하지 않습니다. 페이지 조회 후 연관 데이터를 페이지 단위 `IN` 쿼리로 일괄 조회합니다.

### 생성·수정 시각 — `BaseTimeEntity`

- `created_at`, `updated_at`은 Spring Data JPA Auditing이 채웁니다(마이크로초 정밀도).
- JPA를 거치지 않은 SQL 직접 수정은 `updated_at`을 갱신하지 않습니다. (DB 트리거 없음)

### 인증·인가 — Spring Security

- 이메일·비밀번호 로그인, 서버 메모리 세션 + HttpOnly 쿠키, SPA 방식 CSRF(쿠키 `XSRF-TOKEN` + 헤더 `X-XSRF-TOKEN`). 상세: [auth-design-draft.md](auth-design-draft.md)(확정 명세)
- 규칙: 공개 GET(health·작품·작가)과 인증 진입점만 허용, `/api/v1/admin/**`는 ADMIN, 나머지는 로그인 필요(기본 거부). ERROR 디스패치는 허용합니다.
- 보안 필터 단계의 실패(401·403·CSRF)는 `@RestControllerAdvice`에 도달하지 않으므로 `JsonSecurityErrorHandler`가 `ApiResponse` 형식으로 직접 응답합니다.
- 관리자 계정은 비웹 `admin-cli` 프로필 명령으로만 만듭니다. 이전의 `@ConditionalOnAdminApiEnabled` 조건부 등록은 제거되었습니다.
- local 프로필은 서버를 127.0.0.1에 바인딩합니다(개발 편의, 인증과 별개).

### DB 스키마

- 스키마는 **Flyway만** 변경합니다. (`spring.jpa.hibernate.ddl-auto=validate`)
- 새 마이그레이션: `backend/src/main/resources/db/migration/V{번호}__{설명}.sql`
- 이미 적용된 마이그레이션 파일은 수정하지 않습니다.

### 설정 / Secret

- DB 접속 정보는 환경변수(`DB_*`)로만 주입합니다. 비밀번호 기본값은 두지 않습니다.
- `.env`는 커밋하지 않고, 필요한 변수 이름은 `.env.example`에만 기록합니다.
