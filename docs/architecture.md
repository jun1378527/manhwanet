# 아키텍처 원칙 (Phase 1 Foundation)

## Modular Monolith

- 하나의 Spring Boot 애플리케이션으로 배포합니다. (Microservice 아님)
- 도메인별 최상위 패키지로 모듈을 나눕니다: `com.manhwanet.<domain>`
- 예정 도메인: `auth`, `user`, `work`, `creator`, `genre`, `platform`, `rating`, `review`, `comment`, `ranking`, `search`, `report`, `admin`, `global`
- 현재는 `global`(공통 설정·응답·예외·health)만 존재합니다. 도메인 패키지는 **기능을 실제로 구현할 때** 만듭니다.

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

### DB 스키마

- 스키마는 **Flyway만** 변경합니다. (`spring.jpa.hibernate.ddl-auto=validate`)
- 새 마이그레이션: `backend/src/main/resources/db/migration/V{번호}__{설명}.sql`
- 이미 적용된 마이그레이션 파일은 수정하지 않습니다.

### 설정 / Secret

- DB 접속 정보는 환경변수(`DB_*`)로만 주입합니다. 비밀번호 기본값은 두지 않습니다.
- `.env`는 커밋하지 않고, 필요한 변수 이름은 `.env.example`에만 기록합니다.
