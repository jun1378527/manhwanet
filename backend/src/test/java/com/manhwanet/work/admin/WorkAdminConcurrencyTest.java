package com.manhwanet.work.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.domain.CreatorRepository;
import com.manhwanet.creator.dto.UpdateCreatorRequest;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.VersionConflictException;
import com.manhwanet.provenance.dto.EvidenceRequest;
import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.domain.WorkRepository;
import com.manhwanet.work.dto.AdminWorkDetailResponse;
import com.manhwanet.work.dto.CreateWorkRequest.CreditRequest;
import com.manhwanet.work.dto.CreateWorkRequest.TitleRequest;
import com.manhwanet.work.dto.UpdateWorkRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 수정의 동시성·롤백 (#38, 실제 PostgreSQL, READ COMMITTED).
 * <ul>
 *   <li>같은 version 으로 동시에 수정하면 정확히 하나만 성공하고 나머지는 409, 제목·크레딧이 섞이지 않는다.</li>
 *   <li>잠금을 기다린 수정은 먼저 커밋된 version 을 보고 409 가 된다(결정적 시나리오).</li>
 *   <li>마지막 단계에서 실패하면 제목·크레딧·version 이 모두 되돌아간다.</li>
 *   <li>수정 중인(커밋 전) 작품·잠긴 작가가 별점·리뷰 작성과 다른 작품의 크레딧 추가를 막지 않는다.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkAdminConcurrencyTest {

    private static final int ROUNDS = 20;
    private static final long WAIT_SECONDS = 10;

    @Autowired
    private WorkService workService;

    @Autowired
    private CreatorService creatorService;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private long creatorA;
    private long creatorB;
    private long workId;
    private long adminId;

    @BeforeEach
    void setUp() {
        dropTestTrigger();
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        adminId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role)
                VALUES ('admin@manhwanet.test', '{noop}unused', '관리자', 'ADMIN') RETURNING id
                """, Long.class);
        creatorA = jdbcTemplate.queryForObject("INSERT INTO creators (name) VALUES ('작가A') RETURNING id", Long.class);
        creatorB = jdbcTemplate.queryForObject("INSERT INTO creators (name) VALUES ('작가B') RETURNING id", Long.class);
        workId = jdbcTemplate.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO work_titles (work_id, title, language_code, is_primary) VALUES (?, '처음 제목', 'ko', TRUE)", workId);
        jdbcTemplate.update("INSERT INTO work_creators (work_id, creator_id, role) VALUES (?, ?, 'STORY')", workId, creatorA);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        dropTestTrigger();
    }

    /** 이전 실행이 비정상 종료돼 남은 트리거까지 지운다(공유 테스트 DB 보호). */
    private void dropTestTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_fail_work_update ON works");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_fail_work_update()");
    }

    @Test
    void concurrentUpdatesWithSameVersionLetExactlyOneWin() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long version = currentVersion();
            CyclicBarrier barrier = new CyclicBarrier(2);
            String titleA = "A-" + round;
            String titleB = "B-" + round;
            Future<Object> first = executor.submit(() -> attempt(barrier, request(version, titleA, creatorA)));
            Future<Object> second = executor.submit(() -> attempt(barrier, request(version, titleB, creatorB)));
            Object resultA = first.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Object resultB = second.get(WAIT_SECONDS, TimeUnit.SECONDS);

            List<Object> results = List.of(resultA, resultB);
            assertThat(results).filteredOn(r -> r instanceof AdminWorkDetailResponse).hasSize(1);
            assertThat(results).filteredOn(r -> r instanceof VersionConflictException).hasSize(1)
                    .allSatisfy(r -> {
                        VersionConflictException conflict = (VersionConflictException) r;
                        assertThat(conflict.getErrorCode()).isEqualTo(ErrorCode.WORK_VERSION_CONFLICT);
                        assertThat(conflict.getCurrentVersion()).isEqualTo(version + 1);
                    });

            boolean aWon = resultA instanceof AdminWorkDetailResponse;
            String winnerTitle = aWon ? titleA : titleB;
            long winnerCreator = aWon ? creatorA : creatorB;
            assertThat(currentVersion()).isEqualTo(version + 1);
            // 이긴 요청의 제목·크레딧만 남는다(섞이지 않음)
            assertThat(jdbcTemplate.queryForList("SELECT title FROM work_titles WHERE work_id = ? ORDER BY title",
                    String.class, workId)).containsExactly(winnerTitle, winnerTitle + "-en");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT title FROM work_titles WHERE work_id = ? AND is_primary", String.class, workId))
                    .isEqualTo(winnerTitle);
            assertThat(jdbcTemplate.queryForList("SELECT creator_id FROM work_creators WHERE work_id = ?",
                    Long.class, workId)).containsExactly(winnerCreator);
        }
    }

    @Test
    void waitingUpdateSeesCommittedVersionAndGets409() throws Exception {
        // T1 이 행을 잠근 동안 T2(같은 version 0)가 기다리고, T1 이 version 을 올려 커밋하면 T2 는 409
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            workRepository.lockById(workId).orElseThrow();
            locked.countDown();
            await(release);
            assertThat(workRepository.updateCore(workId, 0, "WEBTOON", null, "T1 이 먼저 바꿈")).isEqualTo(1);
        }));
        assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        Future<Object> waiting = executor.submit(() -> {
            try {
                return workService.update(workId, request(0, "T2 제목", creatorB), adminId);
            } catch (VersionConflictException e) {
                return e;
            }
        });
        try {
            awaitLockWaiter();
            assertThat(waiting.isDone()).as("잠금을 기다려야 한다").isFalse();
        } finally {
            release.countDown();
        }
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        Object result = waiting.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(result).isInstanceOf(VersionConflictException.class);
        assertThat(((VersionConflictException) result).getCurrentVersion()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT title FROM work_titles WHERE work_id = ?", String.class, workId))
                .containsExactly("처음 제목");
        assertThat(jdbcTemplate.queryForObject("SELECT description FROM works WHERE id = ?", String.class, workId))
                .isEqualTo("T1 이 먼저 바꿈");
    }

    @Test
    void concurrentCreatorUpdatesWithSameVersionLetExactlyOneWin() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long version = jdbcTemplate.queryForObject("SELECT version FROM creators WHERE id = ?", Long.class, creatorA);
            CyclicBarrier barrier = new CyclicBarrier(2);
            String nameA = "가-" + round;
            String nameB = "나-" + round;
            Future<Object> first = executor.submit(() -> attemptCreator(barrier, version, nameA));
            Future<Object> second = executor.submit(() -> attemptCreator(barrier, version, nameB));
            List<Object> results = List.of(first.get(WAIT_SECONDS, TimeUnit.SECONDS), second.get(WAIT_SECONDS, TimeUnit.SECONDS));

            assertThat(results).filteredOn(r -> r instanceof VersionConflictException).hasSize(1);
            assertThat(results).filteredOn(r -> !(r instanceof Throwable)).hasSize(1);
            assertThat(jdbcTemplate.queryForObject("SELECT version FROM creators WHERE id = ?", Long.class, creatorA))
                    .isEqualTo(version + 1);
            assertThat(jdbcTemplate.queryForObject("SELECT name FROM creators WHERE id = ?", String.class, creatorA))
                    .isIn(nameA, nameB);
        }
    }

    @Test
    void failureInTheLastStepRollsBackTitlesCreditsAndVersion() {
        // 마지막 단계(works 조건부 UPDATE)에서 실패시킨다. 그 전에 제목·크레딧은 이미 지우고 다시 넣은 상태다.
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION test_fail_work_update() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.id = %d AND NEW.work_format = 'PAGE_COMIC' THEN
                        RAISE EXCEPTION 'test failure while updating work';
                    END IF;
                    RETURN NEW;
                END $$
                """.formatted(workId));
        jdbcTemplate.execute("""
                CREATE TRIGGER test_fail_work_update BEFORE UPDATE ON works
                FOR EACH ROW EXECUTE FUNCTION test_fail_work_update()
                """);

        assertThatThrownBy(() -> workService.update(workId, request(0, "실패할 제목", creatorB), adminId))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(VersionConflictException.class);

        assertThat(currentVersion()).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT title FROM work_titles WHERE work_id = ?", String.class, workId))
                .containsExactly("처음 제목");
        assertThat(jdbcTemplate.queryForList("SELECT creator_id FROM work_creators WHERE work_id = ?", Long.class, workId))
                .containsExactly(creatorA);
        assertThat(jdbcTemplate.queryForObject("SELECT work_format FROM works WHERE id = ?", String.class, workId))
                .isEqualTo("WEBTOON");
    }

    @Test
    void uncommittedWorkUpdateDoesNotBlockRatingOrReviewWrites() throws Exception {
        long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role)
                VALUES ('reader@manhwanet.test', '{noop}unused', '독자', 'USER') RETURNING id
                """, Long.class);
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 실제 수정(제목·크레딧 교체와 works UPDATE)을 끝낸 뒤 커밋하지 않고 기다린다
        Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            workService.update(workId, request(0, "수정 중", creatorB), adminId);
            updated.countDown();
            await(release);
        }));
        try {
            assertThat(updated.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<Integer> rating = executor.submit(() -> jdbcTemplate.update(
                    "INSERT INTO work_ratings (user_id, work_id, score_half) VALUES (?, ?, 8)", userId, workId));
            assertThat(rating.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
            Future<Integer> review = executor.submit(() -> jdbcTemplate.update("""
                    INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler)
                    VALUES (?, ?, '수정 중에도 작성되는 리뷰입니다.', FALSE)
                    """, userId, workId));
            assertThat(review.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            release.countDown();
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        assertThat(currentVersion()).isEqualTo(1);
    }

    @Test
    void lockedCreatorDoesNotBlockCreditChangesOfWorks() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            creatorRepository.lockById(creatorB).orElseThrow();
            locked.countDown();
            await(release);
        }));
        try {
            assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<AdminWorkDetailResponse> update = executor.submit(
                    () -> workService.update(workId, request(0, "작가B 작품", creatorB), adminId));
            assertThat(update.get(WAIT_SECONDS, TimeUnit.SECONDS).version()).isEqualTo(1);
        } finally {
            release.countDown();
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    // ---------- 도우미 ----------

    private Object attempt(CyclicBarrier barrier, UpdateWorkRequest request) throws Exception {
        barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
        try {
            return workService.update(workId, request, adminId);
        } catch (VersionConflictException e) {
            return e;
        }
    }

    private Object attemptCreator(CyclicBarrier barrier, long version, String name) throws Exception {
        barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
        try {
            return creatorService.update(creatorA, new UpdateCreatorRequest(new JsonInteger(version), name, null, "동시 수정 테스트"), adminId);
        } catch (VersionConflictException e) {
            return e;
        }
    }

    private UpdateWorkRequest request(long version, String primaryTitle, long creatorId) {
        List<TitleRequest> titles = new ArrayList<>();
        titles.add(new TitleRequest(primaryTitle, "ko", true));
        titles.add(new TitleRequest(primaryTitle + "-en", "en", false));
        return new UpdateWorkRequest(new JsonInteger(version), "PAGE_COMIC", null, null,
                titles, List.of(new CreditRequest(creatorId, "STORY")),
                List.of(evidence("TITLE"), evidence("CREDIT")), "동시 수정 테스트");
    }

    private static EvidenceRequest evidence(String field) {
        return new EvidenceRequest(field, null, null, "STAFF", null, null, Instant.parse("2026-10-12T00:00:00Z"), null);
    }

    private long currentVersion() {
        return jdbcTemplate.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, workId);
    }

    /** 다른 세션이 행 잠금을 기다리는 상태가 될 때까지 기다린다(최대 WAIT_SECONDS). */
    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no session waited for the row lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
