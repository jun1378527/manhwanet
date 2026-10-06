package com.manhwanet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    private static final LoginLimitProperties DEFAULTS =
            new LoginLimitProperties(5, Duration.ofMinutes(15), Duration.ofMinutes(15), 10_000);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(DEFAULTS, clock);

    private void fail(String key) {
        assertThat(limiter.tryAcquire(key).permitted()).isTrue();
        limiter.recordFailure(key);
    }

    @Test
    void locksAfterFiveFailuresWithinWindowForFifteenMinutes() {
        for (int i = 0; i < 5; i++) {
            fail("a@example.com");
        }
        LoginAttemptLimiter.Decision decision = limiter.tryAcquire("a@example.com");
        assertThat(decision.permitted()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(15 * 60);

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(limiter.tryAcquire("a@example.com").permitted()).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("a@example.com").permitted()).isTrue();
    }

    @Test
    void failuresOlderThanWindowDoNotCount() {
        for (int i = 0; i < 4; i++) {
            fail("b@example.com");
        }
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        fail("b@example.com");
        assertThat(limiter.tryAcquire("b@example.com").permitted()).isTrue();
    }

    @Test
    void successClearsFailures() {
        for (int i = 0; i < 4; i++) {
            fail("c@example.com");
        }
        assertThat(limiter.tryAcquire("c@example.com").permitted()).isTrue();
        limiter.recordSuccess("c@example.com");
        assertThat(limiter.size()).isZero();
        for (int i = 0; i < 4; i++) {
            fail("c@example.com");
        }
        assertThat(limiter.tryAcquire("c@example.com").permitted()).isTrue();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 5; i++) {
            fail("d@example.com");
        }
        assertThat(limiter.tryAcquire("d@example.com").permitted()).isFalse();
        assertThat(limiter.tryAcquire("other@example.com").permitted()).isTrue();
    }

    @Test
    void releaseDoesNotCountAsFailure() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("e@example.com").permitted()).isTrue();
            limiter.release("e@example.com");
        }
        assertThat(limiter.size()).isZero();
    }

    @Test
    void concurrentAttemptsNeverExceedLimit() throws Exception {
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger permitted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (limiter.tryAcquire("race@example.com").permitted()) {
                    permitted.incrementAndGet();
                    Thread.sleep(5); // 비밀번호 검증 시간 흉내 (테스트 스레드만 대기)
                    limiter.recordFailure("race@example.com");
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(permitted.get()).isEqualTo(5);
        assertThat(limiter.tryAcquire("race@example.com").permitted()).isFalse();
    }

    @Test
    void concurrentDistinctKeysAreAllTracked() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String key = "user" + i + "@example.com";
            futures.add(pool.submit(() -> {
                limiter.tryAcquire(key);
                limiter.recordFailure(key);
            }));
        }
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(limiter.size()).isEqualTo(1000);
    }

    @Test
    void sizeIsBoundedByEvictingExpiredThenOldestEntries() {
        LoginAttemptLimiter small = new LoginAttemptLimiter(
                new LoginLimitProperties(5, Duration.ofMinutes(15), Duration.ofMinutes(15), 3), clock);
        for (int i = 0; i < 3; i++) {
            small.tryAcquire("k" + i);
            small.recordFailure("k" + i);
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(small.size()).isEqualTo(3);

        small.tryAcquire("k3");
        small.recordFailure("k3");
        assertThat(small.size()).isEqualTo(3);

        // 만료된 항목이 먼저 정리된다.
        clock.advance(Duration.ofMinutes(16));
        small.tryAcquire("k4");
        small.recordFailure("k4");
        assertThat(small.size()).isEqualTo(1);
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
