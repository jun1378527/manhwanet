# docs

만화넷 설계·운영 문서를 보관하는 디렉터리입니다.

- [architecture.md](architecture.md) — Phase 1 Foundation 아키텍처 원칙과 공통 규약
- [work-creator-spec.md](work-creator-spec.md) — 작품·작가 등록/조회 구현 명세 (데이터 모델, 검증 규칙, API, 등록 API 노출 조건, 테스트 계획)
- [auth-design-draft.md](auth-design-draft.md) — 회원·인증 **확정 명세** (로그인·세션·CSRF·쿠키·실패 제한·관리자 생성·접근 규칙)
- [rating-design-draft.md](rating-design-draft.md) — 작품 별점 **확정 명세** (점수·데이터 모델·API·집계·동시성 보장 범위·테스트)
- [review-design-draft.md](review-design-draft.md) — 작품 리뷰 **확정 명세** (작성 정책·본문 규칙·데이터 모델·API·수정·삭제 충돌·목록·테스트). 백엔드 #19, 화면 #21 구현
- [login-limit-design-draft.md](login-limit-design-draft.md) — 로그인 보호 **확정 명세** (#10: 계정별 제한·용량·전역 해시 게이트·429/503·Retry-After·검증 계획. 잠정 수치 포함). 구현 #28
- [ui-design-draft.md](ui-design-draft.md) — UI 디자인 방향 **초안** (방향 A·B, 화면 시안, 공통 규칙, 디자인만/데이터·API 필요 구분). 시안 파일: [ui-mockups/manhwanet-ui-mockups.html](ui-mockups/manhwanet-ui-mockups.html)
- [work-platform-admin-spec.md](work-platform-admin-spec.md) — 작품·플랫폼 관리 구현 명세 **초안** (#37: ADMIN 수정·동시 수정 충돌(#38 구현), 플랫폼 연결·상태 이력(#39 구현, V7), 외부 ID·공식 링크·근거·관리 변경 기록(#40 구현, V8), 중복 검수, 파일럿, 이슈 #38~#44)
- [collection-staging-spec.md](collection-staging-spec.md) — 수집 실행·staging **구현 기록** (#45: V9~V12, 수집 설정·실행 잠금·재개·중단, 작업 큐, staging 식별 키·등급·변경 이력·실패 보존, 관리자 API, 설계와 다른 점)
- [collector-core-spec.md](collector-core-spec.md) — 수집기 공통 기반 **구현 기록** (#46: V13~V14, 어댑터 계약·실행 진입점(`collector` 프로필), HTTP 요청·목적지 IP 검사·크기·시간 제한, 요청 간격·하루 상한, robots.txt, 재시도·429·접근 차단·인증 리다이렉트, 회로 차단기, 중단·재개·잠금, 설계·라이브러리와 다른 점)
- [staging-review-spec.md](staging-review-spec.md) — staging 검수·공개 등록 **구현 기록** (#51: V15, 수집 revision, 중복 후보·식별 키 충돌, 기존 작품 연결·새 작품 등록·보류·제외, 한 트랜잭션·반복·동시 승인 방지, 미결정 정책과 선행 범위, 가상 데이터 시연)
