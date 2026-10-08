package com.manhwanet.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.review.application.ReviewService;
import com.manhwanet.review.dto.CreateReviewRequest;
import com.manhwanet.review.dto.JsonFlag;
import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.review.dto.JsonText;
import com.manhwanet.review.dto.MyReviewResponse;
import com.manhwanet.review.dto.UpdateReviewRequest;
import com.manhwanet.support.TestDatabaseCleaner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
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
 * 동시 작성, 같은 버전의 동시 수정, 수정·삭제 경쟁, 삭제 후 재작성에 대한 오래된 요청 (실제 PostgreSQL, READ COMMITTED).
 * 명세(docs/review-design-draft.md §4.3~4.5)가 보장하는 것만 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewConcurrencyTest {

    private static final long BLOCK_CHECK_MS = 500;
    private static final int ROUNDS = 25;

    @Autowired
    private ReviewService reviewService;

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

    // ---------- 반복 경쟁 ----------

    @Test
    void concurrentCreatesLeaveOneReviewAndOthersConflict() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbcTemplate.update("DELETE FROM work_reviews");
            CyclicBarrier start = new CyclicBarrier(4);
            List<Future<MyReviewResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String body = "동시에 작성한 리뷰 " + i + "번입니다.";
                futures.add(executor.submit(() -> {
                    start.await();
                    return reviewService.create(userId, workId, create(body));
                }));
            }
            List<String> winners = new ArrayList<>();
            int conflicts = 0;
            for (Future<MyReviewResponse> future : futures) {
                try {
                    winners.add(future.get(30, TimeUnit.SECONDS).review().body());
                } catch (ExecutionException e) {
                    assertThat(errorCode(e)).isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS);
                    conflicts++;
                }
            }
            assertThat(winners).hasSize(1);
            assertThat(conflicts).isEqualTo(3);
            assertThat(bodies()).containsExactlyElementsOf(winners);
        }
    }

    @Test
    void concurrentUpdatesWithTheSameVersionApplyExactlyOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbcTemplate.update("DELETE FROM work_reviews");
            MyReviewResponse original = reviewService.create(userId, workId, create("처음 작성한 리뷰 내용입니다."));
            long reviewId = original.review().id();

            CyclicBarrier start = new CyclicBarrier(4);
            List<Future<MyReviewResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String body = "같은 버전으로 고친 리뷰 " + i + "번입니다.";
                futures.add(executor.submit(() -> {
                    start.await();
                    return reviewService.update(userId, workId, update(reviewId, 0, body));
                }));
            }
            List<MyReviewResponse> successes = new ArrayList<>();
            for (Future<MyReviewResponse> future : futures) {
                try {
                    successes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    assertThat(errorCode(e)).isEqualTo(ErrorCode.REVIEW_VERSION_CONFLICT);
                }
            }
            assertThat(successes).hasSize(1);
            assertThat(successes.get(0).review().version()).isEqualTo(1);
            Map<String, Object> row = row();
            assertThat(row.get("body")).isEqualTo(successes.get(0).review().body());
            assertThat(((Number) row.get("version")).longValue()).isEqualTo(1);
            assertThat(((Number) row.get("id")).longValue()).isEqualTo(reviewId);
        }
    }

    @Test
    void concurrentUpdateAndDeleteWithTheSameVersionLetOnlyOneWin() throws Exception {
        Set<String> observed = new HashSet<>();
        for (int round = 0; round < ROUNDS * 2; round++) {
            jdbcTemplate.update("DELETE FROM work_reviews");
            long reviewId = reviewService.create(userId, workId, create("처음 작성한 리뷰 내용입니다.")).review().id();

            CyclicBarrier start = new CyclicBarrier(2);
            Future<MyReviewResponse> put = executor.submit(() -> {
                start.await();
                return reviewService.update(userId, workId, update(reviewId, 0, "경쟁 중에 고친 리뷰 내용입니다."));
            });
            Future<Object> delete = executor.submit(() -> {
                start.await();
                reviewService.delete(userId, workId, reviewId, 0L);
                return null;
            });
            ErrorCode putError = failure(put);
            ErrorCode deleteError = failure(delete);

            if (putError == null) {
                // 수정이 먼저: 삭제는 바뀐 버전을 보고 409, 리뷰는 수정된 상태로 남는다
                assertThat(deleteError).isEqualTo(ErrorCode.REVIEW_VERSION_CONFLICT);
                assertThat(row().get("body")).isEqualTo("경쟁 중에 고친 리뷰 내용입니다.");
                observed.add("updated");
            } else {
                // 삭제가 먼저: 수정은 리뷰가 없어 404, 리뷰는 없다
                assertThat(putError).isEqualTo(ErrorCode.REVIEW_NOT_FOUND);
                assertThat(deleteError).isNull();
                assertThat(bodies()).isEmpty();
                observed.add("deleted");
            }
        }
        assertThat(observed).isNotEmpty().isSubsetOf("updated", "deleted");
    }

    // ---------- 트랜잭션 순서 재현 ----------

    @Test
    void secondUpdateWaitsForTheLockThenSeesTheNewVersionAndConflicts() throws Exception {
        long reviewId = reviewService.create(userId, workId, create("처음 작성한 리뷰 내용입니다.")).review().id();
        HeldTransaction first = hold(() -> reviewService.update(userId, workId, update(reviewId, 0, "먼저 잠근 수정 내용입니다.")));

        Future<MyReviewResponse> second = executor.submit(
                () -> reviewService.update(userId, workId, update(reviewId, 0, "나중에 온 수정 내용입니다.")));
        assertBlocked(second);

        first.commit();
        assertThat(failure(second)).isEqualTo(ErrorCode.REVIEW_VERSION_CONFLICT);
        assertThat(row().get("body")).isEqualTo("먼저 잠근 수정 내용입니다.");
    }

    @Test
    void deleteWaitsForUncommittedUpdateThenConflicts() throws Exception {
        long reviewId = reviewService.create(userId, workId, create("처음 작성한 리뷰 내용입니다.")).review().id();
        HeldTransaction update = hold(() -> reviewService.update(userId, workId, update(reviewId, 0, "먼저 잠근 수정 내용입니다.")));

        Future<?> delete = executor.submit(() -> {
            reviewService.delete(userId, workId, reviewId, 0L);
            return null;
        });
        assertBlocked(delete);

        update.commit();
        assertThat(failure(delete)).isEqualTo(ErrorCode.REVIEW_VERSION_CONFLICT);
        assertThat(row().get("body")).isEqualTo("먼저 잠근 수정 내용입니다.");
    }

    @Test
    void updateWaitsForUncommittedDeleteThenIsNotFound() throws Exception {
        long reviewId = reviewService.create(userId, workId, create("처음 작성한 리뷰 내용입니다.")).review().id();
        HeldTransaction delete = hold(() -> {
            reviewService.delete(userId, workId, reviewId, 0L);
            return null;
        });

        Future<MyReviewResponse> put = executor.submit(
                () -> reviewService.update(userId, workId, update(reviewId, 0, "삭제 중에 온 수정 내용입니다.")));
        assertBlocked(put);

        delete.commit();
        assertThat(failure(put)).isEqualTo(ErrorCode.REVIEW_NOT_FOUND);
        assertThat(bodies()).isEmpty();
    }

    /**
     * 잠금을 기다리는 동안 대상 리뷰가 삭제되고 새 리뷰가 커밋된 경우.
     * 첫 잠금 조회는 스냅샷 때문에 새 리뷰를 보지 못하지만, 다시 조회해 새 리뷰와 비교하므로 404 가 아니라 409 다.
     */
    @Test
    void staleUpdateWaitingOnLockSeesRecreatedReviewAndConflicts() throws Exception {
        long oldId = reviewService.create(userId, workId, create("처음 작성한 리뷰 내용입니다.")).review().id();
        HeldTransaction recreate = hold(() -> {
            reviewService.delete(userId, workId, oldId, 0L);
            return reviewService.create(userId, workId, create("삭제 후 새로 작성한 리뷰입니다."));
        });

        Future<MyReviewResponse> stalePut = executor.submit(
                () -> reviewService.update(userId, workId, update(oldId, 0, "오래된 화면의 수정 내용입니다.")));
        Future<Object> staleDelete = executor.submit(() -> {
            reviewService.delete(userId, workId, oldId, 0L);
            return null;
        });
        assertBlocked(stalePut);
        assertBlocked(staleDelete);

        recreate.commit();
        assertThat(failure(stalePut)).isEqualTo(ErrorCode.REVIEW_VERSION_CONFLICT);
        assertThat(failure(staleDelete)).isEqualTo(ErrorCode.REVIEW_VERSION_CONFLICT);
        Map<String, Object> row = row();
        assertThat(row.get("body")).isEqualTo("삭제 후 새로 작성한 리뷰입니다.");
        assertThat(((Number) row.get("id")).longValue()).isNotEqualTo(oldId);
        assertThat(((Number) row.get("version")).longValue()).isZero();
    }

    @Test
    void createWaitsForUncommittedDeleteThenSucceeds() throws Exception {
        long reviewId = reviewService.create(userId, workId, create("처음 작성한 리뷰 내용입니다.")).review().id();
        HeldTransaction delete = hold(() -> {
            reviewService.delete(userId, workId, reviewId, 0L);
            return null;
        });

        Future<MyReviewResponse> create = executor.submit(
                () -> reviewService.create(userId, workId, create("삭제를 기다린 뒤 새로 작성합니다.")));
        assertBlocked(create);

        delete.commit();
        MyReviewResponse created = create.get(30, TimeUnit.SECONDS);
        assertThat(created.review().id()).isNotEqualTo(reviewId);
        assertThat(created.review().version()).isZero();
        assertThat(bodies()).containsExactly("삭제를 기다린 뒤 새로 작성합니다.");
    }

    @Test
    void createWaitsForUncommittedCreateThenConflicts() throws Exception {
        HeldTransaction first = hold(() -> reviewService.create(userId, workId, create("먼저 작성한 리뷰 내용입니다.")));

        Future<MyReviewResponse> second = executor.submit(
                () -> reviewService.create(userId, workId, create("나중에 작성한 리뷰 내용입니다.")));
        assertBlocked(second);

        first.commit();
        assertThat(failure(second)).isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS);
        assertThat(bodies()).containsExactly("먼저 작성한 리뷰 내용입니다.");
    }

    @Test
    void updatedAtNeverMovesBackwardsWhenAnEarlierTransactionCommitsLater() throws Exception {
        MyReviewResponse first = reviewService.create(userId, workId, create("처음 작성한 리뷰 내용입니다."));
        long reviewId = first.review().id();

        CountDownLatch earlyStarted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        Future<MyReviewResponse> early = executor.submit(() -> transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT now()", java.sql.Timestamp.class); // 트랜잭션 시작 시각 고정
            earlyStarted.countDown();
            await(proceed);
            return reviewService.update(userId, workId, update(reviewId, 1, "먼저 시작했지만 나중에 반영된 내용"));
        }));
        assertThat(earlyStarted.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(20);
        MyReviewResponse later = reviewService.update(userId, workId, update(reviewId, 0, "나중에 시작했지만 먼저 반영된 내용"));
        proceed.countDown();
        MyReviewResponse earlyResult = early.get(30, TimeUnit.SECONDS);

        assertThat(earlyResult.review().version()).isEqualTo(2);
        assertThat(earlyResult.review().updatedAt()).isAfterOrEqualTo(later.review().updatedAt());
        assertThat(earlyResult.review().createdAt()).isEqualTo(first.review().createdAt());
    }

    // ---------- helpers ----------

    private static CreateReviewRequest create(String body) {
        return new CreateReviewRequest(new JsonText(body), new JsonFlag(false));
    }

    private static UpdateReviewRequest update(long reviewId, long version, String body) {
        return new UpdateReviewRequest(new JsonInteger(reviewId), new JsonInteger(version), new JsonText(body),
                new JsonFlag(false));
    }

    private List<String> bodies() {
        return jdbcTemplate.queryForList(
                "SELECT body FROM work_reviews WHERE user_id = ? AND work_id = ?", String.class, userId, workId);
    }

    private Map<String, Object> row() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, body, version, created_at, updated_at FROM work_reviews WHERE user_id = ? AND work_id = ?",
                userId, workId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** 성공이면 null, BusinessException 이면 그 코드 */
    private static ErrorCode failure(Future<?> future) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return errorCode(e);
        }
    }

    private static ErrorCode errorCode(ExecutionException e) {
        Throwable cause = e.getCause();
        while (cause != null && !(cause instanceof BusinessException)) {
            cause = cause.getCause();
        }
        if (cause == null) {
            throw new AssertionError("unexpected failure", e.getCause());
        }
        return ((BusinessException) cause).getErrorCode();
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
