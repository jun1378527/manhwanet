package com.manhwanet.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.manhwanet.rating.application.RatingService;
import com.manhwanet.rating.dto.MyRatingResponse;
import com.manhwanet.rating.dto.RatingRequest;
import com.manhwanet.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * 같은 사용자의 동시 PUT, PUT·DELETE 경쟁 (실제 PostgreSQL, READ COMMITTED).
 *
 * <p>명세(docs/rating-design-draft.md §6)가 보장하는 것만 검증한다.
 * 행은 0개 또는 1개, 남는 점수는 요청 값 중 하나, 유니크 위반·500 없음, updated_at ≥ created_at.
 * "마지막 요청이 이긴다"는 보장하지 않으며, 트랜잭션을 직접 잡아 그렇지 않은 순서를 재현한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class RatingConcurrencyTest {

    private static final long BLOCK_CHECK_MS = 500;

    @Autowired
    private RatingService ratingService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private long userId;
    private long workId;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role)
                VALUES ('reader@manhwanet.test', '{noop}unused', '독자', 'USER') RETURNING id
                """, Long.class);
        workId = jdbcTemplate.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // ---------- 반복 경쟁: 보장 범위 ----------

    @Test
    void concurrentPutsLeaveExactlyOneRowWithOneOfTheRequestedScores() throws Exception {
        String[] scores = {"1.0", "2.5", "4.0", "5.0"};
        for (int round = 0; round < 30; round++) {
            jdbcTemplate.update("DELETE FROM work_ratings");
            CyclicBarrier start = new CyclicBarrier(scores.length);
            List<Future<MyRatingResponse>> futures = new ArrayList<>();
            for (String score : scores) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return ratingService.putMyRating(userId, workId, request(score));
                }));
            }
            for (Future<MyRatingResponse> future : futures) {
                // 유니크 위반 등 예외 없이 모두 성공해야 한다
                MyRatingResponse response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.score()).isIn(new BigDecimal("1.0"), new BigDecimal("2.5"), new BigDecimal("4.0"), new BigDecimal("5.0"));
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT score_half, created_at, updated_at FROM work_ratings WHERE user_id = ? AND work_id = ?", userId, workId);
            assertThat(rows).hasSize(1);
            assertThat(((Number) rows.get(0).get("score_half")).intValue()).isIn(2, 5, 8, 10);
            assertThat(((Timestamp) rows.get(0).get("updated_at")))
                    .isAfterOrEqualTo((Timestamp) rows.get(0).get("created_at"));
        }
    }

    @Test
    void concurrentPutAndDeleteEndInOneOfTheAllowedStates() throws Exception {
        Set<String> observed = new java.util.HashSet<>();
        for (int round = 0; round < 40; round++) {
            jdbcTemplate.update("DELETE FROM work_ratings");
            if (round % 2 == 0) {
                ratingService.putMyRating(userId, workId, request("3.0"));
            }
            CyclicBarrier start = new CyclicBarrier(2);
            Future<MyRatingResponse> put = executor.submit(() -> {
                start.await();
                return ratingService.putMyRating(userId, workId, request("4.5"));
            });
            Future<Object> delete = executor.submit(() -> {
                start.await();
                ratingService.deleteMyRating(userId, workId);
                return null;
            });
            assertThat(put.get(30, TimeUnit.SECONDS).score()).isEqualByComparingTo("4.5");
            delete.get(30, TimeUnit.SECONDS);

            List<Integer> rows = scoreHalves();
            assertThat(rows).hasSizeLessThanOrEqualTo(1);
            if (!rows.isEmpty()) {
                // 남는다면 PUT 의 값이어야 한다 (3.0 은 PUT 이 덮어쓰거나 DELETE 가 지움)
                assertThat(rows).containsExactly(9);
            }
            observed.add(rows.isEmpty() ? "deleted" : "rated");
        }
        // 어느 결과가 나오는지는 보장하지 않는다. 관찰된 결과만 기록한다.
        assertThat(observed).isNotEmpty().isSubsetOf("deleted", "rated");
    }

    // ---------- 트랜잭션 순서 재현 (§6.3) ----------

    @Test
    void secondPutWaitsForUncommittedInsertThenUpdates() throws Exception {
        HeldTransaction first = hold(() -> ratingService.putMyRating(userId, workId, request("4.5")));

        Future<MyRatingResponse> second = executor.submit(() -> ratingService.putMyRating(userId, workId, request("2.0")));
        assertBlocked(second);

        first.commit();
        assertThat(second.get(30, TimeUnit.SECONDS).score()).isEqualByComparingTo("2.0");
        assertThat(scoreHalves()).containsExactly(4);
    }

    @Test
    void deleteWaitsForUncommittedUpdateThenDeletes() throws Exception {
        ratingService.putMyRating(userId, workId, request("3.0"));
        HeldTransaction update = hold(() -> ratingService.putMyRating(userId, workId, request("4.5")));

        Future<?> delete = executor.submit(() -> ratingService.deleteMyRating(userId, workId));
        assertBlocked(delete);

        update.commit();
        delete.get(30, TimeUnit.SECONDS);
        assertThat(scoreHalves()).isEmpty();
    }

    @Test
    void putWaitsForUncommittedDeleteThenInsertsNewRow() throws Exception {
        MyRatingResponse original = ratingService.putMyRating(userId, workId, request("3.0"));
        HeldTransaction delete = hold(() -> {
            ratingService.deleteMyRating(userId, workId);
            return null;
        });

        Future<MyRatingResponse> put = executor.submit(() -> ratingService.putMyRating(userId, workId, request("4.5")));
        assertBlocked(put);

        delete.commit();
        MyRatingResponse reinserted = put.get(30, TimeUnit.SECONDS);
        assertThat(reinserted.score()).isEqualByComparingTo("4.5");
        assertThat(reinserted.createdAt()).isAfter(original.createdAt());
        assertThat(scoreHalves()).containsExactly(9);
    }

    /**
     * DELETE 가 PUT 보다 나중에 끝났는데도 별점이 남는 순서.
     * 미커밋 INSERT 는 DELETE 에게 보이지 않으므로 DELETE 는 기다리지 않고 0행을 지우고 성공한다.
     */
    @Test
    void deleteDoesNotSeeUncommittedInsertSoRowRemainsAfterBothSucceed() throws Exception {
        HeldTransaction insert = hold(() -> ratingService.putMyRating(userId, workId, request("4.5")));

        Future<?> delete = executor.submit(() -> ratingService.deleteMyRating(userId, workId));
        delete.get(5, TimeUnit.SECONDS); // 막히지 않고 끝난다

        insert.commit();
        assertThat(scoreHalves()).containsExactly(9);
    }

    @Test
    void samePutRepeatedKeepsTimestampsAndConcurrentDifferentScoreNeverMovesUpdatedAtBackwards() throws Exception {
        MyRatingResponse first = ratingService.putMyRating(userId, workId, request("3.0"));
        MyRatingResponse same = ratingService.putMyRating(userId, workId, request("3.0"));
        assertThat(same.createdAt()).isEqualTo(first.createdAt());
        assertThat(same.updatedAt()).isEqualTo(first.updatedAt());

        // 먼저 시작했지만 나중에 반영되는 트랜잭션: now() 는 시작 시각이므로 GREATEST 가 없으면 updated_at 이 되돌아간다
        CountDownLatch earlyStarted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        Future<MyRatingResponse> early = executor.submit(() -> transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT now()", Timestamp.class); // 트랜잭션 시작 시각 고정
            earlyStarted.countDown();
            await(proceed);
            return ratingService.putMyRating(userId, workId, request("1.0"));
        }));
        earlyStarted.await(10, TimeUnit.SECONDS);
        Thread.sleep(20);
        MyRatingResponse later = ratingService.putMyRating(userId, workId, request("5.0"));
        proceed.countDown();
        MyRatingResponse earlyResult = early.get(30, TimeUnit.SECONDS);

        assertThat(earlyResult.score()).isEqualByComparingTo("1.0");
        assertThat(earlyResult.updatedAt()).isAfterOrEqualTo(later.updatedAt());
        assertThat(earlyResult.createdAt()).isEqualTo(first.createdAt());
    }

    // ---------- helpers ----------

    private static RatingRequest request(String score) {
        return new RatingRequest(new RatingRequest.Score(new BigDecimal(score)));
    }

    private List<Integer> scoreHalves() {
        return jdbcTemplate.queryForList(
                "SELECT score_half FROM work_ratings WHERE user_id = ? AND work_id = ?", Integer.class, userId, workId);
    }

    private static void assertBlocked(Future<?> future) throws Exception {
        try {
            future.get(BLOCK_CHECK_MS, TimeUnit.MILLISECONDS);
            throw new AssertionError("expected the operation to wait for the held row lock");
        } catch (TimeoutException expected) {
            // 잠금 대기 중
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private interface Work<T> {
        T run() throws Exception;
    }

    /** 다른 스레드에서 트랜잭션을 열고 작업을 실행한 뒤 commit() 까지 커밋하지 않고 잡아 둔다. */
    private <T> HeldTransaction hold(Work<T> work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> future = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            try {
                work.run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            done.countDown();
            await(release);
        }));
        if (!done.await(10, TimeUnit.SECONDS)) {
            future.get(1, TimeUnit.SECONDS);
            throw new IllegalStateException("held transaction did not start");
        }
        return new HeldTransaction(release, future);
    }

    private record HeldTransaction(CountDownLatch release, Future<?> future) {
        void commit() throws Exception {
            release.countDown();
            future.get(30, TimeUnit.SECONDS);
        }
    }
}
