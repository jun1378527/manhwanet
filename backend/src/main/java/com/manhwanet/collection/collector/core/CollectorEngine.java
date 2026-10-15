package com.manhwanet.collection.collector.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.collection.application.CollectedListing;
import com.manhwanet.collection.application.CrawlRunService;
import com.manhwanet.collection.application.CrawlTaskService;
import com.manhwanet.collection.application.StagingIngestService;
import com.manhwanet.collection.collector.adapter.DiscoveredRef;
import com.manhwanet.collection.collector.adapter.Discovery;
import com.manhwanet.collection.collector.adapter.PlatformAdapter;
import com.manhwanet.collection.collector.adapter.TaskRequest;
import com.manhwanet.collection.collector.net.FetchException;
import com.manhwanet.collection.collector.net.HttpTransport;
import com.manhwanet.collection.collector.net.HttpTransport.RawResponse;
import com.manhwanet.collection.collector.net.LogSafe;
import com.manhwanet.collection.domain.CollectionPolicy;
import com.manhwanet.collection.domain.CollectionPolicyRepository;
import com.manhwanet.collection.domain.CollectorRequestCounterRepository;
import com.manhwanet.collection.domain.DetailStatus;
import com.manhwanet.collection.domain.PolicyRow;
import com.manhwanet.collection.domain.RunKind;
import com.manhwanet.collection.domain.RunRow;
import com.manhwanet.collection.domain.RunStatus;
import com.manhwanet.collection.domain.StopReason;
import com.manhwanet.collection.domain.TaskError;
import com.manhwanet.collection.domain.TaskKind;
import com.manhwanet.collection.domain.TaskRow;
import com.manhwanet.collection.domain.TaskStatus;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.platform.domain.PlatformRepository;
import com.manhwanet.platform.domain.PlatformRow;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수집 실행 진입점(#46, 설계 §5·§7). 이 클래스는 트랜잭션을 걸지 않는다.
 * <ul>
 *   <li>네트워크 요청과 대기는 트랜잭션 밖에서만 한다. DB 쓰기(작업 결과·staging 반영·실행 상태)는 짧은 트랜잭션으로 따로 한다.</li>
 *   <li>모든 쓰기는 #45 서비스가 실행 잠금을 확인한다. 잠금을 잃으면 아무것도 더 쓰지 않고 끝낸다.</li>
 *   <li>수집 설정이 DISABLED 면 실행을 만들지 않고 요청도 보내지 않는다. PROBE 실행은 robots.txt 와 SITEMAP 작업만 요청한다.</li>
 *   <li>요청 간격은 max(5초, 수집 설정 간격, robots Crawl-delay) × (429 뒤 2) + 0~1초 무작위이고, 재시도·리다이렉트·robots·여러 작업 모두에 적용한다.
 *       다음 허용 시각은 실행 행에 남겨, 프로세스가 바뀌어 재개해도 남은 간격·Retry-After 를 지킨다.</li>
 * </ul>
 * 일반 웹 서버는 이 클래스를 부르지 않는다(collector 프로필의 CollectCommand 만 부름).
 */
@Service
public class CollectorEngine {

    private static final Logger log = LoggerFactory.getLogger(CollectorEngine.class);

    /** 요청 간격 하한(설계 §7.2). 설정으로 줄일 수 없다 */
    public static final long MIN_INTERVAL_MILLIS = 5_000;
    /** 요청 간격에 더하는 무작위 지연 상한 */
    public static final int JITTER_MILLIS = 1_000;
    public static final long MAX_INTERVAL_MILLIS = 3_600_000;
    /** robots.txt 다시 읽기 주기 */
    public static final long ROBOTS_TTL_MILLIS = TimeUnit.HOURS.toMillis(6);
    public static final int ROBOTS_ATTEMPTS = 3;
    /** 429 에 Retry-After 가 없거나 틀렸을 때 기다릴 초 */
    public static final int DEFAULT_RETRY_AFTER_SECONDS = 60;
    /** PROBE 하루 요청 상한(설계 §4.2)과 실행당 작업 수 상한 */
    public static final int PROBE_DAILY_CAP = 200;
    public static final int PROBE_MAX_TASKS = 50;
    /** 대기 중 확인 간격 */
    static final long WAIT_SLICE_MILLIS = 1_000;

    private static final Set<TaskError> DETAIL_FAILURES = EnumSet.of(TaskError.GATED, TaskError.NOT_FOUND,
            TaskError.PARSE_FAILED);

    private final CollectorProperties properties;
    private final Map<String, PlatformAdapter> adapters;
    private final PlatformRepository platformRepository;
    private final CollectionPolicyRepository policyRepository;
    private final CrawlRunService runService;
    private final CrawlTaskService taskService;
    private final StagingIngestService ingestService;
    private final CollectorRequestCounterRepository counterRepository;
    private final HttpTransport transport;
    private final PlatformThrottle throttle;
    private final CollectorClock clock;
    private final RandomGenerator random;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public CollectorEngine(CollectorProperties properties, List<PlatformAdapter> adapterList,
            PlatformRepository platformRepository, CollectionPolicyRepository policyRepository, CrawlRunService runService,
            CrawlTaskService taskService, StagingIngestService ingestService,
            CollectorRequestCounterRepository counterRepository, HttpTransport transport, PlatformThrottle throttle,
            CollectorClock clock, RandomGenerator random, PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.adapters = adapterList.stream().collect(Collectors.toUnmodifiableMap(
                a -> a.platformCode().toUpperCase(Locale.ROOT), Function.identity(), (a, b) -> {
                    throw new IllegalStateException("같은 플랫폼의 어댑터가 둘 있습니다: " + a.platformCode());
                }));
        this.platformRepository = platformRepository;
        this.policyRepository = policyRepository;
        this.runService = runService;
        this.taskService = taskService;
        this.ingestService = ingestService;
        this.counterRepository = counterRepository;
        this.transport = transport;
        this.throttle = throttle;
        this.clock = clock;
        this.random = random;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 새 실행을 시작해 끝(완료·정지·차단)까지 돈다. 설정·어댑터·연락처를 먼저 확인하고, 문제가 있으면 실행을 만들지 않는다 */
    public RunReport start(String platformCode, RunKind kind, String owner) {
        requireNoTransaction();
        String code = platformCode == null ? "" : platformCode.strip().toUpperCase(Locale.ROOT);
        PlatformAdapter adapter = adapter(code);
        String userAgent = UserAgent.of(properties.version(), properties.contactUrl());
        PlatformRow platform = platformRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLATFORM_NOT_FOUND));
        PolicyRow policy = policyRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLATFORM_NOT_FOUND));
        requireAllowed(policy, kind);
        RunRow run = runService.start(code, kind, adapter.version(), owner, properties.leaseSeconds(), null);
        log.info("[수집 시작] platform={} run={} kind={} owner={}", code, run.id(), kind, owner);
        return new Session(run, owner, adapter, platform, policy, userAgent).execute(true);
    }

    /** 멈춘(PAUSED·BLOCKED) 실행이나 잠금이 만료된 실행을 같은 run id 로 이어 간다. 남은 작업만 처리한다 */
    public RunReport resume(long runId, String owner) {
        requireNoTransaction();
        RunRow existing = runService.find(runId);
        PlatformAdapter adapter = adapter(existing.platformCode());
        String userAgent = UserAgent.of(properties.version(), properties.contactUrl());
        PlatformRow platform = platformRepository.findByCode(existing.platformCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLATFORM_NOT_FOUND));
        RunRow run = runService.acquire(runId, owner, properties.leaseSeconds());
        PolicyRow policy = policyRepository.findByCode(run.platformCode()).orElseThrow();
        if (!adapter.version().equals(run.adapterVersion())) {
            log.warn("[수집 재개] run={} 어댑터 버전이 다릅니다(실행 {}, 현재 {})", runId, run.adapterVersion(), adapter.version());
        }
        log.info("[수집 재개] platform={} run={} status={} owner={}", run.platformCode(), runId, run.status(), owner);
        return new Session(run, owner, adapter, platform, policy, userAgent).execute(false);
    }

    private PlatformAdapter adapter(String code) {
        PlatformAdapter adapter = adapters.get(code);
        if (adapter == null) {
            throw new IllegalArgumentException("이 플랫폼의 수집 어댑터가 없습니다: " + code);
        }
        return adapter;
    }

    static boolean allowed(PolicyRow policy, RunKind kind) {
        return policy.collectionPolicy() == CollectionPolicy.ENABLED
                || (policy.collectionPolicy() == CollectionPolicy.PROBE && kind == RunKind.PROBE);
    }

    private static void requireAllowed(PolicyRow policy, RunKind kind) {
        if (kind == null || !allowed(policy, kind)) {
            throw new BusinessException(ErrorCode.COLLECTION_NOT_ALLOWED, ErrorCode.COLLECTION_NOT_ALLOWED.getMessage()
                    + " (설정 " + policy.collectionPolicy() + ", 실행 " + kind + ")");
        }
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("수집기는 DB 트랜잭션 밖에서만 실행합니다.");
        }
    }

    private static boolean isLeaseLost(BusinessException e) {
        return e.getErrorCode() == ErrorCode.CRAWL_RUN_LEASE_LOST;
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record RobotsEntry(RobotsRules rules, String sha256, long fetchedAtNanos) {
    }

    /** 실행 하나를 도는 동안의 상태. 한 스레드에서만 쓴다 */
    private final class Session implements WaitGuard {

        private final long runId;
        private final String owner;
        private final RunKind kind;
        private final String code;
        private final PlatformAdapter adapter;
        private final Set<String> allowedHosts;
        private final String userAgent;
        private final HttpTransport.Limits limits;
        private final long renewEveryNanos;
        private final Map<String, RobotsEntry> robots = new HashMap<>();
        private final CircuitBreaker breaker = new CircuitBreaker();
        private final Map<String, Object> stats = new TreeMap<>();
        private PolicyRow policy;
        private String primaryHost;
        private long lastRenewNanos;
        private long maxCrawlDelayMillis;
        private int multiplier = 1;

        Session(RunRow run, String owner, PlatformAdapter adapter, PlatformRow platform, PolicyRow policy, String userAgent) {
            this.runId = run.id();
            this.owner = owner;
            this.kind = run.runKind();
            this.code = run.platformCode();
            this.adapter = adapter;
            this.allowedHosts = Set.copyOf(platform.allowedHosts());
            this.userAgent = userAgent;
            this.policy = policy;
            this.limits = new HttpTransport.Limits((int) properties.connectTimeout().toMillis(),
                    (int) properties.readTimeout().toMillis(), (int) properties.totalTimeout().toMillis(),
                    properties.maxBodyBytes());
            this.renewEveryNanos = TimeUnit.SECONDS.toNanos(properties.leaseSeconds()) / 3;
        }

        RunReport execute(boolean fresh) {
            try {
                lastRenewNanos = clock.nanoTime();
                if (runService.rateLimitHits(runId) > 0) {
                    multiplier = 2;
                }
                RunRow current = runService.find(runId);
                if (current.status() == RunStatus.STOP_REQUESTED) {
                    throw new RunHaltException(Halt.STOP_REQUESTED, "중단 요청된 실행");
                }
                // 재개 때도 시작 호스트(robots.txt 를 먼저 읽을 곳)는 발견 경로에서 정한다. 작업은 새 실행에서만 넣는다
                List<TaskRequest> seeds = adapter.discoverySources(kind);
                primaryHost = seeds.stream().map(s -> hostOf(s.url())).filter(h -> h != null && allowedHosts.contains(h))
                        .findFirst().orElse(allowedHosts.stream().sorted().findFirst().orElseThrow());
                checkpoint();
                try {
                    robotsFor(primaryHost); // 다른 요청보다 먼저 robots.txt
                } catch (TaskOutcomeException o) {
                    throw robotsUnavailable(primaryHost, "robots.txt 를 요청할 수 없는 목적지입니다(" + o.error() + ").");
                }
                if (fresh) {
                    tx.executeWithoutResult(s -> {
                        runService.requireLeaseForWrite(runId, owner, false);
                        for (TaskRequest seed : seeds) {
                            enqueueChecked(seed);
                        }
                    });
                }
                while (true) {
                    checkpoint();
                    List<TaskRow> claimed = taskService.claim(runId, owner, 1);
                    if (claimed.isEmpty()) {
                        Optional<Long> delay = taskService.nextPendingDelayMillis(runId);
                        if (delay.isEmpty()) {
                            try {
                                RunRow done = runService.complete(runId, owner, statsJson());
                                log.info("[수집 완료] platform={} run={} stats={}", code, runId, statsJson());
                                return new RunReport(runId, done.status(), null, Map.copyOf(stats));
                            } catch (BusinessException e) {
                                if (e.getErrorCode() == ErrorCode.CRAWL_RUN_STATE_CONFLICT) {
                                    continue; // 그사이 중단 요청 등 → 다음 확인에서 처리
                                }
                                throw e;
                            }
                        }
                        await(Math.max(200, delay.get()));
                        continue;
                    }
                    process(claimed.get(0));
                }
            } catch (RunHaltException h) {
                return halt(h);
            } catch (BusinessException e) {
                if (isLeaseLost(e)) {
                    return halt(new RunHaltException(Halt.LEASE_LOST, "실행 잠금을 잃었습니다."));
                }
                throw e;
            }
        }

        // ---------- 작업 처리 ----------

        private void process(TaskRow task) {
            TaskContext context = new TaskContext(task);
            Runnable write;
            try {
                write = runAdapter(task, context);
            } catch (RunHaltException h) {
                if (h.taskError() != null) {
                    writeFailure(task, h.taskError(), h.httpStatus(), h.retryAfterSeconds(), null);
                }
                throw h;
            } catch (TaskOutcomeException o) {
                context.throwIfHalted();
                write = () -> writeFailure(task, o.error(), o.httpStatus(), o.retryAfterSeconds(), null);
            } catch (ParseFailureException p) {
                context.throwIfHalted();
                log.warn("[파싱 실패] platform={} run={} task={} url={} {}", code, runId, task.id(), LogSafe.url(task.url()),
                        LogSafe.redactText(String.valueOf(p.getMessage())));
                byte[] sample = p.body() != null ? p.body() : context.lastBody;
                write = () -> writeFailure(task, TaskError.PARSE_FAILED, context.lastStatus, null, LogSafe.redactBody(sample));
            } catch (BusinessException e) {
                throw e;
            } catch (RuntimeException e) {
                context.throwIfHalted();
                log.warn("[어댑터 오류] platform={} run={} task={} {}", code, runId, task.id(), e.getClass().getSimpleName());
                write = () -> writeFailure(task, TaskError.PARSE_FAILED, context.lastStatus, null, null);
            }
            write.run();
        }

        /** 네트워크 단계(트랜잭션 밖). 성공하면 결과를 쓰는 동작을 돌려준다 */
        private Runnable runAdapter(TaskRow task, TaskContext context) {
            switch (task.taskKind()) {
                case DETAIL -> {
                    if (kind == RunKind.PROBE) {
                        return () -> writeFailure(task, TaskError.PROTOCOL_ERROR, null, null, null);
                    }
                    CollectedListing listing = adapter.fetchDetail(task.url(), context);
                    context.throwIfHalted();
                    if (listing == null || !code.equals(listing.platformCode())) {
                        throw new ParseFailureException("어댑터가 이 플랫폼의 결과를 돌려주지 않았습니다.", null);
                    }
                    return () -> writeSuccess(task, context.lastStatus, List.of(listing), List.of());
                }
                case SITEMAP, LIST -> {
                    if (kind == RunKind.PROBE && task.taskKind() != TaskKind.SITEMAP) {
                        return () -> writeFailure(task, TaskError.PROTOCOL_ERROR, null, null, null);
                    }
                    Discovery discovery = adapter.discover(task.taskKind(), task.url(), context);
                    context.throwIfHalted();
                    List<CollectedListing> listings = new ArrayList<>();
                    List<TaskRequest> next = new ArrayList<>(discovery == null ? List.of() : discovery.next());
                    if (discovery != null && kind != RunKind.PROBE) {
                        for (DiscoveredRef ref : discovery.refs()) {
                            if (ref.listOnly() != null
                                    && DetailStatus.SKIPPED_ADULT.name().equals(ref.listOnly().detailStatus())) {
                                listings.add(ref.listOnly()); // 목록 단계 성인 신호: 상세를 요청하지 않음
                            } else if (ref.detailUrl() != null) {
                                next.add(new TaskRequest(TaskKind.DETAIL, ref.detailUrl()));
                            }
                        }
                    }
                    return () -> writeSuccess(task, context.lastStatus, listings, next);
                }
                case ROBOTS -> {
                    String host = hostOf(task.url());
                    if (host == null || !allowedHosts.contains(host)) {
                        return () -> writeFailure(task, TaskError.BLOCKED_DESTINATION, null, null, null);
                    }
                    robotsFor(host);
                    return () -> writeSuccess(task, 200, List.of(), List.of());
                }
                default -> throw new IllegalStateException("unknown task kind");
            }
        }

        private void writeSuccess(TaskRow task, Integer httpStatus, List<CollectedListing> listings, List<TaskRequest> next) {
            try {
                tx.executeWithoutResult(s -> {
                    RunRow run = runService.requireLeaseForWrite(runId, owner, true);
                    boolean stopping = run.status() == RunStatus.STOP_REQUESTED;
                    for (CollectedListing listing : listings) {
                        ingestService.ingest(runId, owner, listing);
                    }
                    if (!stopping) {
                        for (TaskRequest request : next) {
                            enqueueChecked(request);
                        }
                    }
                    taskService.succeed(task.id(), owner, httpStatus);
                });
                count("task_done");
                count("listings", listings.size());
            } catch (BusinessException e) {
                if (isLeaseLost(e) || e.getErrorCode() == ErrorCode.CRAWL_RUN_STATE_CONFLICT) {
                    throw e;
                }
                // 어댑터 결과가 검증을 통과하지 못함(형식 오류·별칭 충돌 등): 결과는 버리고 작업을 파싱 실패로 닫는다
                log.warn("[결과 반영 실패] platform={} run={} task={} code={}", code, runId, task.id(), e.getErrorCode());
                writeFailure(task, TaskError.PARSE_FAILED, httpStatus, null, null);
            }
        }

        private void writeFailure(TaskRow task, TaskError error, Integer httpStatus, Integer retryAfterSeconds, byte[] sample) {
            Optional<CollectedListing> failed = kind != RunKind.PROBE && task.taskKind() == TaskKind.DETAIL
                    && DETAIL_FAILURES.contains(error) ? safeFailedDetail(task.url(), error) : Optional.empty();
            try {
                writeFailureTx(task, error, httpStatus, retryAfterSeconds, sample, failed);
            } catch (BusinessException e) {
                if (isLeaseLost(e) || failed.isEmpty()) {
                    throw e;
                }
                log.warn("[실패 기록의 staging 반영 실패] platform={} run={} task={} code={}", code, runId, task.id(), e.getErrorCode());
                writeFailureTx(task, error, httpStatus, retryAfterSeconds, sample, Optional.empty());
            }
            count("task_" + error.name());
        }

        private void writeFailureTx(TaskRow task, TaskError error, Integer httpStatus, Integer retryAfterSeconds, byte[] sample,
                Optional<CollectedListing> failed) {
            tx.executeWithoutResult(s -> {
                runService.requireLeaseForWrite(runId, owner, true);
                failed.ifPresent(listing -> ingestService.ingest(runId, owner, listing));
                taskService.fail(task.id(), owner, error, httpStatus, retryAfterSeconds, sample);
            });
        }

        private Optional<CollectedListing> safeFailedDetail(String url, TaskError error) {
            try {
                Optional<CollectedListing> listing = adapter.failedDetail(url, error);
                return listing == null ? Optional.empty() : listing.filter(l -> code.equals(l.platformCode()));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        /** 트랜잭션 안에서 부른다. 허용하지 않는 작업은 넣지 않고 기록만 한다 */
        private void enqueueChecked(TaskRequest request) {
            if (request == null || request.kind() == null) {
                return;
            }
            if (kind == RunKind.PROBE) {
                if (request.kind() != TaskKind.SITEMAP) {
                    count("probe_refused");
                    return;
                }
                long total = taskService.countByStatus(runId).values().stream().mapToLong(Long::longValue).sum();
                if (total >= PROBE_MAX_TASKS) {
                    count("probe_refused");
                    return;
                }
            }
            String url;
            try {
                url = CrawlTaskService.taskUrl(request.url());
            } catch (BusinessException e) {
                count("enqueue_invalid");
                return;
            }
            String host = hostOf(url);
            if (host == null || !allowedHosts.contains(host)) {
                count("enqueue_disallowed_host");
                log.warn("[작업 거절] platform={} run={} 허용하지 않은 호스트: {}", code, runId, LogSafe.url(url));
                return;
            }
            taskService.enqueue(runId, owner, request.kind(), url);
        }

        // ---------- 정지 ----------

        private RunReport halt(RunHaltException h) {
            log.warn("[수집 정지] platform={} run={} reason={} {}", code, runId, h.halt(), h.getMessage());
            if (h.halt() == Halt.LEASE_LOST || h.halt() == Halt.INTERRUPTED) {
                RunStatus status = runService.find(runId).status();
                return new RunReport(runId, status, h.halt(), Map.copyOf(stats));
            }
            stats.put("haltReason", h.halt().name());
            stats.put("haltMessage", h.getMessage());
            try {
                RunRow current = runService.find(runId);
                if (h.halt() == Halt.STOP_REQUESTED || current.status() == RunStatus.STOP_REQUESTED) {
                    RunRow stopped = runService.finishStop(runId, owner, statsJson());
                    return new RunReport(runId, stopped.status(), Halt.STOP_REQUESTED, Map.copyOf(stats));
                }
                runService.recordStats(runId, owner, statsJson());
                RunRow after = switch (h.halt()) {
                    case DAILY_CAP, POLICY_CHANGED -> runService.pause(runId, owner);
                    case BLOCKED_ACCESS -> runService.block(runId, owner, StopReason.BLOCKED_ACCESS);
                    case BLOCKED_RATE -> runService.block(runId, owner, StopReason.BLOCKED_RATE);
                    case ROBOTS_UNAVAILABLE -> runService.block(runId, owner, StopReason.ROBOTS_UNAVAILABLE);
                    case CIRCUIT_OPEN -> runService.block(runId, owner, StopReason.CIRCUIT_OPEN);
                    default -> throw new IllegalStateException("unexpected halt " + h.halt());
                };
                if (h.halt() == Halt.BLOCKED_ACCESS || h.halt() == Halt.BLOCKED_RATE || h.halt() == Halt.CIRCUIT_OPEN) {
                    log.error("[알림 필요] platform={} run={} 실행을 멈췄습니다: {}. 원인을 확인한 뒤 재개하세요.", code, runId, h.halt());
                }
                return new RunReport(runId, after.status(), h.halt(), Map.copyOf(stats));
            } catch (BusinessException e) {
                if (isLeaseLost(e)) {
                    return new RunReport(runId, runService.find(runId).status(), Halt.LEASE_LOST, Map.copyOf(stats));
                }
                if (e.getErrorCode() == ErrorCode.CRAWL_RUN_STATE_CONFLICT
                        && runService.find(runId).status() == RunStatus.STOP_REQUESTED) {
                    RunRow stopped = runService.finishStop(runId, owner, statsJson());
                    return new RunReport(runId, stopped.status(), Halt.STOP_REQUESTED, Map.copyOf(stats));
                }
                throw e;
            }
        }

        // ---------- 대기·확인 ----------

        @Override
        public void checkpoint() {
            requireNoTransaction();
            long now = clock.nanoTime();
            if (now - lastRenewNanos >= renewEveryNanos) {
                try {
                    runService.renew(runId, owner, properties.leaseSeconds());
                } catch (BusinessException e) {
                    if (isLeaseLost(e) || e.getErrorCode() == ErrorCode.CRAWL_RUN_STATE_CONFLICT) {
                        throw new RunHaltException(Halt.LEASE_LOST, "잠금을 연장하지 못했습니다.");
                    }
                    throw e;
                }
                lastRenewNanos = now;
                count("lease_renewals");
            }
            RunRow run = runService.find(runId);
            if (!run.heldBy(owner)) {
                throw new RunHaltException(Halt.LEASE_LOST, "실행 잠금을 잃었습니다.");
            }
            if (run.status() == RunStatus.STOP_REQUESTED) {
                throw new RunHaltException(Halt.STOP_REQUESTED, "중단 요청을 받았습니다.");
            }
            if (run.status() != RunStatus.RUNNING) {
                throw new RunHaltException(Halt.LEASE_LOST, "실행 상태가 바뀌었습니다(" + run.status() + ").");
            }
            PolicyRow latest = policyRepository.findByCode(code).orElseThrow();
            policy = latest;
            if (!allowed(latest, kind)) {
                throw new RunHaltException(Halt.POLICY_CHANGED, "수집 설정이 이 실행을 더는 허용하지 않습니다(" + latest.collectionPolicy() + ").");
            }
        }

        @Override
        public void await(long millis) {
            requireNoTransaction();
            checkpoint();
            long remaining = millis;
            while (remaining > 0) {
                long step = Math.min(remaining, WAIT_SLICE_MILLIS);
                try {
                    clock.sleep(step);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RunHaltException(Halt.INTERRUPTED, "대기 중 인터럽트");
                }
                remaining -= step;
                checkpoint();
            }
        }

        // ---------- 요청 ----------

        private int dailyCap() {
            return kind == RunKind.PROBE ? Math.min(PROBE_DAILY_CAP, policy.dailyRequestCap()) : policy.dailyRequestCap();
        }

        private long baseIntervalMillis() {
            long base = Math.max(MIN_INTERVAL_MILLIS, Math.max(policy.rateIntervalMs(), maxCrawlDelayMillis));
            return Math.min(MAX_INTERVAL_MILLIS, base * multiplier);
        }

        private long nextIntervalMillis() {
            return baseIntervalMillis() + random.nextInt(JITTER_MILLIS + 1);
        }

        /** DB 에 남은 간격·Retry-After 가 있으면 그 시각까지 잔다. 읽는 트랜잭션은 대기 전에 끝난다 */
        private void awaitStoredGap() {
            while (true) {
                long remaining = runService.millisUntilNextRequest(runId);
                if (remaining <= 0) {
                    return;
                }
                await(remaining);
            }
        }

        /** 다음 허용 시각을 실행 행에 남긴다. 잠금을 잃었으면 결과 처리 쪽에서 멈추게 예외를 올린다 */
        private void rememberGap(long millis) {
            runService.postponeNextRequest(runId, owner, millis);
        }

        /** 한 번의 HTTP 요청(리다이렉트를 따라가지 않음). 간격·동시 1건·하루 상한·회로 차단기를 적용한다 */
        private RawResponse send(URI uri, Integer attempts) {
            awaitStoredGap();
            PlatformThrottle.Permit permit = throttle.acquire(code, this);
            RawResponse response = null;
            TaskOutcomeException failure = null;
            boolean open;
            try {
                checkpoint();
                if (!counterRepository.tryIncrement(code, dailyCap())) {
                    throw new RunHaltException(Halt.DAILY_CAP, "하루 요청 상한(" + dailyCap() + ")에 닿았습니다.");
                }
                count("requests");
                log.debug("[요청] platform={} run={} {}", code, runId, LogSafe.url(uri));
                try {
                    response = transport.get(uri, userAgent, limits);
                    count("http_" + (response.status() / 100) + "xx");
                    open = breaker.record(response.status() >= 500);
                } catch (FetchException e) {
                    count("transport_" + e.kind().name());
                    open = breaker.record(e.kind() == FetchException.Kind.TIMEOUT
                            || e.kind() == FetchException.Kind.CONNECTION || e.kind() == FetchException.Kind.PROTOCOL);
                    failure = outcome(e, attempts);
                }
            } finally {
                long interval = nextIntervalMillis();
                permit.release(interval);
                rememberGap(interval);
            }
            if (open) {
                TaskError error = failure != null ? failure.error() : response.status() >= 500 ? TaskError.HTTP_5XX : null;
                Integer status = response == null ? null : response.status();
                Integer retry = failure != null ? failure.retryAfterSeconds() : error == null ? null : retryDelay(attempts);
                throw new RunHaltException(Halt.CIRCUIT_OPEN, "연속 실패·실패율 기준을 넘어 회로 차단기를 열었습니다.", error, status, retry);
            }
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        private TaskOutcomeException outcome(FetchException e, Integer attempts) {
            return switch (e.kind()) {
                case TIMEOUT -> new TaskOutcomeException(TaskError.TIMEOUT, null, retryDelay(attempts), e.getMessage());
                case CONNECTION -> new TaskOutcomeException(TaskError.CONNECTION, null, retryDelay(attempts), e.getMessage());
                case BLOCKED_DESTINATION -> new TaskOutcomeException(TaskError.BLOCKED_DESTINATION, null, null, e.getMessage());
                case TOO_LARGE -> new TaskOutcomeException(TaskError.RESPONSE_TOO_LARGE, null, null, e.getMessage());
                case PROTOCOL -> new TaskOutcomeException(TaskError.PROTOCOL_ERROR, null, null, e.getMessage());
            };
        }

        private Integer retryDelay(Integer attempts) {
            if (attempts == null) {
                return null;
            }
            OptionalInt delay = RetryPolicy.retryDelaySeconds(attempts, random);
            return delay.isPresent() ? delay.getAsInt() : null;
        }

        /** 429 처리. 한 실행에서 두 번째면 실행을 멈춘다 */
        private RuntimeException rateLimited(RawResponse response, Integer attempts) {
            long retryAfter = RetryAfter.seconds(response.header("retry-after"), Instant.now())
                    .orElse(DEFAULT_RETRY_AFTER_SECONDS);
            int hits = runService.recordRateLimit(runId, owner);
            stats.put("rateLimitHits", hits);
            multiplier = 2;
            long retryMillis = TimeUnit.SECONDS.toMillis(retryAfter);
            throttle.delayAtLeast(code, retryMillis);
            rememberGap(retryMillis);
            long wait = Math.max(retryAfter, (baseIntervalMillis() + 999) / 1000);
            Integer retry = attempts != null && attempts < CrawlTaskService.MAX_ATTEMPTS
                    ? (int) Math.min(RetryAfter.MAX_SECONDS, wait) : null;
            if (hits >= 2) {
                return new RunHaltException(Halt.BLOCKED_RATE, "한 실행에서 429 를 " + hits + "번 받았습니다.",
                        TaskError.RATE_LIMITED, 429, retry);
            }
            return new TaskOutcomeException(TaskError.RATE_LIMITED, 429, retry, "429, Retry-After " + retryAfter + "초");
        }

        // ---------- robots.txt ----------

        private RobotsRules robotsFor(String host) {
            RobotsEntry cached = robots.get(host);
            if (cached != null && clock.nanoTime() - cached.fetchedAtNanos() < TimeUnit.MILLISECONDS.toNanos(ROBOTS_TTL_MILLIS)) {
                return cached.rules();
            }
            RobotsEntry fresh = fetchRobots(host);
            robots.put(host, fresh);
            maxCrawlDelayMillis = robots.values().stream().mapToLong(e -> e.rules().crawlDelayMillis()).max().orElse(0);
            // robots.txt 요청 자체는 Crawl-delay 를 알기 전에 끝났으므로 다음 요청 시각을 다시 민다
            long afterRobots = nextIntervalMillis();
            throttle.delayAtLeast(code, afterRobots);
            rememberGap(afterRobots);
            stats.put("robots_" + host, fresh.sha256());
            if (cached != null && !cached.sha256().equals(fresh.sha256())) {
                log.info("[robots.txt 변경] platform={} run={} host={}", code, runId, host);
            }
            if (host.equals(primaryHost) && (cached == null || !cached.sha256().equals(fresh.sha256()))) {
                runService.recordRobots(runId, owner, fresh.sha256());
            }
            return fresh.rules();
        }

        private RobotsEntry fetchRobots(String host) {
            URI start = URI.create("https://" + host + "/robots.txt");
            String lastProblem = "응답 없음";
            for (int attempt = 1; attempt <= ROBOTS_ATTEMPTS; attempt++) {
                URI current = start;
                try {
                    for (int hop = 0; ; hop++) {
                        RawResponse response = send(current, null);
                        if (response.isRedirect()) {
                            URI next = hop >= properties.maxRedirects() ? null : redirectTarget(current, response.header("location"));
                            if (next == null || GateDetector.isGate(next) || adapter.isGateRedirect(next) || !allowedTarget(next)) {
                                throw robotsUnavailable(host, "robots.txt 리다이렉트를 따라갈 수 없습니다.");
                            }
                            current = next;
                            continue;
                        }
                        if (BlockDetector.isBlocked(response)) {
                            throw new RunHaltException(Halt.BLOCKED_ACCESS, "robots.txt 요청이 차단되었습니다(" + response.status() + ").");
                        }
                        if (response.status() == 429) {
                            RuntimeException r = rateLimited(response, null);
                            if (r instanceof RunHaltException halt) {
                                throw halt;
                            }
                            lastProblem = "429";
                            break; // 다음 시도(간격·Retry-After 는 throttle 이 지킴)
                        }
                        if (response.status() >= 500) {
                            lastProblem = "HTTP " + response.status();
                            break;
                        }
                        if (response.status() >= 200 && response.status() < 300) {
                            return new RobotsEntry(RobotsRules.parse(response.body()), sha256(response.body()), clock.nanoTime());
                        }
                        // 404 등: RFC 9309 는 "제한 없음"으로 보지만 이 수집기는 읽지 못한 것으로 보고 실행하지 않는다(D-20 보수 해석)
                        throw robotsUnavailable(host, "robots.txt 상태 " + response.status());
                    }
                } catch (TaskOutcomeException o) {
                    if (o.error() == TaskError.BLOCKED_DESTINATION) {
                        throw o; // 공개 주소가 아닌 호스트: 그 작업만 실패(시작 호스트면 실행 정지)
                    }
                    if (o.error() != TaskError.TIMEOUT && o.error() != TaskError.CONNECTION) {
                        throw robotsUnavailable(host, "robots.txt 요청 실패(" + o.error() + ")");
                    }
                    lastProblem = o.error().name();
                }
            }
            throw robotsUnavailable(host, "robots.txt 를 " + ROBOTS_ATTEMPTS + "번 시도했지만 읽지 못했습니다(" + lastProblem + ").");
        }

        private RunHaltException robotsUnavailable(String host, String message) {
            return new RunHaltException(Halt.ROBOTS_UNAVAILABLE, host + ": " + message);
        }

        // ---------- URL ----------

        private boolean allowedTarget(URI uri) {
            return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT));
        }

        private URI redirectTarget(URI from, String location) {
            if (location == null || location.isBlank() || location.length() > 2000) {
                return null;
            }
            try {
                String resolved = from.resolve(new URI(location.strip())).normalize().toString();
                int fragment = resolved.indexOf('#');
                return new URI(fragment < 0 ? resolved : resolved.substring(0, fragment));
            } catch (URISyntaxException | IllegalArgumentException e) {
                return null;
            }
        }

        private void count(String key) {
            count(key, 1);
        }

        private void count(String key, long n) {
            if (n != 0) {
                stats.merge(key, n, (a, b) -> ((Number) a).longValue() + ((Number) b).longValue());
            }
        }

        private String statsJson() {
            try {
                return objectMapper.writeValueAsString(stats);
            } catch (JsonProcessingException e) {
                return "{}";
            }
        }

        /** 어댑터에 주는 요청 통로. 작업 하나에 하나 */
        private final class TaskContext implements FetchContext {
            private final TaskRow task;
            private RunHaltException halted;
            private Integer lastStatus;
            private byte[] lastBody;

            TaskContext(TaskRow task) {
                this.task = task;
            }

            @Override
            public String platformCode() {
                return code;
            }

            @Override
            public RunKind runKind() {
                return kind;
            }

            void throwIfHalted() {
                if (halted != null) {
                    throw halted;
                }
            }

            @Override
            public FetchResponse get(String rawUrl) {
                throwIfHalted();
                try {
                    return fetch(rawUrl);
                } catch (RunHaltException h) {
                    halted = h;
                    throw h;
                }
            }

            private FetchResponse fetch(String rawUrl) {
                URI uri = parseTarget(rawUrl);
                String requested = uri.toString();
                for (int hop = 0; ; hop++) {
                    RobotsRules rules = robotsFor(uri.getHost().toLowerCase(Locale.ROOT));
                    String target = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                    if (!rules.allows(target)) {
                        throw new TaskOutcomeException(TaskError.SKIPPED_ROBOTS, null, null, "robots.txt 가 막은 경로입니다.");
                    }
                    RawResponse response = send(uri, task.attempts());
                    lastStatus = response.status();
                    lastBody = response.body();
                    if (response.isRedirect()) {
                        if (hop >= properties.maxRedirects()) {
                            throw new TaskOutcomeException(TaskError.PROTOCOL_ERROR, response.status(), null, "리다이렉트 한도 초과");
                        }
                        URI next = redirectTarget(uri, response.header("location"));
                        if (next == null) {
                            throw new TaskOutcomeException(TaskError.PROTOCOL_ERROR, response.status(), null, "리다이렉트 위치 형식 오류");
                        }
                        if (GateDetector.isGate(next) || adapter.isGateRedirect(next)) {
                            log.info("[따라가지 않음] platform={} run={} task={} 로그인·인증·결제 리다이렉트: {}", code, runId, task.id(),
                                    LogSafe.url(next));
                            throw new TaskOutcomeException(TaskError.GATED, response.status(), null, "로그인·성인 인증·결제 리다이렉트");
                        }
                        if (!allowedTarget(next)) {
                            log.warn("[따라가지 않음] platform={} run={} task={} 허용하지 않은 리다이렉트: {}", code, runId, task.id(),
                                    LogSafe.url(next));
                            throw new TaskOutcomeException(TaskError.BLOCKED_DESTINATION, response.status(), null,
                                    "허용하지 않은 리다이렉트 목적지");
                        }
                        uri = next;
                        continue;
                    }
                    classify(response);
                    return new FetchResponse(requested, uri.toString(), response.status(), response.header("content-type"),
                            response.body());
                }
            }

            private void classify(RawResponse response) {
                int status = response.status();
                if (BlockDetector.isBlocked(response)) {
                    throw new RunHaltException(Halt.BLOCKED_ACCESS, "접근이 차단되었습니다(HTTP " + status + ", 차단·확인 화면).",
                            TaskError.BLOCKED_ACCESS, status, null);
                }
                if (status >= 200 && status < 300) {
                    return;
                }
                if (status == 401 || status == 402 || status == 407) {
                    throw new TaskOutcomeException(TaskError.GATED, status, null, "인증·결제가 필요한 응답");
                }
                if (status == 404 || status == 410) {
                    throw new TaskOutcomeException(TaskError.NOT_FOUND, status, null, "없음");
                }
                if (status == 429) {
                    throw rateLimited(response, task.attempts());
                }
                if (status >= 500) {
                    throw new TaskOutcomeException(TaskError.HTTP_5XX, status, retryDelay(task.attempts()), "서버 오류");
                }
                if (status >= 400) {
                    throw new TaskOutcomeException(TaskError.HTTP_4XX, status, null, "요청 오류");
                }
                throw new TaskOutcomeException(TaskError.PROTOCOL_ERROR, status, null, "처리하지 않는 응답 상태");
            }

            private URI parseTarget(String rawUrl) {
                try {
                    URI uri = new URI(rawUrl == null ? "" : rawUrl.strip());
                    if (!allowedTarget(uri)) {
                        throw new TaskOutcomeException(TaskError.BLOCKED_DESTINATION, null, null, "허용하지 않은 요청 목적지");
                    }
                    return uri;
                } catch (URISyntaxException e) {
                    throw new TaskOutcomeException(TaskError.BLOCKED_DESTINATION, null, null, "URL 형식 오류");
                }
            }
        }
    }

    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** 테스트·CLI 용: 어댑터가 등록된 플랫폼 코드 */
    public Set<String> adapterCodes() {
        return new LinkedHashSet<>(adapters.keySet());
    }

    Map<TaskStatus, Long> taskCounts(long runId) {
        return taskService.countByStatus(runId);
    }
}
