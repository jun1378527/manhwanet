# 수집기 공통 기반 구현 기록 (#46)

> **상태: 구현됨(2026-10-15, 미커밋).** 기준 커밋 `8302af6`. 설계는 [work-collection-design.md](work-collection-design.md) §4.2·§5·§7(초안)이고, #45 구현 계약은 [collection-staging-spec.md](collection-staging-spec.md)입니다. 이 문서는 실제 구현 계약과 설계·라이브러리와 다른 점을 적습니다. 설계 문서는 고치지 않았습니다.
> 범위: 플랫폼 어댑터 계약, 실행 진입점(`collector` 프로필), 공통 HTTP 요청, 요청 간격·하루 상한, robots.txt, 재시도·429·접근 차단·인증 리다이렉트 처리, 회로 차단기, #45 실행·작업 큐·잠금·staging 연동, 중단·재개와 실패 기록.
> 하지 않은 것: 플랫폼별 실제 어댑터(#47~#50), JS 렌더링(D-14), 대량 수집, 실제 플랫폼 호출. 검증은 로컬 모의 HTTP 서버(127.0.0.1)와 격리 테스트 DB로만 했습니다.

## 1. 마이그레이션

| 파일 | 내용 |
|---|---|
| `V13__add_collector_request_limits.sql` | `crawl_runs.rate_limit_hits`(한 실행에서 받은 429 횟수), `crawl_tasks.last_error` CHECK 에 `HTTP_4XX`·`BLOCKED_DESTINATION`·`RESPONSE_TOO_LARGE`·`PROTOCOL_ERROR` 추가(V9 파일은 고치지 않고 V13 에서 제약을 다시 만듦), `collector_daily_requests`(플랫폼별 하루 요청 수) |
| `V14__add_crawl_run_next_request_at.sql` | `crawl_runs.next_request_at`(다음 요청을 보내도 되는 시각). V13 은 고치지 않음 |
| `V17__add_platform_next_request_at.sql` | `platform_collection_policies.next_request_at`(플랫폼의 다음 요청 시각, #68). V14 파일은 고치지 않음. 기존 실행 행에 남은 시각이 있으면 플랫폼별 가장 늦은 값으로 채움 |

- V1~V12 는 고치지 않았습니다. 새 FK 에 CASCADE 가 없습니다.
- `rate_limit_hits` 를 DB 에 둔 이유: 프로세스가 죽어 다른 프로세스가 같은 실행을 넘겨받아도 "한 실행에서 429 두 번째면 중단"이 이어지게 하기 위해서입니다.
- `next_request_at` 을 DB 에 둔 이유: 요청 간격과 Retry-After 로 미룬 시각이 프로세스 메모리에만 있으면, 요청 직후 프로세스가 끝났을 때 다음 요청이 바로 나가기 때문입니다. 허용 판단과 예약은 플랫폼 행을 봅니다(#68). 같은 실행을 재개할 때와, 실행이 완료·중단·차단된 뒤 새 실행을 시작할 때 모두 그 시각을 유지합니다. 시작 시 이전 실행에서 복사하지 않습니다. `GREATEST` 라서 이미 더 늦은 시각은 앞당겨지지 않고, 실행을 닫는 갱신은 이 시각을 지우지 않습니다.

## 2. 구성

```
collection/collector/
  adapter/  PlatformAdapter, TaskRequest, Discovery, DiscoveredRef
  core/     CollectorEngine(실행 진입점), FetchContext·FetchResponse, PlatformThrottle, RobotsRules, RetryPolicy, RetryAfter,
            CircuitBreaker, BlockDetector, GateDetector, UserAgent, CollectorProperties, CollectorConfig, 예외(RunHalt·TaskOutcome·ParseFailure)
  net/      HttpTransport, CollectorNetwork(HostResolver·AddressPolicy·SocketConnector), PublicAddressPolicy, TlsSocketConnector, LogSafe
  cli/      CollectCommand (collector 프로필 전용)
collection/domain/CollectorRequestCounterRepository
```

#45 서비스에 더한 것(기존 동작은 그대로): `CrawlRunService.recordRateLimit`·`rateLimitHits`·`recordRobots`·`recordStats`, `CrawlTaskService.nextPendingDelayMillis`·`countByStatus`(읽기), `CrawlTaskService.sha256`·`taskUrl` 공개. 쓰기는 모두 실행 잠금을 확인합니다(`requireLeaseForWrite`).

## 3. 실행 진입점과 프로필

- `CollectCommand` 는 `@Profile("collector")` 이고 `application-collector.yml` 이 `spring.main.web-application-type: none` 입니다. 기본(웹) 프로필에서는 등록되지 않습니다. 수집기 빈에는 스케줄·시작 시 실행이 없어서, 웹 서버를 띄우는 것만으로는 요청이 나가지 않습니다.
- 명령: `start`(PROBE·FULL·INCREMENTAL·RECHECK), `resume`(run id), `stop`(run id, 관리자 이메일, 사유 — `CrawlRunService.requestStop` 으로 변경 기록이 남음). 사용법은 README 의 "수집 명령".
- 시작 전에 확인하고, 하나라도 틀리면 **실행을 만들지 않고 요청도 보내지 않습니다**: 어댑터 등록 여부, 연락처 URL(`manhwanet.collector.contact-url`, 기본값 없음), 수집 설정(`DISABLED` 불가, `PROBE` 설정에서는 PROBE 실행만).
- 잠금 소유자는 `호스트이름/PID/무작위 8자` 입니다.

## 4. HTTP 요청 (`HttpTransport`)

| 항목 | 규칙 |
|---|---|
| 방식 | 최소 HTTP/1.1 `GET`. 리다이렉트를 따라가지 않고 돌려준다(수집기가 단계마다 검사) |
| 요청 헤더 | `Host`, `User-Agent: ManhwanetCollector/{버전} (+{연락처 URL})`, `Accept`, `Accept-Encoding: gzip`, `Connection: close` 뿐. 쿠키·인증 헤더 없음. 사용자 에이전트는 바꾸지 않음 |
| 응답 헤더 | 이름을 소문자로. `Set-Cookie` 는 버림(저장·기록 없음) |
| 목적지 | `https` 만. 사용자 정보(`user@`) 불가. 수집기 쪽에서 플랫폼 허용 호스트(`platform_hosts`)·포트 443 만 허용 |
| IP 검사 | 이름을 직접 해석하고, **해석된 주소가 하나라도 공개 주소가 아니면** 연결하지 않음. 검사한 IP 로만 연결하고 TLS 는 원래 호스트 이름으로 SNI·인증서 확인(HTTPS 규칙) |
| 막는 주소 | IPv4 0/8, 10/8, 100.64/10, 127/8, 169.254/16(메타데이터 169.254.169.254 포함), 172.16/12, 192.0.0/24, 192.0.2/24, 192.88.99/24, 192.168/16, 198.18/15, 198.51.100/24, 203.0.113/24, 224/4, 240/4. IPv6 는 2000::/3 밖 전부(::, ::1, fc00::/7·fd00:ec2::254, fe80::/10, ff00::/8 등), 2001::/23, 2001:db8::/32, 비공개 IPv4 를 품은 IPv4 매핑·6to4·NAT64 |
| 시간 제한 | 연결 10초, 읽기 간격 20초, 응답 전체 60초(설정 가능, 상한 30·60·120초) |
| 크기 제한 | 응답 헤더 64KB·200개·한 줄 8KB, 본문 10MB(설정 가능, 1KB~50MB). `Content-Length` 가 크면 읽지 않고 거절, 청크·길이 없는 응답은 읽으면서 상한 검사, gzip 은 **풀린 크기**로 검사 |
| 트랜잭션 | DB 트랜잭션 안에서 부르면 `IllegalStateException`(네트워크 대기 중 DB 연결 점유 방지) |

**테스트용 로컬 접근의 분리:** 운영 코드에는 로컬 주소 허용·평문 연결 설정이 없습니다(`CollectorNetwork.production()` 하나). 테스트는 테스트 소스의 `LoopbackNetwork`(`*.mock.test` 만 127.0.0.1 로 해석, 127.0.0.1 만 허용, 모의 서버 포트로 평문 연결)를 `@Primary` 빈으로 바꿔 끼웁니다. 테스트 클래스는 bootJar 에 들어가지 않습니다.

## 5. 요청 간격·동시성·하루 상한

- **플랫폼당 동시 1건**: 프로세스 안의 `PlatformThrottle` 한 줄을 모든 작업·재시도·리다이렉트 단계·robots 요청이 지납니다. 다른 프로세스와는 "플랫폼당 활성 실행 하나"(#45)와 실행 잠금으로 나뉩니다.
- **간격**: 앞 요청이 끝난 뒤 `max(5초, 수집 설정 rate_interval_ms, robots Crawl-delay) × (이 실행에서 429 를 받았으면 2) + 0~1초 무작위`. 5초 하한은 코드 상수라 설정으로 줄일 수 없습니다. robots.txt 를 읽은 직후에도 Crawl-delay 를 반영해 다음 요청을 미룹니다.
- **Retry-After**: 429 를 받으면 그 시간이 지나기 전에는 같은 플랫폼에 어떤 요청도 보내지 않습니다. 남은 시간은 플랫폼 행 `next_request_at` 에 남습니다. 프로세스를 바꿔 같은 실행을 재개할 때와, 그 실행이 끝난 뒤 새 실행을 시작할 때 모두 유지됩니다.
- **재시작·새 실행**: 요청을 보낸 뒤 플랫폼 행을 한 문장으로 `GREATEST` 갱신합니다. 다음 요청 전에는 그 시각만 읽고, 트랜잭션 밖에서 기다립니다. 실행 잠금 확인은 갱신 전에 합니다. 같은 프로세스의 동시 1건은 메모리 잠금이 계속 맡습니다. 플랫폼당 활성 실행은 하나입니다.
- **하루 상한**: 보내기 직전에 `collector_daily_requests` 를 한 문장으로 올립니다(한국 시간 날짜, 상한 초과 시 올리지 않음). 상한은 수집 설정 `daily_request_cap`, PROBE 실행은 `min(200, 설정값)`. 닿으면 실행을 `PAUSED` 로 두고 통계에 `haltReason=DAILY_CAP` 을 남깁니다.
- 요청 수는 실제로 보내지 않은 요청(허용하지 않은 목적지로 판명되어 연결 전에 멈춘 경우)도 셉니다(보수적).

## 6. robots.txt (RFC 9309, D-20 기본값 유지)

- 실행을 시작할 때 시작 호스트(발견 경로의 첫 호스트)의 robots.txt 를 **다른 요청보다 먼저** 읽고, SHA-256 을 `crawl_runs.robots_sha256` 에 남깁니다. 다른 허용 호스트는 처음 쓸 때 읽습니다. 6시간이 지나면 다시 읽고, 새로 막힌 경로의 작업은 `SKIPPED_ROBOTS` 로 닫힙니다(요청 직전마다 검사).
- 그룹: `ManhwanetCollector`(대소문자 무시, `/버전` 무시) 그룹들을 합쳐 쓰고, 없으면 `*`. 가장 길게 맞는 규칙, 같으면 allow. `*`·`$` 지원, 퍼센트 인코딩 정규화, 앞 500KiB 만 해석, Crawl-delay 상한 3600초.
- 리다이렉트 단계마다 그 목적지의 robots 도 검사합니다.
- **읽지 못하면 실행하지 않음**: 5xx·타임아웃·연결 오류·429 는 3번까지 시도하고, 그래도 안 되면 `BLOCKED`(`ROBOTS_UNAVAILABLE`). 403·차단 화면은 `BLOCKED_ACCESS`.

## 7. 응답별 처리

| 응답 | 작업 | 실행 |
|---|---|---|
| 2xx | 어댑터가 해석. 실패하면 `PARSE_FAILED` + 원문 표본(민감 값 가림, 실행당 200건, 30일) | 계속 |
| 3xx | 단계마다: 로그인·성인 인증·결제로 보이면 따라가지 않고 `GATED`. 허용하지 않은 호스트·https 아님·443 아닌 포트·공개 주소 아님이면 `BLOCKED_DESTINATION`. 최대 5단계(넘으면 `PROTOCOL_ERROR`) | 계속 |
| 401·402·407 | `GATED`(재시도 없음) | 계속 |
| 403·451, CAPTCHA·봇 확인·접근 거부 화면(상태와 무관, 본문 앞 64KB·`cf-mitigated`) | `BLOCKED_ACCESS`(재시도 없음) | **즉시 `BLOCKED`(`BLOCKED_ACCESS`)**, 로그 `[알림 필요]` |
| 404·410 | `NOT_FOUND`(재시도 없음) | 계속 |
| 429 첫 번째 | `RATE_LIMITED`, `max(Retry-After, 현재 간격)` 뒤 재시도(Retry-After 없으면 60초, 상한 86,400초). 간격 2배 | 계속 |
| 429 두 번째(한 실행) | 같은 기록(재개 뒤 다시 시도) | **즉시 `BLOCKED`(`BLOCKED_RATE`)**, 로그 `[알림 필요]` |
| 5xx·타임아웃·연결 오류 | 30초, 2분, 8분, 30분 + 각 0~10% 무작위 뒤 재시도. 최대 4회(처음 포함 5회) 뒤 `FAILED` | 계속(회로 차단기 확인) |
| 그 밖의 4xx | `HTTP_4XX` | 계속 |
| 크기 상한 초과 | `RESPONSE_TOO_LARGE` | 계속 |
| 응답 형식 오류 | `PROTOCOL_ERROR` | 계속(회로 차단기에 셈) |

- 상세(DETAIL) 작업이 `GATED`·`NOT_FOUND`·`PARSE_FAILED` 로 끝나면 어댑터의 `failedDetail` 결과를 같은 트랜잭션에서 staging 에 반영합니다(#45 실패 보존 규칙). 그 반영이 검증에 걸리면 작업 실패만 기록합니다.
- 목록 단계에서 성인 신호가 있는 참조(`DiscoveredRef.listOnly` 가 `SKIPPED_ADULT`)는 상세를 요청하지 않고 목록 값만 반영합니다(설계 §6.3).
- 우회는 없습니다. 사용자 에이전트·IP·쿠키·헤더를 바꾸지 않고, 차단 뒤 다시 시도해 넘어가려 하지 않습니다.

## 8. 회로 차단기

- 연속 5회 실패, 또는 최근 최대 100건(20건 이상 모였을 때) 중 실패가 20%를 넘으면 엽니다. 실패로 세는 것: 타임아웃·연결 오류·5xx·응답 형식 오류. 404·GATED·robots 건너뜀은 세지 않습니다.
- 열리면 진행 중이던 작업을 기록(재시도 가능하면 `PENDING`)하고 실행을 `BLOCKED`(`CIRCUIT_OPEN`)로 둡니다. 실행(프로세스) 단위로 세고, 사람이 확인하고 재개하면 새로 셉니다.

## 9. 중단·재개·잠금과 트랜잭션 경계

- `CollectorEngine` 은 트랜잭션을 걸지 않습니다. 네트워크 요청·대기는 트랜잭션 밖이고, 결과 반영(staging·하위 작업 등록·작업 완료)은 **한 작업당 짧은 트랜잭션 하나**입니다. 시작 시 작업 등록, 실패 기록, 429 횟수, robots 해시, 통계도 각각 짧은 트랜잭션입니다.
- **대기 중 확인**: 모든 대기(요청 간격·Retry-After·재시도 시각)는 1초 단위로 나눠 자며, 사이마다 잠금 연장(잠금 시간의 1/3마다, 기본 300초 → 100초), 중단 요청, 잠금 상실, 수집 설정 변경을 확인합니다. 요청을 보내기 직전에도 확인합니다.
- **중단 요청**(`STOP_REQUESTED`): 보내던 요청은 끝내고 그 결과는 반영하되 새 작업은 넣지 않습니다. 다음 확인에서 `finishStop` 으로 `STOPPED`, 남은 작업은 `SKIPPED`. 대기 중에 요청이 오면 더 보내지 않고 바로 닫습니다.
- **잠금 상실**: 넘겨받은 쪽이 끝나지 않은 작업을 `PENDING` 으로 되돌리고, 잃은 프로세스는 결과·작업·실행 상태를 쓰지 못합니다(#45 공유 잠금 검사). 수집기는 `LEASE_LOST` 로 아무것도 더 쓰지 않고 끝냅니다.
- **수집 설정 변경**: 실행 중에 설정이 이 실행을 허용하지 않게 바뀌면(`DISABLED` 등) `PAUSED` 로 두고 끝냅니다. 재개는 #45 규칙대로 거절됩니다.
- **재개**: `resume(runId)` 는 #45 `acquire` 로 같은 실행을 넘겨받고, robots.txt 를 다시 읽은 뒤 남은 작업만 처리합니다. 끝난 작업은 다시 요청하지 않습니다. 429 횟수가 남아 있으면 간격 2배로 시작합니다. 플랫폼의 `next_request_at` 이 아직 미래면 그 시각까지 기다린 뒤에 요청합니다. 완료·중단·차단으로 실행을 닫아도 이 시각은 남고, 다음 실행이 같은 값을 읽습니다.
- **정지 사유 기록**: 일시 정지·차단 직전에 `crawl_runs.stats` 에 `haltReason`·`haltMessage`·요청 수·상태별 수를 합칩니다.

## 10. 어댑터 계약 (`PlatformAdapter`)

설계 §5.2를 작업 큐 단위로 옮겼습니다.

| 메서드 | 설계 §5.2 | 내용 |
|---|---|---|
| `platformCode()`·`version()` | `code()` | 플랫폼 코드, `crawl_runs.adapter_version` |
| `discoverySources(kind)` | `discoverySources()` | 시작 작업(사이트맵·목록) |
| `discover(kind, url, ctx)` | `discover(source, ctx)` | 사이트맵·목록 → 다음 발견 경로 + 작품 참조 |
| `fetchDetail(url, ctx)` | `fetchDetail(ref, ctx)` | 상세 → `CollectedListing`(#45) |
| `normalizeId(raw)` | 같음 | 정규 ID |
| `failedDetail(url, error)` | (추가) | 상세 실패를 staging 에 남길 값(선택) |
| `isGateRedirect(uri)` | (추가) | 공통 규칙에 더하는 인증·결제 리다이렉트 판단 |

- 어댑터는 `FetchContext.get` 으로만 요청합니다. `get` 은 2xx 만 돌려주고, 그 밖에는 작업 실패(`TaskOutcomeException`) 또는 실행 정지(`RunHaltException`)를 던집니다. 어댑터가 정지 예외를 삼키고 결과를 돌려줘도 수집기는 결과를 버리고 실행을 멈춥니다.
- PROBE 실행: SITEMAP 작업만 실행하고, 작품 참조·LIST·DETAIL 작업과 staging 반영은 하지 않습니다(실행당 작업 50개, 하루 200 요청).
- 작업 URL 은 플랫폼 허용 호스트의 https 주소만 넣습니다(아니면 넣지 않고 통계에 셈).

## 11. 로그·실패 표본

- 요청·응답 헤더는 기록하지 않습니다. URL 을 남길 때는 사용자 정보·`#` 조각을 빼고, 이름이 민감해 보이는 쿼리 값(token·key·session·sig·code 등)을 `[REDACTED]` 로 가립니다. 요청별 로그는 DEBUG 입니다.
- 실패 표본(파싱 실패 원문)은 저장 전에 session·token·csrf·password·api key·cookie·Bearer 같은 이름에 붙은 값과, HTML `name`/`value`·`meta content`(순서·대소문자·줄바꿈·따옴표 없는 속성 포함)를 가립니다. 가리기 전에 본문을 자르지 않습니다. 속성이 끝나기 전에 잘려 그 값이 비밀인지 알 수 없으면 본문은 저장하지 않습니다. 응답 헤더는 표본에 넣지 않습니다.
- 차단(`BLOCKED_ACCESS`·`BLOCKED_RATE`·`CIRCUIT_OPEN`)은 ERROR 로그 `[알림 필요]` 를 남깁니다. 외부 알림(메일·메신저)은 없습니다.

## 12. 설계·이슈·라이브러리와 다른 점

| 항목 | 설계·이슈 | 구현 | 이유 |
|---|---|---|---|
| HTTP 클라이언트 | 설계 §5.1 JDK `HttpClient` | 소켓 위 최소 HTTP/1.1 클라이언트(`HttpTransport`) | `HttpClient` 는 이름 해석을 바꿀 수 없어, 우리가 검사한 IP 와 실제 연결 IP 가 같다고 보장하지 못함(검사 뒤 다시 해석, DNS rebinding). 리다이렉트마다 목적지 IP 검사(이슈 요구 3)를 지키려고 직접 해석·검사한 IP 에 연결. HTTP/2·keep-alive·프록시는 쓰지 않음 |
| 응답 압축 | — | gzip 만 받음(풀린 크기로 상한 검사) | 압축 폭탄 방지 |
| jsoup | 설계 §5.1 | 넣지 않음 | 공통 기반에는 HTML 해석이 없음. 어댑터 이슈에서 추가 |
| robots 404 | RFC 9309: 4xx 는 "제한 없음" | 읽지 못한 것으로 보고 실행하지 않음 | 설계 D-20 "읽지 못하면 실행하지 않음"의 보수 해석. 결정 필요(§13) |
| 회로 차단기 열림 | 설계 §7.3 "일시 정지" | `BLOCKED`(`CIRCUIT_OPEN`) | #45 의 `PAUSED` 에는 사유 열이 없어, 사유가 남는 `BLOCKED` 사용. 재개 방법은 같음 |
| 하루 상한 | 설계 §7.2 "호스트당" | 플랫폼당 | 플랫폼 허용 호스트가 여럿인 곳(미스터블루·코미코)도 한 상한으로 묶음(더 보수적) |
| 동시성 | 설계 §7.2 "호스트당 1" | 플랫폼당 1 | 같음(더 보수적). "동시에 도는 플랫폼 최대 3"은 명령 하나가 플랫폼 하나만 돌리므로 운영 절차로 지킴 |
| 재시도 무작위 | 설계 "무작위 지연을 더함"(양 미정) | 각 기본값의 0~10% | 명세에 양이 없어 정함 |
| 429 재시도 시각 | 설계 "Retry-After 를 따름" | `max(Retry-After, 현재 간격)`, 없으면 60초, 상한 86,400초 | 형식 오류·과도한 값 대비 |
| 실패 사유 | V9 CHECK 11개 | V13 에서 4개 추가 | 허용하지 않은 목적지·크기 초과·형식 오류·기타 4xx 를 구분해 기록 |
| PROBE 범위 | 설계 §4.2 "목록·상세 몇 건" / §7.1 "상세는 수집하지 않음" | robots·SITEMAP 만 | 두 절이 달라 더 좁은 쪽(§7.1)을 따름. 목록·상세 표본이 필요하면 결정 필요 |
| 목록 단계 일반 등급 라벨 | 설계 §6.3 | 상세 작업에 넘기지 않음(성인 신호만 목록 값으로 반영) | 작업 큐에 URL 만 있음. 어댑터가 상세에서 다시 읽음. 필요하면 후속에서 작업 메모 열 추가 |

## 13. 결정하지 않은 정책 (기본 동작만 정함)

- robots.txt 가 404 일 때 실행 여부(지금은 실행하지 않음)
- 연락처 URL(D-16): 기본값 없음. 정해질 때까지 명령마다 지정
- 차단 화면 판단 표지(`g-recaptcha` 등)가 정상 페이지에 섞여 있는 플랫폼의 예외 처리(지금은 멈춤 쪽). 어댑터 이슈에서 확인
- 하루 상한 날짜 기준(지금은 한국 시간), 실제로 보내지 않은 요청도 세는지(지금은 셈)
- 차단 알림 경로(지금은 로그만)
- PROBE 에서 목록·상세 표본을 허용할지

## 14. 테스트

| 테스트 | 내용 |
|---|---|
| `CollectorEngineIntegrationTest` | 모의 HTTP 서버 + 격리 DB 로 §3~§9 전체: 게이트, PROBE 범위, 간격(리다이렉트·재시도·재개·프로세스 재시작·완료 뒤 새 실행·중단 뒤 Retry-After·차단 뒤 남은 대기·동시 시작 포함)·Crawl-delay·남은 Retry-After, robots 금지·다시 읽기·읽기 실패, 429 1회·2회, 403·CAPTCHA, 인증 리다이렉트, 목적지 거절, 5xx 재시도 일정, 회로 차단기, 타임아웃·크기·404·파싱 실패 표본(HTML 속성·잘린 본문), 하루 상한, 설정 변경, 대기 중 중단·잠금 연장·잠금 상실, 요청 중 잠금 상실, 네트워크 대기 중 DB 연결 0, 쿠키 값 미기록 |
| `HttpTransportTest` | 고정 헤더·Set-Cookie 버림, 리다이렉트 미추적, 크기 상한(길이·청크·gzip 폭탄), 전체 시간 제한, 운영 네트워크의 로컬·사설·메타데이터·혼합 해석 거절(연결 전), 트랜잭션 안 호출 거절, TLS 인증서 호스트 이름 확인(테스트용 자체 서명 인증서) |
| `RobotsRulesTest`, `PublicAddressPolicyTest`, `PlatformThrottleTest`, `CollectorCoreUnitTest`, `CollectCommandTest` | robots 해석, IP 판정, 동시 1건·간격, 재시도 계산·Retry-After·회로 차단기·사용자 에이전트·화면 판단·로그 가림, 명령 프로필·인자 |

- 간격은 가상 시계로 잽니다. 가상 시계가 흐른 만큼 대기 중인 작업의 `next_attempt_at` 과 실행·플랫폼의 `next_request_at` 을 당겨 DB 시각과 맞춥니다(테스트 도우미).
- 실제 사이트·DNS 는 쓰지 않습니다. 테스트 해석기는 `*.mock.test` 만 해석합니다.
- #68 회귀는 메모리를 공유하지 않는 엔진으로 확인했습니다. 운영체제 프로세스 두 개를 따로 띄운 확인과 실제 플랫폼 요청은 하지 않았습니다.
