package com.manhwanet.collection.application;

import com.manhwanet.audit.application.AdminChangeLog;
import com.manhwanet.audit.domain.ChangeAction;
import com.manhwanet.audit.domain.ChangeEntityType;
import com.manhwanet.collection.domain.CollectionPolicy;
import com.manhwanet.collection.domain.CollectionPolicyRepository;
import com.manhwanet.collection.domain.CrawlRunRepository;
import com.manhwanet.collection.domain.CrawlTaskRepository;
import com.manhwanet.collection.domain.PolicyRow;
import com.manhwanet.collection.domain.RunKind;
import com.manhwanet.collection.domain.RunRow;
import com.manhwanet.collection.domain.RunStatus;
import com.manhwanet.collection.domain.RunStatusRules;
import com.manhwanet.collection.domain.StopReason;
import com.manhwanet.global.admin.AdminInputs;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 실행의 시작·잠금·중단·재개 (#45, docs/work-collection-design.md §7.4). HTTP 수집기(#46)가 이 서비스를 부른다.
 * <ul>
 *   <li>플랫폼당 활성 실행은 하나(부분 유니크 인덱스). 이미 있으면 새 실행을 만들지 않고 409 와 그 실행 id 를 알린다.</li>
 *   <li>잠금(lease_owner·lease_until)이 만료되면 다른 프로세스가 같은 실행 행을 넘겨받는다. 넘겨받을 때 끝나지 않은
 *       RUNNING 작업은 PENDING 으로 되돌려 남은 작업만 이어 간다.</li>
 *   <li>중단 요청은 STOP_REQUESTED → 수집기가 STOPPED 로 닫고 남은 작업을 SKIPPED 로 닫는다. 끝난 실행은 다시 열지 않는다.</li>
 *   <li>수집 설정이 DISABLED 이면 시작·재개할 수 없고, PROBE 설정에서는 PROBE 실행만 된다. 시각은 모두 DB now() 기준.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class CrawlRunService {

    public static final int MIN_LEASE_SECONDS = 30;
    public static final int MAX_LEASE_SECONDS = 3600;
    private static final Pattern OWNER = Pattern.compile("^[!-~]{1,100}$");
    private static final Pattern ADAPTER = Pattern.compile("^[!-~]{1,30}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final CrawlRunRepository runRepository;
    private final CrawlTaskRepository taskRepository;
    private final CollectionPolicyRepository policyRepository;
    private final AdminChangeLog changeLog;

    public CrawlRunService(CrawlRunRepository runRepository, CrawlTaskRepository taskRepository,
            CollectionPolicyRepository policyRepository, AdminChangeLog changeLog) {
        this.runRepository = runRepository;
        this.taskRepository = taskRepository;
        this.policyRepository = policyRepository;
        this.changeLog = changeLog;
    }

    /** 새 실행. 활성 실행이 있으면 CrawlRunActiveException(409, 활성 실행 id) */
    @Transactional
    public RunRow start(String platformCode, RunKind kind, String adapterVersion, String owner, int leaseSeconds,
            String robotsSha256) {
        AdminInputs in = new AdminInputs();
        String code = platformCode == null ? null : platformCode.strip().toUpperCase(Locale.ROOT);
        if (kind == null) {
            in.add("runKind", "runKind 는 필수입니다.");
        }
        if (adapterVersion == null || !ADAPTER.matcher(adapterVersion).matches()) {
            in.add("adapterVersion", "adapterVersion 은 공백 없는 ASCII 1~30자여야 합니다.");
        }
        if (robotsSha256 != null && !SHA256.matcher(robotsSha256).matches()) {
            in.add("robotsSha256", "robotsSha256 은 소문자 16진수 64자여야 합니다.");
        }
        checkOwner(owner, leaseSeconds, in);
        in.throwIfAny();

        PolicyRow policy = policyRepository.lockForShare(code == null ? "" : code)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLATFORM_NOT_FOUND));
        requireAllowed(policy, kind);
        long id = runRepository.insertIfNoActive(code, kind, adapterVersion, owner, leaseSeconds, robotsSha256)
                .orElseThrow(() -> new CrawlRunActiveException(runRepository.findActive(code)
                        .map(RunRow::id).orElseThrow(() -> new IllegalStateException("active run not visible"))));
        return find(id);
    }

    /**
     * 잠금을 잡는다(재개·넘겨받기). 다른 프로세스가 살아 있는 잠금을 쥐고 있으면 409.
     * 잠금이 만료됐거나 다른 프로세스의 것이었을 때, 그리고 PAUSED·BLOCKED 를 다시 시작할 때는
     * 끝나지 않은 RUNNING 작업을 PENDING 으로 되돌린다.
     * STOP_REQUESTED 에서 유효한 잠금을 가진 같은 소유자가 다시 확인하면 진행 중 작업은 그대로 둔다.
     * 상태는 STOP_REQUESTED 로 유지하고, 중단 마무리(finishStop)에 쓴다.
     */
    @Transactional
    public RunRow acquire(long runId, String owner, int leaseSeconds) {
        AdminInputs in = new AdminInputs();
        checkOwner(owner, leaseSeconds, in);
        in.throwIfAny();
        RunRow run = lock(runId);
        if (RunStatusRules.FINISHED.contains(run.status())) {
            throw new BusinessException(ErrorCode.CRAWL_RUN_STATE_CONFLICT, "끝난 실행(" + run.status() + ")은 다시 열지 않습니다.");
        }
        if (run.leaseValid() && !owner.equals(run.leaseOwner())) {
            throw new BusinessException(ErrorCode.CRAWL_RUN_LEASE_LOST, "다른 프로세스가 이 실행의 잠금을 쥐고 있습니다.");
        }
        RunStatus next = run.status() == RunStatus.STOP_REQUESTED ? RunStatus.STOP_REQUESTED : RunStatus.RUNNING;
        if (next == RunStatus.RUNNING) {
            requireAllowed(policyRepository.lockForShare(run.platformCode()).orElseThrow(), run.runKind());
        }
        boolean reclaim = !run.heldBy(owner)
                || (run.status() != RunStatus.RUNNING && run.status() != RunStatus.STOP_REQUESTED);
        if (reclaim) {
            taskRepository.resetRunning(runId);
        }
        runRepository.lease(runId, next, owner, leaseSeconds);
        return find(runId);
    }

    /** 잠금 연장. 잠금을 쥔 프로세스만 */
    @Transactional
    public RunRow renew(long runId, String owner, int leaseSeconds) {
        AdminInputs in = new AdminInputs();
        checkOwner(owner, leaseSeconds, in);
        in.throwIfAny();
        RunRow run = held(runId, owner, EnumSet.of(RunStatus.RUNNING, RunStatus.STOP_REQUESTED));
        runRepository.lease(runId, run.status(), owner, leaseSeconds);
        return find(runId);
    }

    /** 일시 정지(원인을 확인한 뒤 같은 실행으로 재개). 잠금을 놓는다 */
    @Transactional
    public RunRow pause(long runId, String owner) {
        held(runId, owner, EnumSet.of(RunStatus.RUNNING));
        runRepository.release(runId, RunStatus.PAUSED, null);
        return find(runId);
    }

    /** 차단(429 반복·403·회로 차단 등). 사유 필수, 잠금을 놓는다. 원인을 확인한 뒤 acquire 로 같은 실행을 재개한다 */
    @Transactional
    public RunRow block(long runId, String owner, StopReason reason) {
        if (reason == null || reason == StopReason.OPERATOR_STOP) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "차단 사유가 필요합니다(OPERATOR_STOP 제외).");
        }
        held(runId, owner, EnumSet.of(RunStatus.RUNNING));
        runRepository.release(runId, RunStatus.BLOCKED, reason);
        return find(runId);
    }

    /** 중단 확정: STOP_REQUESTED → STOPPED, 남은 작업은 SKIPPED */
    @Transactional
    public RunRow finishStop(long runId, String owner, String statsJson) {
        held(runId, owner, EnumSet.of(RunStatus.STOP_REQUESTED));
        taskRepository.skipOpen(runId);
        runRepository.finish(runId, RunStatus.STOPPED, StopReason.OPERATOR_STOP, statsJson);
        return find(runId);
    }

    /** 완료: 끝나지 않은 작업(PENDING·RUNNING)이 남아 있으면 409 */
    @Transactional
    public RunRow complete(long runId, String owner, String statsJson) {
        held(runId, owner, EnumSet.of(RunStatus.RUNNING));
        Map<com.manhwanet.collection.domain.TaskStatus, Long> counts = taskRepository.countByStatus(runId);
        long open = counts.get(com.manhwanet.collection.domain.TaskStatus.PENDING)
                + counts.get(com.manhwanet.collection.domain.TaskStatus.RUNNING);
        if (open > 0) {
            throw new BusinessException(ErrorCode.CRAWL_RUN_STATE_CONFLICT, "끝나지 않은 작업이 " + open + "개 남아 있습니다.");
        }
        runRepository.finish(runId, RunStatus.COMPLETED, null, statsJson);
        return find(runId);
    }

    /** 실패로 닫는다. 남은 작업은 SKIPPED */
    @Transactional
    public RunRow fail(long runId, String owner, String statsJson) {
        held(runId, owner, EnumSet.of(RunStatus.RUNNING, RunStatus.STOP_REQUESTED));
        taskRepository.skipOpen(runId);
        runRepository.finish(runId, RunStatus.FAILED, StopReason.ERROR, statsJson);
        return find(runId);
    }

    /**
     * 관리자 중단 요청(ADMIN API). RUNNING 은 STOP_REQUESTED 로(수집기가 진행 중인 요청만 끝내고 닫음),
     * 수집기가 돌지 않는 PAUSED·BLOCKED 는 바로 STOPPED 로 닫는다. 이미 중단 요청 중이면 그대로. 끝난 실행은 409.
     * 바뀐 경우 같은 트랜잭션에서 변경 기록(CRAWL_RUN)을 남긴다.
     */
    @Transactional
    public RunRow requestStop(long runId, String rawReason, long actorId) {
        AdminInputs in = new AdminInputs();
        String reason = in.reason(rawReason, true);
        in.throwIfAny();
        RunRow before = lock(runId);
        if (RunStatusRules.FINISHED.contains(before.status())) {
            throw new BusinessException(ErrorCode.CRAWL_RUN_STATE_CONFLICT, "이미 끝난 실행입니다(" + before.status() + ").");
        }
        if (before.status() == RunStatus.STOP_REQUESTED) {
            return before;
        }
        if (before.status() == RunStatus.RUNNING) {
            runRepository.requestStop(runId);
        } else {
            taskRepository.skipOpen(runId);
            runRepository.finish(runId, RunStatus.STOPPED, StopReason.OPERATOR_STOP, null);
        }
        RunRow after = find(runId);
        changeLog.record(ChangeEntityType.CRAWL_RUN, String.valueOf(runId), ChangeAction.STATUS_CHANGE, null, null,
                snapshot(before), snapshot(after), reason, actorId);
        return after;
    }

    /** #46: 429 를 받았다. 잠금을 쥔 프로세스만. 이 실행에서 받은 429 횟수(이번 포함)를 돌려준다 */
    @Transactional
    public int recordRateLimit(long runId, String owner) {
        requireLeaseForWrite(runId, owner, true);
        return runRepository.incrementRateLimitHits(runId);
    }

    /** #46: 이 실행에서 받은 429 횟수(잠금 넘겨받기 뒤에도 이어서 센다) */
    public int rateLimitHits(long runId) {
        return runRepository.rateLimitHits(runId);
    }

    /** #46: 시작할 때 읽은 robots.txt 해시를 남긴다. 잠금을 쥔 프로세스만 */
    @Transactional
    public void recordRobots(long runId, String owner, String robotsSha256) {
        if (robotsSha256 == null || !SHA256.matcher(robotsSha256).matches()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "robotsSha256 은 소문자 16진수 64자여야 합니다.");
        }
        requireLeaseForWrite(runId, owner, true);
        runRepository.updateRobots(runId, robotsSha256);
    }

    /**
     * #46: 같은 플랫폼의 다음 요청을 지금부터 적어도 millis 뒤로 미룬다. 잠금을 쥔 프로세스만.
     * 호출이 끝나면 트랜잭션을 놓으므로, 기다리는 쪽은 이 메서드 밖에서 잔다.
     */
    @Transactional
    public void postponeNextRequest(long runId, String owner, long millis) {
        if (millis < 0 || millis > java.util.concurrent.TimeUnit.DAYS.toMillis(2)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "다음 요청까지 미룰 시간이 범위를 벗어났습니다.");
        }
        requireLeaseForWrite(runId, owner, true);
        runRepository.postponeNextRequest(runId, millis);
    }

    /** #46: 다음 요청까지 남은 밀리초. 읽기만 하고 행을 잠그지 않는다 */
    @Transactional(readOnly = true)
    public long millisUntilNextRequest(long runId) {
        return runRepository.millisUntilNextRequest(runId);
    }

    /** #46: 통계(JSON 객체)를 합친다. 일시 정지·차단 직전에 사유와 요청 수를 남기는 데 쓴다. 잠금을 쥔 프로세스만 */
    @Transactional
    public void recordStats(long runId, String owner, String statsJson) {
        if (statsJson == null || !statsJson.strip().startsWith("{")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "stats 는 JSON 객체여야 합니다.");
        }
        requireLeaseForWrite(runId, owner, true);
        runRepository.mergeStats(runId, statsJson);
    }

    public RunRow find(long runId) {
        return runRepository.findById(runId).orElseThrow(() -> new BusinessException(ErrorCode.CRAWL_RUN_NOT_FOUND));
    }

    /**
     * 잠금을 쥔 프로세스가 결과·작업을 쓸 때 부른다(공유 잠금). 상태 전환과 직렬화되므로, 잠금을 잃은 프로세스는
     * 넘겨받은 뒤에 쓰지 못한다. 반환한 행은 호출 트랜잭션이 끝날 때까지 상태가 바뀌지 않는다.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public RunRow requireLeaseForWrite(long runId, String owner, boolean allowStopRequested) {
        RunRow run = runRepository.lockForShare(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CRAWL_RUN_NOT_FOUND));
        boolean statusOk = run.status() == RunStatus.RUNNING
                || (allowStopRequested && run.status() == RunStatus.STOP_REQUESTED);
        if (owner == null || !run.heldBy(owner) || !statusOk) {
            throw new BusinessException(ErrorCode.CRAWL_RUN_LEASE_LOST);
        }
        return run;
    }

    private RunRow held(long runId, String owner, EnumSet<RunStatus> allowed) {
        RunRow run = lock(runId);
        if (owner == null || !run.heldBy(owner)) {
            throw new BusinessException(ErrorCode.CRAWL_RUN_LEASE_LOST);
        }
        if (!allowed.contains(run.status())) {
            throw new BusinessException(ErrorCode.CRAWL_RUN_STATE_CONFLICT,
                    "현재 상태(" + run.status() + ")에서는 할 수 없는 요청입니다.");
        }
        return run;
    }

    private RunRow lock(long runId) {
        return runRepository.lockForUpdate(runId).orElseThrow(() -> new BusinessException(ErrorCode.CRAWL_RUN_NOT_FOUND));
    }

    private static void requireAllowed(PolicyRow policy, RunKind kind) {
        if (policy.collectionPolicy() == CollectionPolicy.DISABLED
                || (policy.collectionPolicy() == CollectionPolicy.PROBE && kind != RunKind.PROBE)) {
            throw new BusinessException(ErrorCode.COLLECTION_NOT_ALLOWED, ErrorCode.COLLECTION_NOT_ALLOWED.getMessage()
                    + " (설정 " + policy.collectionPolicy() + ", 실행 " + kind + ")");
        }
    }

    private static void checkOwner(String owner, int leaseSeconds, AdminInputs in) {
        if (owner == null || !OWNER.matcher(owner).matches()) {
            in.add("owner", "owner 는 공백 없는 ASCII 1~100자여야 합니다.");
        }
        if (leaseSeconds < MIN_LEASE_SECONDS || leaseSeconds > MAX_LEASE_SECONDS) {
            in.add("leaseSeconds", "leaseSeconds 는 " + MIN_LEASE_SECONDS + "~" + MAX_LEASE_SECONDS + " 사이여야 합니다.");
        }
    }

    private static Map<String, Object> snapshot(RunRow run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("platformCode", run.platformCode());
        map.put("runKind", run.runKind());
        map.put("status", run.status());
        map.put("stopReason", run.stopReason());
        map.put("leaseOwner", run.leaseOwner());
        return map;
    }
}
