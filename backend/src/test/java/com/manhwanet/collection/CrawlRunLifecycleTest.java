package com.manhwanet.collection;

import static com.manhwanet.collection.CollectionTestSupport.OTHER;
import static com.manhwanet.collection.CollectionTestSupport.OWNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.collection.application.CrawlRunActiveException;
import com.manhwanet.collection.application.CrawlRunService;
import com.manhwanet.collection.application.CrawlTaskService;
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
import com.manhwanet.support.TestDatabaseCleaner;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 수집 실행·작업 큐: 설정 게이트, 활성 실행 하나, 잠금 넘겨받기·재개, 중단, 재시도, 동시 요청 (#45, 실제 PostgreSQL) */
@SpringBootTest
@ActiveProfiles("test")
class CrawlRunLifecycleTest {

    private static final int LEASE = 60;

    @Autowired
    private CrawlRunService runs;

    @Autowired
    private CrawlTaskService tasks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private long adminId;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbc, environment);
        adminId = CollectionTestSupport.insertAdmin(jdbc);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void collectionPolicyGatesRuns() {
        assertThatThrownBy(() -> runs.start("RIDI", RunKind.PROBE, "ridi-0.1", OWNER, LEASE, null))
                .extracting("errorCode").isEqualTo(ErrorCode.COLLECTION_NOT_ALLOWED);
        CollectionTestSupport.setPolicy(jdbc, "RIDI", "PROBE", adminId);
        assertThatThrownBy(() -> runs.start("RIDI", RunKind.FULL, "ridi-0.1", OWNER, LEASE, null))
                .extracting("errorCode").isEqualTo(ErrorCode.COLLECTION_NOT_ALLOWED);
        RunRow probe = runs.start("ridi", RunKind.PROBE, "ridi-0.1", OWNER, LEASE, "c".repeat(64));
        assertThat(probe.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(probe.heldBy(OWNER)).isTrue();
        assertThatThrownBy(() -> runs.start("NOPE", RunKind.PROBE, "x", OWNER, LEASE, null))
                .extracting("errorCode").isEqualTo(ErrorCode.PLATFORM_NOT_FOUND);
        assertThatThrownBy(() -> runs.start("RIDI", RunKind.PROBE, "x", "has space", 10, "BAD"))
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void onlyOneActiveRunPerPlatformEvenUnderConcurrency() throws Exception {
        CollectionTestSupport.setPolicy(jdbc, "RIDI", "ENABLED", adminId);
        for (int round = 0; round < 10; round++) {
            CyclicBarrier barrier = new CyclicBarrier(3);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String owner = "c" + i;
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        return runs.start("RIDI", RunKind.FULL, "ridi-0.1", owner, LEASE, null);
                    } catch (CrawlRunActiveException e) {
                        return e;
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }
            List<RunRow> started = results.stream().filter(r -> r instanceof RunRow).map(r -> (RunRow) r).toList();
            assertThat(started).hasSize(1);
            assertThat(results).filteredOn(r -> r instanceof CrawlRunActiveException).hasSize(2)
                    .allSatisfy(e -> assertThat(((CrawlRunActiveException) e).getActiveRunId()).isEqualTo(started.get(0).id()));
            jdbc.update("UPDATE crawl_runs SET status = 'COMPLETED', finished_at = now(), lease_owner = NULL, lease_until = NULL");
        }
        assertThat(CollectionTestSupport.count(jdbc, "crawl_runs")).isEqualTo(10);
    }

    @Test
    void expiredLeaseIsTakenOverAndUnfinishedTasksResume() {
        CollectionTestSupport.setPolicy(jdbc, "RIDI", "ENABLED", adminId);
        long run = runs.start("RIDI", RunKind.FULL, "ridi-0.1", OWNER, LEASE, null).id();
        for (int i = 1; i <= 4; i++) {
            tasks.enqueue(run, OWNER, TaskKind.DETAIL, "https://ridibooks.com/books/" + i);
        }
        List<TaskRow> claimed = tasks.claim(run, OWNER, 3);
        tasks.succeed(claimed.get(0).id(), OWNER, 200);

        // 살아 있는 잠금은 넘겨받을 수 없다
        assertThatThrownBy(() -> runs.acquire(run, OTHER, LEASE)).extracting("errorCode")
                .isEqualTo(ErrorCode.CRAWL_RUN_LEASE_LOST);
        // 프로세스가 죽어 잠금이 만료되면 같은 실행을 넘겨받고, 끝나지 않은 작업만 다시 대기열로
        CollectionTestSupport.expireLease(jdbc, run);
        RunRow taken = runs.acquire(run, OTHER, LEASE);
        assertThat(taken.id()).isEqualTo(run);
        assertThat(taken.heldBy(OTHER)).isTrue();
        assertThat(statusOf(claimed.get(0).id())).isEqualTo("DONE");
        assertThat(statusOf(claimed.get(1).id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM crawl_tasks WHERE id = ?", Integer.class, claimed.get(1).id()))
                .isEqualTo(1);
        assertThat(CollectionTestSupport.count(jdbc, "crawl_runs")).isEqualTo(1);

        // 잠금을 잃은 프로세스는 더 쓸 수 없다
        assertThatThrownBy(() -> tasks.enqueue(run, OWNER, TaskKind.DETAIL, "https://ridibooks.com/books/9"))
                .extracting("errorCode").isEqualTo(ErrorCode.CRAWL_RUN_LEASE_LOST);
        assertThatThrownBy(() -> tasks.succeed(claimed.get(2).id(), OWNER, 200)).extracting("errorCode")
                .isEqualTo(ErrorCode.CRAWL_RUN_LEASE_LOST);

        List<TaskRow> resumed = tasks.claim(run, OTHER, 10);
        assertThat(resumed).extracting(TaskRow::id).doesNotContain(claimed.get(0).id()).hasSize(3);
        resumed.forEach(t -> tasks.succeed(t.id(), OTHER, 200));
        RunRow done = runs.complete(run, OTHER, "{\"detail\":4}");
        assertThat(done.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(done.leaseOwner()).isNull();
        assertThatThrownBy(() -> runs.acquire(run, OWNER, LEASE)).extracting("errorCode")
                .isEqualTo(ErrorCode.CRAWL_RUN_STATE_CONFLICT);
    }

    @Test
    void pauseBlockAndStopKeepTheSameRun() {
        CollectionTestSupport.setPolicy(jdbc, "RIDI", "ENABLED", adminId);
        long run = runs.start("RIDI", RunKind.FULL, "ridi-0.1", OWNER, LEASE, null).id();
        tasks.enqueue(run, OWNER, TaskKind.LIST, "https://ridibooks.com/category/new-releases/1");
        tasks.enqueue(run, OWNER, TaskKind.DETAIL, "https://ridibooks.com/books/1");

        assertThatThrownBy(() -> runs.block(run, OWNER, null)).isInstanceOf(BusinessException.class);
        RunRow blocked = runs.block(run, OWNER, StopReason.BLOCKED_RATE);
        assertThat(blocked.status()).isEqualTo(RunStatus.BLOCKED);
        assertThat(blocked.leaseOwner()).isNull();
        // 같은 플랫폼에 새 실행은 만들 수 없고, 원인을 확인한 뒤 같은 실행을 재개한다
        assertThatThrownBy(() -> runs.start("RIDI", RunKind.INCREMENTAL, "ridi-0.1", OTHER, LEASE, null))
                .isInstanceOf(CrawlRunActiveException.class);
        RunRow resumed = runs.acquire(run, OTHER, LEASE);
        assertThat(resumed.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(resumed.stopReason()).isNull();

        // 수집 설정을 끄면 재개할 수 없다
        runs.pause(run, OTHER);
        CollectionTestSupport.setPolicy(jdbc, "RIDI", "DISABLED", adminId);
        assertThatThrownBy(() -> runs.acquire(run, OTHER, LEASE)).extracting("errorCode")
                .isEqualTo(ErrorCode.COLLECTION_NOT_ALLOWED);
        CollectionTestSupport.setPolicy(jdbc, "RIDI", "ENABLED", adminId);
        runs.acquire(run, OTHER, LEASE);

        TaskRow inFlight = tasks.claim(run, OTHER, 1).get(0);
        RunRow stopping = runs.requestStop(run, "운영 중단 테스트", adminId);
        assertThat(stopping.status()).isEqualTo(RunStatus.STOP_REQUESTED);
        // 중단 요청은 잠금 상실과 다르다. 새 작업은 없고, 잠금을 다시 확인해도 진행 중 작업은 남는다
        assertThat(tasks.claim(run, OTHER, 1)).isEmpty();
        assertThatThrownBy(() -> tasks.enqueue(run, OTHER, TaskKind.DETAIL, "https://ridibooks.com/books/stop"))
                .extracting("errorCode").isEqualTo(ErrorCode.CRAWL_RUN_STATE_CONFLICT);
        assertThat(runs.acquire(run, OTHER, LEASE).status()).isEqualTo(RunStatus.STOP_REQUESTED);
        assertThat(statusOf(inFlight.id())).isEqualTo("RUNNING");
        tasks.succeed(inFlight.id(), OTHER, 200);
        assertThatThrownBy(() -> runs.complete(run, OTHER, null)).extracting("errorCode")
                .isEqualTo(ErrorCode.CRAWL_RUN_STATE_CONFLICT);
        RunRow stopped = runs.finishStop(run, OTHER, null);
        assertThat(stopped.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(jdbc.queryForList("SELECT status FROM crawl_tasks ORDER BY id", String.class)).containsExactly("DONE", "SKIPPED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_log WHERE entity_type = 'CRAWL_RUN'", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> runs.requestStop(run, "다시", adminId)).extracting("errorCode")
                .isEqualTo(ErrorCode.CRAWL_RUN_STATE_CONFLICT);

        // 수집기가 돌지 않는(PAUSED) 실행의 중단 요청은 바로 닫는다
        long second = runs.start("RIDI", RunKind.INCREMENTAL, "ridi-0.1", OWNER, LEASE, null).id();
        runs.pause(second, OWNER);
        assertThat(runs.requestStop(second, "정지된 실행 정리", adminId).status()).isEqualTo(RunStatus.STOPPED);
    }

    @Test
    void tasksAreDeduplicatedRetriedAndClaimedOnce() throws Exception {
        CollectionTestSupport.setPolicy(jdbc, "RIDI", "ENABLED", adminId);
        long run = runs.start("RIDI", RunKind.FULL, "ridi-0.1", OWNER, LEASE, null).id();
        CrawlTaskService.Enqueued first = tasks.enqueue(run, OWNER, TaskKind.DETAIL, "HTTPS://RidiBooks.com/books/1");
        CrawlTaskService.Enqueued again = tasks.enqueue(run, OWNER, TaskKind.DETAIL, "https://ridibooks.com/books/1");
        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.task().id()).isEqualTo(first.task().id());
        assertThat(tasks.enqueue(run, OWNER, TaskKind.LIST, "https://ridibooks.com/books/1").created()).isTrue();
        assertThatThrownBy(() -> tasks.enqueue(run, OWNER, TaskKind.DETAIL, "http://ridibooks.com/books/2"))
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        // 일시적 오류는 정한 시각 뒤에 다시, 시도 횟수를 넘으면 FAILED
        TaskRow claimed = tasks.claim(run, OWNER, 1).get(0);
        TaskRow retry = tasks.fail(claimed.id(), OWNER, TaskError.HTTP_5XX, 503, 120, null);
        assertThat(retry.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(retry.lastHttpStatus()).isEqualTo(503);
        assertThat(tasks.claim(run, OWNER, 10)).extracting(TaskRow::id).doesNotContain(claimed.id());
        jdbc.update("UPDATE crawl_tasks SET next_attempt_at = now(), attempts = 4 WHERE id = ?", claimed.id());
        TaskRow last = tasks.claim(run, OWNER, 10).stream().filter(t -> t.id() == claimed.id()).findFirst().orElseThrow();
        assertThat(tasks.fail(last.id(), OWNER, TaskError.TIMEOUT, null, 60, null).status()).isEqualTo(TaskStatus.FAILED);

        // 파싱 실패 원문은 gzip 표본으로 남기고, robots 금지는 SKIPPED
        TaskRow parse = tasks.enqueue(run, OWNER, TaskKind.DETAIL, "https://ridibooks.com/books/3").task();
        tasks.claim(run, OWNER, 10);
        tasks.fail(parse.id(), OWNER, TaskError.PARSE_FAILED, 200, null, "<html>깨진 페이지</html>".getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM crawl_failure_samples WHERE task_id = ?", Integer.class, parse.id()))
                .isEqualTo(1);
        TaskRow robots = tasks.enqueue(run, OWNER, TaskKind.DETAIL, "https://ridibooks.com/books/4").task();
        tasks.claim(run, OWNER, 10);
        assertThat(tasks.fail(robots.id(), OWNER, TaskError.SKIPPED_ROBOTS, null, null, null).status())
                .isEqualTo(TaskStatus.SKIPPED);
        assertThatThrownBy(() -> tasks.succeed(robots.id(), OWNER, 200)).extracting("errorCode")
                .isEqualTo(ErrorCode.CRAWL_RUN_STATE_CONFLICT);

        // 동시에 가져가도 같은 작업이 두 번 나가지 않는다
        for (int i = 10; i < 60; i++) {
            tasks.enqueue(run, OWNER, TaskKind.DETAIL, "https://ridibooks.com/books/" + i);
        }
        CyclicBarrier barrier = new CyclicBarrier(4);
        List<Future<List<TaskRow>>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return tasks.claim(run, OWNER, 20);
            }));
        }
        Set<Long> ids = new HashSet<>();
        int total = 0;
        for (Future<List<TaskRow>> f : futures) {
            List<TaskRow> got = f.get(10, TimeUnit.SECONDS);
            total += got.size();
            got.forEach(t -> ids.add(t.id()));
        }
        assertThat(total).isEqualTo(ids.size()).isEqualTo(50);
    }

    @Test
    void stopRequestLeavesInFlightWorkAndTakeoverStillReclaimsIt() {
        CollectionTestSupport.setPolicy(jdbc, "RIDI", "ENABLED", adminId);
        long run = runs.start("RIDI", RunKind.FULL, "ridi-0.1", OWNER, LEASE, null).id();
        tasks.enqueue(run, OWNER, TaskKind.DETAIL, "https://ridibooks.com/books/hold");
        tasks.enqueue(run, OWNER, TaskKind.DETAIL, "https://ridibooks.com/books/drop");
        List<TaskRow> claimed = tasks.claim(run, OWNER, 2);
        runs.requestStop(run, "중단 중 실패 기록", adminId);
        assertThat(tasks.claim(run, OWNER, 1)).isEmpty();
        assertThat(tasks.fail(claimed.get(1).id(), OWNER, TaskError.NOT_FOUND, 404, null, null).status())
                .isEqualTo(TaskStatus.FAILED);

        CollectionTestSupport.expireLease(jdbc, run);
        assertThatThrownBy(() -> tasks.succeed(claimed.get(0).id(), OWNER, 200)).extracting("errorCode")
                .isEqualTo(ErrorCode.CRAWL_RUN_LEASE_LOST);
        assertThatThrownBy(() -> tasks.claim(run, OWNER, 1)).extracting("errorCode")
                .isEqualTo(ErrorCode.CRAWL_RUN_LEASE_LOST);
        runs.acquire(run, OTHER, LEASE);
        assertThat(statusOf(claimed.get(0).id())).isEqualTo("PENDING");
        assertThat(statusOf(claimed.get(1).id())).isEqualTo("FAILED");
    }

    private String statusOf(long taskId) {
        return jdbc.queryForObject("SELECT status FROM crawl_tasks WHERE id = ?", String.class, taskId);
    }
}
